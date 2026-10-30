package com.neethu.gouxiang.chat

import com.neethu.gouxiang.ai.adapter.ChatMessage
import com.neethu.gouxiang.ai.adapter.ChatRole
import com.neethu.gouxiang.ai.adapter.LlmConfig
import com.neethu.gouxiang.ai.adapter.LlmStreamEvent
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.MemoryEntity
import com.neethu.gouxiang.data.MessageEntity
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天编排器单测:上下文组装 + 流式多泡落库(假 LLM,零网络)。
 */
class ChatOrchestratorTest {

    // ── 上下文组装 ──────────────────────────────────────────────────

    @Test
    fun `system prompt contains persona protocol and memories`() {
        val p = ChatContextBuilder.systemPrompt(char, memories, maxBubbles = 4, maxBubbleChars = 20)
        assertTrue(p.contains("苏馨玥"))
        assertTrue(p.contains("美术系大四学生"))
        assertTrue(p.contains("我的称呼:老公"))
        assertTrue(p.contains("一起看了新房"))
        assertTrue(p.contains("≤20字/条"))
        assertTrue(p.contains("连发 4 条"))
        assertTrue(p.contains("[sticker:"))
        // 连发是硬规则且带 few-shot 示例(实机 F2:只提一句会输出单泡)
        assertTrue(p.contains("必须输出 4 条独立的短消息"))
        assertTrue(p.contains("[sticker:歪头撒娇]"))
    }

    @Test
    fun `event memories sorted by time desc and capped at 6`() {
        val many = (1..9).map {
            MemoryEntity(characterId = 1, category = "event", content = "事$it", at = it.toLong())
        }
        val p = ChatContextBuilder.systemPrompt(char, many)
        assertTrue(p.contains("事9"))
        assertTrue(!p.contains("事3")) // 9,8,7,6,5,4 → 最旧的 3 被裁掉
    }

    @Test
    fun `history encodes assistant actions and placeholder kinds`() {
        val h = ChatContextBuilder.history(
            listOf(
                MessageEntity(
                    conversationId = 1, turnId = "t1", role = "assistant",
                    content = "那房子真的好喜欢",
                    actionsJson = """["靠在你肩头"]""",
                    kind = "text",
                ),
                MessageEntity(
                    conversationId = 1, turnId = "t2", role = "user",
                    content = "快了快了", kind = "text",
                ),
                MessageEntity(
                    conversationId = 1, turnId = "t3", role = "assistant",
                    content = "哼", actionsJson = "[]", kind = "sticker",
                ),
            ),
        )
        assertEquals("(靠在你肩头)那房子真的好喜欢", h[0].content)
        assertEquals(ChatRole.ASSISTANT, h[0].role)
        assertEquals("快了快了", h[1].content)
        assertEquals(ChatRole.USER, h[1].role)
        assertEquals("[sticker:哼]", h[2].content)
    }

    // ── 编排器回合(假 LLM)────────────────────────────────────────

    @Test
    fun `full round persists user message and multi-bubbles in order`() = runTest {
        val store = FakeStore(char, memories)
        val llm = fakeLlm(listOf("老公,(靠在你肩头)那房子真的好喜欢!\n[sticker:歪头撒娇]"))
        val orch = ChatOrchestrator(store, llm, bubbleGapMs = 0)
        // ViewModel 契约:用户消息先落库,编排器只管回复侧
        store.insertMessage(MessageEntity(conversationId = 1, turnId = "u", role = "user", content = "想搬新家了"))
        val collected = mutableListOf<MessageEntity>()
        orch.respondTo(1L, 1L, "想搬新家了", onAssistantSaved = { collected += it })

        // 用户消息已落库(ViewModel 落,非编排器)
        assertEquals("想搬新家了", store.inserted.first { it.role == "user" }.content)
        // 2 条 assistant 泡:文本(带动作)+ 贴纸
        val assistants = store.inserted.filter { it.role == "assistant" }
        assertEquals(2, assistants.size)
        // 单个换行不切段(对齐 F1 既定行为,MultiBubbleParserTest 同款):
        // 第一行整体为一条泡,() 动作剥离进 actionsJson
        assertEquals("老公,那房子真的好喜欢!", assistants[0].content)
        assertEquals("""["靠在你肩头"]""", assistants[0].actionsJson)
        assertEquals("sticker", assistants[1].kind)
        assertEquals("歪头撒娇", assistants[1].content)
        // 回调与落库一致(顺序)
        assertEquals(assistants, collected)
        // 会话摘要刷新为最后一泡,且按泡型降级(附录 C-I3):贴纸泡=「[表情]」
        assertEquals("[表情]", store.lastSummary)
        assertEquals(false, orch.running.value)
    }

