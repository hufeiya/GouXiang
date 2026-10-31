package com.neethu.gouxiang.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 侧移动作场景括号包裹(2026-10-31 用户口径:侧移转写落输入框的文字必须用
 * 小括号括起来,动作=括号文本;已带括号不重复包,防「((..))」叠加)。
 */
class VoiceActionBracketTest {

    @Test fun `普通文本补半角括号`() {
        assertEquals("(拍拍手)", MainViewModel.wrapActionBracket("拍拍手"))
    }

    @Test fun `首尾空白先裁再包`() {
        assertEquals("(嗯。)", MainViewModel.wrapActionBracket(" 嗯。 "))
    }

    @Test fun `已有半角括号不重复包`() {
        assertEquals("(笑)", MainViewModel.wrapActionBracket("(笑)"))
    }

    @Test fun `已有全角括号不重复包`() {
        assertEquals("（歪头）", MainViewModel.wrapActionBracket("（歪头）"))
    }

    @Test fun `全半角混搭按已包裹处理(宽松,同协议 actionRegex 口径)`() {
        assertEquals("(歪头）", MainViewModel.wrapActionBracket("(歪头）"))
        assertEquals("（歪头)", MainViewModel.wrapActionBracket("（歪头)"))
    }

    @Test fun `括号动作夹正文的混合文本整体重包(保证整串是括号文本)`() {
        // ASR 偶发自带内嵌动作描述:整体不是「首开尾闭」就补外包,内嵌括号原样保留
        assertEquals("((笑)拍拍手)", MainViewModel.wrapActionBracket("(笑)拍拍手"))
    }

    @Test fun `单字符与空串也补括号`() {
        assertEquals("(嗯)", MainViewModel.wrapActionBracket("嗯"))
        assertEquals("()", MainViewModel.wrapActionBracket(""))
    }

    @Test fun `括号不配对视为普通文本重包`() {
        assertEquals("((笑)", MainViewModel.wrapActionBracket("(笑"))
        assertEquals("(笑)()", MainViewModel.wrapActionBracket("笑)("))
    }
}
