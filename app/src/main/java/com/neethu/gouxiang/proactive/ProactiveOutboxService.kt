package com.neethu.gouxiang.proactive

import android.content.Context
import android.util.Log
import com.neethu.gouxiang.ai.GenerationPrefs
import com.neethu.gouxiang.data.GouXiangDatabase
import com.neethu.gouxiang.data.MessageEntity
import com.neethu.gouxiang.data.ProactiveBatchState
import com.neethu.gouxiang.data.ProactiveLedger
import com.neethu.gouxiang.protocol.ProactiveMessages
import com.neethu.gouxiang.protocol.ProactivePolicy
import kotlinx.coroutines.CancellationException
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * 主动消息出站服务(D 工作包 D2/D3/D4 编排核心):
 *
 * 后台周期扫描([com.neethu.gouxiang.work.DerivativeExecutor] 的 Worker 路径)
 * 与前台进页([com.neethu.gouxiang.ui.MainViewModel.openChat])共用同一条链路:
 * **开关自查 → 账本快照 → 频控判定(ProactivePolicy)→ 会话级事务预约 →
 * 事务外生成(LLM)→ 事务收口(状态机 + 日计数 + 未读角标)→ 通知
 * (仅后台来源,幂等门)**。预约唯一性挡住两条入口同时生成同一会话;
 * 失败可重试,不静默记成功。
 *
 * 生成请求不在任何数据库事务内(公共约束红线);LLM 结果经
 * [GenerationResult] 三态区分后按账本状态机落库。
 */
