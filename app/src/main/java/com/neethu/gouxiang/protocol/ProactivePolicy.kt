package com.neethu.gouxiang.protocol

/**
 * 主动消息频控与回归策略(D 工作包 D2,纯函数,JVM 单测锁死):
 *
 * 门组顺序 = 判定优先级(原因串进调试观测,顺序变化是行为变化):
 * 1. 未读积压 —— 有未消费批次不再堆新批次(「不重复轰炸」的硬顶);
 * 2. 安静时段 —— 本地时段 [quietStartHour, quietEndHour) 不发(夜间打扰);
 * 3. 在线输入避让 —— 用户 [userActiveGraceMs] 内刚发过言不插话;
 * 4. 每日上限 —— 今日已完成批次数达 [dailyCap] 即止(次日自动重置);
 * 5. 最小间隔 —— 距上次批次完成不足 [minIntervalMs] 即止;
 * 6. 场景判定 —— 委托 [ProactiveMessages.pickTrigger](首次/冷热分档)。
 *
 * 所有数值为**本地可配置默认**(GenerationPrefs 读取;不是任何竞品的
 * 服务端参数,文案/注释不得宣称)。
 */
object ProactivePolicy {

    /** 门禁与批次上限(默认值 = 本地拍板;经 prefs 可改)。 */
    data class Config(
        /** 自动开关(GenerationPrefs.proactiveOutbox;关 = 一律 Block)。 */
        val enabled: Boolean = true,
        /** 批次间最小间隔(默认 2h)。 */
        val minIntervalMs: Long = 2L * 60 * 60 * 1000,
        /** 每日批次上限(默认 6;按本地日重置)。 */
        val dailyCap: Int = 6,
        /** 安静时段起点(含,默认 23 点)。 */
        val quietStartHour: Int = 23,
        /** 安静时段终点(不含,默认 8 点)。 */
        val quietEndHour: Int = 8,
        /** 单批生成条数上限(回归批次上限,钳制连发协议)。 */
        val batchMax: Int = 3,
        /** 单轮扫描会话数上限(轮转分页的页大小)。 */
        val scanLimit: Int = 50,
        /** 用户在线输入避让窗口(默认 5min)。 */
        val userActiveGraceMs: Long = 5L * 60 * 1000,
        /** 离线阈值(委托 pickTrigger;默认 2h)。 */
        val offlineThresholdMs: Long = ProactiveMessages.DEFAULT_OFFLINE_THRESHOLD_MS,
    )

    /** 频控快照(账本读出;见 ProactiveLedger.Snapshot)。 */
    data class Snapshot(
        val conversationId: Long,
        val everProactive: Boolean,
        /** 用户活跃时间(null=尚未发言)。 */
        val lastUserActivityAt: Long?,
        /** 最近批次完成时刻(null=从未)。 */
        val lastProactiveAt: Long?,
        val dailyCount: Int,
        val dailyStamp: String,
        val unseenCount: Int,
    )

    /** 判定结果。 */
    sealed interface Decision {
        /** 放行:[kind] 场景,[batchMax] 为本批条数上限(回归批次上限)。 */
        data class Fire(val kind: ProactiveMessages.Kind, val batchMax: Int, val reason: String) : Decision

        /** 拦截:[reason] 可读原因(调试观测/测试断言)。 */
        data class Block(val reason: String) : Decision
    }

    /**
     * 频控判定(纯函数)。[localHour] = now 的本地小时(注入以可测);
     * [localDay] = 今天的本地日戳(与快照 dailyStamp 比对做日上限重置)。
     */
    fun evaluate(
        now: Long,
        config: Config,
        snapshot: Snapshot,
        localDay: String,
        localHour: Int,
    ): Decision {
        if (!config.enabled) return Decision.Block("自动开关已关闭")
        if (snapshot.unseenCount > 0) {
            return Decision.Block("有 ${snapshot.unseenCount} 条未读主动消息未消费")
        }
        if (isQuietHour(localHour, config)) {
            return Decision.Block("安静时段(${config.quietStartHour}~${config.quietEndHour} 点)")
        }
        val lastUser = snapshot.lastUserActivityAt
        if (lastUser != null && now - lastUser < config.userActiveGraceMs) {
            return Decision.Block("用户刚在输入,避让")
        }
        val todayCount = if (snapshot.dailyStamp == localDay) snapshot.dailyCount else 0
        if (todayCount >= config.dailyCap) {
            return Decision.Block("今日批次已达上限 ${config.dailyCap}")
        }
        val lastBatch = snapshot.lastProactiveAt
        if (lastBatch != null && now - lastBatch < config.minIntervalMs) {
            return Decision.Block("距上次主动消息不足最小间隔")
        }
        val kind = ProactiveMessages.pickTrigger(
            everProactive = snapshot.everProactive,
            lastActivityAt = snapshot.lastUserActivityAt,
            now = now,
            offlineThresholdMs = config.offlineThresholdMs,
        ) ?: return Decision.Block("距上次对话未达离线阈值")
        return Decision.Fire(
            kind = kind,
            batchMax = config.batchMax.coerceAtLeast(1),
            reason = when (kind) {
                ProactiveMessages.Kind.FIRST_HELLO -> "首次主动开场"
                ProactiveMessages.Kind.CHECK_IN -> "离线过久,关心型"
                ProactiveMessages.Kind.DAILY_SHARE -> "离线回归,日常分享"
            },
        )
    }

    /** 安静时段判定(支持跨午夜区间,如 23~8;起==终视为无安静时段)。 */
    fun isQuietHour(hour: Int, config: Config): Boolean {
        val start = config.quietStartHour
        val end = config.quietEndHour
        if (start == end) return false
        return if (start < end) hour in start until end else hour >= start || hour < end
    }

    /**
     * 扫描轮转偏移(D2「处理扫描上限,避免多会话漏扫」):会话总数 [total]
     * 超过单轮上限 [limit] 时,按 [cursor] 轮转起始页(每轮扫一页,页序循环),
     * 保证全部会话在 ceil(total/limit) 轮内都被扫到;total<=limit 恒回 0。
     */
    fun nextScanOffset(cursor: Int, limit: Int, total: Int): Int {
        if (limit <= 0 || total <= limit) return 0
        val pages = (total + limit - 1) / limit
        return (cursor % pages) * limit
    }
}
