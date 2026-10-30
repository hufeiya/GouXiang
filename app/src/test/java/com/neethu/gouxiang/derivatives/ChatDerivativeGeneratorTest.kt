package com.neethu.gouxiang.derivatives

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.neethu.gouxiang.ai.GenerationPrefs
import com.neethu.gouxiang.ai.adapter.ChatMessage
import com.neethu.gouxiang.ai.adapter.ChatRole
import com.neethu.gouxiang.ai.adapter.LlmStreamEvent
import com.neethu.gouxiang.chat.ChatOrchestrator
import com.neethu.gouxiang.chat.DerivativeFailure
import com.neethu.gouxiang.chat.DerivativeFailureKind
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.ChatDerivativeEntity
import com.neethu.gouxiang.data.ConversationEntity
import com.neethu.gouxiang.data.DerivativeTaskRecord
import com.neethu.gouxiang.data.GouXiangDatabase
import com.neethu.gouxiang.data.MessageEntity
import java.io.File
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * G1-G3 生成管线单测(假 LLM/假生图,零网络):文本先出图片可选、schema
 * 校验后才落库、长度控制的章数边界、版本拦截(回溯过期任务不回写)、幂等
 * (重复收口/迟到旧任务/记录已删/成功记录补图)、合照失败可重试、重启对账。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatDerivativeGeneratorTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), GouXiangDatabase::class.java,
    ).allowMainThreadQueries().build()

    /** 捕获到的请求消息与配置(断言素材/maxTokens 用)。 */
    private val capturedRequests = mutableListOf<List<ChatMessage>>()
    private val capturedConfigs = mutableListOf<com.neethu.gouxiang.ai.adapter.LlmConfig>()

    /** 待回放的 LLM 输出(按请求顺序弹出;队列空=空输出)。 */
    private val replies = ArrayDeque<String>()

    private val llm = ChatOrchestrator.Llm { messages, config ->
        capturedRequests += messages
        capturedConfigs += config
        val reply = replies.removeFirstOrNull().orEmpty()
        flow {
            emit(LlmStreamEvent.TextDelta(reply))
            emit(LlmStreamEvent.Finish("stop"))
        }
    }

    /** 配图缝:返回 null(默认)= 生图失败;测试按需改写。 */
    private var imageFile: File? = null

    private val config = com.neethu.gouxiang.ai.adapter.LlmConfig(
        baseUrl = "http://localhost", apiKey = "k", model = "m",
    )

    private val generator = ChatDerivativeGenerator(
        context = ApplicationProvider.getApplicationContext(),
        db = db,
        llm = llm,
        llmConfig = { config },
        imageProvider = { _, _ -> imageFile },
    )

    @Before
    fun seed() {
        kotlinx.coroutines.runBlocking {
            db.characterDao().upsert(
                CharacterEntity(
                    id = 1, name = "苏馨玥", profile = "美术系大四学生,说话带一点调侃",
                    intro = "", exampleLines = "", openingLine = "来啦?",
                    appearance = "长发", appearanceStyle = "写实摄影风", createdAt = 1,
                ),
            )
            db.conversationDao().upsert(
                ConversationEntity(
                    id = 2, characterId = 1, mode = "im",
                    lastSummary = "", lastAt = 10, unread = 0, createdAt = 1,
                ),
            )
            // 3 回合:turn-a(1,2)/ turn-b(3,4)/ turn-c(5,6)
            db.messageDao().insert(MessageEntity(id = 1, conversationId = 2, turnId = "t-a", role = "user", content = "早上的话", at = 1))
            db.messageDao().insert(MessageEntity(id = 2, conversationId = 2, turnId = "t-a", role = "assistant", content = "早呀", at = 2))
            db.messageDao().insert(MessageEntity(id = 3, conversationId = 2, turnId = "t-b", role = "user", content = "天台见", at = 3))
            db.messageDao().insert(MessageEntity(id = 4, conversationId = 2, turnId = "t-b", role = "assistant", content = "好,带上奶茶", at = 4))
            db.messageDao().insert(MessageEntity(id = 5, conversationId = 2, turnId = "t-c", role = "user", content = "晚风很舒服", at = 5))
            db.messageDao().insert(MessageEntity(id = 6, conversationId = 2, turnId = "t-c", role = "assistant", content = "下次再来", at = 6))
        }
    }

    @After
    fun cleanup() {
        db.close()
        GenerationPrefs.setHandbookImages(ApplicationProvider.getApplicationContext(), false)
    }

    private fun handbookOutput(paragraphs: Int = 3): String =
        "{\"title\":\"放学后的天台\",\"mood\":\"微甜\",\"paragraphs\":[" +
            (1..paragraphs).joinToString(",") { "{\"body\":\"第${it}段的话\"}" } +
            "],\"imagePrompt\":\"晚霞\"}"

    private fun novelOutput(chapters: Int): String =
        "{\"title\":\"我们的夏天\",\"introduction\":\"简介\",\"chapters\":[" +
            (1..chapters).joinToString(",") {
                "{\"title\":\"第${it}章\",\"overview\":\"概要$it\",\"content\":\"第${it}章正文\"}"
            } + "],\"imagePrompt\":\"\"}"

    private suspend fun newRecord(
        kind: String,
        optionJson: String = "{}",
        sourceVersion: Long = 0, // 未回溯会话的版本行缺省为 0(memory_conversation_versions)
        requestId: String = "req-1",
        startId: Long? = 1,
        endId: Long? = 6,
    ): Long = db.chatDerivativeDao().insert(
        ChatDerivativeEntity(
            kind = kind,
            characterId = 1,
            conversationId = 2,
            optionJson = optionJson,
            sourceStartMessageId = startId,
            sourceEndMessageId = endId,
            sourceVersion = sourceVersion,
            requestId = requestId,
            imageStatus = ChatDerivativeEntity.IMAGE_PENDING,
            createdAt = 100,
            updatedAt = 100,
        ),
    )

    // ── G1 手帐 ──────────────────────────────────────────────────────

    @Test
    fun `handbook commits validated text with image skipped by default`() = runBlocking {
        replies += handbookOutput(3)
        val id = newRecord(ChatDerivativeEntity.KIND_HANDBOOK)
        assertTrue(generator.generate(id, "req-1"))
        val row = db.chatDerivativeDao().byId(id)!!
        assertEquals(ChatDerivativeEntity.STATE_SUCCEEDED, row.state)
        assertEquals("放学后的天台", row.title)
        val handbook = ChatDerivativeOutput.handbookFromJson(row.bodyJson)!!
        assertEquals(3, handbook.paragraphs.size)
        assertEquals("微甜", handbook.mood)
        // 配图默认关(GenerationPrefs.handbookImages 默认 false)→ skipped,纯文字可看
        assertEquals(ChatDerivativeEntity.IMAGE_SKIPPED, row.imageStatus)
        assertTrue(row.imageFile.isBlank())
        // prompt 只携带选中区间内的素材(区间 1..6 = 全部,断言有人设与对话)
        val user = capturedRequests.last().last { it.role == ChatRole.USER }
        assertTrue(user.content.contains("天台见"))
        val system = capturedRequests.last().first { it.role == ChatRole.SYSTEM }
        assertTrue(system.content.contains("苏馨玥"))
    }

    @Test
    fun `handbook prompt only contains the selected source range`() = runBlocking {
        replies += handbookOutput(3)
        // 选中最近一回合(消息 5..6):更早的「天台见」不进素材
        val id = newRecord(ChatDerivativeEntity.KIND_HANDBOOK, startId = 5, endId = 6)
        assertTrue(generator.generate(id, "req-1"))
        val user = capturedRequests.last().last { it.role == ChatRole.USER }
        assertTrue(user.content.contains("晚风很舒服"))
        assertFalse(user.content.contains("天台见"))
    }

    @Test
    fun `handbook image failure keeps the committed text and marks image failed`() = runBlocking {
        GenerationPrefs.setHandbookImages(ApplicationProvider.getApplicationContext(), true)
        replies += handbookOutput(3)
        val id = newRecord(ChatDerivativeEntity.KIND_HANDBOOK)
        assertTrue(generator.generate(id, "req-1"))
        val row = db.chatDerivativeDao().byId(id)!!
        assertEquals(ChatDerivativeEntity.STATE_SUCCEEDED, row.state)
        assertEquals(ChatDerivativeEntity.IMAGE_FAILED, row.imageStatus)
        assertTrue(ChatDerivativeOutput.handbookFromJson(row.bodyJson)!!.paragraphs.isNotEmpty())
    }

    @Test
    fun `handbook image success writes file path`() = runBlocking {
        GenerationPrefs.setHandbookImages(ApplicationProvider.getApplicationContext(), true)
        imageFile = File.createTempFile("handbook", ".jpg").apply { deleteOnExit() }
        replies += handbookOutput(3)
        val id = newRecord(ChatDerivativeEntity.KIND_HANDBOOK)
        assertTrue(generator.generate(id, "req-1"))
        val row = db.chatDerivativeDao().byId(id)!!
        assertEquals(ChatDerivativeEntity.IMAGE_COMPLETE, row.imageStatus)
        assertEquals(imageFile!!.absolutePath, row.imageFile)
    }

    @Test
    fun `succeeded handbook stuck in pending resumes image only without llm`() = runBlocking {
        GenerationPrefs.setHandbookImages(ApplicationProvider.getApplicationContext(), true)
        // 先收口成「成功但图 pending」(模拟提交后、回填前进程被杀)
        val id = newRecord(ChatDerivativeEntity.KIND_HANDBOOK)
        db.chatDerivativeDao().markSucceededIfGenerating(id, "手帐", ChatDerivativeOutput.handbookToJson(ChatDerivativeOutput.parseHandbook(handbookOutput(3))!!), 500L)
        val requestsBefore = capturedRequests.size
        assertTrue(generator.generate(id, "req-1"))
        // 不重发 LLM;图片缝回填 complete
        assertEquals(requestsBefore, capturedRequests.size)
        assertEquals(ChatDerivativeEntity.IMAGE_FAILED, db.chatDerivativeDao().byId(id)!!.imageStatus)
    }

    // ── G2 小说 ──────────────────────────────────────────────────────

    @Test
    fun `novel commits chapters inside the length bound with length budget`() = runBlocking {
        replies += novelOutput(5)
        val id = newRecord(ChatDerivativeEntity.KIND_NOVEL, "{\"length\":\"medium\"}")
        assertTrue(generator.generate(id, "req-1"))
        val row = db.chatDerivativeDao().byId(id)!!
        assertEquals(ChatDerivativeEntity.STATE_SUCCEEDED, row.state)
        val novel = ChatDerivativeOutput.novelFromJson(row.bodyJson)!!
        assertEquals(5, novel.chapters.size)
        // 输出预算随长度档(中篇 2400),温度 0.8
        assertEquals(2400, capturedConfigs.last().maxTokens)
        assertEquals(0.8f, capturedConfigs.last().temperature)
        assertEquals(ChatDerivativeEntity.IMAGE_SKIPPED, row.imageStatus)
        // 章数边界进了 system 契约
        assertTrue(capturedRequests.last().first { it.role == ChatRole.SYSTEM }.content.contains("4~5 章"))
    }

    @Test
    fun `novel chapter count outside bound fails as parse failure and writes nothing`() = runBlocking {
        replies += novelOutput(2) // 中篇要 4~5 章
        replies += novelOutput(9)
        val id = newRecord(ChatDerivativeEntity.KIND_NOVEL, "{\"length\":\"medium\"}")
        val failure = runCatching { generator.generate(id, "req-1") }.exceptionOrNull()
        assertTrue("应抛 DerivativeFailure(PARSE)", failure is DerivativeFailure)
        assertEquals(DerivativeFailureKind.PARSE, (failure as DerivativeFailure).kind)
        assertEquals(2, capturedRequests.size) // 解析失败重试一次
        val row = db.chatDerivativeDao().byId(id)!!
        assertEquals(ChatDerivativeEntity.STATE_GENERATING, row.state) // Worker 收口拨失败
        assertTrue(row.bodyJson.isEmpty())
    }

    @Test
    fun `novel retry once recovers a transient bad format`() = runBlocking {
        replies += "格式坏了"
        replies += novelOutput(3)
        val id = newRecord(ChatDerivativeEntity.KIND_NOVEL, "{\"length\":\"short\"}")
        assertTrue(generator.generate(id, "req-1"))
        assertEquals(ChatDerivativeEntity.STATE_SUCCEEDED, db.chatDerivativeDao().byId(id)!!.state)
    }

    // ── G3 合照 ──────────────────────────────────────────────────────

    @Test
    fun `photo commits image with description without llm`() = runBlocking {
        imageFile = File.createTempFile("photo", ".jpg").apply { deleteOnExit() }
        val id = newRecord(ChatDerivativeEntity.KIND_PHOTO, "{\"description\":\"天台看晚霞\"}")
        val requestsBefore = capturedRequests.size
        assertTrue(generator.generate(id, "req-1"))
        assertEquals(requestsBefore, capturedRequests.size) // 合照无 LLM 文本
        val row = db.chatDerivativeDao().byId(id)!!
        assertEquals(ChatDerivativeEntity.STATE_SUCCEEDED, row.state)
        assertEquals(ChatDerivativeEntity.IMAGE_COMPLETE, row.imageStatus)
        assertEquals(imageFile!!.absolutePath, row.imageFile)
        assertEquals("天台看晚霞", ChatDerivativeOutput.photoDescriptionFromJson(row.bodyJson))
        assertTrue(row.notified) // 通知幂等门在本进程可写(无通知权限时仅降级为库内标记)
    }

    @Test
    fun `photo image failure keeps a retryable failed record`() = runBlocking {
        val id = newRecord(ChatDerivativeEntity.KIND_PHOTO, "{\"description\":\"天台看晚霞\"}")
        assertFalse(generator.generate(id, "req-1"))
        val row = db.chatDerivativeDao().byId(id)!!
        assertEquals(ChatDerivativeEntity.STATE_FAILED, row.state)
        assertTrue(row.failureReason.contains("图片生成失败"))
        assertTrue(row.imageFile.isBlank())
    }

    @Test
    fun `photo blank description fails without image call`() = runBlocking {
        val id = newRecord(ChatDerivativeEntity.KIND_PHOTO, "{\"description\":\"  \"}")
        assertFalse(generator.generate(id, "req-1"))
        val row = db.chatDerivativeDao().byId(id)!!
        assertEquals(ChatDerivativeEntity.STATE_FAILED, row.state)
        assertTrue(row.failureReason.contains("描述为空"))
    }

    // ── 版本拦截 / 幂等 / 对账 ────────────────────────────────────────

    @Test
    fun `stale source version after rewind fails without writing or requesting`() = runBlocking {
        val id = newRecord(ChatDerivativeEntity.KIND_HANDBOOK, sourceVersion = 999) // 会话版本仍是 0
        assertFalse(generator.generate(id, "req-1"))
        val row = db.chatDerivativeDao().byId(id)!!
        assertEquals(ChatDerivativeEntity.STATE_FAILED, row.state)
        assertTrue(row.failureReason.contains("聊天历史已更改"))
        assertTrue(row.bodyJson.isEmpty())
        assertEquals(0, capturedRequests.size) // 版本拦截在请求前
    }

    @Test
    fun `finished record is idempotent and late request cannot overwrite`() = runBlocking {
        val id = newRecord(ChatDerivativeEntity.KIND_NOVEL, "{\"length\":\"short\"}")
        replies += novelOutput(3)
        assertTrue(generator.generate(id, "req-1"))
        val committed = db.chatDerivativeDao().byId(id)!!
        // 迟到旧任务(旧 requestId)不覆盖
        replies += novelOutput(2)
        assertTrue(generator.generate(id, "req-old"))
        assertEquals(committed.bodyJson, db.chatDerivativeDao().byId(id)!!.bodyJson)
        // 已收口记录重复执行同样幂等,且不再发请求
        assertEquals(1, capturedRequests.size)
        assertTrue(generator.generate(id, "req-1"))
        assertEquals(1, capturedRequests.size)
    }

    @Test
    fun `deleted record is a no-op not a failure`() = runBlocking {
        assertTrue(generator.generate(recordId = 404, requestId = "req-1"))
        assertEquals(0, capturedRequests.size)
    }

    @Test
    fun `reconcile fails interrupted records with terminal task and keeps queued ones`() = runBlocking {
        val taskDao = db.derivativeTaskDao()
        val failed = newRecord(ChatDerivativeEntity.KIND_HANDBOOK, requestId = "req-f")
        taskDao.insertIfMissing(
            DerivativeTaskRecord("w-f", "req-f", "HANDBOOK", "MANUAL", 2, 1, "failed", "生成失败，请重试", 1, 1, 1),
        )
        val queued = newRecord(ChatDerivativeEntity.KIND_PHOTO, requestId = "req-q")
        taskDao.insertIfMissing(
            DerivativeTaskRecord("w-q", "req-q", "PHOTO", "MANUAL", 2, 1, "running", null, 1, 1, null),
        )
        val orphan = newRecord(ChatDerivativeEntity.KIND_NOVEL, requestId = "req-x") // 任务行丢失(入队前中断)
        assertEquals(2, generator.reconcileInterrupted())
        assertEquals(ChatDerivativeEntity.STATE_FAILED, db.chatDerivativeDao().byId(failed)!!.state)
        assertEquals("生成失败，请重试", db.chatDerivativeDao().byId(failed)!!.failureReason)
        assertEquals(ChatDerivativeEntity.STATE_FAILED, db.chatDerivativeDao().byId(orphan)!!.state)
        assertEquals(ChatDerivativeEntity.STATE_GENERATING, db.chatDerivativeDao().byId(queued)!!.state)
    }
}
