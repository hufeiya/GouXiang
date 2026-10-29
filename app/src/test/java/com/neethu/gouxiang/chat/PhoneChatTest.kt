package com.neethu.gouxiang.chat

import com.neethu.gouxiang.ai.adapter.ChatMessage
import com.neethu.gouxiang.ai.adapter.ChatRole
import com.neethu.gouxiang.ai.adapter.LlmConfig
import com.neethu.gouxiang.ai.adapter.LlmStreamEvent
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.MemoryEntity
import com.neethu.gouxiang.data.MessageEntity
import com.neethu.gouxiang.protocol.CardJson
import com.neethu.gouxiang.protocol.CardPayload
import com.neethu.gouxiang.protocol.PhoneCardKind
import com.neethu.gouxiang.protocol.ProactiveMessages
import com.neethu.gouxiang.protocol.toProtocolLine
import com.neethu.gouxiang.protocol.voiceDurationLabel
import com.neethu.gouxiang.protocol.voiceDurationSec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机聊天 F4 单测:连发区间协议、主动消息触发判定与回合、卡片编解码、
 * 语音条时长、无计费说明文案(范围 2 红线:不得出现猫粮/扣费字样)。
 */
class PhoneChatTest {

    // ── 输出协议:2~4 连发区间(范围 4)───────────────────────────────

    @Test
    fun `phone style prompt demands 2 to 4 burst and shorter lines`() {
        ChatContextBuilder.withPhoneStyle {
            val p = ChatContextBuilder.systemPrompt(char, memories, maxBubbles = 3, maxBubbleChars = 24)
            assertTrue("应声明 2~4 连发,实际:\n$p", p.contains("连发 2~4 条"))
            assertTrue(p.contains("必须输出 2~4 条独立的短消息"))
            assertTrue("手机聊天每条应更短(≤24 字)", p.contains("不超过 24个字"))
        }
    }

    @Test
    fun `default im style keeps 1-to-max burst semantics`() {
        ChatContextBuilder.withPhoneStyle(false) {
            val p = ChatContextBuilder.systemPrompt(char, memories, maxBubbles = 3, maxBubbleChars = 30)
            assertTrue(p.contains("连发 3 条"))
            assertFalse("IM 形态不应出现 2~4 区间", p.contains("2~4 条"))
        }
    }

    @Test
    fun `protocol never mentions billing`() {
        // 范围 2 红线:本地版无计费,协议与说明文案不得出现猫粮/扣费
        ChatContextBuilder.withPhoneStyle {
            val p = ChatContextBuilder.systemPrompt(char, memories)
            assertFalse(p.contains("猫粮"))
            assertFalse(p.contains("扣费"))
        }
    }

    // ── 主动消息触发判定(范围 5)────────────────────────────────────

    @Test
    fun `first entry triggers proactive hello`() {
        assertEquals(
            ProactiveMessages.Kind.FIRST_HELLO,
            ProactiveMessages.pickTrigger(everProactive = false, lastActivityAt = now, now = now),
        )
        assertEquals(
            ProactiveMessages.Kind.FIRST_HELLO,
            ProactiveMessages.pickTrigger(everProactive = true, lastActivityAt = null, now = now),
        )
    }

    @Test
    fun `recent activity does not trigger`() {
        assertNull(
            ProactiveMessages.pickTrigger(
                everProactive = true,
                lastActivityAt = now - 1_000,
                now = now,
            ),
        )
    }

    @Test
    fun `offline over threshold picks hot or cold kind`() {
        val threshold = ProactiveMessages.DEFAULT_OFFLINE_THRESHOLD_MS
        // 2h~12h:热档日常分享
        assertEquals(
            ProactiveMessages.Kind.DAILY_SHARE,
            ProactiveMessages.pickTrigger(true, now - threshold - 60_000, now),
        )
        // ≥12h:冷档关心
        assertEquals(
            ProactiveMessages.Kind.CHECK_IN,
            ProactiveMessages.pickTrigger(
                true,
                now - ProactiveMessages.COLD_OFFLINE_MS - 60_000,
                now,
            ),
        )
    }

    @Test
    fun `custom threshold is honored`() {
        // 阈值调到 5 分钟:4 分钟前不触发,6 分钟前触发
        val t = 5 * 60_000L
        assertNull(ProactiveMessages.pickTrigger(true, now - 4 * 60_000, now, t))
        assertEquals(
            ProactiveMessages.Kind.DAILY_SHARE,
            ProactiveMessages.pickTrigger(true, now - 6 * 60_000, now, t),
        )
    }

    // ── 主动消息回合(假 LLM 走编排器)───────────────────────────────

