package com.neethu.gouxiang.chat

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.neethu.gouxiang.ai.adapter.LlmConfig
import com.neethu.gouxiang.ai.adapter.LlmStreamEvent
import com.neethu.gouxiang.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DurableMemoryProcessorTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GouXiangDatabase::class.java).allowMainThreadQueries().build()
    private val source = object : MemorySource {
        override suspend fun all(characterId: Long) = db.memoryDao().forCharacter(characterId)
        override suspend fun insert(memory: MemoryEntity) = db.memoryDao().insert(memory)
        override suspend fun update(memory: MemoryEntity) = db.memoryDao().update(memory)
        override suspend fun deleteById(id: Long) = db.memoryDao().deleteById(id)
        override suspend fun deleteSince(characterId: Long, categories: Set<String>, sinceMs: Long) = db.memoryDao().deleteSince(characterId, categories, sinceMs)
    }
    private val book = MemoryBook(source)
    private val config = LlmConfig(baseUrl = "", apiKey = "", model = "test")
    private fun character(id: Long) = CharacterEntity(id, "角色$id", "", "", "", "", "", "", createdAt = 0)
    private fun processor(llm: ChatOrchestrator.Llm) = DurableMemoryProcessor(db, MemoryExtractor(book, llm, { config }))
    private suspend fun add(p: DurableMemoryProcessor, id: Long, turn: String, user: String, assistant: String): Long {
        val userId = db.messageDao().insert(MessageEntity(conversationId = id, turnId = "user-$turn", role = "user", content = user))
        db.messageDao().insert(MessageEntity(conversationId = id, turnId = turn, role = "assistant", content = assistant))
        p.append(character(id), id, turn, user, assistant, userId)
        return userId
    }
    @After fun close() = db.close()

    @Test fun isolationAndChannelFailurePreservePendingTurns() = runBlocking {
        var failProfile = true
        val llm = ChatOrchestrator.Llm { messages, _ -> flow {
            val profile = messages.first().content.contains("用户画像")
            val text = if (profile && failProfile) "bad" else if (profile) "{\"name\":null,\"identity\":null}" else "{\"ops\":[{\"action\":\"add\",\"content\":\"事件\"}]}"
            emit(LlmStreamEvent.TextDelta(text))
        } }
        val p = processor(llm)
        add(p, 1, "t1", "一号\u0001用户", "一号回复")
        add(p, 2, "t2", "", "主动消息\u0002内容")
        assertFalse(p.run(character(1), 1))
        assertTrue(p.pending(1).single().eventDone)
        assertFalse(p.pending(1).single().profileDone)
        assertEquals("", p.pending(2).single().user)
        assertEquals("主动消息\u0002内容", p.pending(2).single().assistant)
        failProfile = false
        assertTrue(p.run(character(1), 1))
        assertTrue(p.pending(1).isEmpty())
        assertEquals(1, book.events(1).size)
        assertTrue(book.events(2).isEmpty())
        assertEquals(1, p.pending(2).size)
    }

    @Test fun rewindRejectsLateResultAndKeepsNewTurns() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val llm = ChatOrchestrator.Llm { messages, _ -> flow {
            if (messages.first().content.contains("用户画像")) {
                started.complete(Unit); resume.await(); emit(LlmStreamEvent.TextDelta("{\"name\":\"旧称呼\",\"identity\":null}"))
            } else emit(LlmStreamEvent.TextDelta("{\"ops\":[{\"action\":\"add\",\"content\":\"旧事件\"}]}"))
        } }
        val p = processor(llm)
        val first = add(p, 1, "old", "旧用户", "旧回复")
        val job = async { p.run(character(1), 1) }
        started.await()
        p.rewind(1, first)
        add(p, 1, "new", "新用户", "新回复")
        resume.complete(Unit)
        job.await()
        assertNull(book.profile(1).name)
        // A new turn is not consumed by the invalidated profile snapshot.
        assertEquals("new", p.pending(1).single().turnId)
        assertFalse(p.pending(1).single().profileDone)
    }
    @Test fun manualProfileEditDuringRequestIsNotOverwritten() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val llm = ChatOrchestrator.Llm { messages, _ -> flow {
            if (messages.first().content.contains("用户画像")) {
                started.complete(Unit); resume.await(); emit(LlmStreamEvent.TextDelta("{\"name\":\"旧称呼\",\"identity\":null}"))
            } else emit(LlmStreamEvent.TextDelta("{\"ops\":[]}"))
        } }
        val p = processor(llm)
        add(p, 1, "old", "用户", "回复")
        val job = async { p.run(character(1), 1) }
        started.await()
        book.writeProfile(1, MemoryCategories.SUBTYPE_NAME, "手工称呼")
        resume.complete(Unit)
        assertFalse(job.await())
        assertEquals("手工称呼", book.profile(1).name)
        assertFalse(p.pending(1).single().profileDone)
    }

    @Test fun simultaneousProcessorsClaimEachChannelOnlyOnce() = runBlocking {
        withTimeout(10_000) {
            val started = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val profileCalls = AtomicInteger()
            val eventCalls = AtomicInteger()
            val llm = ChatOrchestrator.Llm { messages, _ -> flow {
                if (messages.first().content.contains("用户画像")) {
                    profileCalls.incrementAndGet()
                    started.complete(Unit)
                    resume.await()
                    emit(LlmStreamEvent.TextDelta("{\"name\":null,\"identity\":null}"))
                } else {
                    eventCalls.incrementAndGet()
                    emit(LlmStreamEvent.TextDelta("{\"ops\":[{\"action\":\"add\",\"content\":\"同一段经历\"}]}"))
                }
            } }
            val foreground = processor(llm)
            val worker = processor(llm)
            add(foreground, 1, "t1", "用户内容", "角色回复")
            val foregroundRun = async { foreground.run(character(1), 1) }
            started.await()
            // The other instance must not duplicate the profile request; it may process events.
            assertFalse(worker.run(character(1), 1))
            assertEquals(1, profileCalls.get())
            assertEquals(1, eventCalls.get())
            resume.complete(Unit)
            assertTrue(foregroundRun.await())
            assertEquals(1, profileCalls.get())
            assertEquals(1, eventCalls.get())
            assertTrue(foreground.pending(1).isEmpty())
            assertEquals(1, book.events(1).size)
        }
    }

    @Test fun turnAddedDuringExtractionRemainsForItsUnprocessedChannel() = runBlocking {
        withTimeout(10_000) {
            val started = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val eventInput = mutableListOf<String>()
            val llm = ChatOrchestrator.Llm { messages, _ -> flow {
                if (messages.first().content.contains("用户画像")) {
                    started.complete(Unit)
                    resume.await()
                    emit(LlmStreamEvent.TextDelta("{\"name\":null,\"identity\":null}"))
                } else {
                    eventInput.add(messages.last().content)
                    emit(LlmStreamEvent.TextDelta("{\"ops\":[]}"))
                }
            } }
            val p = processor(llm)
            add(p, 1, "old", "最初的用户", "最初的回复")
            val job = async { p.run(character(1), 1) }
            started.await()
            add(p, 1, "new", "新增的用户", "新增的回复")
            resume.complete(Unit)
            assertTrue(job.await())
            val remaining = p.pending(1).single()
            assertEquals("new", remaining.turnId)
            assertFalse(remaining.profileDone)
            assertTrue(remaining.eventDone)
            assertTrue(eventInput.single().contains("新增的用户"))
            assertEquals(listOf("old", "new"), db.memoryWindowDao().allTurns(1).map { it.turnId })
        }
    }

    @Test fun eventFailureRetriesWithoutRepeatingSuccessfulProfile() = runBlocking {
        val profileCalls = AtomicInteger()
        val eventCalls = AtomicInteger()
        val llm = ChatOrchestrator.Llm { messages, _ -> flow {
            if (messages.first().content.contains("用户画像")) {
                profileCalls.incrementAndGet()
                emit(LlmStreamEvent.TextDelta("{\"name\":\"小名\",\"identity\":null}"))
            } else if (eventCalls.incrementAndGet() == 1) {
                emit(LlmStreamEvent.TextDelta("invalid"))
            } else emit(LlmStreamEvent.TextDelta("{\"ops\":[{\"action\":\"add\",\"content\":\"最终事件\"}]}"))
        } }
        val p = processor(llm)
        add(p, 1, "t", "用户内容", "角色回复")
        assertFalse(p.run(character(1), 1))
        assertTrue(p.pending(1).single().profileDone)
        assertFalse(p.pending(1).single().eventDone)
        assertTrue(p.run(character(1), 1))
        assertEquals(1, profileCalls.get())
        assertEquals(2, eventCalls.get())
        assertEquals("小名", book.profile(1).name)
        assertEquals(listOf("最终事件"), book.events(1).map { it.content })
        assertTrue(p.pending(1).isEmpty())
    }

    @Test fun expiredLeaseIsRecoveredButLiveLeaseIsRespected() = runBlocking {
        val profileCalls = AtomicInteger()
        val eventCalls = AtomicInteger()
        val llm = ChatOrchestrator.Llm { messages, _ -> flow {
            if (messages.first().content.contains("用户画像")) {
                profileCalls.incrementAndGet()
                emit(LlmStreamEvent.TextDelta("{\"name\":null,\"identity\":null}"))
            } else {
                eventCalls.incrementAndGet()
                emit(LlmStreamEvent.TextDelta("{\"ops\":[]}"))
            }
        } }
        val p = processor(llm)
        add(p, 1, "t", "用户内容", "角色回复")
        val initial = p.pending(1).single()
        db.memoryWindowDao().updateTurns(listOf(initial.copy(profileLease = "live", profileLeaseAt = System.currentTimeMillis(), eventLease = "expired", eventLeaseAt = 0)))
        assertFalse(p.run(character(1), 1))
        assertEquals(0, profileCalls.get())
        assertEquals(1, eventCalls.get())
        val after = p.pending(1).single()
        assertTrue(after.eventDone)
        assertEquals("live", after.profileLease)
        db.memoryWindowDao().updateTurns(listOf(after.copy(profileLeaseAt = 0)))
        assertTrue(p.run(character(1), 1))
        assertEquals(1, profileCalls.get())
        assertEquals(1, eventCalls.get())
        assertTrue(p.pending(1).isEmpty())
    }

    @Test fun rewindThenRetryOnlyAppliesNewHistory() = runBlocking {
        withTimeout(10_000) {
            val started = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val profileCalls = AtomicInteger()
            val eventCalls = AtomicInteger()
            val llm = ChatOrchestrator.Llm { messages, _ -> flow {
                if (messages.first().content.contains("用户画像")) {
                    if (profileCalls.incrementAndGet() == 1) {
                        started.complete(Unit)
                        resume.await()
                        emit(LlmStreamEvent.TextDelta("{\"name\":\"旧称呼\",\"identity\":null}"))
                    } else emit(LlmStreamEvent.TextDelta("{\"name\":\"新称呼\",\"identity\":null}"))
                } else {
                    eventCalls.incrementAndGet()
                    assertTrue(messages.last().content.contains("新用户"))
                    assertFalse(messages.last().content.contains("旧用户"))
                    emit(LlmStreamEvent.TextDelta("{\"ops\":[{\"action\":\"add\",\"content\":\"新经历\"}]}"))
                }
            } }
            val p = processor(llm)
            val oldUserId = add(p, 1, "old", "旧用户", "旧回复")
            val oldRun = async { p.run(character(1), 1) }
            started.await()
            p.rewind(1, oldUserId)
            add(p, 1, "new", "新用户", "新回复")
            resume.complete(Unit)
            assertFalse(oldRun.await())
            assertNull(book.profile(1).name)
            assertTrue(p.run(character(1), 1))
            assertEquals("新称呼", book.profile(1).name)
            assertEquals(listOf("新经历"), book.events(1).map { it.content })
            assertEquals(2, profileCalls.get())
            assertEquals(1, eventCalls.get())
            assertTrue(p.pending(1).isEmpty())
        }
    }

    @Test fun expiredLeaseReplacementRejectsLateFormerOwner() = runBlocking {
        withTimeout(10_000) {
            val firstStarted = CompletableDeferred<Unit>()
            val resumeFirst = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val llm = ChatOrchestrator.Llm { messages, _ -> flow {
                if (messages.first().content.contains("用户画像")) {
                    if (calls.incrementAndGet() == 1) {
                        firstStarted.complete(Unit)
                        resumeFirst.await()
                        emit(LlmStreamEvent.TextDelta("{\"name\":\"过期结果\",\"identity\":null}"))
                    } else emit(LlmStreamEvent.TextDelta("{\"name\":\"新租约结果\",\"identity\":null}"))
                } else emit(LlmStreamEvent.TextDelta("{\"ops\":[]}"))
            } }
            val original = processor(llm)
            val replacement = processor(llm)
            add(original, 1, "t", "用户内容", "角色回复")
            val oldRun = async { original.run(character(1), 1) }
            firstStarted.await()
            val leased = original.pending(1).single()
            assertTrue(leased.profileLease.isNotEmpty())
            db.memoryWindowDao().updateTurns(listOf(leased.copy(profileLeaseAt = 0)))
            assertTrue(replacement.run(character(1), 1))
            resumeFirst.complete(Unit)
            assertFalse(oldRun.await())
            assertEquals("新租约结果", book.profile(1).name)
            assertTrue(original.pending(1).isEmpty())
            assertEquals(2, calls.get())
        }
    }

}
