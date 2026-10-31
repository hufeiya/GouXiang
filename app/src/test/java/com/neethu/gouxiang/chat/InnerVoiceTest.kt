package com.neethu.gouxiang.chat

import com.neethu.gouxiang.ai.adapter.LlmConfig
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.MemoryEntity
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F8 心声单测(假 LLM,零网络):输出解析(宽松 JSON/截断/条数上限)、
 * 实体盖章(at 错开/条数下限判失败)、请求通道失败安全、prompt 携带记忆
 * 与无计费红线(范围 3:「剩余 N 天过期」等时效/计费字样不得出现)。
 */
class InnerVoiceTest {

    // ── 解析:宽松 JSON 与清洗 ───────────────────────────────────────

    @Test
    fun `parser accepts json wrapped in chatter and markdown fences`() {
        val raw = "好的:\n```json\n{\"voices\":[" +
            "{\"title\":\"呛水仍不服输\",\"echo\":\"你还记得我硬学会游泳的模样吗\",\"content\":\"海水真冷啊\\n\\n可我记得你说过\"}," +
            "{\"title\":\"小情绪\",\"echo\":\"今天有点想你\",\"content\":\"短文\"}" +
            "]}\n```\n以上。"
        val drafts = InnerVoiceGenerator.parse(raw)
        assertEquals(2, drafts.size)
        assertEquals("呛水仍不服输", drafts[0].title)
        assertEquals("你还记得我硬学会游泳的模样吗", drafts[0].echo)
        assertEquals("海水真冷啊\n\n可我记得你说过", drafts[0].content)
    }

    @Test
    fun `parser tolerates legacy json without content`() {
        val drafts = InnerVoiceGenerator.parse(
            "{\"voices\":[{\"title\":\"旧形态\",\"echo\":\"只有短句\"}]}",
        )
        assertEquals(1, drafts.size)
        assertEquals("", drafts[0].content)
    }

    @Test
    fun `parser escapes raw newlines inside json strings`() {
        // 模型偶发在字符串里直接换行(违反 JSON):预处理应转义且段落分隔保留
        val raw = "{\"voices\":[" +
            "{\"title\":\"海边\",\"echo\":\"你还记得吗\",\"content\":\"海水真冷啊。\n\n可我记得你说过。\"}" +
            "]}"
        val drafts = InnerVoiceGenerator.parse(raw)
        assertEquals(1, drafts.size)
        assertEquals("海水真冷啊。\n\n可我记得你说过。", drafts[0].content)
    }

    @Test
    fun `parser clamps overlong title echo and content`() {
        val raw = "{\"voices\":[" +
            "{\"title\":\"" + "标".repeat(50) + "\",\"echo\":\"" + "心".repeat(80) + "\",\"content\":\"" + "文".repeat(400) + "\"}" +
            "]}"
        val drafts = InnerVoiceGenerator.parse(raw)
        assertEquals(1, drafts.size)
        assertEquals(InnerVoiceGenerator.TITLE_MAX_CHARS, drafts[0].title.length)
        assertEquals(InnerVoiceGenerator.ECHO_MAX_CHARS, drafts[0].echo.length)
        assertEquals(InnerVoiceGenerator.CONTENT_MAX_CHARS, drafts[0].content.length)
    }

    @Test
    fun `parser caps at four voices and drops blank rows`() {
        val rows = (1..6).joinToString(",") {
            if (it == 3) "{\"title\":\"\",\"echo\":\"\"}" else "{\"title\":\"心$it\",\"echo\":\"心声$it\"}"
        }
        val drafts = InnerVoiceGenerator.parse("{\"voices\":[$rows]}")
        assertEquals(InnerVoiceGenerator.MAX_VOICES, drafts.size)
    }

    @Test
    fun `parser returns empty for non-json or wrong shape`() {
        assertTrue(InnerVoiceGenerator.parse("今天想你了,但我不想告诉你~").isEmpty())
        assertTrue(InnerVoiceGenerator.parse("{\"ops\":[]}").isEmpty())
        assertTrue(InnerVoiceGenerator.parse("{\"voices\":\"not-array\"}").isEmpty())
    }

    // ── 盖章:条数下限 / at 错开 ─────────────────────────────────────

    @Test
    fun `to entities stamps sequential at and returns empty below minimum`() {
        val two = "{\"voices\":[" +
            "{\"title\":\"a\",\"echo\":\"第一条\",\"content\":\"正文甲\"},{\"title\":\"b\",\"echo\":\"第二条\",\"content\":\"正文乙\"}]}"
        val ok = InnerVoiceGenerator.toEntities(two, characterId = 7L, baseAt = 1000L)
        assertEquals(2, ok.size)
        assertEquals(7L, ok[0].characterId)
        assertEquals(1000L, ok[0].at)
        assertEquals(1001L, ok[1].at) // 依次错开,倒序列表顺序稳定
        assertEquals("正文甲", ok[0].content)
        assertEquals("正文乙", ok[1].content)
        // 不足 2 条 = 生成失败(空列表)
        val one = "{\"voices\":[{\"title\":\"a\",\"echo\":\"只有一条\"}]}"
        assertTrue(InnerVoiceGenerator.toEntities(one, 7L, 1000L).isEmpty())
    }

