package com.neethu.gouxiang.chat

import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.MemoryEntity
import com.neethu.gouxiang.data.MemoryTurn
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F3 记忆系统单测(假 LLM,零网络):
 * ops 解析 / 事件总结盖章 / 画像判定 / 容量分带 / 注入模糊后缀 / 协调器阈值与静默门。
 */
class MemorySystemTest {

    // ── ops 宽松解析 ────────────────────────────────────────────────

    @Test
    fun `ops parser accepts json wrapped in chatter and applies caps`() {
        val raw = "好的,这是结果:\n{\"ops\":[" +
            "{\"action\":\"add\",\"content\":\"周末一起去了美术馆\",\"category\":\"event\",\"importance\":9}," +
            "{\"action\":\"add\",\"content\":\"\",\"category\":\"event\"}" +
            "]}\n以上。"
        val ops = MemoryOpsParser.parse(raw)!!
        assertEquals(1, ops.size)
        assertEquals("周末一起去了美术馆", ops[0].content)
        assertEquals(9, ops[0].importance)
    }

    @Test
    fun `ops parser overlong content clamped to 120 and unknown action dropped`() {
        val long = "事".repeat(300)
        val raw = "{\"ops\":[" +
            "{\"action\":\"add\",\"content\":\"$long\"}," +
            "{\"action\":\"update\",\"id\":\"x\",\"content\":\"y\"}" +
            "]}"
        val ops = MemoryOpsParser.parse(raw)!!
        assertEquals(1, ops.size)
        assertEquals(120, ops[0].content.length)
    }

    @Test
    fun `ops parser returns null for non-json reply`() {
        assertNull(MemoryOpsParser.parse("今天聊得很开心,没有要记的事~"))
        assertNull(MemoryOpsParser.parse("{\"notops\":[]}"))
    }

    @Test
    fun `ops parser max five ops`() {
        val raw = (1..8).joinToString(",") {
            "{\"action\":\"add\",\"content\":\"事件$it\"}"
        }
        assertEquals(5, MemoryOpsParser.parse("{\"ops\":[$raw]}")!!.size)
    }

    // ── 容量渐忘(分带)────────────────────────────────────────────

    @Test
    fun `capacity counts only event memories`() {
        val mems = listOf(
            profileRow("我的称呼:老公"),
            eventRow(1L, at = 10),
            eventRow(2L, at = 20),
        )
        val st = MemoryCapacity.state(mems)
        assertEquals(2, st.used)
        assertEquals(0, st.fadedCount)
    }

    @Test
    fun `over capacity oldest events are faded not removed`() {
        // 造 22 条 event(容量 20):按 at 倒序,最旧 2 条 faded,仍全部返回
        val mems = (1..22).map { eventRow(it.toLong(), at = it.toLong()) }
        val (banded, state) = MemoryCapacity.band(mems)
        assertEquals(22, banded.size) // 不物理删除
        assertEquals(2, state.fadedCount)
        assertEquals(20, state.usedCapped)
        val faded = banded.filter { it.faded }.map { it.memory.id }.toSet()
        assertEquals(setOf(1L, 2L), faded) // 最旧的 1、2 号
    }

    @Test
    fun `profile memories never faded regardless of capacity`() {
        val mems = (1..25).map { eventRow(it.toLong(), at = it.toLong()) } + profileRow("我的身份:上班族")
        val (banded, _) = MemoryCapacity.band(mems)
        val profile = banded.first { it.memory.category == "profile" }
        assertFalse(profile.faded)
    }

    // ── 注入块(画像 + TopN + 模糊后缀)────────────────────────────

