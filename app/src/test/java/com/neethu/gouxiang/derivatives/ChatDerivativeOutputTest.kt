package com.neethu.gouxiang.derivatives

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手帐/小说输出 schema 校验单测(G1/G2;纯 JVM):段数/章数边界、字段清洗、
 * 坏输入不抛、序列化往返、prompt 红线(全量扫描禁「猫粮/扣费/计费/会员」)。
 */
class ChatDerivativeOutputTest {

    private fun handbookRaw(paragraphs: Int = 3, prefix: String = ""): String {
        val items = (1..paragraphs).joinToString(",") {
            "{\"body\":\"${prefix}第${it}段的手帐正文\"}"
        }
        return "好的,这是手帐:{\"title\":\"放学后的天台\",\"mood\":\"微甜\",\"paragraphs\":[$items],\"imagePrompt\":\"天台的晚霞\"} 请查收"
    }

    @Test
    fun `handbook parses through chatter and clamps fields`() {
        val h = ChatDerivativeOutput.parseHandbook(handbookRaw(3))!!
        assertEquals("放学后的天台", h.title)
        assertEquals("微甜", h.mood)
        assertEquals(3, h.paragraphs.size)
        assertEquals("第1段的手帐正文", h.paragraphs[0].body)
        assertEquals("天台的晚霞", h.imagePrompt)
    }

    @Test
    fun `handbook below three paragraphs fails closed`() {
        assertNull(ChatDerivativeOutput.parseHandbook(handbookRaw(2)))
        assertNull(ChatDerivativeOutput.parseHandbook(handbookRaw(0)))
        assertNull(ChatDerivativeOutput.parseHandbook("完全没有 JSON"))
        assertNull(ChatDerivativeOutput.parseHandbook("{\"paragraphs\":\"not-array\"}"))
    }

    @Test
    fun `handbook caps at eight paragraphs and trims long bodies`() {
        val h = ChatDerivativeOutput.parseHandbook(handbookRaw(11))!!
        assertEquals(ChatDerivativeOutput.HANDBOOK_PARAGRAPH_MAX, h.paragraphs.size)
        val longBody = "长".repeat(ChatDerivativeOutput.HANDBOOK_BODY_MAX + 50)
        val raw = "{\"title\":\"t\",\"paragraphs\":[{\"body\":\"$longBody\"},{\"body\":\"b\"},{\"body\":\"c\"}]}"
        val clipped = ChatDerivativeOutput.parseHandbook(raw)!!
        assertEquals(ChatDerivativeOutput.HANDBOOK_BODY_MAX, clipped.paragraphs[0].body.length)
        // 空白段被丢弃后不足下界 → 失败
        assertNull(ChatDerivativeOutput.parseHandbook("{\"paragraphs\":[{\"body\":\"  \"},{\"body\":\"b\"},{\"body\":\"c\"}]}"))
    }

    private fun novelRaw(chapters: Int): String {
        val items = (1..chapters).joinToString(",") {
            "{\"title\":\"第${it}章\",\"overview\":\"概要$it\",\"content\":\"正文$it 的内容\"}"
        }
        return "{\"title\":\"我们的夏天\",\"introduction\":\"由对话写成的小说\",\"chapters\":[$items],\"imagePrompt\":\"\"}"
    }

    @Test
    fun `novel accepts chapters inside the length bounds`() {
        for (length in NovelLength.entries) {
            val n = ChatDerivativeOutput.parseNovel(novelRaw(length.chapters.first), length)!!
            assertEquals(length.chapters.first, n.chapters.size)
            assertEquals("我们的夏天", n.title)
            assertEquals("由对话写成的小说", n.introduction)
        }
    }

