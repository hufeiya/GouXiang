package com.neethu.gouxiang.ui

import com.neethu.gouxiang.data.MessageEntity
import com.neethu.gouxiang.ui.phone.rewindSnippet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天页气泡分组单测:一轮回复(同 turnId)聚为一组连发泡。
 */
class BubbleGroupingTest {

    private fun msg(
        role: String,
        turnId: String,
        content: String,
        kind: String = "text",
        actionsJson: String = "[]",
    ) = MessageEntity(
        conversationId = 1, turnId = turnId, role = role, kind = kind,
        content = content, actionsJson = actionsJson, at = 1,
    )

    @Test
    fun `assistant same-turn rows merge into one group`() {
        val items = toBubbleItems(
            listOf(
                msg("assistant", "t1", "老公,"),
                msg("assistant", "t1", "今天好想你呀", actionsJson = """["靠在你肩头"]"""),
                msg("assistant", "t2", "新的话题"),
            ),
        )
        assertEquals(2, items.size)
        assertEquals(2, items[0].bubbles.size)
        assertEquals(1, items[1].bubbles.size)
        assertFalse(items[0].fromUser)
        // 组尾标记只在最后一泡
        assertFalse(items[0].bubbles[0].lastInGroup)
        assertTrue(items[0].bubbles[1].lastInGroup)
        assertEquals(listOf("靠在你肩头"), items[0].bubbles[1].actions)
    }

    @Test
    fun `user message is its own group marked fromUser`() {
        val items = toBubbleItems(
            listOf(
                msg("user", "u1", "在吗"),
                msg("assistant", "a1", "在的"),
            ),
        )
        assertEquals(2, items.size)
        assertTrue(items[0].fromUser)
        assertFalse(items[1].fromUser)
    }

    @Test
    fun `blank rows are skipped and group keeps others`() {
        val items = toBubbleItems(
            listOf(
                msg("assistant", "t1", "  "),
                msg("assistant", "t1", "有效内容"),
            ),
        )
        assertEquals(1, items.size)
        assertEquals(1, items[0].bubbles.size)
        assertEquals("有效内容", items[0].bubbles[0].content)
    }

    // ── 回溯弹窗点名目标消息(真机回归:长按误触相邻泡需肉眼可辨)────

    @Test
    fun `rewind snippet flattens whitespace and truncates at 12 chars`() {
        // 全文不超 12 字:原样保留
        assertEquals("在吗", rewindSnippet(" 在吗 "))
        // 长文截断加省略号
        assertEquals(
            "(举着手里的奶泡)刚才买…",
            rewindSnippet("(举着手里的奶泡)刚才买的抹茶拿铁奶泡超绵密"),
        )
        // 换行/连续空白压平为单空格(多段消息不做多行弹窗)
        assertEquals("第一段 第二段", rewindSnippet("第一段\n\n第二段"))
    }

    // ── 回合失败显式化(02 文档 §3.5):failedTurn 状态迁移 ───────────

    @Test
    fun `failed turn recorded then cleared after retry succeeds`() {
        // 失败回合 → 记 failedTurn(重试条依据,携带原用户文本与会话)
        var state = ChatUiState(conversationId = 7L)
            .afterTurnResult(com.neethu.gouxiang.chat.TurnResult.Failed("在吗"))
        assertEquals(FailedTurn(conversationId = 7L, userText = "在吗"), state.failedTurn)
        // 重试成功(第二次假 LLM 正常出泡,编排器层见 ChatOrchestratorTest)
        // → failedTurn 清空,失败条撤除
        state = state.afterTurnResult(com.neethu.gouxiang.chat.TurnResult.Completed(emptyList()))
        assertNull("重试成功应清空失败态", state.failedTurn)
    }

    @Test
    fun `voice round failure shows retry bar only for same conversation`() {
        // 语音通话回合失败(02 文档 §3.5 显式化不留盲区):聊天页停在同一会话
        // → 记 failedTurn,挂断回页即见重试条(重试按原 ASR 文本,消息已在库)
        val state = ChatUiState(conversationId = 9L)
            .applyVoiceRoundResult(9L, com.neethu.gouxiang.chat.TurnResult.Failed("想你了"))
        assertEquals(FailedTurn(conversationId = 9L, userText = "想你了"), state.failedTurn)
        // 聊天页已关闭/切走(会话不同):不污染当前页状态
        val other = ChatUiState(conversationId = 1L)
            .applyVoiceRoundResult(9L, com.neethu.gouxiang.chat.TurnResult.Failed("想你了"))
        assertNull("不同会话不应置失败条", other.failedTurn)
        // 同会话语音回合成功:清旧失败条(与发送路径同一迁移语义)
        val cleared = state
            .applyVoiceRoundResult(9L, com.neethu.gouxiang.chat.TurnResult.Completed(emptyList()))
        assertNull("语音回合成功应清旧失败条", cleared.failedTurn)
    }
}
