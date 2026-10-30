package com.neethu.gouxiang.sidestory

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
import com.neethu.gouxiang.data.ConversationEntity
import com.neethu.gouxiang.data.DerivativeTaskRecord
import com.neethu.gouxiang.data.GouXiangDatabase
import com.neethu.gouxiang.data.MessageEntity
import com.neethu.gouxiang.data.SidestoryRecordEntity
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
 * F3/F4 生成管线单测(假 LLM,零网络):文本先出图片可选、schema 校验后才
 * 落库、版本拦截(回溯过期任务不回写)、幂等(重复收口/迟到旧任务/记录已删)、
 * E 工作包状态注入、重启对账(任务终态 → 记录拨失败)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SidestoryGeneratorTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), GouXiangDatabase::class.java,
    ).allowMainThreadQueries().build()

    /** 捕获到的请求消息(断言 prompt 素材用)。 */
    private val capturedRequests = mutableListOf<List<ChatMessage>>()

    /** 待回放的 LLM 输出(按请求顺序弹出;队列空=空输出)。 */
    private val replies = ArrayDeque<String>()

    private val llm = ChatOrchestrator.Llm { messages, _ ->
        capturedRequests += messages
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

    private val generator = SidestoryGenerator(
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
            db.messageDao().insert(MessageEntity(conversationId = 2, turnId = "t1", role = "user", content = "今天好累", at = 5))
            db.messageDao().insert(MessageEntity(conversationId = 2, turnId = "t1", role = "assistant", content = "那快歇着", at = 6))
        }
    }

    @After
    fun cleanup() {
        db.close()
        GenerationPrefs.setSidestoryImages(
            ApplicationProvider.getApplicationContext(),
            false,
        )
    }

    private fun theaterOutput(pages: Int = 3): String =
        "{\"title\":\"微醺的坦白\",\"pages\":[" +
            (1..pages).joinToString(",") { "{\"title\":\"格$it\",\"body\":\"第${it}格的话\",\"imagePrompt\":\"\"}" } +
            "]}"

    private suspend fun newRecord(
        templateKey: String,
        sourceVersion: Long = 0, // 未回溯会话的版本行缺省为 0(memory_conversation_versions)
        requestId: String = "req-1",
    ): Long = db.sidestoryRecordDao().insert(
        SidestoryRecordEntity(
            kind = SidestoryRecordEntity.KIND_SIDESTORY,
            templateKey = templateKey,
            templateVersion = SidestoryCatalog.VERSION,
            characterId = 1,
            conversationId = 2,
            sourceStartMessageId = 1,
            sourceEndMessageId = 2,
            sourceVersion = sourceVersion,
            requestId = requestId,
            imageStatus = SidestoryRecordEntity.IMAGE_PENDING,
            createdAt = 100,
            updatedAt = 100,
        ),
    )

    // ── 成功链路 ─────────────────────────────────────────────────────

    @Test
    fun `theater generation commits validated text first with image skipped by default`() = runBlocking {
        replies += theaterOutput(3)
        val id = newRecord("Drunk")
        assertTrue(generator.generate(id, "req-1"))
        val row = db.sidestoryRecordDao().byId(id)!!
        assertEquals(SidestoryRecordEntity.STATE_SUCCEEDED, row.state)
        assertEquals("微醺的坦白", row.title)
        val pages = SidestoryOutput.fromJson(row.bodyJson)
        assertEquals(3, pages.size)
        assertEquals("第1格的话", pages[0].body)
        // 配图默认关(GenerationPrefs.sidestoryImages 默认 false)→ skipped,纯文字可看
        assertEquals(SidestoryRecordEntity.IMAGE_SKIPPED, row.imageStatus)
        assertTrue(row.imageFile.isBlank())
        // prompt 携带人设与最近对话
        val user = capturedRequests.last().last { it.role == ChatRole.USER }
        assertTrue(user.content.contains("今天好累"))
        val system = capturedRequests.last().first { it.role == ChatRole.SYSTEM }
        assertTrue(system.content.contains("苏馨玥"))
        assertTrue(system.content.contains("选题与基调"))
    }

    @Test
    fun `image failure keeps the committed text and marks image failed`() = runBlocking {
        GenerationPrefs.setSidestoryImages(ApplicationProvider.getApplicationContext(), true)
        replies += theaterOutput(3)
        val id = newRecord("Drunk")
        assertTrue(generator.generate(id, "req-1"))
        val row = db.sidestoryRecordDao().byId(id)!!
        assertEquals(SidestoryRecordEntity.STATE_SUCCEEDED, row.state)
        assertEquals(SidestoryRecordEntity.IMAGE_FAILED, row.imageStatus)
        assertTrue(SidestoryOutput.fromJson(row.bodyJson).isNotEmpty())
    }

    @Test
    fun `image success writes file path and complete status`() = runBlocking {
        GenerationPrefs.setSidestoryImages(ApplicationProvider.getApplicationContext(), true)
        imageFile = File.createTempFile("sidestory", ".jpg")
        imageFile!!.deleteOnExit()
        replies += theaterOutput(3)
        val id = newRecord("Drunk")
        assertTrue(generator.generate(id, "req-1"))
        val row = db.sidestoryRecordDao().byId(id)!!
        assertEquals(SidestoryRecordEntity.IMAGE_COMPLETE, row.imageStatus)
        assertEquals(imageFile!!.absolutePath, row.imageFile)
    }

    @Test
    fun `checkphone commits eleven stamped sections`() = runBlocking {
        val entry = SidestoryCatalog.byKey("checkphone")!!
        replies += "{\"title\":\"TA的手机\",\"pages\":[" +
            entry.sections.joinToString(",") { "{\"title\":\"乱写\",\"body\":\"$it 的内容\"}" } + "]}"
        val id = newRecord("checkphone")
        assertTrue(generator.generate(id, "req-1"))
        val row = db.sidestoryRecordDao().byId(id)!!
        val pages = SidestoryOutput.fromJson(row.bodyJson)
        assertEquals(11, pages.size)
        assertEquals(entry.sections, pages.map { it.title })
    }

    // ── 失败链路(schema 校验后才落库)──────────────────────────────

    @Test
    fun `unparsable output twice throws parse failure and writes nothing`() = runBlocking {
        replies += "我不会输出 JSON"
        replies += "真的不会"
        val id = newRecord("Drunk")
        val failure = runCatching { generator.generate(id, "req-1") }.exceptionOrNull()
        assertTrue("应抛 DerivativeFailure(PARSE)", failure is DerivativeFailure)
        assertEquals(DerivativeFailureKind.PARSE, (failure as DerivativeFailure).kind)
        assertEquals(2, capturedRequests.size) // 解析失败重试一次(06 专项口径)
        val row = db.sidestoryRecordDao().byId(id)!!
        assertEquals(SidestoryRecordEntity.STATE_GENERATING, row.state) // Worker 收口拨失败
        assertTrue(row.bodyJson.isEmpty())
    }

    @Test
    fun `retry once recovers a transient bad format`() = runBlocking {
        replies += "格式坏了"
        replies += theaterOutput(4)
        val id = newRecord("Drunk")
        assertTrue(generator.generate(id, "req-1"))
        assertEquals(SidestoryRecordEntity.STATE_SUCCEEDED, db.sidestoryRecordDao().byId(id)!!.state)
    }

    // ── 版本拦截 / 幂等 ─────────────────────────────────────────────

    @Test
    fun `stale source version after rewind fails without writing`() = runBlocking {
        val id = newRecord("Drunk", sourceVersion = 999) // 会话版本仍是 0,快照版本 999 = 过期
        assertFalse(generator.generate(id, "req-1"))
        val row = db.sidestoryRecordDao().byId(id)!!
        assertEquals(SidestoryRecordEntity.STATE_FAILED, row.state)
        assertTrue(row.failureReason.contains("聊天历史已更改"))
        assertTrue(row.bodyJson.isEmpty())
        assertEquals(0, capturedRequests.size) // 版本拦截在请求前
    }

    @Test
    fun `finished record is an idempotent no-op and late request cannot overwrite`() = runBlocking {
        val id = newRecord("Drunk")
        replies += theaterOutput(3)
        assertTrue(generator.generate(id, "req-1"))
        val committed = db.sidestoryRecordDao().byId(id)!!
        // 迟到旧任务(旧 requestId)不覆盖
        replies += theaterOutput(5)
        assertTrue(generator.generate(id, "req-old"))
        assertEquals(committed.bodyJson, db.sidestoryRecordDao().byId(id)!!.bodyJson)
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

    // ── E 状态复用(查Ta状态)────────────────────────────────────────

    @Test
    fun `peekintoheart embeds the E status snapshot into the prompt`() = runBlocking {
        db.characterStatusDao().insert(
            com.neethu.gouxiang.data.CharacterStatusEntity(
                characterId = 1, conversationId = 2, emoji = "☁️",
                mood = "有点低落", doing = "在整理相册", thought = "想给他看",
                sourceKind = "turn", generatedAt = 1000,
            ),
        )
        val entry = SidestoryCatalog.byKey("peekintoheart")!!
        replies += "{\"title\":\"TA的内心\",\"pages\":[" +
            entry.sections.joinToString(",") { "{\"title\":\"$it\",\"body\":\"$it 内容\"}" } + "]}"
        val id = newRecord("peekintoheart")
        assertTrue(generator.generate(id, "req-1"))
        val user = capturedRequests.last().last { it.role == ChatRole.USER }
        assertTrue(user.content.contains("有点低落"))
        assertTrue(user.content.contains("在整理相册"))
        assertEquals(4, SidestoryOutput.fromJson(db.sidestoryRecordDao().byId(id)!!.bodyJson).size)
    }

    // ── 重启对账 ─────────────────────────────────────────────────────

    @Test
    fun `reconcile fails interrupted records with terminal task and keeps queued ones`() = runBlocking {
        val taskDao = db.derivativeTaskDao()
        val failed = newRecord("Drunk", requestId = "req-f")
        taskDao.insertIfMissing(
            DerivativeTaskRecord("w-f", "req-f", "SIDESTORY", "MANUAL", 2, 1, "failed", "生成失败，请重试", 1, 1, 1),
        )
        val queued = newRecord("Drunk", requestId = "req-q")
        taskDao.insertIfMissing(
            DerivativeTaskRecord("w-q", "req-q", "SIDESTORY", "MANUAL", 2, 1, "running", null, 1, 1, null),
        )
        val orphan = newRecord("Drunk", requestId = "req-x") // 任务行丢失(入队前中断)
        assertEquals(2, generator.reconcileInterrupted())
        assertEquals(SidestoryRecordEntity.STATE_FAILED, db.sidestoryRecordDao().byId(failed)!!.state)
        assertEquals("生成失败，请重试", db.sidestoryRecordDao().byId(failed)!!.failureReason)
        assertEquals(SidestoryRecordEntity.STATE_FAILED, db.sidestoryRecordDao().byId(orphan)!!.state)
        assertEquals(SidestoryRecordEntity.STATE_GENERATING, db.sidestoryRecordDao().byId(queued)!!.state)
    }
}
