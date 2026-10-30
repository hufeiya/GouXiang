package com.neethu.gouxiang.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.neethu.gouxiang.chat.DerivativeFailureKind
import com.neethu.gouxiang.chat.DerivativeKind
import com.neethu.gouxiang.chat.DerivativeTask
import com.neethu.gouxiang.chat.DerivativeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.neethu.gouxiang.data.DerivativeTaskRecord
import com.neethu.gouxiang.data.GouXiangRepo
import java.util.concurrent.TimeUnit

/**
 * 衍生任务 Worker(§2.2:单 Worker 分发多种任务):
 * inputData 取 [DerivativeTask.KEY_KIND]/[KEY_CONVERSATION_ID] →
 * [DerivativeExecutor.execute] → true=success / false=retry(退避不静默丢),
 * 非法 kind/缺参=failure(编程错误,重试无意义)。
 *
 * M0 收尾:每次终态迁移同时落失败原因分类([DerivativeFailureKind] →
 * `derivative_tasks.failureKind`),任务历史页据此展示;时间取 [DerivativeClock]
 * (测试可注入可控时钟)。
 */
class DerivativeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val kindName = inputData.getString(DerivativeTask.KEY_KIND)
            ?: return Result.failure()
        val kind = runCatching { DerivativeKind.valueOf(kindName) }.getOrNull()
            ?: return Result.failure()
        val conversationId = inputData.getLong(DerivativeTask.KEY_CONVERSATION_ID, -1L)
        val source = inputData.getString(DerivativeTask.KEY_SOURCE)
            ?.let { runCatching { DerivativeSource.valueOf(it) }.getOrNull() }
            ?: return Result.failure(androidx.work.workDataOf("reason" to "旧任务来源不明，请重新生成"))
        val characterId = inputData.getLong(DerivativeTask.KEY_CHARACTER_ID, -1L)
        val day = inputData.getString(DerivativeTask.KEY_LOCAL_DAY) ?: DerivativeTask.localDay()
        val requestId = inputData.getString(DerivativeTask.KEY_REQUEST_ID) ?: id.toString()
        val imageRetryRequestId = inputData.getString(DerivativeTask.KEY_IMAGE_RETRY_REQUEST_ID)
        val recordId = inputData.getLong(DerivativeTask.KEY_RECORD_ID, -1L)
        val task = DerivativeTask(
            kind, conversationId, source, characterId, day,
            imageRetryRequestId = imageRetryRequestId, recordId = recordId,
        )
        val dao = GouXiangRepo.get(app).db.derivativeTaskDao()
        val now = DerivativeClock.nowMillis()
        dao.insertIfMissing(DerivativeTaskRecord(id.toString(), requestId, kind.name, source.name,
            conversationId, characterId, "queued", null, now, now, null))
        suspend fun record(state: String, reason: String? = null, terminal: Boolean = false, failureKind: DerivativeFailureKind? = null) {
            val time = DerivativeClock.nowMillis()
            dao.setStateWithKind(id.toString(), state, reason, failureKind?.name, time, if (terminal) time else null)
            // 番外/心动(F)与手帐/小说/合照(G):任务终态同步落记录行,失败状态
            // 在记录列表可见可重试(与任务历史一致,不假成功);成功态由生成事务
            // 收口,这里只在失败/取消/跳过时拨记录(条件更新只在 generating 生效)。
            if (kind in RECORD_BACKED_KINDS && task.recordId > 0L && state in TERMINAL_FAILURE_STATES) {
                val db = GouXiangRepo.get(app).db
                if (kind == DerivativeKind.SIDESTORY) {
                    db.sidestoryRecordDao().markFailedIfGenerating(task.recordId, reason ?: "生成失败，请重试", time)
                } else {
                    db.chatDerivativeDao().markFailedIfGenerating(task.recordId, reason ?: "生成失败，请重试", time)
                }
            }
        }
        if (source == DerivativeSource.AUTOMATIC && (
                !automaticEnabled(app, kind) ||
                    (kind == DerivativeKind.INNER_VOICE && task.characterId > 0L && !innerVoiceAutoAllowed(app, task.characterId)) ||
                    inputData.getLong(KEY_AUTOMATIC_EPOCH, -1L) != automaticEpoch(app, kind) ||
                    (kind in setOf(DerivativeKind.MOMENT, DerivativeKind.INNER_VOICE) && day != DerivativeTask.localDay())
            )) {
            record("skipped", "自动生成已关闭或请求已过期", terminal = true)
            return Result.success(androidx.work.workDataOf("disposition" to "skipped"))
        }
        record("running")
        return try {
            val ok = DerivativeExecutor(app).execute(kind, conversationId, task, requestId)
            DerivativeEvents.post(kind, conversationId, success = ok)
            if (ok) {
                val imageStatus = com.neethu.gouxiang.data.GouXiangRepo.get(app).db
                    .derivativeResultDao().byRequestId(requestId)?.imageStatus
                record("succeeded", if (imageStatus == "failed") "文字已保存，配图生成失败" else null, terminal = true)
                Result.success(androidx.work.workDataOf("warning" to
                    if (imageStatus == "failed") "文字已保存，配图生成失败" else ""))
            } else {
                val retry = source == DerivativeSource.AUTOMATIC && runAttemptCount < 2
                record(if (retry) "queued" else "failed", "生成失败，请检查网络与模型配置后重试", !retry,
                    failureKind = if (retry) null else DerivativeFailureKind.UNKNOWN)
                failureOrRetry(source)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                record("cancelled", "任务已停止", terminal = true, failureKind = DerivativeFailureKind.USER_CANCEL)
            }
            throw cancelled
        } catch (stale: StaleDerivativeSourceException) {
            record("failed", "聊天历史已更改，请重新生成", terminal = true, failureKind = DerivativeFailureKind.UNKNOWN)
            Result.failure(androidx.work.workDataOf("reason" to "聊天历史已更改，请重新生成"))
        } catch (failure: com.neethu.gouxiang.chat.DerivativeFailure) {
            val retry = failure.retryable && source == DerivativeSource.AUTOMATIC && runAttemptCount < 2
            record(if (retry) "queued" else "failed", failure.reason, !retry,
                failureKind = if (retry) null else failure.kind)
            if (retry) Result.retry() else Result.failure(androidx.work.workDataOf("reason" to failure.reason))
        } catch (failure: Exception) {
            DerivativeEvents.post(kind, conversationId, success = false)
            val retry = source == DerivativeSource.AUTOMATIC && runAttemptCount < 2
            record(if (retry) "queued" else "failed", "生成失败，请检查网络与模型配置后重试", !retry,
                failureKind = if (retry) null else DerivativeFailureKind.UNKNOWN)
            failureOrRetry(source)
        }
    }

    private fun failureOrRetry(source: DerivativeSource): Result =
        if (source == DerivativeSource.AUTOMATIC && runAttemptCount < 2) Result.retry()
        else Result.failure(androidx.work.workDataOf("reason" to "生成失败，请检查网络与模型配置后重试"))

    companion object {

        /** 番外/心动记录要同步拨失败的终态(succeeded 由生成事务收口,不在列)。 */
        internal val TERMINAL_FAILURE_STATES = setOf("failed", "cancelled", "skipped")

        /**
         * 记录背书型 kind(F 番外/心动 + G 手帐/小说/合照):任务终态同步落
         * 记录行的集合([DerivativeTask.recordId] 定位)。
         */
        internal val RECORD_BACKED_KINDS = setOf(
            DerivativeKind.SIDESTORY,
            DerivativeKind.HANDBOOK,
            DerivativeKind.NOVEL,
            DerivativeKind.PHOTO,
        )

        /**
         * 心声自动的角色级放行(G4):全局自动开关之外再查角色开关
         * (`characters.innerVoiceAuto`,关开关的角色不入队/不执行)。
         */
        internal suspend fun innerVoiceAutoAllowed(context: Context, characterId: Long): Boolean {
            if (!com.neethu.gouxiang.ai.GenerationPrefs.innerVoiceAuto(context)) return false
            if (characterId <= 0L) return true
            return GouXiangRepo.get(context).characterById(characterId)?.innerVoiceAuto ?: true
        }

        /** 网络约束 + 指数退避 30s(§2.2 约定)。 */
        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        private val backoff = BackoffPolicy.EXPONENTIAL to 30L

        /**
         * 入队一条衍生任务(幂等 KEEP:同唯一名在队/在跑即跳过本次重复入队)。
         * 点击路径与自动路径共用本入口;自动路径的开关闸门在调用方
         * (Scheduler)与 Worker 执行时双闸。
         *
         * [requestIdOverride] 供任务历史「重试」沿用原请求 ID(文字任务未出结果
         * 则完整重跑且结果事务按同 requestId 幂等;已出文字则走补图重试),不传
         * 时按任务来源生成(手动=新 UUID,自动=按角色+本地日期)。
         */
        suspend fun enqueue(context: Context, task: DerivativeTask, requestIdOverride: String? = null) = withContext(Dispatchers.IO) {
            val db = GouXiangRepo.get(context).db
            val characterId = if (task.characterId > 0) task.characterId
                else db.conversationDao().byId(task.conversationId)?.characterId ?: -1L
            val resolved = task.copy(characterId = characterId)
            val requestId = requestIdOverride ?: task.imageRetryRequestId ?: if (task.source == DerivativeSource.AUTOMATIC && characterId > 0L)
                DerivativeTask.automaticKey(resolved) else java.util.UUID.randomUUID().toString()
            val request = OneTimeWorkRequestBuilder<DerivativeWorker>()
                .setConstraints(if (resolved.requiresNetwork) constraints else Constraints.NONE)
                .setBackoffCriteria(backoff.first, backoff.second, TimeUnit.SECONDS)
                .setInputData(resolved.toInputDataCompat(requestId, task.automaticEpoch ?: automaticEpoch(context, task.kind)))
                .build()
            val manager = WorkManager.getInstance(context)
            val dao = db.derivativeTaskDao()
            val now = DerivativeClock.nowMillis()
            val workId = request.id.toString()
            dao.insertIfMissing(DerivativeTaskRecord(workId, requestId, task.kind.name, task.source.name,
                task.conversationId, characterId, "queued", null, now, now, null))
            try {
                manager.enqueueUniqueWork(DerivativeTask.uniqueName(resolved), ExistingWorkPolicy.KEEP, request).result.get()
                // KEEP rejected this request; there is no corresponding WorkManager item.
                if (manager.getWorkInfoById(request.id).get() == null) dao.deleteById(workId)
            } catch (failure: Exception) {
                val at = DerivativeClock.nowMillis()
                dao.setStateWithKind(workId, "failed", "任务入队失败，请重试", DerivativeFailureKind.UNKNOWN.name, at, at)
                throw failure
            }
        }
    }
}

