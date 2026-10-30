package com.neethu.gouxiang.ui

import com.neethu.gouxiang.ui.character.CharacterDraft
import com.neethu.gouxiang.ui.character.CharacterFormCopy
import com.neethu.gouxiang.ui.character.CharacterFormLimits
import com.neethu.gouxiang.ui.character.CharacterFormValidator
import com.neethu.gouxiang.ui.character.avatarCacheKey
import com.neethu.gouxiang.ui.character.splitOpeningLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C 工作包 C1:角色表单校验器 + 开场白动作解析 + 版本化头像缓存键(纯 JVM)。
 */
class CharacterFormValidatorTest {

    private fun draft(
        name: String = "小满",
        profile: String = "22岁,爱画画,称呼对话者为「用户」",
        opening: String = "(挥手)你好呀",
        appearance: String = "年轻女性,黑长直",
    ) = CharacterDraft(
        name = name,
        profile = profile,
        openingLine = opening,
        intro = "初遇简介",
        exampleLines = "(笑)今天也要加油呀",
        appearance = appearance,
        appearanceStyle = "写实摄影风",
    )

    @Test
    fun requiredFieldsProduceExplicitErrors() {
        val errors = CharacterFormValidator.validate(CharacterDraft())
        assertEquals(CharacterFormCopy.ERR_NAME_REQUIRED, errors.name)
        assertEquals(CharacterFormCopy.ERR_PROFILE_REQUIRED, errors.profile)
        assertEquals(CharacterFormCopy.ERR_OPENING_REQUIRED, errors.openingLine)
        assertTrue(errors.hasErrors)
    }

    @Test
    fun blankOnlyAndEdgeWhitespaceAreFlaggedSeparately() {
        val blanks = CharacterFormValidator.validate(draft(name = "   ", profile = " \t "))
        assertEquals(CharacterFormCopy.ERR_ONLY_BLANKS, blanks.name)
        assertEquals(CharacterFormCopy.ERR_ONLY_BLANKS, blanks.profile)
        val edges = CharacterFormValidator.validate(draft(name = " 小满 "))
        assertEquals(CharacterFormCopy.ERR_EDGE_BLANKS, edges.name)
    }

    @Test
    fun lengthLimitsPerField() {
        val over = CharacterFormValidator.validate(
            draft(
                name = "名".repeat(CharacterFormLimits.NAME_MAX + 1),
                profile = "设".repeat(CharacterFormLimits.PROFILE_MAX + 1),
                opening = "开".repeat(CharacterFormLimits.OPENING_MAX + 1),
                appearance = "形".repeat(CharacterFormLimits.APPEARANCE_MAX + 1),
            ),
        )
        assertTrue(over.name!!.contains("角色名"))
        assertTrue(over.profile!!.contains("超出字数上限"))
        assertTrue(over.openingLine!!.contains("超出字数上限"))
        assertTrue(over.appearance!!.contains("超出字数上限"))
        // 边界值恰好等于上限:不报错
        val atLimit = CharacterFormValidator.validate(
            draft(
                name = "名".repeat(CharacterFormLimits.NAME_MAX),
                profile = "设".repeat(CharacterFormLimits.PROFILE_MAX),
                opening = "开".repeat(CharacterFormLimits.OPENING_MAX),
            ),
        )
        assertNull(atLimit.name)
        assertNull(atLimit.profile)
        assertNull(atLimit.openingLine)
    }

    @Test
    fun duplicateNameFlaggedButEditingSelfIsNot() {
        val errors = CharacterFormValidator.validate(draft(name = "小满"), nameOwnerId = 7L)
        assertEquals(CharacterFormCopy.ERR_NAME_TAKEN, errors.name)
        // 编辑自身(id 相同)不算重名
        val editingSelf = CharacterFormValidator.validate(draft(name = "小满"), nameOwnerId = 7L)
            .let { CharacterDraft(id = 7L, name = "小满", profile = "p", openingLine = "o") }
            .let { CharacterFormValidator.validate(it, nameOwnerId = 7L) }
        assertNull(editingSelf.name)
        // 无占用
        assertNull(CharacterFormValidator.validate(draft(name = "小满"), nameOwnerId = null).name)
    }

    @Test
    fun validDraftHasNoErrors() {
        val errors = CharacterFormValidator.validate(draft())
        assertFalse(errors.hasErrors)
    }

    @Test
    fun splitOpeningLineExtractsHalfAndFullWidthActions() {
        val (content, actions) = splitOpeningLine("(靠在你肩头)那房子真的好喜欢,(轻声)什么时候搬呀?")
        assertEquals("那房子真的好喜欢,什么时候搬呀?", content)
        assertEquals(listOf("靠在你肩头", "轻声"), actions)
    }

    @Test
    fun splitOpeningLineWithoutActionsKeepsRawContent() {
        val (content, actions) = splitOpeningLine("今天也要加油呀")
        assertEquals("今天也要加油呀", content)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun splitOpeningLineOfPureActionsFallsBackInCaller() {
        // 纯动作开场白:剥完为空串;落库侧由 saveCharacter 的 ifBlank 兜底回原文
        val (content, actions) = splitOpeningLine("(挥了挥手)")
        assertEquals("", content)
        assertEquals(listOf("挥了挥手"), actions)
    }

    @Test
    fun avatarCacheKeyIsVersioned() {
        // v0 = 既有磁盘缓存命名(兼容种子角色的旧缓存),v1+ 带版本后缀
        assertEquals("character-9", avatarCacheKey(9L, 0L))
        assertEquals("character-9-v1", avatarCacheKey(9L, 1L))
        assertEquals("character-9-v2", avatarCacheKey(9L, 2L))
        // 改写形象 → 版本 +1 → 键变化 → 生成新文件,不复用旧头像
        assertTrue(avatarCacheKey(9L, 1L) != avatarCacheKey(9L, 2L))
    }
}