    @Test
    fun `stream error with no content marks turn failed without assistant rows`() = runTest {
        // 02 文档 §3.5 回合失败显式化:流 Error 无内容不再伪装 FALLBACK_TEXT
        // 兜底泡,用户回合不落任何 assistant 行,失败态显式可感知等重试。
        val store = FakeStore(char, memories)
        val llm = object : ChatOrchestrator.Llm {
            override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> =
                flow { emit(LlmStreamEvent.Error(IllegalStateException("boom"))) }
        }
        val orch = ChatOrchestrator(store, llm, bubbleGapMs = 0)
        var hookCalled = false
        orch.memoryHook = ChatOrchestrator.TurnMemoryHook { _, _, _ -> hookCalled = true }
        val result = orch.respondTo(1L, 1L, "在吗", {})

        // 不落任何 assistant 泡;失败结果携带原用户文本供重试
        assertEquals(0, store.inserted.filter { it.role == "assistant" }.size)
        assertTrue("流错误应判回合失败", result is TurnResult.Failed)
        assertEquals("在吗", (result as TurnResult.Failed).userText)
        // 会话摘要不被失败回合刷新;记忆收尾不触发
        assertEquals("", store.lastSummary)
        assertFalse("失败回合不应触发记忆收尾", hookCalled)
    }

    @Test
    fun `no first delta before watchdog marks turn failed`() = runTest {
        // 首 delta 看门狗(02 文档 §3.5:30s 无首 delta → 回合失败):注入 1ms
        // 阈值 + 永不产出任何事件的假流(模拟连接挂起),应判失败且不落泡。
        val store = FakeStore(char, memories)
        val llm = object : ChatOrchestrator.Llm {
            override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> =
                flow { awaitCancellation() }
        }
        val orch = ChatOrchestrator(store, llm, bubbleGapMs = 0, firstDeltaTimeoutMs = 1L)
        val result = orch.respondTo(1L, 1L, "在吗", {})

        assertTrue("首 delta 超时应判回合失败", result is TurnResult.Failed)
        assertEquals(0, store.inserted.filter { it.role == "assistant" }.size)
        assertEquals(false, orch.running.value)
    }

    @Test
    fun `failed turn retried after llm recovers completes with bubbles`() = runTest {
        // 失败重试:首回合失败不落泡,同一用户文本重试(用户消息已在库不重复
        // 落),第二次假 LLM 恢复 → 正常泡落库、结果为完成态(failedTurn 由
        // ViewModel 层按 Completed 清空,见 BubbleGroupingTest 状态迁移用例)。
        val store = FakeStore(char, memories)
        var failNext = true
        val llm = object : ChatOrchestrator.Llm {
            override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> = flow {
                if (failNext) {
                    failNext = false
                    emit(LlmStreamEvent.Error(IllegalStateException("boom")))
                } else {
                    emit(LlmStreamEvent.TextDelta("老公,在呢"))
                    emit(LlmStreamEvent.Finish("stop"))
                }
            }
        }
        val orch = ChatOrchestrator(store, llm, bubbleGapMs = 0)

        assertTrue("首回合应失败", orch.respondTo(1L, 1L, "在吗", {}) is TurnResult.Failed)
        assertEquals(0, store.inserted.filter { it.role == "assistant" }.size)

        val retried = orch.respondTo(1L, 1L, "在吗", {})
        assertTrue("重试应成功", retried is TurnResult.Completed)
        val assistants = store.inserted.filter { it.role == "assistant" }
        assertEquals(1, assistants.size)
        assertEquals("老公,在呢", assistants[0].content)
        assertEquals(false, orch.running.value)
    }

    @Test
    fun `blank llm output still produces one fallback bubble`() = runTest {
        val store = FakeStore(char, memories)
        val orch = ChatOrchestrator(store, fakeLlm(emptyList()), bubbleGapMs = 0)
        orch.respondTo(1L, 1L, "你好", {})
        val assistants = store.inserted.filter { it.role == "assistant" }
        assertEquals(1, assistants.size)
        assertEquals(ChatOrchestrator.FALLBACK_TEXT, assistants[0].content)
    }