/** inputData 组装(用 WorkManager 原生 Data,兼容 §2.2 的 PersistableBundle 键序)。 */
internal fun automaticEnabled(context: Context, kind: DerivativeKind): Boolean = when (kind) {
    DerivativeKind.INNER_VOICE -> com.neethu.gouxiang.ai.GenerationPrefs.innerVoiceAuto(context)
    DerivativeKind.MOMENT -> com.neethu.gouxiang.ai.GenerationPrefs.momentsDaily(context)
    DerivativeKind.MEMORY_SUMMARY -> com.neethu.gouxiang.ai.GenerationPrefs.memoryOvernight(context)
    DerivativeKind.PROACTIVE_OUTBOX -> com.neethu.gouxiang.ai.GenerationPrefs.proactiveOutbox(context)
    else -> false
}

internal const val KEY_AUTOMATIC_EPOCH = "derivative_automatic_epoch"
private const val EPOCH_PREFS = "derivative_automatic_epochs"

internal fun automaticEpoch(context: Context, kind: DerivativeKind): Long =
    context.getSharedPreferences(EPOCH_PREFS, Context.MODE_PRIVATE).getLong(kind.name, 0L)

internal fun invalidateAutomatic(context: Context, kind: DerivativeKind) {
    val preferences = context.getSharedPreferences(EPOCH_PREFS, Context.MODE_PRIVATE)
    preferences.edit().putLong(kind.name, automaticEpoch(context, kind) + 1).commit()
}

private fun DerivativeTask.toInputDataCompat(requestId: String, epoch: Long): androidx.work.Data =
    androidx.work.Data.Builder()
        .putString(DerivativeTask.KEY_KIND, kind.name)
        .putLong(DerivativeTask.KEY_CONVERSATION_ID, conversationId)
        .putString(DerivativeTask.KEY_SOURCE, source.name)
        .putLong(DerivativeTask.KEY_CHARACTER_ID, characterId)
        .putString(DerivativeTask.KEY_LOCAL_DAY, localDay)
        .putString(DerivativeTask.KEY_REQUEST_ID, requestId)
        .putString(DerivativeTask.KEY_IMAGE_RETRY_REQUEST_ID, imageRetryRequestId)
        .putLong(KEY_AUTOMATIC_EPOCH, epoch)
        .putLong(DerivativeTask.KEY_RECORD_ID, recordId)
        .build()