    // ── 请求通道:失败安全 ───────────────────────────────────────────

    @Test
    fun `channel returns raw text on success and null on error stream`() = runTest {
        val ok = InnerVoiceChannel(fakeLlm("{\"voices\":[]}"), { config })
        assertEquals("{\"voices\":[]}", ok.request("s", "u"))
        val err = InnerVoiceChannel(errorLlm(), { config })
        assertNull(err.request("s", "u"))
        val empty = InnerVoiceChannel(emptyLlm(), { config })
        assertNull(empty.request("s", "u"))
    }

    // ── prompt:素材与红线 ───────────────────────────────────────────

    @Test
    fun `system prompt carries persona memories and voice contract`() {
        val p = InnerVoiceGenerator.systemPrompt(char, memories)
        assertTrue(p.contains("「苏馨玥」"))
        assertTrue(p.contains("[人设] 美术系大四学生"))
        assertTrue(p.contains("[记忆]"))
        assertTrue(p.contains("我的称呼:老公"))
        assertTrue(p.contains("2~4 条心声"))
        // 输出契约:单行 JSON shape(升级:短文 content 字段)
        assertTrue(p.contains("\"voices\""))
        assertTrue(p.contains("\"content\""))
        assertTrue(p.contains("短文"))
    }

    @Test
    fun `user prompt replays recent turns`() {
        val u = InnerVoiceGenerator.userPrompt(
            char,
            listOf(com.neethu.gouxiang.data.MemoryTurn("今天好累", "那快歇着,我给你画张速写")),
        )
        assertTrue(u.contains("用户: 今天好累"))
        assertTrue(u.contains("苏馨玥: 那快歇着,我给你画张速写"))
        val empty = InnerVoiceGenerator.userPrompt(char, emptyList())
        assertTrue(empty.contains("还没有聊天记录"))
    }

    @Test
    fun `prompts never mention billing or expiry`() {
        // 范围 3 红线:本地版无计费/无时效,生成文案不得出现猫粮/扣费/过期字样
        val p = InnerVoiceGenerator.systemPrompt(char, memories)
        assertFalse(p.contains("猫粮"))
        assertFalse(p.contains("扣费"))
        assertFalse(p.contains("过期"))
        assertFalse(p.contains("剩余"))
    }

    // ── 日期行(范围 3:只显示日期)─────────────────────────────────

    @Test
    fun `date format is yyyy MM dd`() {
        // 2026-10-27 12:00 UTC epoch → 格式化恒为 yyyy.MM.dd(不带「剩余 N 天过期」)
        val at = 1790500000000L // 2026-10-27 附近
        val label = com.neethu.gouxiang.ui.innervoice.formatVoiceDate(at)
        assertTrue(label.matches(Regex("\\d{4}\\.\\d{2}\\.\\d{2}")))
        assertFalse(label.contains("过期"))
        assertFalse(label.contains("剩余"))
    }

    // ── 基建 ────────────────────────────────────────────────────────

    private val char = CharacterEntity(
        id = 1L,
        name = "苏馨玥",
        profile = "美术系大四学生",
        intro = "i",
        exampleLines = "",
        openingLine = "o",
        appearance = "a",
        appearanceStyle = "写实摄影风",
        createdAt = 0,
    )

    private val memories = listOf(
        MemoryEntity(characterId = 1L, category = "relationship", content = "我的称呼:老公", importance = 9, at = 1),
        MemoryEntity(characterId = 1L, category = "event", content = "两人一起去了美术馆", importance = 5, at = 2),
    )

    private val config = LlmConfig(baseUrl = "http://localhost", apiKey = "k", model = "m")

    private fun fakeLlm(reply: String): ChatOrchestrator.Llm = ChatOrchestrator.Llm { _, _ ->
        flow {
            emit(com.neethu.gouxiang.ai.adapter.LlmStreamEvent.TextDelta(reply))
            emit(com.neethu.gouxiang.ai.adapter.LlmStreamEvent.Finish("stop"))
        }
    }

    private fun errorLlm(): ChatOrchestrator.Llm = ChatOrchestrator.Llm { _, _ ->
        flow { emit(com.neethu.gouxiang.ai.adapter.LlmStreamEvent.Error(IllegalStateException("boom"))) }
    }

    private fun emptyLlm(): ChatOrchestrator.Llm = ChatOrchestrator.Llm { _, _ -> flow { } }
}