    @Test
    fun `running flag toggles during round`() = runTest {
        val store = FakeStore(char, memories)
        var midRoundRunning = false
        val orch = ChatOrchestrator(store, fakeLlm(emptyList()), bubbleGapMs = 0)
        orch.respondTo(1L, 1L, "hi", {
            midRoundRunning = orch.running.value
        })
        assertTrue("回合内 running 应为 true", midRoundRunning)
        assertEquals(false, orch.running.value)
    }

    // ── 状态条规则 ──────────────────────────────────────────────────

    @Test
    fun `status rule reacts to user keywords with emoji first`() {
        val s = StatusRule.generate(char, listOf(userMsg("饿了,想吃火锅")))
        // emoji/text 分字段:emoji 靠前但不做字符串切片(防代理对劈裂)
        assertEquals("🍔", s.emoji)
        assertTrue(s.text.contains("吃饭"))
        assertTrue(s.render().startsWith(s.emoji))
    }

    @Test
    fun `status rule falls back to default when no signal`() {
        val s = StatusRule.generate(char, emptyList())
        assertEquals("💤", s.emoji)
        assertTrue(s.text.isNotEmpty())
    }

    @Test
    fun `status rule uses assistant action as context`() {
        val s = StatusRule.generate(
            char,
            listOf(
                userMsg("嗯嗯"),
                MessageEntity(
                    conversationId = 1, turnId = "t", role = "assistant",
                    kind = "text", content = "好呀",
                    actionsJson = """["趴在桌上画画"]""",
                ),
            ),
        )
        assertEquals("✨", s.emoji)
        assertTrue(s.text.contains("趴在桌上画画".take(8)))
    }

    // ── 单泡长文兜底切分(02 号文档:>2×maxBubbleChars 才按标点强制切分)──

    @Test
    fun `long single text bubble is split into multiple by punctuation`() {
        val orch = ChatOrchestrator(FakeStore(char, memories), fakeLlm(emptyList()), bubbleGapMs = 0)
        val long = longBurstText
        // 新口径:>2×30=60 字才触发(旧 20 字阈值用例已按文档加长)
        assertTrue("用例文本须>2×30=60 字,实际 ${long.length}", long.length > 60)
        val parts = orch.splitSingleTextBubble(
            com.neethu.gouxiang.protocol.Bubble(com.neethu.gouxiang.protocol.Bubble.Kind.TEXT, long),
            maxBubbleChars = 30,
        )
        assertTrue("应切成多条,实际 ${parts.size}", parts.size >= 2)
        assertTrue(parts.size <= 3)
        // 内容无损:各段 text 拼回仍是原文
        assertTrue(parts.joinToString("") { it.text }.length == long.length)
        // 动作只挂第一条
        val withActions = parts.map { it.actions }
        assertTrue(withActions[0].isEmpty() || withActions.drop(1).all { it.isEmpty() })
    }

    @Test
    fun `short single bubble stays unsplit`() {
        val orch = ChatOrchestrator(FakeStore(char, memories), fakeLlm(emptyList()), bubbleGapMs = 0)
        val short = "好呀,那就这么说定了"
        val parts = orch.splitSingleTextBubble(
            com.neethu.gouxiang.protocol.Bubble(com.neethu.gouxiang.protocol.Bubble.Kind.TEXT, short),
            maxBubbleChars = 30,
        )
        assertEquals(1, parts.size)
        assertEquals(short, parts[0].text)
    }

    @Test
    fun `long single bubble without punctuation stays unsplit`() {
        val orch = ChatOrchestrator(FakeStore(char, memories), fakeLlm(emptyList()), bubbleGapMs = 0)
        // >60 字且无标点:超长但无可用切点,按「无标点边界保持原样」不切
        val long = "这句话完全没有标点但是很长" + "很长".repeat(30)
        assertTrue("用例文本须>2×30=60 字才覆盖无标点分支,实际 ${long.length}", long.length > 60)
        val parts = orch.splitSingleTextBubble(
            com.neethu.gouxiang.protocol.Bubble(com.neethu.gouxiang.protocol.Bubble.Kind.TEXT, long),
            maxBubbleChars = 30,
        )
        assertEquals(1, parts.size)
    }

