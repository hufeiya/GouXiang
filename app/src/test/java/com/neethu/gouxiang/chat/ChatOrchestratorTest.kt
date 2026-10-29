package com.neethu.gouxiang.chat

import com.neethu.gouxiang.ai.adapter.ChatMessage
import com.neethu.gouxiang.ai.adapter.ChatRole
import com.neethu.gouxiang.ai.adapter.LlmConfig
import com.neethu.gouxiang.ai.adapter.LlmStreamEvent
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.MemoryEntity
import com.neethu.gouxiang.data.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        // 会话摘要刷新为最后一泡
        assertEquals("歪头撒娇", store.lastSummary)
        assertEquals(false, orch.running.value)
    }

    @Test
    fun `stream error with no content yields fallback bubble`() = runTest {
        val store = FakeStore(char, memories)
        val llm = object : ChatOrchestrator.Llm {
            override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> =
                flow { emit(LlmStreamEvent.Error(IllegalStateException("boom"))) }
        }
        val orch = ChatOrchestrator(store, llm, bubbleGapMs = 0)
        orch.respondTo(1L, 1L, "在吗", {})
        val assistants = store.inserted.filter { it.role == "assistant" }
        assertEquals(1, assistants.size)
        // 兜底文案里的 (戳了戳你) 按协议剥离为该泡的动作展示,正文保留剩余部分
        assertEquals("……信号有点糟,再说一遍好不好?", assistants[0].content)
        assertEquals("""["戳了戳你"]""", assistants[0].actionsJson)
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

    // ── 单泡长文兜底切分(实机 F2:模型不分行 → 确定性拆分保多泡观感)──

    @Test
    fun `long single text bubble is split into multiple by punctuation`() {
        val orch = ChatOrchestrator(FakeStore(char, memories), fakeLlm(emptyList()), bubbleGapMs = 0)
        val long = "老公做的番茄鸡蛋面就超棒的呀,我还想再加好多好多的葱花,然后煎个溏心蛋配着吃啦,想想就饿了"
        val parts = orch.splitSingleTextBubble(com.neethu.gouxiang.protocol.Bubble(com.neethu.gouxiang.protocol.Bubble.Kind.TEXT, long))
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
        val parts = orch.splitSingleTextBubble(com.neethu.gouxiang.protocol.Bubble(com.neethu.gouxiang.protocol.Bubble.Kind.TEXT, short))
        assertEquals(1, parts.size)
        assertEquals(short, parts[0].text)
    }

    @Test
    fun `long single bubble without punctuation stays unsplit`() {
        val orch = ChatOrchestrator(FakeStore(char, memories), fakeLlm(emptyList()), bubbleGapMs = 0)
        val long = "这句话完全没有标点但是很长很长很长很长很长很长很长很长很长"
        val parts = orch.splitSingleTextBubble(com.neethu.gouxiang.protocol.Bubble(com.neethu.gouxiang.protocol.Bubble.Kind.TEXT, long))
        assertEquals(1, parts.size)
    }

    // ── 测试基建 ────────────────────────────────────────────────────

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
