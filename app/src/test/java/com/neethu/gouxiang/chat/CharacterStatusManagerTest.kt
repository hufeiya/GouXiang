package com.neethu.gouxiang.chat

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.CharacterStatusEntity
import com.neethu.gouxiang.data.GouXiangDatabase
import com.neethu.gouxiang.data.MemoryConversationVersion
import com.neethu.gouxiang.data.MessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * E 工作包 E2/E3:角色状态记录的行为单测(内存库,真实事务与版本边界)。
 * 覆盖:schema 校验落库 / 解析失败不落库(最近有效保持)/ 回溯版本拦截(过期
 * 任务不回写)/ 回合收尾冷却与空回合守卫 / 回溯标记失效与角色隔离 / 手动注入 /
 * 记录优先规则兜底。
 *
 * 事务在 Room 自有线程执行并跨线程续跑协程,用 runBlocking + 真实时钟等待
 * 收尾(ProactiveLedgerTest 同款风格),不依赖虚拟时间调度。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CharacterStatusManagerTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), GouXiangDatabase::class.java,
    ).allowMainThreadQueries().build()

    private var charA = 0L
    private var charB = 0L
    private var convA = 0L
    private var convB = 0L

    @Before
    fun seed(): Unit = runBlocking {
        val dao = db.seedDao()
        val a = dao.saveCharacterAtomic(
            character = character("状态甲"),
            openingContent = "嗨", openingActions = emptyList(), openingAt = 1L, appearanceChanged = false,
        )
        val b = dao.saveCharacterAtomic(
            character = character("状态乙"),
            openingContent = "嗨", openingActions = emptyList(), openingAt = 1L, appearanceChanged = false,
        )
        charA = a.characterId; convA = a.conversationId
        charB = b.characterId; convB = b.conversationId
    }

    @After fun close() = db.close()

    private fun character(name: String) = CharacterEntity(
        name = name, profile = "设定", intro = "", exampleLines = "",
        openingLine = "嗨", appearance = "", appearanceStyle = "", createdAt = 1L,
    )

    private fun entityOf(characterId: Long) = CharacterEntity(
        id = characterId, name = "角色", profile = "p", intro = "", exampleLines = "",
        openingLine = "o", appearance = "a", appearanceStyle = "", createdAt = 0,
    )

    private fun manager(llm: ChatOrchestrator.Llm, scope: CoroutineScope = CoroutineScope(Dispatchers.Default)) =
        CharacterStatusManager(
            db = db,
            llm = llm,
            llmConfig = { com.neethu.gouxiang.ai.adapter.LlmConfig("http://localhost", "k", "m") },
            scope = scope,
        )

    private fun okLlm(reply: String): ChatOrchestrator.Llm = ChatOrchestrator.Llm { _, _ ->
        flow {
            emit(com.neethu.gouxiang.ai.adapter.LlmStreamEvent.TextDelta(reply))
            emit(com.neethu.gouxiang.ai.adapter.LlmStreamEvent.Finish("stop"))
        }
    }

    /** 真实时钟等待(后台 fire-and-forget 的收尾轮询;超时即失败)。 */
    private suspend fun await(timeoutMs: Long = 3000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(20)
        assertTrue("等待条件超时", condition())
    }

    // ── schema 校验落库 ─────────────────────────────────────────────

    @Test
    fun `generate writes active record with source range and version`() = runBlocking {
        val endId = db.messageDao().insert(
            MessageEntity(conversationId = convA, turnId = "turn-1", role = "assistant", kind = "text", content = "哈喽", at = 9),
        )
        val m = manager(okLlm("{\"emoji\":\"🤔\",\"mood\":\"开心\",\"doing\":\"在等你的消息\",\"thinking\":\"他在忙什么\"}"))
        val record = m.generate(entityOf(charA), convA, "turn-1", "你在干嘛", "(托腮)在想你呀", userMessageId = 10L, sourceKind = CharacterStatusEntity.SOURCE_TURN)
        assertNotNull(record)
        val rows = db.characterStatusDao().forCharacter(charA)
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("🤔", row.emoji)
        assertEquals("开心", row.mood)
        assertEquals("在等你的消息", row.doing)
        assertEquals("他在忙什么", row.thought)
        assertEquals(CharacterStatusEntity.SOURCE_TURN, row.sourceKind)
        assertEquals(convA, row.conversationId)
        assertEquals(10L, row.sourceStartMessageId)
        assertEquals(endId, row.sourceEndMessageId)
        assertEquals(CharacterStatusEntity.STATE_ACTIVE, row.state)
        assertTrue(row.generatedAt > 0)
    }

    @Test
    fun `parser rejects garbage and clamps fields`() {
        // 非 JSON / 缺 doing → null(调用方不落库)
        assertNull(StatusSnapshotParser.parse("今天天气不错~"))
        assertNull(StatusSnapshotParser.parse("{\"mood\":\"开心\"}"))
        // 超长截断 + emoji 非法兜底 + thinking 缺省归空
        val snap = StatusSnapshotParser.parse(
            "{\"emoji\":\"ab\",\"mood\":\"${"心".repeat(60)}\",\"doing\":\"${"做".repeat(60)}\"}",
        )!!
        assertEquals(StatusSnapshotParser.FIELD_MAX_CHARS, snap.doing.length)
        assertEquals(StatusSnapshotParser.FIELD_MAX_CHARS, snap.mood.length)
        assertEquals(StatusSnapshotParser.DEFAULT_EMOJI, snap.emoji)
        assertEquals("", snap.thought)
        // 单个 BMP 外 emoji(代理对)合法保留
        assertEquals("🍔", StatusSnapshotParser.parse("{\"emoji\":\"🍔\",\"doing\":\"吃东西\"}")!!.emoji)
    }

    @Test
    fun `generate failure keeps last valid state`() = runBlocking {
        val good = manager(okLlm("{\"doing\":\"在等你的消息\"}"))
        assertNotNull(good.generate(entityOf(charA), convA, "turn-1", "hi", "哈喽", null, CharacterStatusEntity.SOURCE_TURN))
        // 之后的回合 LLM 输出垃圾 → 不写行,最近有效状态保留为唯一一行
        val bad = manager(okLlm("对不起我不知道该说什么"))
        assertNull(bad.generate(entityOf(charA), convA, "turn-2", "还在吗", "在呀", null, CharacterStatusEntity.SOURCE_TURN))
        val rows = db.characterStatusDao().forCharacter(charA)
        assertEquals(1, rows.size)
        assertEquals("在等你的消息", rows.single().doing)
        // 网络硬失败(流抛异常)同样不落库
        val broken = manager(ChatOrchestrator.Llm { _, _ -> throw java.io.IOException("offline") })
        assertNull(broken.generate(entityOf(charA), convA, "turn-3", "嗯", "嗯嗯", null, CharacterStatusEntity.SOURCE_TURN))
        assertEquals(1, db.characterStatusDao().forCharacter(charA).size)
    }

    // ── 回溯版本拦截(过期任务不回写)──────────────────────────────

    @Test
    fun `rewind during request blocks stale write-back via version`() = runBlocking {
        // LLM 流中途把会话版本 +1(模拟回溯并发),候选落库被拦截
        val racingLlm = ChatOrchestrator.Llm { _, _ ->
            flow {
                emit(com.neethu.gouxiang.ai.adapter.LlmStreamEvent.TextDelta("{\"doing\":\"过期状态\"}"))
                db.memoryWindowDao().setVersion(MemoryConversationVersion(convA, 1L))
                emit(com.neethu.gouxiang.ai.adapter.LlmStreamEvent.Finish("stop"))
            }
        }
        assertNull(manager(racingLlm).generate(entityOf(charA), convA, "turn-1", "hi", "哈喽", null, CharacterStatusEntity.SOURCE_TURN))
        assertTrue(db.characterStatusDao().forCharacter(charA).isEmpty())
    }

    // ── 回合收尾:冷却、空回合守卫与角色隔离 ───────────────────────

    @Test
    fun `onTurnCompleted respects cooldown skips empty turns and isolates characters`() = runBlocking {
        var calls = 0
        val countingLlm = ChatOrchestrator.Llm { _, _ ->
            calls++
            flow {
                emit(com.neethu.gouxiang.ai.adapter.LlmStreamEvent.TextDelta("{\"emoji\":\"🤔\",\"doing\":\"在等你的消息\"}"))
                emit(com.neethu.gouxiang.ai.adapter.LlmStreamEvent.Finish("stop"))
            }
        }
        val scope = CoroutineScope(Dispatchers.Default)
        val m = manager(countingLlm, scope)
        m.minIntervalMs = 10 * 60_000L // 冷却窗远大于两次调用间的真实时钟间隔
        m.onTurnCompleted(entityOf(charA), convA, "turn-1", "hi", "哈喽", null)
        await { calls == 1 }
        await { db.characterStatusDao().forCharacter(charA).size == 1 } // 落库收尾
        // 冷却窗内的第二轮不触发(同毫秒内调用,真实时钟间隔为 0)
        m.onTurnCompleted(entityOf(charA), convA, "turn-2", "还在吗", "在呀", null)
        Thread.sleep(300)
        assertEquals(1, calls)
        // 空文本回合不触发(兜底/降级回合不会污染状态记录)
        m.onTurnCompleted(entityOf(charA), convA, "turn-3", "", "", null)
        Thread.sleep(200)
        assertEquals(1, calls)
        // 只有一条落库,且归属角色 A;B 角色零记录(状态按角色隔离)
        assertEquals(1, db.characterStatusDao().forCharacter(charA).size)
        assertNull(db.characterStatusDao().latestActive(charB))
        assertTrue(db.characterStatusDao().forCharacter(charB).isEmpty())
    }

    // ── 回溯标记失效(与 DurableMemoryProcessor.rewind 同事务语义)──

    @Test
    fun `rewind marks affected records stale and keeps others active`() = runBlocking {
        val dao = db.characterStatusDao()
        val old = CharacterStatusEntity(
            characterId = charA, conversationId = convA, emoji = "💤", mood = "平静",
            doing = "旧状态", thought = "", sourceKind = "turn", sourceConversationId = convA,
            sourceTurnId = "turn-old", sourceStartMessageId = 1L, sourceEndMessageId = 5L,
            sourceVersion = 0, generatedAt = 100,
        )
        val fresh = old.copy(id = 0, doing = "新状态", sourceStartMessageId = 10L, sourceEndMessageId = 20L, generatedAt = 200)
        val otherChar = old.copy(id = 0, characterId = charB, conversationId = convB, doing = "别的角色状态", generatedAt = 150)
        dao.insert(old); dao.insert(fresh); dao.insert(otherChar)

        // 回溯到消息 #15:锚定区间含 15 的记录被标记(新状态 10..20 命中,旧状态 1..5 保留)
        assertEquals(1, dao.markStaleFrom(convA, 15L))
        assertEquals(CharacterStatusEntity.STATE_STALE, dao.forCharacter(charA).single { it.doing == "新状态" }.state)
        assertEquals(CharacterStatusEntity.STATE_ACTIVE, dao.forCharacter(charA).single { it.doing == "旧状态" }.state)
        // 其他角色不受影响(状态按角色隔离)
        assertEquals(CharacterStatusEntity.STATE_ACTIVE, dao.forCharacter(charB).single().state)
        // 失效行不再作为当前状态:latestActive 回落到未受影响的旧状态
        assertEquals("旧状态", dao.latestActive(charA)!!.doing)
        // 清理失效记录只删 stale
        assertEquals(1, dao.clearStale(charA))
        assertEquals(1, dao.forCharacter(charA).size)
    }

    // ── 手动注入与规则兜底 ─────────────────────────────────────────

    @Test
    fun `manual injection writes manual source and rejects empty doing`() = runBlocking {
        val m = manager(okLlm("unused"))
        val record = m.injectManual(entityOf(charA), convA, mood = "兴奋", doing = "在准备周末的画展", thought = "希望他能来", emoji = null)
        assertEquals(CharacterStatusEntity.SOURCE_MANUAL, record.sourceKind)
        assertEquals("✨", record.emoji)
        assertEquals(CharacterStatusEntity.STATE_ACTIVE, record.state)
        assertEquals("在准备周末的画展", db.characterStatusDao().latestActive(charA)!!.doing)
        // 空 doing 拒绝(无内容的状态没有意义)
        try {
            m.injectManual(entityOf(charA), convA, mood = "", doing = "  ", thought = "", emoji = null)
            throw AssertionError("空 doing 应被拒绝")
        } catch (expected: IllegalArgumentException) {
            // 预期
        }
    }

    @Test
    fun `current line prefers record and falls back to rule when record goes stale`() = runBlocking {
        val m = manager(okLlm("{\"emoji\":\"🤔\",\"doing\":\"在等你的消息\"}"))
        val character = entityOf(charA)
        val recent = listOf(
            MessageEntity(conversationId = convA, turnId = "turn-u", role = "user", content = "饿了想吃火锅", at = 5),
        )
        // 无记录 → StatusRule 规则兜底(关键词命中)
        assertEquals("🍔", m.currentLine(character, convA, recent).emoji)
        // 有记录 → 记录优先(回合来源,带锚点)
        val msgId = db.messageDao().insert(
            MessageEntity(conversationId = convA, turnId = "turn-x", role = "assistant", kind = "text", content = "哈喽", at = 9),
        )
        assertNotNull(m.generate(character, convA, "turn-x", "hi", "哈喽", null, CharacterStatusEntity.SOURCE_TURN))
        val recordLine = m.currentLine(character, convA, recent)
        assertEquals("🤔", recordLine.emoji)
        assertEquals("在等你的消息", recordLine.text)
        // 回溯命中锚点 → 记录失效,回落规则兜底
        assertEquals(1, db.characterStatusDao().markStaleFrom(convA, msgId))
        assertEquals("🍔", m.currentLine(character, convA, recent).emoji)
    }

    @Test
    fun `manual injected line shows while no newer turn record exists`() = runBlocking {
        val m = manager(okLlm("{\"doing\":\"回合状态\"}"))
        val character = entityOf(charA)
        // 注入手动状态 → 状态条立即取它(离线可验收,无需 LLM)
        m.injectManual(character, convA, mood = "低落", doing = "在整理旧照片", thought = "", emoji = "🍂")
        val line = m.currentLine(character, convA, emptyList())
        assertEquals("🍂", line.emoji)
        assertEquals("在整理旧照片", line.text)
        // 失效记录不回写:手动记录不锚定消息,回溯消息不影响它(来源为空)
        assertEquals(0, db.characterStatusDao().markStaleFrom(convA, 1L))
        assertFalse(db.characterStatusDao().latestActive(charA)!!.isStale)
    }
}
