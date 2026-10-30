package com.neethu.gouxiang.work

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.neethu.gouxiang.data.GouXiangRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Reconcile interruptions between the app's history and WorkManager's separate database. */
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
        val reason = when (state) {
            "failed" -> info?.outputData?.getString("reason") ?: "任务未完成，请重新生成"
            "cancelled" -> "任务已取消"
            "skipped" -> "自动生成已关闭或请求已过期"
            else -> info?.outputData?.getString("warning")?.takeIf { it.isNotBlank() }
        }
        val now = System.currentTimeMillis()
        dao.setState(task.workId, state, reason, now, now)
    }
}