    @Test
    fun `novel rejects chapter counts outside the chosen bound`() {
        // 长度控制是明确选择:章数越界判失败,不静默裁剪
        assertNull(ChatDerivativeOutput.parseNovel(novelRaw(1), NovelLength.SHORT))
        assertNull(ChatDerivativeOutput.parseNovel(novelRaw(4), NovelLength.SHORT))
        assertNull(ChatDerivativeOutput.parseNovel(novelRaw(3), NovelLength.MEDIUM))
        assertNull(ChatDerivativeOutput.parseNovel(novelRaw(9), NovelLength.LONG))
    }

    @Test
    fun `novel drops empty chapters before counting`() {
        val raw = "{\"chapters\":[{\"content\":\"有内容\"},{\"content\":\"  \"},{\"content\":\"也有\"},{\"content\":\"还有\"}]}"
        val n = ChatDerivativeOutput.parseNovel(raw, NovelLength.SHORT)!!
        assertEquals(3, n.chapters.size)
    }

    @Test
    fun `body json roundtrips for both shapes`() {
        val h = ChatDerivativeOutput.parseHandbook(handbookRaw(4))!!
        val h2 = ChatDerivativeOutput.handbookFromJson(ChatDerivativeOutput.handbookToJson(h))!!
        assertEquals(h.title, h2.title)
        assertEquals(h.mood, h2.mood)
        assertEquals(h.paragraphs, h2.paragraphs)
        val n = ChatDerivativeOutput.parseNovel(novelRaw(3), NovelLength.SHORT)!!
        val n2 = ChatDerivativeOutput.novelFromJson(ChatDerivativeOutput.novelToJson(n))!!
        assertEquals(n.chapters, n2.chapters)
        // 坏 JSON 回落 null,详情层不崩
        assertNull(ChatDerivativeOutput.handbookFromJson("broken"))
        assertNull(ChatDerivativeOutput.novelFromJson("broken"))
        assertEquals("", ChatDerivativeOutput.photoDescriptionFromJson("broken"))
    }

    @Test
    fun `photo body json keeps description and prompt`() {
        val json = ChatDerivativeOutput.photoToJson("天台看晚霞\"引号", "prompt 内文")
        assertEquals("天台看晚霞\"引号", ChatDerivativeOutput.photoDescriptionFromJson(json))
        assertTrue(json.contains("prompt"))
    }

    @Test
    fun `prompts never mention billing words and carry schema bounds`() {
        val forbidden = listOf("猫粮", "扣费", "计费", "会员")
        val character = com.neethu.gouxiang.data.CharacterEntity(
            id = 1, name = "苏馨玥", profile = "设定", intro = "", exampleLines = "",
            openingLine = "", appearance = "", appearanceStyle = "", createdAt = 1,
        )
        for (length in NovelLength.entries) {
            val system = ChatDerivativePrompts.novelSystemPrompt(character, null, length)
            for (word in forbidden) {
                assertTrue("小说 prompt 红线字样「$word」", !system.contains(word))
            }
            assertTrue(system.contains("${length.chapters.first}~${length.chapters.last} 章"))
        }
        val hSystem = ChatDerivativePrompts.handbookSystemPrompt(character, null)
        for (word in forbidden) {
            assertTrue("手帐 prompt 红线字样「$word」", !hSystem.contains(word))
        }
        assertTrue(hSystem.contains("${ChatDerivativeOutput.HANDBOOK_PARAGRAPH_MIN}~${ChatDerivativeOutput.HANDBOOK_PARAGRAPH_MAX} 段"))
        val photoPrompt = ChatDerivativePrompts.photoPrompt(character, "天台看晚霞")
        for (word in forbidden) {
            assertTrue("合照 prompt 红线字样「$word」", !photoPrompt.contains(word))
        }
        assertTrue(photoPrompt.contains("天台看晚霞"))
    }

    @Test
    fun `option copy carries no billing words`() {
        val forbidden = listOf("猫粮", "扣费", "计费", "会员")
        val labels = DerivativeRange.Preset.entries.map { it.label } + NovelLength.entries.map { it.label }
        for (text in labels) {
            for (word in forbidden) {
                assertTrue("选项文案红线字样「$word」:$text", !text.contains(word))
            }
        }
    }
}
