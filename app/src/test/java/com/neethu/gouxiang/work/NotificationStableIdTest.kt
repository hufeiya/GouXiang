package com.neethu.gouxiang.work

import com.neethu.gouxiang.chat.DerivativeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 通知稳定 ID(M0 收尾工作包 3):同角色/会话/类型的后续完成替换同一条通知
 * (中断恢复重跑不重复提醒);不同会话/类型互不覆盖;主动消息每会话一条。
 */
class NotificationStableIdTest {

    @Test
    fun `same kind character conversation keeps one notification id`() {
        val a = NotificationPoster.stableDerivativeId(DerivativeKind.MOMENT, "苏馨玥", 7L)
        val b = NotificationPoster.stableDerivativeId(DerivativeKind.MOMENT, "苏馨玥", 7L)
        assertEquals(a, b)
    }

    @Test
    fun `different conversation kind or character gets its own id`() {
        val base = NotificationPoster.stableDerivativeId(DerivativeKind.MOMENT, "苏馨玥", 7L)
        assertNotEquals(base, NotificationPoster.stableDerivativeId(DerivativeKind.MOMENT, "苏馨玥", 8L))
        assertNotEquals(base, NotificationPoster.stableDerivativeId(DerivativeKind.INNER_VOICE, "苏馨玥", 7L))
        assertNotEquals(base, NotificationPoster.stableDerivativeId(DerivativeKind.MOMENT, "洛瑶", 7L))
    }

    @Test
    fun `proactive id is per conversation`() {
        assertEquals(
            NotificationPoster.stableProactiveId(7L),
            NotificationPoster.stableProactiveId(7L),
        )
        assertNotEquals(
            NotificationPoster.stableProactiveId(7L),
            NotificationPoster.stableProactiveId(8L),
        )
        // 与衍生内容命名空间隔离,不同通道不撞 ID
        assertNotEquals(
            NotificationPoster.stableProactiveId(7L),
            NotificationPoster.stableDerivativeId(DerivativeKind.MOMENT, "proactive", 7L),
        )
    }
}
