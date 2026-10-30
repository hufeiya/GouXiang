package com.neethu.gouxiang.work

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.neethu.gouxiang.chat.DerivativeFailureKind
import com.neethu.gouxiang.data.GouXiangRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Reconcile interruptions between the app's history and WorkManager's separate database.
 *
 * M0 收尾:终态迁移同时落失败分类(取消=用户取消;WorkManager 报 FAILED 而本侧
 * 没有分类时回落「未知」,真实分类由 Worker 自己的失败收口写入)。
 */
internal suspend fun reconcileDerivativeTasks(context: Context) = withContext(Dispatchers.IO) {
    val dao = GouXiangRepo.get(context).db.derivativeTaskDao()
    val manager = WorkManager.getInstance(context)
    for (task in dao.unfinished()) {
        val id = runCatching { UUID.fromString(task.workId) }.getOrNull() ?: continue
        val info = manager.getWorkInfoById(id).get()
        val state = when (info?.state) {
            null -> "failed"
            WorkInfo.State.CANCELLED -> "cancelled"
            WorkInfo.State.FAILED -> "failed"
            WorkInfo.State.SUCCEEDED -> if (info.outputData.getString("disposition") == "skipped") "skipped" else "succeeded"
            else -> continue
        }
        val failureKind = when (state) {
            "cancelled" -> DerivativeFailureKind.USER_CANCEL
            "failed" -> DerivativeFailureKind.UNKNOWN
            else -> null
        }
        val reason = when (state) {
            "failed" -> info?.outputData?.getString("reason") ?: "任务未完成，请重新生成"
            "cancelled" -> "任务已取消"
            "skipped" -> "自动生成已关闭或请求已过期"
            else -> info?.outputData?.getString("warning")?.takeIf { it.isNotBlank() }
        }
        val now = DerivativeClock.nowMillis()
        dao.setStateWithKind(task.workId, state, reason, failureKind?.name, now, now)
    }
}