    @Test
    fun `injection block contains profile lines and faded suffix for over capacity events`() {
        val mems = buildList {
            add(profileRow("我的称呼:老公"))
            add(profileRow("我的身份:上班族"))
            add(eventRow(1L, at = 5))
            addAll((2..24).map { eventRow(it.toLong(), at = it.toLong()) })
        }
        val block = MemoryInjection.block("苏馨玥", mems, "在干嘛")!!
        assertTrue(block.contains("[记忆]"))
        assertTrue(block.contains("我的称呼:老公"))
        assertTrue(block.contains("我的身份:上班族"))
        // 容量内取最新 NORMAL_MAX 条(事件24..19);事件5 属容量外(模糊带)
        assertTrue(block.contains("事件24"))
        // faded 取最新 4 条容量外(事件5/4/3/2,at 平局事件1 归容量内)
        assertTrue(block.contains("事件2${MemoryCapacity.FADED_SUFFIX}"))
        assertTrue(block.contains("以下记忆逐渐模糊"))
        assertTrue(block.contains(MemoryCapacity.FADED_SUFFIX))
        // faded 注入最多 4 条(防 prompt 膨胀)
        val fadedLines = block.lineSequence().count { it.contains(MemoryCapacity.FADED_SUFFIX) }
        assertEquals(MemoryInjection.FADED_MAX, fadedLines)
    }

    @Test
    fun `injection block returns null when nothing memorable`() {
        assertNull(MemoryInjection.block("苏馨玥", emptyList(), "hi"))
    }

    // ── 事件总结(假 LLM)──────────────────────────────────────────

    @Test
    fun `summarize stamps category event and persists via book`() = runTest {
        val book = bookOf(FakeMemorySource())
        val llm = fakeLlm(
            "{\"ops\":[{\"action\":\"add\",\"content\":\"用户和苏馨玥约定周末去看新房,苏馨玥开始列画具清单\",\"category\":\"junk\",\"importance\":1}]}",
        )
        val ext = MemoryExtractor(book, llm, { fakeConfig })
        val ok = ext.summarize(listOf(MemoryTurn("周末去看房吗", "好呀,我超期待!")), char)
        assertTrue(ok)
        val events = book.events(char.id)
        assertEquals(1, events.size)
        // 客户端盖章:category=event、importance=5(模型值不信任)
        assertEquals("event", events[0].category)
        assertEquals(5, events[0].importance)
        assertTrue(events[0].content.contains("新房"))
    }

    @Test
    fun `summarize dedups same content on retry`() = runTest {
        val book = bookOf(FakeMemorySource())
        val ext = MemoryExtractor(
            book,
            fakeLlm("{\"ops\":[{\"action\":\"add\",\"content\":\"两人约定周末看新房\",\"category\":\"event\"}]}"),
            { fakeConfig },
        )
        val turns = listOf(MemoryTurn("周末去看房吗", "好呀"))
        assertTrue(ext.summarize(turns, char))
        assertTrue(ext.summarize(turns, char))
        assertEquals(1, book.events(char.id).size) // 去重不翻倍
    }

    @Test
    fun `summarize empty ops is success with no rows`() = runTest {
        val book = bookOf(FakeMemorySource())
        val ext = MemoryExtractor(book, fakeLlm("{\"ops\":[]}"), { fakeConfig })
        assertTrue(ext.summarize(listOf(MemoryTurn("嗯嗯", "哈哈")), char))
        assertTrue(book.events(char.id).isEmpty())
    }

    @Test
    fun `summarize unparseable output fails`() = runTest {
        val book = bookOf(FakeMemorySource())
        val ext = MemoryExtractor(book, fakeLlm("对不起我不太明白你的意思哦~"), { fakeConfig })
        assertFalse(ext.summarize(listOf(MemoryTurn("讲个事", "你说呀")), char))
        assertTrue(book.events(char.id).isEmpty())
    }

    // ── 画像判定(绝不安造 + null 语义)────────────────────────────

    @Test
    fun `extract profile writes name and identity`() = runTest {
        val book = bookOf(FakeMemorySource())
        val ext = MemoryExtractor(book, fakeLlm("{\"name\":\"老公\",\"identity\":\"上班族\"}"), { fakeConfig })
        assertTrue(ext.extractProfile(listOf(MemoryTurn("以后叫我老公吧", "好~老公")), char))
        val p = book.profile(char.id)
        assertEquals("老公", p.name)
        assertEquals("上班族", p.identity)
    }

