package com.neethu.gouxiang.work

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.neethu.gouxiang.ai.GenerationPrefs
import com.neethu.gouxiang.ai.config.AiConfig
import com.neethu.gouxiang.ai.config.AiConfigStore
import com.neethu.gouxiang.ai.config.AiProvider
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.ContentCandidateEntity
import com.neethu.gouxiang.data.ContentTurnEntity
import com.neethu.gouxiang.data.ConversationEntity
import com.neethu.gouxiang.data.GouXiangRepo
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = ContentDiscoveryWorkerQuotaTest.NoopApplication::class)
class ContentDiscoveryWorkerQuotaTest {
    class NoopApplication : Application()

    @Test fun fourSeenTodayDoNotBlockDiscoveryButTwentyUnseenStillDo() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = GouXiangRepo.get(context).db
        val dao = db.contentDiscoveryDao()
        val now = System.currentTimeMillis()
        val characterId = db.characterDao().upsert(CharacterEntity(
            name = "discovery-test-${UUID.randomUUID()}", profile = "", intro = "", exampleLines = "",
            openingLine = "", appearance = "", appearanceStyle = "", createdAt = now,
        ))
        val conversationId = db.conversationDao().upsert(ConversationEntity(characterId = characterId))
        suspend fun insertTurn(index: Int) = dao.insertTurn(ContentTurnEntity(
            conversationId = conversationId, characterId = characterId, replyTurnId = "turn-$index",
            userMessageId = 1000L + index * 2, endMessageId = 1001L + index * 2,
            sourceVersion = 0, user = "用户消息", assistant = "角色回复", completedAt = now,
        ))
        suspend fun insertExisting(index: Int, reminder: String) = dao.insertCandidate(ContentCandidateEntity(
            conversationId = conversationId, characterId = characterId, kind = "HANDBOOK",
            title = "已有标题$index", startTurnId = 1, endTurnId = 1,
            startMessageId = 1, endMessageId = 2, sourceVersion = 0, createdAt = now,
            candidateKey = "existing-$conversationId-$index", reminderStatus = reminder,
        ))
        val firstTurns = (1..8).map { insertTurn(it) }
        repeat(4) { insertExisting(it, "SEEN") }

        // A loopback-only fake SSE endpoint means the test can never call the user's model.
        val listener = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val requests = AtomicInteger()
        val modelResult = """{"decision":"create","end_turn_index":8,"candidates":[{"kind":"HANDBOOK","title":"新手账"},{"kind":"NOVEL","title":"新故事"}]}"""
        val event = "data: {\"choices\":[{\"delta\":{\"content\":${JsonPrimitive(modelResult)}},\"finish_reason\":null}]}\n\ndata: [DONE]\n\n"
        val server = Thread {
            try {
                while (!listener.isClosed) {
                    listener.accept().use { socket ->
                        requests.incrementAndGet()
                        val input = socket.getInputStream().bufferedReader()
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val bytes = event.toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                }
            } catch (_: SocketException) {
                // Closing the listener terminates accept after the test.
            }
        }.apply { isDaemon = true; start() }
        val previousConfig = AiConfigStore.load(context)
        val previousHandbook = GenerationPrefs.handbookAuto(context)
        val previousNovel = GenerationPrefs.novelAuto(context)
        try {
            GenerationPrefs.setHandbookAuto(context, false)
            GenerationPrefs.setNovelAuto(context, false)
            AiConfigStore.save(context, AiConfig(provider = AiProvider.CUSTOM, llmModel = "test-model",
                customBaseUrl = "http://127.0.0.1:${listener.localPort}/v1", apiKeyCustom = "test-key"))
            fun worker() = TestListenableWorkerBuilder<ContentDiscoveryWorker>(context,
                workDataOf("conversation" to conversationId, "idle" to false)).build()

            assertTrue(worker().doWork() is ListenableWorker.Result.Success)
            assertEquals(1, requests.get())
            val created = dao.observeForConversation(conversationId).first()
            assertEquals(6, created.size)
            assertEquals(setOf("新手账", "新故事"), created.filter { it.reminderStatus == "UNSEEN" }.map { it.title }.toSet())
            assertEquals(firstTurns.last(), dao.state(conversationId)!!.consumedTurnId)

            // Fill the separate unread limit and confirm it preserves the next eight turns.
            repeat(18) { insertExisting(it + 4, "UNSEEN") }
            val pending = (9..16).map { insertTurn(it) }
            val before = dao.state(conversationId)
            assertEquals(20, dao.unseenCount(conversationId))
            assertTrue(worker().doWork() is ListenableWorker.Result.Success)
            assertEquals(1, requests.get())
            assertEquals(before, dao.state(conversationId))
            assertEquals(pending, dao.turnsAfter(conversationId, firstTurns.last()).map { it.id })
        } finally {
            AiConfigStore.save(context, previousConfig)
            GenerationPrefs.setHandbookAuto(context, previousHandbook)
            GenerationPrefs.setNovelAuto(context, previousNovel)
            listener.close()
            server.join(2_000)
        }
    }
}
