package com.neethu.gouxiang.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.neethu.gouxiang.ai.adapter.LlmConfig
import com.neethu.gouxiang.chat.ChatOrchestrator
import com.neethu.gouxiang.chat.DurableMemoryProcessor
import com.neethu.gouxiang.chat.MemoryBook
import com.neethu.gouxiang.chat.MemoryExtractor
import com.neethu.gouxiang.chat.MemorySource
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatDaoAccessRewindTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), GouXiangDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val chat = ChatDaoAccess(db)
    private val memorySource = object : MemorySource {
        override suspend fun all(characterId: Long) = db.memoryDao().forCharacter(characterId)
        override suspend fun update(memory: MemoryEntity) = db.memoryDao().update(memory)
        override suspend fun insert(memory: MemoryEntity) = db.memoryDao().insert(memory)
        override suspend fun deleteById(id: Long) = db.memoryDao().deleteById(id)
        override suspend fun deleteSince(characterId: Long, categories: Set<String>, sinceMs: Long) =
            db.memoryDao().deleteSince(characterId, categories, sinceMs)
    }
    private val processor = DurableMemoryProcessor(
        db,
        MemoryExtractor(MemoryBook(memorySource), ChatOrchestrator.Llm { _, _ -> emptyFlow() }) {
            LlmConfig(baseUrl = "", apiKey = "", model = "test")
        },
    )

    @After fun close() = db.close()

    private fun assistant(conversationId: Long, turn: String, text: String) =
        MessageEntity(conversationId = conversationId, turnId = turn, role = "assistant", content = text)

    @Test
    fun lastBubbleAndPendingTurnCommitTogetherWithExactUserBoundary() = runBlocking {
        db.conversationDao().upsert(ConversationEntity(id = 1, characterId = 7))
        val userId = db.messageDao().insert(MessageEntity(conversationId = 1, turnId = "u", role = "user", content = "hello"))
        val turn = ChatOrchestrator.CompletedMemoryTurn(7, "hello", "first", userId)
        assertTrue(chat.insertMessageWithTurn(assistant(1, "reply", "first"), 0, turn, completed = false))
        assertFalse(db.memoryWindowDao().turns(1).single().completed)
        assertTrue(chat.insertMessageWithTurn(assistant(1, "reply", "last"), 0, turn.copy(assistant = "first\nlast"), completed = true))
        val rows = db.messageDao().allForConversation(1)
        val pending = db.memoryWindowDao().turns(1).single()
        assertEquals(userId, pending.startMessageId)
        assertEquals(rows.last().id, pending.endMessageId)
        assertEquals("first\nlast", pending.assistant)
        assertTrue(pending.completed)
        assertEquals("last", db.conversationDao().byId(1)?.lastSummary)

        // A duplicate turn must roll back its last message when the unique turn insert fails.
        assertTrue(runCatching { chat.insertMessageWithTurn(assistant(1, "reply", "duplicate"), 0, turn, completed = true) }.isFailure)
        assertEquals(3, db.messageDao().allForConversation(1).size)
        assertEquals(1, db.memoryWindowDao().turns(1).size)
    }

    @Test
    fun interruptedPartialTurnIsRecoveredWithoutInventingMissingBubbles() = runBlocking {
        val userId = db.messageDao().insert(MessageEntity(conversationId = 1, turnId = "u", role = "user", content = "hello"))
        val turn = ChatOrchestrator.CompletedMemoryTurn(7, "hello", "first", userId)
        assertTrue(chat.insertMessageWithTurn(assistant(1, "reply", "first"), 0, turn, completed = false))
        assertFalse(db.memoryWindowDao().turns(1).single().completed)
        assertTrue(db.memoryWindowDao().pendingConversationIds().isEmpty())

        // Equivalent to the database callback when the app opens after process death.
        db.memoryWindowDao().recoverInterruptedTurns()
        val recovered = db.memoryWindowDao().turns(1).single()
        assertTrue(recovered.completed)
        assertEquals("first", recovered.assistant)
        assertEquals(listOf(1L), db.memoryWindowDao().pendingConversationIds())
    }

    @Test
    fun rewindMarksOnlyAffectedDerivativeResultsStale() = runBlocking {
        val earlier = db.messageDao().insert(MessageEntity(conversationId = 1, turnId = "old", role = "user", content = "old"))
        val anchor = db.messageDao().insert(MessageEntity(conversationId = 1, turnId = "new", role = "user", content = "new"))
        val unaffected = db.momentDao().insert(MomentEntity(characterId = 7, text = "earlier"))
        val affected = db.momentDao().insert(MomentEntity(characterId = 7, text = "affected"))
        val voice = db.innerVoiceDao().insertAll(listOf(InnerVoiceEntity(characterId = 7, title = "affected", echo = "text"))).single()
        db.derivativeResultDao().insert(DerivativeResultEntity("old-result", "MOMENT", 7, unaffected.toString(),
            sourceConversationId = 1, sourceEndMessageId = earlier))
        db.derivativeResultDao().insert(DerivativeResultEntity("new-result", "MOMENT", 7, affected.toString(),
            sourceConversationId = 1, sourceEndMessageId = anchor))
        db.derivativeResultDao().insert(DerivativeResultEntity("voice-result", "INNER_VOICE", 7, voice.toString(),
            sourceConversationId = 1, sourceEndMessageId = anchor))

        processor.rewind(1, anchor)
        assertFalse(db.momentDao().byId(unaffected)!!.isStale)
        assertTrue(db.momentDao().byId(affected)!!.isStale)
        assertTrue(db.innerVoiceDao().forCharacter(7).single().isStale)
    }

    @Test
    fun rewindBetweenBubblesRejectsStaleCompletionWithoutAffectingAnotherConversation() = runBlocking {
        val userId = db.messageDao().insert(MessageEntity(conversationId = 1, turnId = "u", role = "user", content = "hello"))
        val old = ChatOrchestrator.CompletedMemoryTurn(7, "hello", "first", userId)
        assertTrue(chat.insertMessageWithTurn(assistant(1, "old", "first"), 0, old, completed = false))
        processor.rewind(1, userId)
        assertFalse(chat.insertMessageWithTurn(assistant(1, "old", "last"), 0, old.copy(assistant = "first\nlast"), completed = true))
        assertTrue(db.messageDao().allForConversation(1).isEmpty())
        assertTrue(db.memoryWindowDao().turns(1).isEmpty())

        // Versions are conversation-scoped; an unrelated conversation can still commit at version zero.
        val other = ChatOrchestrator.CompletedMemoryTurn(8, "", "proactive", null)
        assertTrue(chat.insertMessageWithTurn(assistant(2, "other", "proactive"), 0, other, completed = true))
        assertEquals(1, db.messageDao().allForConversation(2).size)
        assertEquals(8L, db.memoryWindowDao().turns(2).single().characterId)
    }
    @Test
    fun rewindWithinConsumedMultiBubbleTurnRebuildsOnlyRetainedPrefix() = runBlocking {
        val userId = db.messageDao().insert(MessageEntity(conversationId = 1, turnId = "u", role = "user", content = "原始输入"))
        val turn = ChatOrchestrator.CompletedMemoryTurn(7, "原始输入", "第一泡", userId)
        assertTrue(chat.insertMessageWithTurn(assistant(1, "reply", "第一泡"), 0, turn, false))
        assertTrue(chat.insertMessageWithTurn(assistant(1, "reply", "第二泡"), 0, turn.copy(assistant = "第一泡\n第二泡"), false))
        assertTrue(chat.insertMessageWithTurn(assistant(1, "reply", "第三泡"), 0, turn.copy(assistant = "第一泡\n第二泡\n第三泡"), true))
        val before = db.memoryWindowDao().allTurns(1).single()
        db.memoryWindowDao().updateTurns(listOf(before.copy(profileDone = true, eventDone = true, profileLease = "old", eventLease = "old")))
        assertTrue(db.memoryWindowDao().turns(1).isEmpty())
        val bubbles = db.messageDao().allForConversation(1).filter { it.role == "assistant" }
        val anchor = bubbles.last().id
        db.memoryDao().insert(MemoryEntity(characterId = 7, category = "event", content = "来源于整轮", sourceConversationId = 1, sourceEndMessageId = anchor))
        db.memoryDao().insert(MemoryEntity(characterId = 7, category = "event", content = "手工记忆"))

        processor.rewind(1, anchor)
        val retained = db.memoryWindowDao().turns(1).single()
        assertEquals(before.id, retained.id)
        assertEquals(userId, retained.startMessageId)
        assertEquals(bubbles[1].id, retained.endMessageId)
        assertEquals("原始输入", retained.user)
        assertEquals("第一泡\n第二泡", retained.assistant)
        assertTrue(retained.completed)
        assertFalse(retained.profileDone)
        assertFalse(retained.eventDone)
        assertEquals("", retained.profileLease)
        assertEquals("", retained.eventLease)
        assertEquals(listOf("手工记忆"), db.memoryDao().forCharacter(7).map { it.content })
        assertEquals(3, db.messageDao().allForConversation(1).size)
        assertEquals(1L, db.memoryWindowDao().version(1))
    }

    @Test
    fun rewindWithinProactiveTurnPreservesEmptyUserAndRetainedBubble() = runBlocking {
        val turn = ChatOrchestrator.CompletedMemoryTurn(7, "", "(TA 主动发来)第一泡", null)
        assertTrue(chat.insertMessageWithTurn(assistant(1, "proactive-1", "第一泡"), 0, turn, false))
        assertTrue(chat.insertMessageWithTurn(assistant(1, "proactive-1", "第二泡"), 0,
            turn.copy(assistant = "(TA 主动发来)第一泡\n第二泡"), true))
        val anchor = db.messageDao().allForConversation(1).last().id
        processor.rewind(1, anchor)
        val retained = db.memoryWindowDao().turns(1).single()
        assertEquals("", retained.user)
        assertEquals("(TA 主动发来)第一泡", retained.assistant)
    }

}
