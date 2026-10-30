package com.neethu.gouxiang.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D 工作包 D2:主动消息频控与回归策略(纯函数门组)单测。
 * 门组顺序、安静时段跨午夜、日上限按本地日重置、输入避让、未读积压硬顶、
 * 扫描轮转分页全覆盖;数值全部为本地可配置默认(非任何竞品参数)。
 */
class ProactivePolicyTest {

    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    private fun config(
        enabled: Boolean = true,
        minIntervalMs: Long = 2 * hour,
        dailyCap: Int = 6,
        quietStart: Int = 23,
        quietEnd: Int = 8,
        graceMs: Long = 5 * 60_000L,
    ) = ProactivePolicy.Config(
        enabled = enabled,
        minIntervalMs = minIntervalMs,
        dailyCap = dailyCap,
        quietStartHour = quietStart,
        quietEndHour = quietEnd,
        userActiveGraceMs = graceMs,
    )

    private fun snapshot(
        everProactive: Boolean = true,
        lastUserAt: Long? = now - 24 * hour,
        lastProactiveAt: Long? = null,
        dailyCount: Int = 0,
        dailyStamp: String = "",
        unseen: Int = 0,
    ) = ProactivePolicy.Snapshot(
        conversationId = 1,
        everProactive = everProactive,
        lastUserActivityAt = lastUserAt,
        lastProactiveAt = lastProactiveAt,
        dailyCount = dailyCount,
        dailyStamp = dailyStamp,
        unseenCount = unseen,
    )

    // ── 场景判定(委托 pickTrigger)────────────────────────────────

    @Test
    fun `first hello fires for conversation without proactive history`() {
        val d = ProactivePolicy.evaluate(now, config(), snapshot(everProactive = false, lastUserAt = now - hour), "2026-10-31", 14)
        assertTrue("应放行,实际 $d", d is ProactivePolicy.Decision.Fire)
        assertEquals(ProactiveMessages.Kind.FIRST_HELLO, (d as ProactivePolicy.Decision.Fire).kind)
    }

    @Test
    fun `offline regression picks hot or cold kind`() {
        val hot = ProactivePolicy.evaluate(now, config(), snapshot(lastUserAt = now - 3 * hour), "2026-10-31", 14)
        assertEquals(ProactiveMessages.Kind.DAILY_SHARE, (hot as ProactivePolicy.Decision.Fire).kind)
        val cold = ProactivePolicy.evaluate(now, config(), snapshot(lastUserAt = now - 13 * hour), "2026-10-31", 14)
        assertEquals(ProactiveMessages.Kind.CHECK_IN, (cold as ProactivePolicy.Decision.Fire).kind)
    }

    @Test
    fun `fresh conversation without threshold pass blocks`() {
        val d = ProactivePolicy.evaluate(now, config(), snapshot(lastUserAt = now - 30 * 60_000), "2026-10-31", 14)
        assertTrue(d is ProactivePolicy.Decision.Block)
        assertTrue((d as ProactivePolicy.Decision.Block).reason.contains("离线阈值"))
    }

    // ── 门组:未读积压 / 安静时段 / 输入避让 / 日上限 / 最小间隔 ──────

    @Test
    fun `unseen backlog blocks new batches`() {
        val d = ProactivePolicy.evaluate(now, config(), snapshot(unseen = 2), "2026-10-31", 14)
        assertTrue((d as ProactivePolicy.Decision.Block).reason.contains("未读"))
    }

    @Test
    fun `switch off blocks everything`() {
        val d = ProactivePolicy.evaluate(now, config(enabled = false), snapshot(), "2026-10-31", 14)
        assertEquals("自动开关已关闭", (d as ProactivePolicy.Decision.Block).reason)
    }

    @Test
    fun `quiet hours block inside window and allow outside`() {
        // 安静 23~8:23 点与凌晨 3 点拦,8 点与 22 点放行
        for (h in intArrayOf(23, 0, 3, 7)) {
            val d = ProactivePolicy.evaluate(now, config(), snapshot(), "2026-10-31", h)
            assertTrue("h=$h 应拦", d is ProactivePolicy.Decision.Block)
            assertTrue((d as ProactivePolicy.Decision.Block).reason.contains("安静"))
        }
        for (h in intArrayOf(8, 12, 22)) {
            val d = ProactivePolicy.evaluate(now, config(), snapshot(), "2026-10-31", h)
            assertTrue("h=$h 应放行,实际 $d", d is ProactivePolicy.Decision.Fire)
        }
    }