    @Test
    fun `extract profile null both is success no write`() = runTest {
        val book = bookOf(FakeMemorySource())
        val ext = MemoryExtractor(book, fakeLlm("{\"name\":null,\"identity\":null}"), { fakeConfig })
        assertTrue(ext.extractProfile(listOf(MemoryTurn("今天天气不错", "是呀")), char))
        assertTrue(book.profile(char.id).isEmpty)
    }

    @Test
    fun `extract profile same subtype updates in place not append`() = runTest {
        val book = bookOf(FakeMemorySource())
        book.writeProfile(char.id, MemoryCategories.SUBTYPE_NAME, "老公")
        val ext = MemoryExtractor(
            book,
            fakeLlm("{\"name\":\"宝贝\",\"identity\":null}"),
            { fakeConfig },
        )
        assertTrue(ext.extractProfile(listOf(MemoryTurn("以后改口叫我宝贝", "好!宝贝")), char))
        val rows = book.all(char.id).filter { it.category == "profile" }
        assertEquals(1, rows.size) // 原地覆盖,不新增
        assertEquals("宝贝", rows[0].content)
    }

    // ── 协调器(阈值/静默门/手动总结)──────────────────────────────

    @Test
    fun `coordinator triggers summary when window reaches everyTurns`() = runTest {
        val book = bookOf(FakeMemorySource())
        val llm = delayedLlm("{\"ops\":[{\"action\":\"add\",\"content\":\"两人约定周五一起看新房\",\"category\":\"event\"}]}")
        val ext = MemoryExtractor(book, llm, { fakeConfig })
        val coord = ChatMemoryCoordinator(
            source = FakeMemorySource(),
            book = book,
            extractor = ext,
            scope = this,
            windowStore = FakeWindowStore(),
            everyTurns = 2,
            minChars = 999,
        )
        repeat(2) {
            coord.onTurnCompleted(char, "第${it}轮聊天说到了看新房的计划", "好呀我很期待")
        }
        // fire-and-forget:等后台收尾(假 LLM 带 delay,推进虚拟时钟)
        this.testScheduler.advanceUntilIdle()
        assertTrue(book.events(char.id).isNotEmpty())
        assertEquals(0, windowTurns(coord))
    }

    @Test
    fun `coordinator silence gate drops tiny window without request`() = runTest {
        val book = bookOf(FakeMemorySource())
        var called = 0
        val countingLlm = ChatOrchestrator.Llm { _, _ ->
            called++
            emptyStream()
        }
        val ext = MemoryExtractor(book, countingLlm, { fakeConfig })
        val coord = ChatMemoryCoordinator(
            source = FakeMemorySource(),
            book = book,
            extractor = ext,
            scope = this,
            windowStore = FakeWindowStore(),
            everyTurns = 1,
            minChars = 0,
        )
        coord.onTurnCompleted(char, "嗯", "哦")
        this.testScheduler.advanceUntilIdle()
        assertEquals(0, called)
        assertEquals(0, windowTurns(coord))
    }

    @Test
    fun `summarizeNow bypasses threshold and clears window on success`() = runTest {
        val book = bookOf(FakeMemorySource())
        val ext = MemoryExtractor(
            book,
            fakeLlm("{\"ops\":[{\"action\":\"add\",\"content\":\"用户告诉苏馨玥下周搬新家,两人都很兴奋\",\"category\":\"event\"}]}"),
            { fakeConfig },
        )
        val coord = ChatMemoryCoordinator(
            source = FakeMemorySource(),
            book = book,
            extractor = ext,
            scope = this,
            windowStore = FakeWindowStore(),
            everyTurns = 999, // 永不自动触发
            minChars = 999,
        )
        // 先垫一轮回合进窗口
        coord.onTurnCompleted(char, "我们要搬新家啦", "太好了!")
        val turns = listOf(MemoryTurn("我们要搬新家啦", "太好了!"))
        val ok = coord.summarizeNow(char, turns)
        assertTrue(ok)
        assertTrue(book.events(char.id).isNotEmpty())
        assertEquals(0, windowTurns(coord))
    }

