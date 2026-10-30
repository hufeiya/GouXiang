package com.neethu.gouxiang.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.neethu.gouxiang.ai.GenerationPrefs
import com.neethu.gouxiang.chat.DerivativeKind
import com.neethu.gouxiang.chat.DerivativeTask
import java.util.concurrent.TimeUnit

/**
 * 周期任务调度(docs/background-generation-plan-20261030.md §3.4/§4 M1):
 * - `applyXxx(context, enabled)`:开 → 幂等 UPDATE 注册周期任务;关 → cancel;
 *   设置页 Switch 与 GouXiangApp.bootstrap 共用同一 API(幂等)。
 * - unique 名与开关一一对应(`sched:xxx`),Worker 执行时二次自查开关防竞态
 *   ([DerivativeWorker] 前置守卫由各自动 Worker 的 [AutoGuardWorker.shouldRun])。
 */
object DerivativeScheduler {

    /** 网络约束(所有周期任务同款)。 */
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    // ── 各开关的应用入口(§3.4 API 面)────────────────────────────────

    /** 心声自动(M1 闲时批量;每 6h 扫一次,命中当日未生成的会话入队)。 */
    fun applyInnerVoiceAuto(context: Context, enabled: Boolean) {
        applyPeriodic(context, enabled, UNIQUE_INNER_VOICE_AUTO) {
            PeriodicWorkRequest.Builder(InnerVoiceAutoWorker::class.java, 6, TimeUnit.HOURS)
        }
    }

    /** 朋友圈每日(每 24h 一条;日戳防重复补发,Worker 内自查)。 */
    fun applyMomentsDaily(context: Context, enabled: Boolean) {
        applyPeriodic(context, enabled, UNIQUE_MOMENTS_DAILY) {
            PeriodicWorkRequest.Builder(MomentsDailyWorker::class.java, 24, TimeUnit.HOURS)
        }
    }

    /** 隔夜记忆总结兜底(每 6h 检查闲置窗口)。 */
    fun applyMemoryOvernight(context: Context, enabled: Boolean) {
        applyPeriodic(context, enabled, UNIQUE_MEMORY_OVERNIGHT) {
            PeriodicWorkRequest.Builder(MemoryOvernightWorker::class.java, 6, TimeUnit.HOURS)
        }
    }

    /** 离线主动消息 outbox(§4 M1.2:每 4h 扫 pickTrigger,对齐 B9 语义)。 */
    fun applyProactiveOutbox(context: Context, enabled: Boolean) {
        applyPeriodic(context, enabled, UNIQUE_PROACTIVE_OUTBOX) {
            PeriodicWorkRequest.Builder(ProactiveOutboxWorker::class.java, 4, TimeUnit.HOURS)
        }
    }

    /** 冷启动兜底(GouXiangApp.onCreate):逐项读 prefs,只注册开关为开的任务。 */
    fun bootstrap(context: Context) {
        applyInnerVoiceAuto(context, GenerationPrefs.innerVoiceAuto(context))
        applyMomentsDaily(context, GenerationPrefs.momentsDaily(context))
        applyMemoryOvernight(context, GenerationPrefs.memoryOvernight(context))
        applyProactiveOutbox(context, GenerationPrefs.proactiveOutbox(context))
    }

    /** 开→幂等 UPDATE 注册;关→cancel(设置页与 bootstrap 共用)。 */
    private inline fun applyPeriodic(
        context: Context,
        enabled: Boolean,
        uniqueName: String,
        builder: () -> PeriodicWorkRequest.Builder,
    ) {
        val manager = WorkManager.getInstance(context)
        if (enabled) {
            val request = builder()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .build()
            manager.enqueueUniquePeriodicWork(uniqueName, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            manager.cancelUniqueWork(uniqueName)
        }
    }

    // ── unique 名(与开关一一对应;dumpsys jobscheduler 验收的锚点)────

    const val UNIQUE_INNER_VOICE_AUTO = "sched:inner_voice_auto"
    const val UNIQUE_MOMENTS_DAILY = "sched:moments_daily"
    const val UNIQUE_MEMORY_OVERNIGHT = "sched:memory_overnight"
    const val UNIQUE_PROACTIVE_OUTBOX = "sched:proactive_outbox"
}

/**
 * 周期自动任务基类(§3.4 防竞态):Worker 执行时**再次自查开关**,关了即
 * 直接成功退出不烧请求(任务可能已在队列里、用户刚关掉开关)。
 */abstract class AutoGuardWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    /** 该任务对应的 GenerationPrefs 开关读法。 */
    protected abstract fun gate(context: Context): Boolean

