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

    // ── 分段拼合「话一(动作)话二」(2026-10-31 用户口径:按住中原位/侧移
    //    互切分段录音,原位段原文+侧移段括号,中间漏括号即此缺失)─────────

    @Test fun `三段拼合中间动作段带括号`() {
        val text = MainViewModel.buildSegmentedText(
            listOf(false to "你好呀", true to "拍拍手", false to "最近怎么样"),
        )
        assertEquals("你好呀(拍拍手)最近怎么样", text)
    }

    @Test fun `空白段跳过不占位`() {
        val text = MainViewModel.buildSegmentedText(
            listOf(false to "", true to " 点头 ", false to "  "),
        )
        assertEquals("(点头)", text)
    }

    @Test fun `全空段返回空串`() {
        assertEquals("", MainViewModel.buildSegmentedText(listOf(false to "", true to " ")))
        assertEquals("", MainViewModel.buildSegmentedText(emptyList()))
    }

    @Test fun `单段原位与旧版直发行为一致`() {
        assertEquals("就一句话", MainViewModel.buildSegmentedText(listOf(false to "就一句话")))
    }

    @Test fun `单段侧移整段带括号与旧版一致`() {
        assertEquals("(动作描述)", MainViewModel.buildSegmentedText(listOf(true to "动作描述")))
    }

    @Test fun `相邻动作段各自带括号相连`() {
        val text = MainViewModel.buildSegmentedText(
            listOf(true to "拍拍手", true to "点头"),
        )
        assertEquals("(拍拍手)(点头)", text)
    }

    @Test fun `动作段夹正文段首尾均正确`() {
        val text = MainViewModel.buildSegmentedText(
            listOf(true to "歪头", false to "你说什么"),
        )
        assertEquals("(歪头)你说什么", text)
    }
}