    @Test
    fun `window round trips through persistence`() = runTest {
        val store = FakeWindowStore()
        val coord = ChatMemoryCoordinator(
            source = FakeMemorySource(),
            book = bookOf(FakeMemorySource()),
            extractor = MemoryExtractor(bookOf(FakeMemorySource()), fakeLlm("{\"ops\":[]}"), { fakeConfig }),
            scope = this,
            windowStore = store,
            everyTurns = 999,
            minChars = 999,
        )
        coord.onTurnCompleted(char, "用户说的话", "角色的回复")
        val payload = store.payload!!
        assertTrue(payload.isNotEmpty())
        val turns = ChatMemoryCoordinator.WindowCodec.toTurns(payload)
        assertEquals(1, turns.size)
        assertEquals("用户说的话", turns[0].user)
        assertEquals("角色的回复", turns[0].assistant)
    }

    // ── 基建 ────────────────────────────────────────────────────────

    private val char = CharacterEntity(
        id = 1L,
        name = "苏馨玥",
        profile = "p",
        intro = "i",
        exampleLines = "",
        openingLine = "o",
        appearance = "a",
        appearanceStyle = "写实摄影风",
        createdAt = 0,
    )

    private val fakeConfig = com.neethu.gouxiang.ai.adapter.LlmConfig(
        baseUrl = "http://localhost", apiKey = "k", model = "m",
    )

    private fun profileRow(content: String) = MemoryEntity(
        characterId = 1L, category = "profile", content = content, importance = 8, at = 1,
    )

    private fun eventRow(id: Long, at: Long) = MemoryEntity(
        id = id, characterId = 1L, category = "event", content = "事件$id", importance = 5, at = at,
    )

    private fun bookOf(source: FakeMemorySource) = MemoryBook(source).also {
        it.characterNameResolver = { "苏馨玥" }
    }

    /** 单发回复的假 LLM(每次调用回复同一文本;协调器防抖下无并发问题)。 */
    private fun fakeLlm(reply: String): ChatOrchestrator.Llm = ChatOrchestrator.Llm { _, _ ->
        kotlinx.coroutines.flow.flow {
            emit(com.neethu.gouxiang.ai.adapter.LlmStreamEvent.TextDelta(reply))
            emit(com.neethu.gouxiang.ai.adapter.LlmStreamEvent.Finish("stop"))
        }
    }

    /**
     * 带 delay 的假 LLM:模拟真实网络节奏。零 delay 的 flow 会被
     * collect 同步串行执行完,后台 Job 的 onTurnCompleted 边界与
     * 虚拟时钟推进脱钩;delay 让 advanceUntilIdle 驱动后台收尾。
     */
    private fun delayedLlm(reply: String): ChatOrchestrator.Llm = ChatOrchestrator.Llm { _, _ ->
        kotlinx.coroutines.flow.flow {
            kotlinx.coroutines.delay(10)
            emit(com.neethu.gouxiang.ai.adapter.LlmStreamEvent.TextDelta(reply))
            emit(com.neethu.gouxiang.ai.adapter.LlmStreamEvent.Finish("stop"))
        }
    }

    private fun emptyStream(): kotlinx.coroutines.flow.Flow<com.neethu.gouxiang.ai.adapter.LlmStreamEvent> =
        kotlinx.coroutines.flow.flow { }

    private suspend fun windowTurns(coord: ChatMemoryCoordinator): Int {
        val line = coord.windowLine()
        return line.substringAfter("window=").substringBefore(" turns").toInt()
    }

    /** 内存窗口存储(验证序列化回路)。 */
    private class FakeWindowStore : com.neethu.gouxiang.chat.MemoryWindowStore {
        var payload: String? = null
        override suspend fun loadPayload(): String? = payload
        override suspend fun save(payload: String) {
            this.payload = payload
        }
    }
}