    @Test
    fun `proactive round persists bubbles under proactive turnId`() = runTest {
        val store = FakeStore(char, memories)
        val llm = fakeLlm(listOf("刚画完一张速写\n(把手机举到你面前)你看你看\n[sticker:得意]"))
        val orch = ChatOrchestrator(store, llm, bubbleGapMs = 0)
        val collected = mutableListOf<MessageEntity>()
        orch.respondProactively(
            1L, 1L,
            sceneLine = ProactiveMessages.sceneLine(ProactiveMessages.Kind.DAILY_SHARE, 3 * 60 * 60 * 1000L),
            onAssistantSaved = { collected += it },
        )
        assertTrue("主动回合 turnId 应带 proactive- 前缀", store.inserted.first().turnId.startsWith("proactive-"))
        assertEquals(3, store.inserted.size)
        assertEquals("刚画完一张速写", store.inserted[0].content)
        assertEquals("得意", store.inserted[2].content)
        // 会话行摘要刷到最后一泡
        assertEquals("得意", store.lastSummary)
        assertEquals(collected, store.inserted)
        assertFalse(orch.running.value)
    }

    @Test
    fun `proactive round failure yields proactive fallback`() = runTest {
        val store = FakeStore(char, memories)
        val orch = ChatOrchestrator(store, fakeLlm(emptyList()), bubbleGapMs = 0)
        orch.respondProactively(1L, 1L, "场景指令", {})
        val assistants = store.inserted.filter { it.role == "assistant" }
        assertEquals(1, assistants.size)
        assertEquals(ChatOrchestrator.PROACTIVE_FALLBACK_TEXT, assistants[0].content)
    }

    // ── 卡片(范围 3)───────────────────────────────────────────────

    @Test
    fun `card payload roundtrip and protocol line`() {
        val card = CardPayload(PhoneCardKind.TRANSFER, "520", "给你买奶茶")
        val line = card.toProtocolLine()
        assertEquals("[卡片:转账 520|给你买奶茶]", line)
        val parsed = com.neethu.gouxiang.protocol.parseCardLine(line)
        assertEquals(PhoneCardKind.TRANSFER, parsed?.kind)
        assertEquals("520", parsed?.title)
        assertEquals("给你买奶茶", parsed?.note)
    }

    @Test
    fun `card line without note parses`() {
        val parsed = com.neethu.gouxiang.protocol.parseCardLine("[卡片:位置 中央公园]")
        assertEquals(PhoneCardKind.LOCATION, parsed?.kind)
        assertEquals("中央公园", parsed?.title)
        assertEquals("", parsed?.note)
    }

    @Test
    fun `bad card line returns null`() {
        assertNull(com.neethu.gouxiang.protocol.parseCardLine("普通文本"))
        assertNull(com.neethu.gouxiang.protocol.parseCardLine("[卡片:不存在类型 123]"))
        assertNull(com.neethu.gouxiang.protocol.parseCardLine("[卡片:]"))
    }

    @Test
    fun `card json roundtrip is failure safe`() {
        val card = CardPayload(PhoneCardKind.MUSIC, "晴天", "周杰伦")
        val decoded = CardJson.decodeOrNull(CardJson.encode(card))
        assertEquals(card, decoded)
        assertNull(CardJson.decodeOrNull("not json"))
    }

    @Test
    fun `card summary degrades to kind label when blank`() {
        assertEquals("[转账]", CardPayload(PhoneCardKind.TRANSFER).summary())
        assertEquals("[图片] 自拍", CardPayload(PhoneCardKind.IMAGE, "自拍").summary())
    }

    // ── 语音条时长(范围 1「•)N″」)────────────────────────────────

    @Test
    fun `voice duration estimates by 4 chars per second`() {
        assertEquals(1, voiceDurationSec("哼")) // 不足 4 字 → 1 秒下限
        assertEquals(2, voiceDurationSec("今天真的好累啊")) // 7 字 → 2 秒
        assertEquals(60, voiceDurationSec("字".repeat(500))) // 上限 60 秒
    }

    @Test
    fun `voice label matches reference format`() {
        assertEquals("2\"", voiceDurationLabel(2))
        assertEquals("4\"", voiceDurationLabel(4))
    }

    // ── 卡片上下文回放(模型读得到自己发过的卡)───────────────────────

    @Test
    fun `card message replays as protocol line in history`() {
        val card = CardPayload(PhoneCardKind.TRANSFER, "1314", "奖励给我宝宝的")
        val h = ChatContextBuilder.history(
            listOf(
                MessageEntity(
                    conversationId = 1, turnId = "t", role = "user",
                    kind = "card", content = card.summary(), actionsJson = CardJson.encode(card), at = 1,
                ),
            ),
        )
        assertEquals("[卡片:转账 1314|奖励给我宝宝的]", h[0].content)
    }

    // ── 测试基建(惯例同 ChatOrchestratorTest)────────────────────────

    private val now = 1_800_000_000_000L

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
        MemoryEntity(characterId = 1, category = "event", content = "两人一起看了新房", importance = 6, at = 1),
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
