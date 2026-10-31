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
    fun `json sticker kind degrades to parenthesized text`() {
        val raw = """[
            {"kind":"text","text":"老公,","actions":["靠在你肩头"]},
            {"kind":"sticker","text":"歪头撒娇"},
            {"kind":"text","text":"那房子真的好喜欢,什么时候能搬进去呀?"}
        ]"""
        val r = MultiBubbleParser.parse(raw)
        assertEquals(3, r.bubbles.size)
        assertEquals(Bubble.Kind.TEXT, r.bubbles[0].kind)
        assertEquals(listOf("靠在你肩头"), r.bubbles[0].actions)
        // 存量 sticker kind 降级为括号文本(2026-10-31 口径),不再出表情泡
        assertEquals(Bubble.Kind.TEXT, r.bubbles[1].kind)
        assertEquals("(歪头撒娇)", r.bubbles[1].text)
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
        // 兜底通路:第一段是不合法 JSON 残片当文本,第二段带动作,第三段
        // [sticker:哼] 已降级为括号文本(哼)(输入词「靠在你肩头」在
        // actionWhitelist 白名单内,照常剥离为 Action)
        assertEquals(Bubble.Kind.TEXT, r.bubbles.last().kind)
        assertEquals("(哼)", r.bubbles.last().text)
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
    fun `inline sticker marker degrades img voice keep kinds`() {
        val r = MultiBubbleParser.parse(
            "哈哈\n[sticker:笑到打滚]\n[img:我们今天的合照]\n(语音:老公快回来吃饭!)",
        )
        assertEquals(4, r.bubbles.size)
        // sticker 行内标记降级为括号文本(不裂泡、不出表情占位卡)
        assertEquals(Bubble.Kind.TEXT, r.bubbles[1].kind)
        assertEquals("(笑到打滚)", r.bubbles[1].text)
        assertEquals(Bubble.Kind.IMAGE, r.bubbles[2].kind)
        assertEquals(Bubble.Kind.VOICE, r.bubbles[3].kind)
        assertEquals("老公快回来吃饭!", r.bubbles[3].text)
    }

    @Test
    fun `trailing sticker marker degrades inline into same bubble`() {
        // 2026-10-31 口径:协议不再有表情泡,[sticker:…] 行尾缀原位降级为括号
        // 文本留在同一段正文里,流式草稿与上屏观感一致(此前拆出独立贴纸泡)
        val r = MultiBubbleParser.parse("要给你画一面整墙的手绘墙啦[sticker:眼睛亮晶晶]")
        assertEquals(1, r.bubbles.size)
        assertEquals(Bubble.Kind.TEXT, r.bubbles[0].kind)
        assertEquals("要给你画一面整墙的手绘墙啦(眼睛亮晶晶)", r.bubbles[0].text)
    }

    @Test
    fun `trailing img and card markers also split off`() {
        val r = MultiBubbleParser.parse("给你看我今天的穿搭[img:今日穿搭]")
        assertEquals(2, r.bubbles.size)
        assertEquals(Bubble.Kind.IMAGE, r.bubbles[1].kind)
        assertEquals("给你看我今天的穿搭", r.bubbles[0].text)

        // 卡片行尾缀:拆出的 CARD 泡 text 保留整行标记(落库侧 parseCardLine 解载荷)
        val c = MultiBubbleParser.parse("给你转了个零花钱[卡片:转账 520|给你买奶茶]")
        assertEquals(2, c.bubbles.size)
        assertEquals(Bubble.Kind.CARD, c.bubbles[1].kind)
        assertEquals("[卡片:转账 520|给你买奶茶]", c.bubbles[1].text)
    }

    @Test
    fun `whole line sticker marker degrades and fullwidth colon trailing works`() {
        // 整行 [sticker:…] 降级为一条括号文本泡;全角冒号行尾缀同样原位降级
        val whole = MultiBubbleParser.parse("[sticker:眼睛亮晶晶]")
        assertEquals(1, whole.bubbles.size)
        assertEquals(Bubble.Kind.TEXT, whole.bubbles[0].kind)
        assertEquals("(眼睛亮晶晶)", whole.bubbles[0].text)

        val fw = MultiBubbleParser.parse("好可爱呀[sticker：揉脸]")
        assertEquals(1, fw.bubbles.size)
        assertEquals(Bubble.Kind.TEXT, fw.bubbles[0].kind)
        assertEquals("好可爱呀(揉脸)", fw.bubbles[0].text)
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
        assertEquals(Bubble.Kind.TEXT, r.bubbles[2].kind)
        assertEquals("(晚安表情)", r.bubbles[2].text)
    }

    @Test
    fun `fullwidth and halfwidth parens both parsed as actions`() {
        // 输入词「凑近你耳边」「低头轻笑」均在 actionWhitelist 白名单内,
        // 全/半角括号照常剥离为 Action(白名单机制不改变本用例)
        val r = MultiBubbleParser.parse("(凑近你耳边)嘘——\n(低头轻笑)")
        // 第二段是纯动作行,按设计归并进最后一泡
        assertEquals(1, r.bubbles.size)
        assertEquals("嘘——", r.bubbles[0].text)
        assertEquals(listOf("凑近你耳边", "低头轻笑"), r.bubbles[0].actions)
    }

    @Test
    fun `action-only lines merge into next bubble`() {
        // 输入词「轻轻拍你的背」在 actionWhitelist 白名单内,动作行照旧归并
        val r = MultiBubbleParser.parse("(轻轻拍你的背)\n别难过了")
        assertEquals(1, r.bubbles.size)
        assertEquals("别难过了", r.bubbles[0].text)
        assertEquals(listOf("轻轻拍你的背"), r.bubbles[0].actions)
    }

    @Test
    fun `trailing action-only line attaches to last bubble`() {
        // 输入词「瘫在你身上」在 actionWhitelist 白名单内,收尾动作行照旧挂末泡
        val r = MultiBubbleParser.parse("今天也好累啊\n(瘫在你身上)")
        assertEquals(1, r.bubbles.size)
        assertEquals(listOf("瘫在你身上"), r.bubbles[0].actions)
    }

    // ── () 动作白名单 + 纯标点段丢弃(02 文档 §3.3 规则4/规则5)────────

    @Test
    fun `non whitelist parens keep as text without actions`() {
        // 规则4:「转了个圈」不在 actionWhitelist 白名单内,保留正文、不产 actions
        val r = MultiBubbleParser.parse("(转了个圈)哈哈,被你抓到了")
        assertEquals(1, r.bubbles.size)
        assertEquals("(转了个圈)哈哈,被你抓到了", r.bubbles[0].text)
        assertTrue(r.bubbles[0].actions.isEmpty())
        // 同段混合:白名单内「点头」照常剥离,白名单外保留
        val mixed = MultiBubbleParser.parse("(点头)(转了个圈)你好呀")
        assertEquals("(转了个圈)你好呀", mixed.bubbles[0].text)
        assertEquals(listOf("点头"), mixed.bubbles[0].actions)
    }

    @Test
    fun `pure punctuation segments are dropped`() {
        // 规则5:纯标点段(无字母/数字/汉字)不成泡
        val r = MultiBubbleParser.parse("老公\n。。。")
        assertEquals(1, r.bubbles.size)
        assertEquals("老公", r.bubbles[0].text)
        val r2 = MultiBubbleParser.parse("！！\n想你了")
        assertEquals(1, r2.bubbles.size)
        assertEquals("想你了", r2.bubbles[0].text)
    }

    @Test
    fun `voice special case still wins over action whitelist`() {
        // (语音:…) 整行特判先于 () 动作白名单识别,白名单机制下不回归
        val r = MultiBubbleParser.parse("老公我先睡了\n(语音:晚安,帮你留门)")
        assertEquals(2, r.bubbles.size)
        assertEquals(Bubble.Kind.TEXT, r.bubbles[0].kind)
        assertEquals(Bubble.Kind.VOICE, r.bubbles[1].kind)
        assertEquals("晚安,帮你留门", r.bubbles[1].text)
    }

    @Test
    fun `action whitelist is testable and contains project keywords`() {
        // 白名单为 object 内可测常量:兜底文案与既有用例关键词必须在内,
        // 防白名单漏配导致兜底/教学样例动作被当正文
        listOf(
            "戳了戳你", "靠在你肩头", "凑近你耳边", "低头轻笑",
            "轻轻拍你的背", "瘫在你身上", "凑过来瞄你的屏幕",
        ).forEach { assertTrue("白名单应含「$it」", it in MultiBubbleParser.actionWhitelist) }
        // 文档通用短动作抽查(歪头:实机高频动作词)
        assertTrue("点头" in MultiBubbleParser.actionWhitelist)
        assertTrue("微笑" in MultiBubbleParser.actionWhitelist)
        assertTrue("歪头" in MultiBubbleParser.actionWhitelist)
        // 非动作词不在白名单内(防全量剥离回归)
        assertTrue("转了个圈" !in MultiBubbleParser.actionWhitelist)
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

    // ── 卡片行(卡片消息端到端打通)────────────────────────────────

    @Test
    fun `card line in fallback path keeps original line as text`() {
        // 换行兜底通路:整行 [卡片:…] 识别为 CARD,text 保留原行(落库侧再解析载荷)
        val r = MultiBubbleParser.parse("给你转了个零花钱\n[卡片:转账 520|给你买奶茶]")
        assertEquals(2, r.bubbles.size)
        assertEquals(Bubble.Kind.CARD, r.bubbles[1].kind)
        assertEquals("[卡片:转账 520|给你买奶茶]", r.bubbles[1].text)
    }

    @Test
    fun `json bare string and object forms both produce card`() {
        // 裸串:lenient JSON 把方括号消费成数组定界符(全角冒号、无空格时走 JSON
        // 通路),裸串只剩「卡片:…」前缀——解析侧补回方括号,与整行形态统一
        val bare = MultiBubbleParser.parse("[卡片：转账520]")
        assertEquals(1, bare.bubbles.size)
        assertEquals(Bubble.Kind.CARD, bare.bubbles[0].kind)
        assertEquals("[卡片:转账520]", bare.bubbles[0].text)
        // 对象形态:{"kind":"card","text":…} → 同样产出 CARD
        val obj = MultiBubbleParser.parse("""[{"kind":"card","text":"[卡片:位置 中央公园]"}]""")
        assertEquals(1, obj.bubbles.size)
        assertEquals(Bubble.Kind.CARD, obj.bubbles[0].kind)
        assertEquals("[卡片:位置 中央公园]", obj.bubbles[0].text)
    }

    @Test
    fun `plain text mentioning card is not misread as card`() {
        // 没有 [卡片:…] 整行标记的普通文本(即便含「卡片」二字)不误判
        val r = MultiBubbleParser.parse("我发你一张卡片看看\n这张卡片做得真好")
        assertEquals(2, r.bubbles.size)
        assertTrue(r.bubbles.all { it.kind == Bubble.Kind.TEXT })
    }
}