    final override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        if (!gate(applicationContext)) return androidx.work.ListenableWorker.Result.success()
        return runAuto()
    }

    /** 开关通过后的任务体。 */
    protected abstract suspend fun runAuto(): androidx.work.ListenableWorker.Result
}

/** 心声闲时自动批量:对每个 im 会话入队一条 INNER_VOICE(入队 KEEP 幂等)。 */
class InnerVoiceAutoWorker(context: Context, params: WorkerParameters) :
    AutoGuardWorker(context, params) {

    override fun gate(context: Context): Boolean = GenerationPrefs.innerVoiceAuto(context)

    override suspend fun runAuto(): androidx.work.ListenableWorker.Result {
        val repo = com.neethu.gouxiang.data.GouXiangRepo.get(applicationContext)
        return runCatching {
            for (conv in repo.db.conversationDao().latest(50)) {
                if (conv.mode != "im") continue
                DerivativeWorker.enqueue(
                    applicationContext,
                    DerivativeTask(DerivativeKind.INNER_VOICE, conv.id),
                )
            }
            androidx.work.ListenableWorker.Result.success()
        }.getOrDefault(androidx.work.ListenableWorker.Result.retry())
    }
}

/** 朋友圈每日:对每个角色会话入队一条 MOMENT(Executor 内按当日查重防重发)。 */
class MomentsDailyWorker(context: Context, params: WorkerParameters) :
    AutoGuardWorker(context, params) {

    override fun gate(context: Context): Boolean = GenerationPrefs.momentsDaily(context)

    override suspend fun runAuto(): androidx.work.ListenableWorker.Result {
        val app = applicationContext
        val repo = com.neethu.gouxiang.data.GouXiangRepo.get(app)
        return runCatching {
            // 日戳防重复补发(§4 M1.4):同 epoch day 内周期重跑不再入队。
            val today = DerivativeExecutor.todayStartMs() / (24L * 60 * 60 * 1000)
            if (GenerationPrefs.momentsLastDay(app) == today) return@runCatching androidx.work.ListenableWorker.Result.success()
            GenerationPrefs.setMomentsLastDay(app, today)
            for (conv in repo.db.conversationDao().latest(50)) {
                if (conv.mode != "im") continue
                DerivativeWorker.enqueue(
                    app,
                    DerivativeTask(DerivativeKind.MOMENT, conv.id),
                )
            }
            androidx.work.ListenableWorker.Result.success()
        }.getOrDefault(androidx.work.ListenableWorker.Result.retry())
    }
}

/** 隔夜记忆总结兜底(A16):对唯一窗口挂的会话入队 MEMORY_SUMMARY。 */
class MemoryOvernightWorker(context: Context, params: WorkerParameters) :
    AutoGuardWorker(context, params) {

    override fun gate(context: Context): Boolean = GenerationPrefs.memoryOvernight(context)

    override suspend fun runAuto(): androidx.work.ListenableWorker.Result {
        val app = applicationContext
        val repo = com.neethu.gouxiang.data.GouXiangRepo.get(app)
        return runCatching {
            val payload = repo.db.memoryWindowDao().loadPayload()
            val turns = payload?.let { com.neethu.gouxiang.data.deserializeWindow(it) }.orEmpty()
            if (turns.isEmpty()) return@runCatching androidx.work.ListenableWorker.Result.success()
            // 窗口无会话归属(单行表);取最近会话作为素材回放目标。
            val conv = repo.db.conversationDao().latest(1).firstOrNull()
                ?: return@runCatching androidx.work.ListenableWorker.Result.success()
            DerivativeWorker.enqueue(
                app,
                DerivativeTask(DerivativeKind.MEMORY_SUMMARY, conv.id),
            )
            androidx.work.ListenableWorker.Result.success()
        }.getOrDefault(androidx.work.ListenableWorker.Result.retry())
    }
}

/** 离线主动消息 outbox(§4 M1.2):直接执行扫批(不转 OneTime,扫批即任务)。 */
class ProactiveOutboxWorker(context: Context, params: WorkerParameters) :
    AutoGuardWorker(context, params) {

    override fun gate(context: Context): Boolean = GenerationPrefs.proactiveOutbox(context)

    override suspend fun runAuto(): androidx.work.ListenableWorker.Result =
        runCatching {
            val ok = DerivativeExecutor(applicationContext)
                .execute(DerivativeKind.PROACTIVE_OUTBOX, -1L)
            if (ok) androidx.work.ListenableWorker.Result.success() else androidx.work.ListenableWorker.Result.retry()
        }.getOrDefault(androidx.work.ListenableWorker.Result.retry())
}
