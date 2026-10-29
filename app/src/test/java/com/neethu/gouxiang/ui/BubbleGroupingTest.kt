package com.neethu.gouxiang.ui

import com.neethu.gouxiang.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
