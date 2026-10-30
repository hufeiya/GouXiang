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
import com.neethu.gouxiang.chat.DerivativeKind
import com.neethu.gouxiang.chat.DerivativeTask
import java.util.concurrent.TimeUnit

/**
 * 衍生任务 Worker(§2.2:单 Worker 分发多种任务):
 * inputData 取 [DerivativeTask.KEY_KIND]/[KEY_CONVERSATION_ID] →
 * [DerivativeExecutor.execute] → true=success / false=retry(退避不静默丢),
 * 非法 kind/缺参=failure(编程错误,重试无意义)。
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
        return runCatching {
            DerivativeExecutor(app).execute(kind, conversationId)
        }.fold(
            onSuccess = { ok ->
                if (ok) {
                    DerivativeEvents.post(kind, conversationId, success = true)
                    Result.success()
                } else {
                    DerivativeEvents.post(kind, conversationId, success = false)
                    Result.retry()
                }
            },
            onFailure = {
                DerivativeEvents.post(kind, conversationId, success = false)
                Result.retry()
            },
        )
    }

    companion object {

        /** 网络约束 + 指数退避 30s(§2.2 约定)。 */
        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        private val backoff = BackoffPolicy.EXPONENTIAL to 30L

        /**
         * 入队一条衍生任务(幂等 KEEP:同唯一名在队/在跑即跳过本次重复入队)。
         * 点击路径与自动路径共用本入口;自动路径的开关闸门在调用方
         * (Scheduler)与 Worker 执行时双闸。
         */
        fun enqueue(context: Context, task: DerivativeTask) {
            val request = OneTimeWorkRequestBuilder<DerivativeWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(backoff.first, backoff.second, TimeUnit.SECONDS)
                .setInputData(task.toInputDataCompat())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                DerivativeTask.uniqueName(task),
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}

/** inputData 组装(用 WorkManager 原生 Data,兼容 §2.2 的 PersistableBundle 键序)。 */
private fun DerivativeTask.toInputDataCompat(): androidx.work.Data =
    androidx.work.Data.Builder()
        .putString(DerivativeTask.KEY_KIND, kind.name)
        .putLong(DerivativeTask.KEY_CONVERSATION_ID, conversationId)
        .build()
