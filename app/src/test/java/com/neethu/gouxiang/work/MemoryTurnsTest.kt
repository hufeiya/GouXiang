package com.neethu.gouxiang.work

import com.neethu.gouxiang.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DerivativeExecutor.toMemoryTurns] 纯函数单测(从 MainViewModel 迁出的
 * 消息→提炼窗口回合规则;零 Android 依赖)。
 */
class MemoryTurnsTest {

    private fun msg(
        role: String,
        content: String,
        turnId: String = "t1",
        kind: String = "text",
    ) = MessageEntity(
        conversationId = 1,
        turnId = turnId,
        role = role,
        kind = kind,
        content = content,
        at = 0L,
    )

    @Test
    fun `user and assistant bubble groups collapse into turns`() {
        val turns = DerivativeExecutor.toMemoryTurns(
            listOf(
                msg("user", "今天好累"),
                msg("assistant", "(递来一杯热茶)", turnId = "a1"),
                msg("assistant", "歇会儿吧", turnId = "a1"),
                msg("user", "谢谢你"),
                msg("assistant", "不客气呀", turnId = "a2"),
            ),
        )
        assertEquals(2, turns.size)
        assertEquals("今天好累", turns[0].user)
        assertEquals("(递来一杯热茶)\n歇会儿吧", turns[0].assistant)
        assertEquals("谢谢你", turns[1].user)
        assertEquals("不客气呀", turns[1].assistant)
    }

    @Test
    fun `leading assistant message is dropped until a user appears`() {
        val turns = DerivativeExecutor.toMemoryTurns(
            listOf(
                msg("assistant", "(主动发来)早上好", turnId = "p1"),
                msg("user", "早"),
                msg("assistant", "昨晚睡得好吗", turnId = "a1"),
            ),
        )
        assertEquals(1, turns.size)
        assertEquals("早", turns[0].user)
    }

    @Test
    fun `blank user lines do not open a turn`() {
        val turns = DerivativeExecutor.toMemoryTurns(
            listOf(
                msg("user", "   "),
                msg("user", "在吗"),
                msg("assistant", "在的", turnId = "a1"),
            ),
        )
        assertEquals(1, turns.size)
        assertEquals("在吗", turns[0].user)
        assertTrue(turns[0].assistant == "在的")
    }
}
