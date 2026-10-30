package com.neethu.gouxiang.sidestory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F 工作包 F2 模板目录单测:15 条番外 + 3 条心动事件(条数/唯一 key/文案完整/
 * 输出边界/分区清单/输入校验),以及红线(全部用户可见文案与 prompt 严禁
 * 猫粮/扣费/计费/会员字样——与 DerivativeTaskTest 同一口径扩到本包)。
 */
class SidestoryCatalogTest {

    @Before
    fun validateCatalog() = SidestoryCatalog.validate()

    @Test
    fun `catalog carries 15 sidestory templates and 3 heartbeat events`() {
        assertEquals(15, SidestoryCatalog.entries.size)
        assertEquals(3, SidestoryCatalog.heartbeatEvents.size)
        assertTrue(SidestoryCatalog.entries.none { it.isHeartbeat })
        assertTrue(SidestoryCatalog.heartbeatEvents.all { it.isHeartbeat })
    }

    @Test
    fun `crafted five templates carry complete copy`() {
        val crafted = setOf("his_collection", "Drunk", "his_dream", "write_for_you", "annual_report")
        for (key in crafted) {
            val e = SidestoryCatalog.byKey(key)!!
            assertTrue(e.name.isNotBlank())
            assertTrue(e.subtitle.length >= 8)
            assertTrue(e.loading.length >= 8)
            assertTrue(e.button.isNotBlank())
            assertTrue("精做模板 $key 的选题基调应具体(≥30字)", e.brief.length >= 30)
            assertTrue(e.optionalImage)
        }
    }

    @Test
    fun `all entries are schema complete and generatable`() {
        for (e in SidestoryCatalog.entries + SidestoryCatalog.heartbeatEvents) {
            assertTrue("${e.key} 缺标题", e.name.isNotBlank())
            assertTrue("${e.key} 缺副标题", e.subtitle.isNotBlank())
            assertTrue("${e.key} 缺 loading", e.loading.isNotBlank())
            assertTrue("${e.key} 缺按钮文案", e.button.isNotBlank())
            assertTrue("${e.key} 缺选题基调", e.brief.isNotBlank())
            assertTrue("${e.key} 输出下界非法", e.pageMin >= 1)
            assertTrue("${e.key} 输出上界超限", e.pageMax <= 12)
            assertTrue("${e.key} 边界倒挂", e.pageMin <= e.pageMax)
            when (e.pageStyle) {
                SidestoryCatalog.PageStyle.THEATER -> assertTrue(e.sections.isEmpty())
                SidestoryCatalog.PageStyle.SECTIONS -> {
                    assertEquals("${e.key} 清单形态边界必须等于分区数", e.sections.size, e.pageMin)
                    assertEquals(e.sections.size, e.pageMax)
                }
            }
        }
    }

    @Test
    fun `heartbeat events define fixed sections and status reuse`() {
        val checkphone = SidestoryCatalog.byKey("checkphone")!!
        assertEquals(11, checkphone.sections.size)
        assertTrue(checkphone.sections.contains("支付记录"))
        assertTrue(checkphone.sections.contains("备忘录"))
        val peek = SidestoryCatalog.byKey("peekintoheart")!!
        assertEquals(listOf("内心独白", "心情", "状态", "位置"), peek.sections)
        assertTrue(peek.usesCharacterStatus)
        val jealous = SidestoryCatalog.byKey("jealous")!!
        assertEquals(SidestoryCatalog.PageStyle.THEATER, jealous.pageStyle)
        assertFalse(jealous.usesCharacterStatus)
    }

    @Test
    fun `byKey resolves both catalogs and returns null for unknown`() {
        assertTrue(SidestoryCatalog.byKey("his_collection") != null)
        assertTrue(SidestoryCatalog.byKey("checkphone") != null)
        assertNull(SidestoryCatalog.byKey("no_such_template"))
        assertNull(SidestoryCatalog.byKey(""))
    }

    @Test
    fun `copy never mentions billing words`() {
        val forbidden = listOf("猫粮", "扣费", "计费", "会员")
        val copies = (SidestoryCatalog.entries + SidestoryCatalog.heartbeatEvents).flatMap { e ->
            listOf(e.name, e.subtitle, e.loading, e.button, e.brief) + e.sections
        }
        assertTrue("目录文案不应为空", copies.size > 100)
        for (text in copies) {
            for (word in forbidden) {
                assertFalse("红线字样「$word」出现于「${text.take(24)}」", text.contains(word))
            }
        }
    }

    @Test
    fun `prompts never mention billing words`() {
        val forbidden = listOf("猫粮", "扣费", "计费", "会员")
        val character = com.neethu.gouxiang.data.CharacterEntity(
            id = 1, name = "苏馨玥", profile = "设定", intro = "", exampleLines = "",
            openingLine = "", appearance = "", appearanceStyle = "", createdAt = 1,
        )
        for (e in SidestoryCatalog.entries + SidestoryCatalog.heartbeatEvents) {
            val system = SidestoryPrompts.systemPrompt(character, e, memoriesBlock = null)
            val user = SidestoryPrompts.userPrompt(character, e, emptyList(), status = null)
            for (text in listOf(system, user)) {
                for (word in forbidden) {
                    assertFalse("prompt 红线字样「$word」出现于 ${e.key}", text.contains(word))
                }
            }
        }
    }

    @Test
    fun `status event prompt embeds E status snapshot when present`() {
        val peek = SidestoryCatalog.byKey("peekintoheart")!!
        val character = com.neethu.gouxiang.data.CharacterEntity(
            id = 1, name = "苏馨玥", profile = "设定", intro = "", exampleLines = "",
            openingLine = "", appearance = "", appearanceStyle = "", createdAt = 1,
        )
        val status = com.neethu.gouxiang.data.CharacterStatusEntity(
            characterId = 1, conversationId = 2, emoji = "☁️",
            mood = "有点低落", doing = "在整理相册", thought = "想给他看",
            sourceKind = "turn", generatedAt = 1000,
        )
        val withStatus = SidestoryPrompts.userPrompt(character, peek, emptyList(), status)
        assertTrue(withStatus.contains("有点低落"))
        assertTrue(withStatus.contains("在整理相册"))
        val without = SidestoryPrompts.userPrompt(character, peek, emptyList(), status = null)
        assertTrue(without.contains("暂无状态记录"))
        // 非状态事件不注入状态段(与 09 专项「常驻状态一行 vs 深度查看」的边界一致)
        val drunk = SidestoryCatalog.byKey("Drunk")!!
        assertFalse(SidestoryPrompts.userPrompt(character, drunk, emptyList(), status).contains("TA当前状态"))
    }

    @Test
    fun `sections prompt declares exact partition contract`() {
        val checkphone = SidestoryCatalog.byKey("checkphone")!!
        val character = com.neethu.gouxiang.data.CharacterEntity(
            id = 1, name = "苏馨玥", profile = "设定", intro = "", exampleLines = "",
            openingLine = "", appearance = "", appearanceStyle = "", createdAt = 1,
        )
        val system = SidestoryPrompts.systemPrompt(character, checkphone, null)
        assertTrue(system.contains("恰好 11 格"))
        assertTrue(system.contains("浏览器"))
        assertTrue(system.contains("导航行程"))
        val drunk = SidestoryCatalog.byKey("Drunk")!!
        assertTrue(SidestoryPrompts.systemPrompt(character, drunk, null).contains("3~6 格"))
    }
}
