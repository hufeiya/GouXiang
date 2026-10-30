package com.neethu.gouxiang.ui

import com.neethu.gouxiang.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D 工作包 D4:离线回归分隔(进页消费的主动批次 → 气泡组上的分隔锚点)。
 * 纯函数锁定:分隔只挂批次 turnId 对应的组,文案带实际条数;已读/回溯后的
 * 批次不再产生分隔。
 */
class OfflineSeparatorTest {

    private fun row(id: Long, turnId: String, role: String, at: Long) = MessageEntity(
        id = id, conversationId = 1, turnId = turnId, role = role,
        kind = "text", content = "内容$id", at = at,
    )

    @Test
    fun separatorsAttachOnlyToConsumedBatchTurnIds() {
        val messages = listOf(
            row(1, "turn-user-1", "user", 1),
            row(2, "proactive-b1", "assistant", 2),
            row(3, "proactive-b1", "assistant", 3),
            row(4, "proactive-b2", "assistant", 4),
        )
        val separators = mapOf(
            "proactive-b1" to "离线期间发来了 2 条消息",
            "proactive-b2" to "离线期间发来了 1 条消息",
        )
        val items = toBubbleItems(messages, separators)
        val byTurn = items.associateBy { it.turnId }
        assertEquals("离线期间发来了 2 条消息", byTurn["proactive-b1"]?.separator)
        assertEquals("离线期间发来了 1 条消息", byTurn["proactive-b2"]?.separator)
        assertNull("用户组不挂分隔", byTurn["turn-user-1"]?.separator)
        // 组内气泡顺序不变(有序回放,数据层 at/id 单调)
        assertEquals(listOf("内容2", "内容3"), byTurn["proactive-b1"]?.bubbles?.map { it.content })
    }

    @Test
    fun staleTurnIdsProduceNoSeparator() {
        // 回溯后批次已消失:残留的分隔映射不再命中任何组(无幽灵分隔)
        val messages = listOf(row(1, "turn-user-1", "user", 1))
        val items = toBubbleItems(messages, mapOf("proactive-gone" to "离线期间发来了 3 条消息"))
        assertTrue(items.none { it.separator != null })
    }

    @Test
    fun mainViewModelSeparatorCopyCarriesActualCount() {
        val consumed = listOf(
            com.neethu.gouxiang.proactive.ProactiveOutboxService.ConsumedBatch("b1", "proactive-a", 3, degraded = false),
            com.neethu.gouxiang.proactive.ProactiveOutboxService.ConsumedBatch("b2", "", 2, degraded = true),
        )
        val map = offlineSeparators(consumed)
        assertEquals(mapOf("proactive-a" to "离线期间发来了 3 条消息"), map)
    }

    @Test
    fun separatorCopyCarriesNoBillingWords() {
        // D 工作包文案红线:分隔文案与频控原因严禁敏感商业字样(同 DerivativeTaskTest 口径)
        val forbidden = listOf("猫粮", "扣费", "计费", "会员", "过期")
        val copies = listOf(
            offlineSeparators(
                listOf(
                    com.neethu.gouxiang.proactive.ProactiveOutboxService.ConsumedBatch("b", "t", 5, degraded = false),
                ),
            ).values.single(),
            "自动开关已关闭", "有 2 条未读主动消息未消费", "安静时段(23~8 点)", "用户刚在输入,避让",
            "今日批次已达上限 6", "距上次主动消息不足最小间隔", "距上次对话未达离线阈值",
        )
        for (text in copies) {
            for (word in forbidden) {
                assertTrue("红线字样「$word」不应出现于:$text", !text.contains(word))
            }
        }
    }
}
