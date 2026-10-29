package com.neethu.gouxiang.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多泡输出协议解析器单测(§6.3 约定的两条通路 + 边界)。
 */
class MultiBubbleParserTest {

    // ── A. JSON 数组通路 ────────────────────────────────────────────

    @Test
    fun `json array with text sticker and actions`() {
        val raw = """[
            {"kind":"text","text":"老公,","actions":["靠在你肩头"]},
            {"kind":"sticker","text":"歪头撒娇"},
            {"kind":"text","text":"那房子真的好喜欢,什么时候能搬进去呀?"}
        ]"""
        val r = MultiBubbleParser.parse(raw)
        assertEquals(3, r.bubbles.size)
        assertEquals(Bubble.Kind.TEXT, r.bubbles[0].kind)
        assertEquals(listOf("靠在你肩头"), r.bubbles[0].actions)
        assertEquals(Bubble.Kind.STICKER, r.bubbles[1].kind)
        assertEquals("歪头撒娇", r.bubbles[1].text)
        assertNull(r.proactivity)
    }

    @Test
    fun `json object with bubbles and proactivity`() {
        val raw = """
            {"bubbles":[{"kind":"text","text":"在忙吗?"}],
             "proactivity":{"kind":"text","text":"刚煮了奶茶,给你留了一杯"}}
        """.trimIndent()
        val r = MultiBubbleParser.parse(raw)
        assertEquals(1, r.bubbles.size)
        assertNotNull(r.proactivity)
        assertEquals("刚煮了奶茶,给你留了一杯", r.proactivity!!.text)
    }

    @Test
    fun `json wrapped in code fence still parses`() {
        val raw = "```json\n[{\"kind\":\"img\",\"text\":\"黄昏的天台\"}]\n```"
        val r = MultiBubbleParser.parse(raw)
        assertEquals(1, r.bubbles.size)
        assertEquals(Bubble.Kind.IMAGE, r.bubbles[0].kind)
    }

    @Test
    fun `unknown kind degrades to text and bubbles capped at 4`() {
        val raw = """[
            {"kind":"text","text":"1"},
            {"kind":"weird","text":"2"},
            {"kind":"image","text":"3"},
            {"kind":"voice","text":"4"},
            {"kind":"text","text":"5"}
        ]"""
        val r = MultiBubbleParser.parse(raw)
        assertEquals(4, r.bubbles.size)
        assertEquals(Bubble.Kind.TEXT, r.bubbles[1].kind)
        assertEquals(Bubble.Kind.VOICE, r.bubbles[3].kind)
    }

    @Test
    fun `malformed json falls back to delimiter path`() {
        val raw = "[{not json at all\n老公,(靠在你肩头)吃饭了吗?\n[sticker:哼]"
        val r = MultiBubbleParser.parse(raw)
        // 兜底通路:第一段是不合法 JSON 残片当文本,第二段带动作,第三段贴纸
        assertEquals(Bubble.Kind.STICKER, r.bubbles.last().kind)
        assertEquals("哼", r.bubbles.last().text)
        assertTrue(r.bubbles.any { it.actions.contains("靠在你肩头") })
    }

    // ── B. 分隔符兜底通路 ───────────────────────────────────────────

    @Test
    fun `plain lines split into text bubbles`() {
        val r = MultiBubbleParser.parse("老公,\n今天加班到几点呀?")
        assertEquals(2, r.bubbles.size)
        assertEquals(Bubble.Kind.TEXT, r.bubbles[0].kind)
        assertEquals("今天加班到几点呀?", r.bubbles[1].text)
    }

    @Test
    fun `pipe separator also splits`() {
        val r = MultiBubbleParser.parse("在吗 ||| 想你了")
        assertEquals(2, r.bubbles.size)
        assertEquals("在吗", r.bubbles[0].text)
        assertEquals("想你了", r.bubbles[1].text)
    }

