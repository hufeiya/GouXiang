package com.neethu.gouxiang.chat

import com.neethu.gouxiang.ai.adapter.ChatMessage
import com.neethu.gouxiang.ai.adapter.ChatRole
import com.neethu.gouxiang.ai.adapter.LlmConfig
import com.neethu.gouxiang.ai.adapter.LlmStreamEvent
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.MemoryEntity
import com.neethu.gouxiang.data.MessageEntity
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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

    @Test
    fun `image round and retry send current image once and replay available history`() = runTest {
        val image = java.nio.file.Files.createTempFile("chat-image", ".jpg").toFile()
        image.writeBytes(byteArrayOf(1, 2, 3))
        try {
            val store = FakeStore(char, memories)
            val old = MessageEntity(id = 1, conversationId = 1, turnId = "old", role = "user", kind = "image", content = "旧图片", mediaPath = image.path)
            val missing = old.copy(id = 2, content = "缺失图片", mediaPath = image.path + ".missing")
            val current = old.copy(id = 3, content = "当前图片")
            store.inserted.addAll(listOf(old, missing, current))
            val requests = mutableListOf<List<ChatMessage>>()
            val llm = ChatOrchestrator.Llm { messages, _ ->
                requests.add(messages)
                flow {
                    emit(LlmStreamEvent.TextDelta("看到了"))
                    emit(LlmStreamEvent.Finish("stop"))
                }
            }
            val orchestrator = ChatOrchestrator(store, llm, bubbleGapMs = 0)
            repeat(2) { // The same persisted id is used on retry/regeneration.
                orchestrator.respondTo(1, 1, current.content, {}, userMessageId = current.id)
            }
            requests.forEach { messages ->
                assertEquals(1, messages.count { it.content == current.content })
                assertEquals(listOf("data:image/jpeg;base64,AQID"), messages.last().images)
                assertEquals(listOf("data:image/jpeg;base64,AQID"), messages.first { it.content == old.content }.images)
                assertTrue(messages.first { it.content == missing.content }.images.isEmpty())
            }
            val missingCurrent = missing.copy(id = 4)
            store.inserted.add(missingCurrent)
            val result = orchestrator.respondTo(1, 1, missingCurrent.content, {}, userMessageId = missingCurrent.id)
            assertTrue(result is TurnResult.Failed)
            assertEquals(2, requests.size)
        } finally {
            image.delete()
        }
    }

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
        // 表情包标记已废除(2026-10-31 口径):prompt 不再示范 sticker 标签,
        // 非语言内容改教括号文本直接写进消息
        assertTrue(!p.contains("[sticker:"))
        assertTrue(p.contains("括号写在消息里"))
        // 连发是硬规则且带 few-shot 示例(实机 F2:只提一句会输出单泡)
        assertTrue(p.contains("必须输出 4 条独立的短消息"))
        assertTrue(p.contains("(歪头撒娇)"))
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
        // 存量表情行降级为括号文本回放(不再示范 sticker 标记)
        assertEquals("(哼)", h[2].content)
    }

    @Test
    fun `overlapping im and proactive phone requests keep their own prompt styles`() = runTest {
        val bothWaiting = CompletableDeferred<Unit>()
        var requestsWaiting = 0
        val captured = mutableMapOf<String, String>()
        val store = object : ChatOrchestrator.DaoAccess {
            override suspend fun character(characterId: Long) = char
            override suspend fun memories(characterId: Long): List<MemoryEntity> {
                requestsWaiting++
                if (requestsWaiting == 2) bothWaiting.complete(Unit)
                bothWaiting.await()
                return emptyList()
            }
            override suspend fun history(conversationId: Long, limit: Int) = emptyList<MessageEntity>()
            override suspend fun insertMessage(message: MessageEntity) = Unit
            override suspend fun touchConversation(conversationId: Long, summary: String, at: Long) = Unit
        }
        val llm = ChatOrchestrator.Llm { messages, _ ->
            captured[messages.last().content] = messages.first().content
            flow {
                emit(LlmStreamEvent.TextDelta("你好"))
                emit(LlmStreamEvent.Finish("stop"))
            }
        }
        val im = ChatOrchestrator(store, llm, bubbleGapMs = 0)
        val phone = ChatOrchestrator(store, llm, bubbleGapMs = 0)
        val imJob = async { im.respondTo(1, 1, "IM", {}, phoneStyle = false) }
        val phoneJob = async { phone.respondProactively(2, 1, "PHONE", {}, phoneStyle = true) }
        imJob.await()
        phoneJob.await()
        assertTrue(captured.getValue("IM").contains("必须输出 3 条独立的短消息"))
        assertFalse(captured.getValue("IM").contains("更碎、更像真人手速"))
        assertTrue(captured.getValue("PHONE").contains("更碎、更像真人手速"))
    }

    // ── 编排器回合(假 LLM)────────────────────────────────────────

    @Test
    fun `full round persists user message and multi-bubbles in order`() = runTest {
        val store = FakeStore(char, memories)
        // 残留 sticker 标记降级为括号动作文本(歪头撒娇在白名单内→剥离进 actions)
        val llm = fakeLlm(listOf("老公,(靠在你肩头)那房子真的好喜欢!\n[sticker:歪头撒娇]"))
        val orch = ChatOrchestrator(store, llm, bubbleGapMs = 0)
        // ViewModel 契约:用户消息先落库,编排器只管回复侧
        store.insertMessage(MessageEntity(conversationId = 1, turnId = "u", role = "user", content = "想搬新家了"))
        val collected = mutableListOf<MessageEntity>()
        orch.respondTo(1L, 1L, "想搬新家了", onAssistantSaved = { collected += it })

        // 用户消息已落库(ViewModel 落,非编排器)
        assertEquals("想搬新家了", store.inserted.first { it.role == "user" }.content)
        // 1 条 assistant 泡:两行文本合成一泡(猫箱口径);「靠在你肩头」在白名单
        // 内剥离进 actionsJson,降级出的「(歪头撒娇)」不在白名单,按括号口径
        // 保留为正文,不再出表情泡
        val assistants = store.inserted.filter { it.role == "assistant" }
        assertEquals(1, assistants.size)
        assertEquals("老公,那房子真的好喜欢!\n(歪头撒娇)", assistants[0].content)
        assertEquals("""["靠在你肩头"]""", assistants[0].actionsJson)
        assertEquals("text", assistants[0].kind)
        // 回调与落库一致(顺序)
        assertEquals(assistants, collected)
        // 会话摘要刷新为最后一泡(文本泡摘要=文本)
        assertEquals("老公,那房子真的好喜欢!\n(歪头撒娇)", store.lastSummary)
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

    // ── 回复泡合并(猫箱口径:一轮回复的文字合成一条消息,不拆分)──────

    @Test
    fun `multi-line text reply merges into one bubble`() {
        val orch = ChatOrchestrator(FakeStore(char, memories), fakeLlm(emptyList()), bubbleGapMs = 0)
        val merged = orch.mergeConsecutiveTextBubbles(
            listOf(
                com.neethu.gouxiang.protocol.Bubble(com.neethu.gouxiang.protocol.Bubble.Kind.TEXT, "老公,在呢"),
                com.neethu.gouxiang.protocol.Bubble(com.neethu.gouxiang.protocol.Bubble.Kind.TEXT, "嘿嘿"),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals("老公,在呢\n嘿嘿", merged[0].text)
    }

    @Test
    fun `merged bubble keeps first bubble actions`() {
        // () 动作描述整个回合的场景:合并后只保留首泡动作,不重复挂到每段
        val orch = ChatOrchestrator(FakeStore(char, memories), fakeLlm(emptyList()), bubbleGapMs = 0)
        val merged = orch.mergeConsecutiveTextBubbles(
            listOf(
                com.neethu.gouxiang.protocol.Bubble(com.neethu.gouxiang.protocol.Bubble.Kind.TEXT, "老公,在呢", listOf("靠在你肩头")),
                com.neethu.gouxiang.protocol.Bubble(com.neethu.gouxiang.protocol.Bubble.Kind.TEXT, "嘿嘿"),
            ),
        )
        assertEquals(listOf("靠在你肩头"), merged[0].actions)
        assertEquals("老公,在呢\n嘿嘿", merged[0].text)
    }

    @Test
    fun `text and placeholder bubbles stay separate across merge`() {
        // 合并只作用相邻 TEXT 泡;图片/卡片等占位泡保持独立一泡
        val orch = ChatOrchestrator(FakeStore(char, memories), fakeLlm(emptyList()), bubbleGapMs = 0)
        val merged = orch.mergeConsecutiveTextBubbles(
            listOf(
                com.neethu.gouxiang.protocol.Bubble(com.neethu.gouxiang.protocol.Bubble.Kind.TEXT, "第一句"),
                com.neethu.gouxiang.protocol.Bubble(com.neethu.gouxiang.protocol.Bubble.Kind.TEXT, "第二句"),
                com.neethu.gouxiang.protocol.Bubble(com.neethu.gouxiang.protocol.Bubble.Kind.IMAGE, "黄昏的天台"),
                com.neethu.gouxiang.protocol.Bubble(com.neethu.gouxiang.protocol.Bubble.Kind.TEXT, "又冒出一句"),
            ),
        )
        assertEquals(3, merged.size)
        assertEquals("第一句\n第二句", merged[0].text)
        assertEquals(com.neethu.gouxiang.protocol.Bubble.Kind.IMAGE, merged[1].kind)
        assertEquals("又冒出一句", merged[2].text)
    }

    @Test
    fun `long single text bubble stays one bubble without split`() {
        // 猫箱口径:超长单泡不再按标点强切,原样一条上屏(旧兜底切分已移除)
        val orch = ChatOrchestrator(FakeStore(char, memories), fakeLlm(emptyList()), bubbleGapMs = 0)
        val long = longBurstText
        val merged = orch.mergeConsecutiveTextBubbles(
            listOf(com.neethu.gouxiang.protocol.Bubble(com.neethu.gouxiang.protocol.Bubble.Kind.TEXT, long)),
        )
        assertEquals(1, merged.size)
        assertEquals(long, merged[0].text)
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
            store, fakeLlm(listOf("老公,在呢\n嘿嘿\n[img:晚霞照片]")),
            bubbleGapMs = 900, random = Random(42L),
        )
        val savedAt = mutableListOf<Long>()
        orch.respondTo(1L, 1L, "在吗", onAssistantSaved = { savedAt += testScheduler.currentTime })

        // 两行文字合成一泡 + 图片泡 = 2 泡 → 1 个泡间间隔(猫箱口径不拆分);
        // runTest 虚拟时间上,间隔即 delay 抽样值
        assertEquals(2, savedAt.size)
        savedAt.zipWithNext { prev, cur -> cur - prev }.forEach { gap ->
            assertTrue("泡间间隔应在 600~1200ms,实际 $gap", gap in 600L..1200L)
        }
    }

    @Test
    fun `zero bubble gap skips delay entirely`() = runTest {
        // bubbleGapMs=0 = 完全跳过延迟:虚拟时间零推进(既有测试注入 0 的契约)
        val store = FakeStore(char, memories)
        val orch = ChatOrchestrator(store, fakeLlm(listOf("老公,在呢\n嘿嘿\n[img:晚霞照片]")), bubbleGapMs = 0)
        val savedAt = mutableListOf<Long>()
        orch.respondTo(1L, 1L, "在吗", onAssistantSaved = { savedAt += testScheduler.currentTime })

        assertEquals(2, savedAt.size)
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

        // 主泡:文本 + 降级括号文本(sticker kind)→ 猫箱口径合并成一泡;
        // 分享泡独立一泡排在主泡之后 → 共 2 条 assistant 行
        val assistants = store.inserted.filter { it.role == "assistant" }
        assertEquals(2, assistants.size)
        assertEquals("老公,\n(歪头撒娇)", assistants[0].content)
        assertEquals("刚煮了奶茶,给你留了一杯", assistants[1].content)
        // 全行同 turnId
        assertEquals(1, assistants.map { it.turnId }.distinct().size)
        // 回调与落库一致(顺序);会话摘要刷到最后一泡(分享泡)
        assertEquals(assistants, collected)
        assertEquals("刚煮了奶茶,给你留了一杯", store.lastSummary)
        // 分享内容照常进记忆窗口
        assertTrue("记忆收尾应含分享文本", hookAssistant.contains("刚煮了奶茶,给你留了一杯"))
    }

    @Test
    fun `proactivity bubble stays separate after merged main bubble`() = runTest {
        val store = FakeStore(char, memories)
        val long = longBurstText // 单泡长文不再切分,原样一条
        val raw = """
            {"bubbles":[{"kind":"text","text":"$long"}],
             "proactivity":{"kind":"text","text":"刚煮了奶茶,给你留了一杯"}}
        """.trimIndent()
        val orch = ChatOrchestrator(store, fakeLlm(listOf(raw)), bubbleGapMs = 0)
        orch.respondTo(1L, 1L, "在忙吗", {})

        val assistants = store.inserted.filter { it.role == "assistant" }
        // 主泡合并为一条(长文不切分),分享泡独立一泡排末位
        assertEquals(2, assistants.size)
        assertEquals(long, assistants[0].content)
        assertEquals("刚煮了奶茶,给你留了一杯", assistants[1].content)
    }

    // ── 测试基建 ────────────────────────────────────────────────────

    /**
     * 长文用例共用的单泡长文(61 字):合并口径下原样一条上屏不切分,
     * 锁「超长也不拆」的猫箱行为。
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

        override suspend fun messageById(id: Long): MessageEntity? = inserted.firstOrNull { it.id == id }

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