    @Test
    fun `single bubble within twice char limit stays unsplit`() {
        // 02 号文档口径:>2×maxBubbleChars 才强切 —— 20~60 字的单泡即使带标点
        // 也不再切(旧 20 字阈值会把下面这条 45 字文切碎,此用例锁回归)。
        val orch = ChatOrchestrator(FakeStore(char, memories), fakeLlm(emptyList()), bubbleGapMs = 0)
        val mid = "老公做的番茄鸡蛋面就超棒的呀,我还想再加好多好多的葱花,然后煎个溏心蛋配着吃啦,想想就饿了"
        assertTrue("用例应落在 20~60 字区间,实际 ${mid.length}", mid.length in 20..60)
        val parts = orch.splitSingleTextBubble(
            com.neethu.gouxiang.protocol.Bubble(com.neethu.gouxiang.protocol.Bubble.Kind.TEXT, mid),
            maxBubbleChars = 30,
        )
        assertEquals(1, parts.size)
        assertEquals(mid, parts[0].text)
        // 边界:恰好 2×30=60 字(含标点),「>2×上限」为严格大于,等于不切
        val atLimit = "我想跟你说一件事呀,".repeat(6)
        assertEquals(60, atLimit.length)
        val atLimitParts = orch.splitSingleTextBubble(
            com.neethu.gouxiang.protocol.Bubble(com.neethu.gouxiang.protocol.Bubble.Kind.TEXT, atLimit),
            maxBubbleChars = 30,
        )
        assertEquals(1, atLimitParts.size)
    }

    // ── 泡间连发节奏(04 号文档「条间隔 600~1200ms 随机」)────────────

    @Test
    fun `bubble gap draws stay within 600 to 1200ms and vary`() {
        // 纯函数 + 注入定种 Random:抽样 200 次全部落在 600~1200ms 且不全同
        val orch = ChatOrchestrator(
            FakeStore(char, memories), fakeLlm(emptyList()),
            bubbleGapMs = 900, random = Random(42L),
        )
        assertEquals(600L, ChatOrchestrator.BUBBLE_GAP_MIN_MS)
        assertEquals(1200L, ChatOrchestrator.BUBBLE_GAP_MAX_MS)
        val gaps = List(200) { orch.nextBubbleGapMs() }
        gaps.forEach { gap ->
            assertTrue("间隔应在 600~1200ms,实际 $gap", gap in 600L..1200L)
        }
        assertTrue("间隔应有随机变化,实际 ${gaps.distinct().size} 种", gaps.distinct().size > 1)
    }

    @Test
    fun `persist bubbles delays randomized 600 to 1200ms between bubbles`() = runTest {
        val store = FakeStore(char, memories)
        val orch = ChatOrchestrator(
            store, fakeLlm(listOf("老公,在呢\n嘿嘿\n[sticker:歪头撒娇]")),
            bubbleGapMs = 900, random = Random(42L),
        )
        val savedAt = mutableListOf<Long>()
        orch.respondTo(1L, 1L, "在吗", onAssistantSaved = { savedAt += testScheduler.currentTime })

        // 3 条泡 → 2 个泡间间隔;runTest 虚拟时间上,间隔即 delay 抽样值
        assertEquals(3, savedAt.size)
        savedAt.zipWithNext { prev, cur -> cur - prev }.forEach { gap ->
            assertTrue("泡间间隔应在 600~1200ms,实际 $gap", gap in 600L..1200L)
        }
    }

    @Test
    fun `zero bubble gap skips delay entirely`() = runTest {
        // bubbleGapMs=0 = 完全跳过延迟:虚拟时间零推进(既有测试注入 0 的契约)
        val store = FakeStore(char, memories)
        val orch = ChatOrchestrator(store, fakeLlm(listOf("老公,在呢\n嘿嘿\n[sticker:歪头撒娇]")), bubbleGapMs = 0)
        val savedAt = mutableListOf<Long>()
        orch.respondTo(1L, 1L, "在吗", onAssistantSaved = { savedAt += testScheduler.currentTime })

        assertEquals(3, savedAt.size)
        assertEquals("bubbleGapMs=0 应完全跳过延迟", 0L, testScheduler.currentTime)
    }

    // ── 日常分享泡(proactivity,02 文档 §3.3:每次回复可附 0~1 条)──