    @Test
    fun `inline sticker img voice markers recognized`() {
        val r = MultiBubbleParser.parse(
            "哈哈\n[sticker:笑到打滚]\n[img:我们今天的合照]\n(语音:老公快回来吃饭!)",
        )
        assertEquals(4, r.bubbles.size)
        assertEquals(Bubble.Kind.STICKER, r.bubbles[1].kind)
        assertEquals("笑到打滚", r.bubbles[1].text)
        assertEquals(Bubble.Kind.IMAGE, r.bubbles[2].kind)
        assertEquals(Bubble.Kind.VOICE, r.bubbles[3].kind)
        assertEquals("老公快回来吃饭!", r.bubbles[3].text)
    }

    // ── 语音条格式归一化(F4 验收 medium 修复)────────────────────────

    @Test
    fun `square bracket voice with fullwidth colon also parses as voice`() {
        // F4 实机实锤:模型自发输出 [语音：…](方括号 + 全角冒号),此前被当文本泡
        val r = MultiBubbleParser.parse("[语音：晚安呀老公]")
        assertEquals(1, r.bubbles.size)
        assertEquals(Bubble.Kind.VOICE, r.bubbles[0].kind)
        assertEquals("晚安呀老公", r.bubbles[0].text)
    }

    @Test
    fun `all four voice notations normalize to one voice kind`() {
        val variants = listOf(
            "(语音:晚安呀老公)",   // 提示词教的半角圆括号变体
            "(语音：晚安呀老公)",   // 全角冒号圆括号
            "[语音:晚安呀老公]",   // 半角冒号方括号
            "[语音：晚安呀老公]",   // F4 模型自发变体
        )
        variants.forEach { raw ->
            val r = MultiBubbleParser.parse(raw)
            assertEquals("variant=$raw", 1, r.bubbles.size)
            assertEquals("variant=$raw", Bubble.Kind.VOICE, r.bubbles[0].kind)
            assertEquals("variant=$raw", "晚安呀老公", r.bubbles[0].text)
        }
    }

    @Test
    fun `square bracket voice inside mixed burst keeps sequence`() {
        val r = MultiBubbleParser.parse("老公我先睡了\n[语音：晚安呀老公]\n[sticker:晚安表情]")
        assertEquals(3, r.bubbles.size)
        assertEquals(Bubble.Kind.TEXT, r.bubbles[0].kind)
        assertEquals(Bubble.Kind.VOICE, r.bubbles[1].kind)
        assertEquals(Bubble.Kind.STICKER, r.bubbles[2].kind)
    }

    @Test
    fun `fullwidth and halfwidth parens both parsed as actions`() {
        val r = MultiBubbleParser.parse("(凑近你耳边)嘘——\n(低头轻笑)")
        // 第二段是纯动作行,按设计归并进最后一泡
        assertEquals(1, r.bubbles.size)
        assertEquals("嘘——", r.bubbles[0].text)
        assertEquals(listOf("凑近你耳边", "低头轻笑"), r.bubbles[0].actions)
    }

    @Test
    fun `action-only lines merge into next bubble`() {
        val r = MultiBubbleParser.parse("(轻轻拍你的背)\n别难过了")
        assertEquals(1, r.bubbles.size)
        assertEquals("别难过了", r.bubbles[0].text)
        assertEquals(listOf("轻轻拍你的背"), r.bubbles[0].actions)
    }

    @Test
    fun `trailing action-only line attaches to last bubble`() {
        val r = MultiBubbleParser.parse("今天也好累啊\n(瘫在你身上)")
        assertEquals(1, r.bubbles.size)
        assertEquals(listOf("瘫在你身上"), r.bubbles[0].actions)
    }

    @Test
    fun `more than 4 segments capped at 4`() {
        val r = MultiBubbleParser.parse("一\n二\n三\n四\n五\n六")
        assertEquals(4, r.bubbles.size)
        assertEquals("四", r.bubbles[3].text)
    }

    @Test
    fun `blank input yields empty reply`() {
        val r = MultiBubbleParser.parse("   \n  ")
        assertTrue(r.bubbles.isEmpty())
    }

    @Test
    fun `single line without markup is one text bubble`() {
        val r = MultiBubbleParser.parse("收到!")
        assertEquals(1, r.bubbles.size)
        assertEquals("收到!", r.bubbles[0].text)
        assertEquals(Bubble.Kind.TEXT, r.bubbles[0].kind)
    }
}