class ProactiveOutboxService(
    private val context: Context,
    private val db: GouXiangDatabase,
    private val ledger: ProactiveLedger,
    /** 生成实现(生产 = ChatOrchestrator 包装;测试注入假实现)。 */
    private val generate: suspend (GenerationRequest) -> GenerationResult,
    /** 通知投递(生产 = NotificationPoster.proactive;测试收集断言)。 */
    private val notify: suspend (Notification) -> Unit,
    /** 自动开关读法(默认 GenerationPrefs;测试注入固定值)。 */
    private val switch: () -> Boolean = { GenerationPrefs.proactiveOutbox(context) },
    private val clock: () -> Long = System::currentTimeMillis,
    /** 本地日戳(默认系统时区;测试注入固定值锁日上限/重置)。 */
    private val localDay: (Long) -> String = ::localDayOf,
    /** 本地小时(安静时段判定;测试注入)。 */
    private val localHour: (Long) -> Int = ::hourOf,
) {

    /** 生成请求(预约通过后发;事务外执行)。 */
    data class GenerationRequest(
        val requestId: String,
        val conversationId: Long,
        val characterId: Long,
        val kind: ProactiveMessages.Kind,
        /** 距用户上次活跃的毫秒数(场景指令用;从未发言为 null)。 */
        val offlineMs: Long?,
        /** 回归批次上限(单批条数硬顶,钳制连发协议)。 */
        val batchMax: Int,
        val phoneStyle: Boolean,
    )

    /** 生成结果三态(D3)。 */
    sealed interface GenerationResult {
        /** 真实生成成功:回合 id + 落库消息行。 */
        data class Ready(val turnId: String, val messages: List<MessageEntity>) : GenerationResult

        /** 有意的模板降级(兜底文本已落库;不算真实成功,不自动重试)。 */
        data class Fallback(val turnId: String, val messages: List<MessageEntity>, val reason: String) : GenerationResult

        /** 生成失败(无消息落库;可重试)。 */
        data class Failed(val reason: String) : GenerationResult
    }

    /** 通知载荷(实际新消息数 + 会话定向跳转)。 */
    data class Notification(val conversationId: Long, val characterName: String, val count: Int)

    /** 单会话处理结果(扫描汇总/调试观测/任务历史)。 */
    sealed interface Report {
        data class Generated(val conversationId: Long, val kind: ProactiveMessages.Kind, val count: Int, val degraded: Boolean) : Report
        data class Blocked(val conversationId: Long, val reason: String) : Report
        data class Failed(val conversationId: Long, val reason: String) : Report
        data class Skipped(val conversationId: Long, val reason: String) : Report
    }

    /** 扫描一轮的汇总(worker 重试语义读 [anyFailed])。 */
    data class ScanReport(val reports: List<Report>) {
        val anyFailed: Boolean get() = reports.any { it is Report.Failed }
        val generated: List<Report.Generated> get() = reports.filterIsInstance<Report.Generated>()
    }

    // ── 后台扫描(D2:轮转分页 + 逐会话同链路)──────────────────────

    /**
     * 扫一轮:开关关 → 空转(无生成,验收 #1 的扫描侧);否则作废超租约的
     * 在途行(进程中断恢复),按轮转偏移取一页会话,逐个走 [autoGenerate]。
     * 轮转游标每轮推进,会话数超过单轮上限时旧会话不长期漏扫。
     */
    suspend fun scanOnce(): ScanReport {
        if (!switch()) return ScanReport(emptyList())
        val config = policyConfig()
        val now = clock()
        ledger.recoverStaleAll(STALE_LEASE_MS, now)
        val total = db.conversationDao().countAll()
        val offset = ProactivePolicy.nextScanOffset(GenerationPrefs.proactiveScanCursor(context), config.scanLimit, total)
        GenerationPrefs.advanceProactiveScanCursor(context)
        val conversations = db.conversationDao().latestPaged(config.scanLimit, offset)
        val reports = mutableListOf<Report>()
        for (conv in conversations) {
            reports += autoGenerate(conv.id, background = true)
        }
        return ScanReport(reports)
    }

    // ── 单会话自动生成(后台扫描与前台进页共用)────────────────────

    /**
     * 评估 + 预约 + 生成 + 收口:[background] = true(周期扫描)时成功后投
     * 通知;false(前台进页)时批次立即标记已读、不投通知(用户正看着会话)。
     * 开关关闭一律跳过(验收 #1 的进页侧)。
     */
    suspend fun autoGenerate(conversationId: Long, background: Boolean): Report {
        if (!switch()) return Report.Skipped(conversationId, "自动开关已关闭")
        val conv = db.conversationDao().byId(conversationId)
            ?: return Report.Skipped(conversationId, "会话不存在")
        val character = db.characterDao().byId(conv.characterId)
            ?: return Report.Skipped(conversationId, "角色不存在")
        val config = policyConfig()
        val now = clock()
        ledger.recoverStaleFor(conversationId, STALE_LEASE_MS, now)
        val snapshot = ledger.snapshot(conversationId, character.id)
        val everProactive = db.messageDao().countProactiveTurnMessages(conversationId) > 0
        val decision = ProactivePolicy.evaluate(
            now = now,
            config = config,
            snapshot = ProactivePolicy.Snapshot(
                conversationId = conversationId,
                everProactive = everProactive,
                lastUserActivityAt = snapshot.lastUserActivityAt,
                lastProactiveAt = snapshot.lastProactiveAt,
                dailyCount = snapshot.dailyCount,
                dailyStamp = snapshot.dailyStamp,
                unseenCount = snapshot.unseenCount,
            ),
            localDay = localDay(now),
            localHour = localHour(now),
        )
        val fire = when (decision) {
            is ProactivePolicy.Decision.Block -> return Report.Blocked(conversationId, decision.reason)
            is ProactivePolicy.Decision.Fire -> decision
        }
        return executeReserved(
            conversationId = conversationId,
            character = character,
            kind = fire.kind,
            batchMax = fire.batchMax,
            background = background,
        )
    }

    /**
     * 预约 → 生成 → 收口(D2/D3 核心)。预约被拒(在途批次已在)= 幂等门
     * 拦截,跳过不重复生成;生成异常统一记 FAILED(可重试),不静默记成功。
     */
    suspend fun executeReserved(
        conversationId: Long,
        character: com.neethu.gouxiang.data.CharacterEntity,
        kind: ProactiveMessages.Kind,
        batchMax: Int,
        background: Boolean,
    ): Report {
        val now = clock()
        val requestId = "proactive-${UUID.randomUUID()}"
        val reserved = ledger.reserve(
            conversationId = conversationId,
            characterId = character.id,
            kind = kind.name,
            requestId = requestId,
            now = now,
            localDay = localDay(now),
            staleLeaseMs = STALE_LEASE_MS,
        ) ?: return Report.Skipped(conversationId, "已有在途批次(预约门禁)")
        ledger.markGenerating(requestId, clock())
        val offlineMs = ledger.snapshot(conversationId, character.id).lastUserActivityAt
            ?.let { clock() - it }
        val request = GenerationRequest(
            requestId = requestId,
            conversationId = conversationId,
            characterId = character.id,
            kind = kind,
            offlineMs = offlineMs,
            batchMax = batchMax,
            phoneStyle = db.conversationDao().byId(conversationId)?.mode == "phone",
        )
        val result = try {
            generate(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "proactive generation failed conv=$conversationId: ${failure.message}")
            GenerationResult.Failed(failure.message ?: "生成异常")
        }
        return when (result) {
            is GenerationResult.Ready -> settle(
                requestId, conversationId, character.name, kind,
                turnId = result.turnId, messages = result.messages,
                degraded = false, background = background,
            )
            is GenerationResult.Fallback -> settle(
                requestId, conversationId, character.name, kind,
                turnId = result.turnId, messages = result.messages,
                degraded = true, background = background,
            )
            is GenerationResult.Failed -> {
                ledger.markFailed(requestId, result.reason, clock())
                Report.Failed(conversationId, result.reason)
            }
        }
    }

    /** 成功/降级共用收口:账本事务 + (后台)通知幂等投递 + (前台)即刻已读。 */
    private suspend fun settle(
        requestId: String,
        conversationId: Long,
        characterName: String,
        kind: ProactiveMessages.Kind,
        turnId: String,
        messages: List<MessageEntity>,
        degraded: Boolean,
        background: Boolean,
    ): Report {
        val now = clock()
        val ok = ledger.finalizeBatch(
            requestId = requestId,
            degraded = degraded,
            turnId = turnId,
            messageIds = messages.map { it.id },
            now = now,
            localDay = localDay(now),
        )
        if (!ok) return Report.Skipped(conversationId, "批次已作废(回溯/中断)")
        if (background) {
            // 通知幂等门:claim 成功才真正投递(重试/中断恢复不重复提醒);
            // 条数 = 本批实际落库的新消息数;多会话各自定向跳转。
            if (ledger.claimNotification(requestId, clock())) {
                notify(Notification(conversationId, characterName, messages.size))
            }
        } else {
            // 前台进页生成:用户在场,即刻已读(不出应用内红点也不出通知)。
            ledger.consumeConversation(conversationId, clock())
        }
        return Report.Generated(conversationId, kind, messages.size, degraded)
    }

    // ── 前台进页(D4)───────────────────────────────────────────────

    /** 展示行(UI 离线分隔用;消费时刻返回,不携带账本内部态)。 */
    data class ConsumedBatch(val batchId: String, val turnId: String, val count: Int, val degraded: Boolean)

    /**
     * 进页消费:会话未读批次 → 已读(角标清零),返回被消费的批次(UI 据此
     * 在各离线批次组前插分隔)。[generateIfAllowed] = true 时再按门禁尝试补
     * 一批(用户在场,不投通知;手机聊天进页传 true,IM 进页维持不触发)。
     * 开关关闭只消费不生成。
     */
    suspend fun onConversationOpened(conversationId: Long, generateIfAllowed: Boolean): List<ConsumedBatch> {
        val consumed = ledger.consumeConversation(conversationId, clock())
        if (generateIfAllowed) autoGenerate(conversationId, background = false)
        return consumed.map {
            ConsumedBatch(
                batchId = it.batchId,
                turnId = it.turnId,
                count = it.count,
                degraded = it.state == ProactiveBatchState.FALLBACK.name,
            )
        }
    }

    // ── 配置 ───────────────────────────────────────────────────────

    /** 从 prefs 读显式覆盖值,未覆盖的键回落 [ProactivePolicy.Config] 默认。 */
    fun policyConfig(): ProactivePolicy.Config {
        val def = ProactivePolicy.Config()
        return ProactivePolicy.Config(
            enabled = switch(),
            minIntervalMs = GenerationPrefs.proactiveOverrideLong(context, GenerationPrefs.KEY_PROACTIVE_MIN_INTERVAL_MS) ?: def.minIntervalMs,
            dailyCap = GenerationPrefs.proactiveOverrideInt(context, GenerationPrefs.KEY_PROACTIVE_DAILY_CAP) ?: def.dailyCap,
            quietStartHour = GenerationPrefs.proactiveOverrideInt(context, GenerationPrefs.KEY_PROACTIVE_QUIET_START_HOUR) ?: def.quietStartHour,
            quietEndHour = GenerationPrefs.proactiveOverrideInt(context, GenerationPrefs.KEY_PROACTIVE_QUIET_END_HOUR) ?: def.quietEndHour,
            batchMax = GenerationPrefs.proactiveOverrideInt(context, GenerationPrefs.KEY_PROACTIVE_BATCH_MAX) ?: def.batchMax,
            scanLimit = GenerationPrefs.proactiveOverrideInt(context, GenerationPrefs.KEY_PROACTIVE_SCAN_LIMIT) ?: def.scanLimit,
            userActiveGraceMs = GenerationPrefs.proactiveOverrideLong(context, GenerationPrefs.KEY_PROACTIVE_USER_ACTIVE_GRACE_MS) ?: def.userActiveGraceMs,
            offlineThresholdMs = GenerationPrefs.proactiveOverrideLong(context, GenerationPrefs.KEY_PROACTIVE_OFFLINE_THRESHOLD_MS) ?: def.offlineThresholdMs,
        )
    }

    companion object {
        private const val TAG = "ProactiveOutbox"

        /** 在途预约租约(超过视为进程中断,作废放行重试)。 */
        const val STALE_LEASE_MS: Long = 10L * 60 * 1000

        fun localDayOf(at: Long): String =
            java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(at))

        fun hourOf(at: Long): Int =
            Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.HOUR_OF_DAY)
    }
}
