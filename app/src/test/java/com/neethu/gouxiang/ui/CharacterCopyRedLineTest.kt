package com.neethu.gouxiang.ui

import com.neethu.gouxiang.ui.character.CharacterFormCopy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C 工作包文案红线:角色表单/创作页/图库的全部用户可见文案,
 * 严禁出现「猫粮/扣费/计费/会员」字样(与 DerivativeTaskTest 的
 * 通知/设置页红线用例同一口径,覆盖面扩到本工作包新增文案)。
 */
class CharacterCopyRedLineTest {

    private val forbidden = listOf("猫粮", "扣费", "计费", "会员")

    @Test
    fun formCopyCarriesNoBillingWords() {
        val strings = CharacterFormCopy::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { it.isAccessible = true; it.get(null) as String } +
            CharacterFormCopy.STYLE_PRESETS +
            listOf(
                CharacterFormCopy.errTooLong(null, 10),
                CharacterFormCopy.errTooLong("角色名", 24),
            )
        assertTrue("文案清单不应为空", strings.size > 20)
        for (text in strings) {
            for (word in forbidden) {
                assertFalse("红线字样「$word」出现于:$text", text.contains(word))
            }
        }
    }
}