    @Test
    fun `json reply with proactivity persists share bubble after main bubbles`() = runTest {
        val store = FakeStore(char, memories)
        val raw = """
            {"bubbles":[{"kind":"text","text":"老公,"},{"kind":"sticker","text":"歪头撒娇"}],
             "proactivity":{"kind":"text","text":"刚煮了奶茶,给你留了一杯"}}
        """.trimIndent()
        val orch = ChatOrchestrator(store, fakeLlm(listOf(raw)), bubbleGapMs = 0)
        var hookAssistant = ""
        orch.memoryHook = ChatOrchestrator.TurnMemoryHook { _, _, text -> hookAssistant = text }
        val collected = mutableListOf<MessageEntity>()
        orch.respondTo(1L, 1L, "在忙吗", onAssistantSaved = { collected += it })

        // assistant 行数 = 主泡数(2)+ 分享泡(1),分享泡独立一泡排在主泡之后
        val assistants = store.inserted.filter { it.role == "assistant" }
        assertEquals(3, assistants.size)
        assertEquals("老公,", assistants[0].content)
        assertEquals("sticker", assistants[1].kind)
        assertEquals("刚煮了奶茶,给你留了一杯", assistants[2].content)
        // 全行同 turnId
        assertEquals(1, assistants.map { it.turnId }.distinct().size)
        // 回调与落库一致(顺序);会话摘要刷到最后一泡(分享泡)
        assertEquals(assistants, collected)
        assertEquals("刚煮了奶茶,给你留了一杯", store.lastSummary)
        // 分享内容照常进记忆窗口
        assertTrue("记忆收尾应含分享文本", hookAssistant.contains("刚煮了奶茶,给你留了一杯"))
    }

    @Test
    fun `proactivity bubble is not split and stays after split main bubbles`() = runTest {
        val store = FakeStore(char, memories)
        val long = longBurstText // >2×30=60 字,主泡才会触发兜底切分
        val raw = """
            {"bubbles":[{"kind":"text","text":"$long"}],
             "proactivity":{"kind":"text","text":"刚煮了奶茶,给你留了一杯"}}
        """.trimIndent()
        val orch = ChatOrchestrator(store, fakeLlm(listOf(raw)), bubbleGapMs = 0)
        orch.respondTo(1L, 1L, "在忙吗", {})

        val assistants = store.inserted.filter { it.role == "assistant" }
        // 主泡单条长文按兜底切分(2~3 条),分享泡不参与切分、原样排末位
        assertTrue("应为主泡切分段+分享泡,实际 ${assistants.size} 条", assistants.size >= 3)
        assertEquals("刚煮了奶茶,给你留了一杯", assistants.last().content)
        // 主泡各段拼回即原文(分享文本未被切碎混入主泡)
        assertEquals(long, assistants.dropLast(1).joinToString("") { it.content })
    }

    // ── 测试基建 ────────────────────────────────────────────────────

    /**
     * 切分用例共用的单泡长文(61 字,>2×30=60):按新口径会沿标点切成
     * 2~3 条,且各段拼回无损;旧 45 字文已低于新阈值,专用于「区间内不切」。
     */
    private val longBurstText =
        "老公做的番茄鸡蛋面就超棒的呀,我还想再多加好多好多的葱花,然后还要煎一个溏心蛋配着一起吃,再配一杯冰可乐,想想就已经饿了呀"

    private val char = CharacterEntity(
        id = 1,
        name = "苏馨玥",
        profile = "22岁,美术系大四学生,和用户同居一年",
        intro = "简介",
        exampleLines = "(笑着)喏。",
        openingLine = "嗨",
        appearance = "desc",
        appearanceStyle = "写实摄影风",
        createdAt = 0,
    )
    private val memories = listOf(
        MemoryEntity(characterId = 1, category = "relationship", content = "我的称呼:老公", importance = 9, at = 1),
        MemoryEntity(characterId = 1, category = "profile", content = "我的身份:上班族", importance = 8, at = 1),
        MemoryEntity(characterId = 1, category = "event", content = "两人一起看了新房", importance = 6, at = 1),
    )

    private fun userMsg(text: String) = MessageEntity(
        conversationId = 1, turnId = "u", role = "user", content = text, at = 1,
    )

    /** 分段发射的假流。 */
    private fun fakeLlm(parts: List<String>): ChatOrchestrator.Llm =
        object : ChatOrchestrator.Llm {
            override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> =
                flow {
                    parts.forEach { emit(LlmStreamEvent.TextDelta(it)) }
                    emit(LlmStreamEvent.Finish("stop"))
                }
        }

    private class FakeStore(
        private val char: CharacterEntity,
        private val memories: List<MemoryEntity>,
    ) : ChatOrchestrator.DaoAccess {
        val inserted = mutableListOf<MessageEntity>()
        var lastSummary: String = ""

        override suspend fun history(conversationId: Long, limit: Int): List<MessageEntity> =
            inserted.takeLast(limit)

        override suspend fun memories(characterId: Long): List<MemoryEntity> = memories

        override suspend fun character(characterId: Long): CharacterEntity = char

        override suspend fun insertMessage(message: MessageEntity) {
            inserted += message
        }

        override suspend fun touchConversation(conversationId: Long, summary: String, at: Long) {
            lastSummary = summary
        }
    }
}