    @Test
    fun `quiet window crossing midnight boundary`() {
        assertEquals(false, ProactivePolicy.isQuietHour(22, config()))
        assertEquals(true, ProactivePolicy.isQuietHour(23, config()))
        assertEquals(true, ProactivePolicy.isQuietHour(7, config()))
        assertEquals(false, ProactivePolicy.isQuietHour(8, config()))
        // 起==终 = 无安静时段
        assertEquals(false, ProactivePolicy.isQuietHour(3, config(quietStart = 8, quietEnd = 8)))
    }

    @Test
    fun `user just spoke blocks within grace window`() {
        val d = ProactivePolicy.evaluate(
            now, config(), snapshot(everProactive = false, lastUserAt = now - 60_000), "2026-10-31", 14,
        )
        assertTrue((d as ProactivePolicy.Decision.Block).reason.contains("避让"))
        // 超过避让窗口 → 首开场景放行
        val ok = ProactivePolicy.evaluate(
            now, config(), snapshot(everProactive = false, lastUserAt = now - 10 * 60_000), "2026-10-31", 14,
        )
        assertTrue(ok is ProactivePolicy.Decision.Fire)
    }

    @Test
    fun `daily cap blocks and resets next local day`() {
        val cfg = config(dailyCap = 2)
        val blocked = ProactivePolicy.evaluate(
            now, cfg, snapshot(dailyCount = 2, dailyStamp = "2026-10-31"), "2026-10-31", 14,
        )
        assertTrue((blocked as ProactivePolicy.Decision.Block).reason.contains("上限"))
        // 次日(或旧 stamp 缺失)自动重置为 0
        val nextDay = ProactivePolicy.evaluate(
            now, cfg, snapshot(dailyCount = 2, dailyStamp = "2026-10-30"), "2026-10-31", 14,
        )
        assertTrue(nextDay is ProactivePolicy.Decision.Fire)
        val fresh = ProactivePolicy.evaluate(
            now, cfg, snapshot(dailyCount = 2, dailyStamp = ""), "2026-10-31", 14,
        )
        assertTrue(fresh is ProactivePolicy.Decision.Fire)
    }

    @Test
    fun `min interval blocks recent batch and allows after it passes`() {
        val cfg = config(minIntervalMs = 2 * hour)
        val recent = ProactivePolicy.evaluate(
            now, cfg, snapshot(lastProactiveAt = now - hour), "2026-10-31", 14,
        )
        assertTrue((recent as ProactivePolicy.Decision.Block).reason.contains("最小间隔"))
        val later = ProactivePolicy.evaluate(
            now, cfg, snapshot(lastProactiveAt = now - 3 * hour), "2026-10-31", 14,
        )
        assertTrue(later is ProactivePolicy.Decision.Fire)
    }

    @Test
    fun `gate order puts backlog and quiet before interval`() {
        // 未读 + 安静时段同时命中:报未读(顺序 1)
        val d = ProactivePolicy.evaluate(now, config(), snapshot(unseen = 1), "2026-10-31", 3)
        assertTrue((d as ProactivePolicy.Decision.Block).reason.contains("未读"))
    }

    // ── 扫描轮转(处理扫描上限,防多会话漏扫)───────────────────────

    @Test
    fun `scan rotation stays at zero when total fits one page`() {
        assertEquals(0, ProactivePolicy.nextScanOffset(0, 50, 30))
        assertEquals(0, ProactivePolicy.nextScanOffset(5, 50, 50))
        assertEquals(0, ProactivePolicy.nextScanOffset(7, 50, 0))
    }

    @Test
    fun `scan rotation cycles all pages so no conversation is starved`() {
        val limit = 10
        val total = 35 // 4 页:0/10/20/30
        val offsets = (0 until 8).map { ProactivePolicy.nextScanOffset(it, limit, total) }
        assertEquals(listOf(0, 10, 20, 30, 0, 10, 20, 30), offsets)
        // 任意连续 4 轮覆盖全部页 → 全部会话在 4 轮内被扫到
        val cover = offsets.take(4).toSet()
        assertEquals(setOf(0, 10, 20, 30), cover)
    }
}
