package com.neethu.gouxiang.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 头像生图 prompt 组装单测:appearance + 风格词缀(ImageGenAdapter 契约,
 * 由调用方拼好);修复前 ensureAvatars 只喂 appearance、风格词缀缺失。
 */
class AvatarPromptTest {

    @Test
    fun `style appended after full-width separator`() {
        // 有风格词缀:appearance + 「。」+ 风格,与心声/朋友圈配图拼接一致
        assertEquals(
            "短发少女,黑色卫衣。写实摄影风",
            buildAvatarPrompt("短发少女,黑色卫衣", "写实摄影风"),
        )
    }

    @Test
    fun `empty style keeps appearance without trailing separator`() {
        // 风格词缀为空串:原样返回,不挂孤立的「。」尾分隔符
        assertEquals("短发少女,黑色卫衣", buildAvatarPrompt("短发少女,黑色卫衣", ""))
    }

    @Test
    fun `blank style treated as empty`() {
        // 纯空白词缀同样不拼(避免「。」后跟一串空白污染 prompt)
        assertEquals("短发少女,黑色卫衣", buildAvatarPrompt("短发少女,黑色卫衣", "   "))
    }
}
