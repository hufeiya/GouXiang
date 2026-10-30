package com.neethu.gouxiang.sidestory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F 工作包 F2 输出 schema 单测:宽松截取/清洗/条数边界/固定分区盖章/
 * bodyJson 往返;任何非法输入返回 null 不抛(解析失败不污染正式记录)。
 */
class SidestoryOutputTest {

    private val theater = SidestoryCatalog.byKey("Drunk")!!

    @Test
    fun `parse accepts json wrapped in chatter and fences`() {
        val raw = "好的:\n```json\n{\"title\":\"微醺\",\"pages\":[" +
            "{\"title\":\"\",\"body\":\"第一格\",\"imagePrompt\":\"夜色\"}," +
            "{\"title\":\"二\",\"body\":\"第二格\"}," +
            "{\"title\":\"三\",\"body\":\"第三格\"}" +
            "]}\n```\n以上。"
        val v = SidestoryOutput.parse(raw, theater)!!
        assertEquals("微醺", v.title)
        assertEquals(3, v.pages.size)
        assertEquals("夜色", v.pages[0].imagePrompt)
    }

    @Test
    fun `parse clamps overlong fields and drops blank bodies`() {
        val longBody = "长".repeat(500)
        val raw = "{\"title\":\"" + "标".repeat(30) + "\",\"pages\":[" +
            "{\"title\":\"a\",\"body\":\"$longBody\"}," +
            "{\"title\":\"b\",\"body\":\"   \"}," +
            "{\"title\":\"c\",\"body\":\"第三格\"}," +
            "{\"title\":\"d\",\"body\":\"第四格\"}]}"
        val v = SidestoryOutput.parse(raw, theater)!!
        assertEquals(SidestoryOutput.TITLE_MAX_CHARS, v.title.length)
        assertEquals(3, v.pages.size) // 空白 body 丢弃
        assertEquals(SidestoryOutput.BODY_MAX_CHARS, v.pages[0].body.length)
    }

    @Test
    fun `theater below min pages fails and above max is capped`() {
        val two = "{\"title\":\"t\",\"pages\":[" +
            "{\"body\":\"一\"},{\"body\":\"二\"}]}"
        assertNull("少于 3 格判失败", SidestoryOutput.parse(two, theater))
        val eight = (1..8).joinToString(",") { "{\"body\":\"格$it\"}" }
        val capped = SidestoryOutput.parse("{\"title\":\"t\",\"pages\":[$eight]}", theater)!!
        assertEquals(theater.pageMax, capped.pages.size)
    }

    @Test
    fun `fixed sections must match count and titles are stamped from catalog`() {
        val checkphone = SidestoryCatalog.byKey("checkphone")!!
        // 10/11 区 → 失败(schema 越界)
        val tenSections = checkphone.sections.dropLast(1)
            .joinToString(",") { "{\"title\":\"$it\",\"body\":\"内容\"}" }
        assertNull(SidestoryOutput.parse("{\"pages\":[$tenSections]}", checkphone))
        // 11 区但标题漂移 → 标题按目录盖章,不算失败
        val drifted = checkphone.sections.joinToString(",") { "{\"title\":\"乱写的\",\"body\":\"$it 内容\"}" }
        val v = SidestoryOutput.parse("{\"pages\":[$drifted]}", checkphone)!!
        assertEquals(checkphone.sections, v.pages.map { it.title })
    }

    @Test
    fun `non json or wrong shape returns null`() {
        assertNull(SidestoryOutput.parse("今天想你了,但我不想告诉你~", theater))
        assertNull(SidestoryOutput.parse("{\"ops\":[]}", theater))
        assertNull(SidestoryOutput.parse("{\"pages\":\"not-array\"}", theater))
        assertNull(SidestoryOutput.parse("{\"title\":\"t\"}", theater)) // 缺 pages
        assertNull(SidestoryOutput.parse("", theater))
    }

    @Test
    fun `json roundtrip survives newline and quotes`() {
        val v = SidestoryOutput.Validated(
            title = "标题\"引号\"",
            pages = listOf(
                SidestoryOutput.Page("分区", "第一行\n第二行\"引\"", ""),
            ),
        )
        val json = SidestoryOutput.toJson(v)
        val back = SidestoryOutput.fromJson(json)
        assertEquals(1, back.size)
        assertEquals("第一行\n第二行\"引\"", back[0].body)
        assertEquals("标题\"引号\"", SidestoryOutput.titleFromJson(json))
    }

    @Test
    fun `fromJson never throws on dirty payloads`() {
        assertTrue(SidestoryOutput.fromJson("").isEmpty())
        assertTrue(SidestoryOutput.fromJson("not json").isEmpty())
        assertTrue(SidestoryOutput.fromJson("{\"pages\":{}}").isEmpty())
        assertEquals("", SidestoryOutput.titleFromJson("broken"))
    }
}
