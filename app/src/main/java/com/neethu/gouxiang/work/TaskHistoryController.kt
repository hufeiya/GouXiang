package com.neethu.gouxiang.work

import android.content.Context
import androidx.work.WorkManager
import com.neethu.gouxiang.chat.DerivativeFailureKind
import com.neethu.gouxiang.chat.DerivativeKind
import com.neethu.gouxiang.chat.DerivativeSource
import com.neethu.gouxiang.chat.DerivativeTask
import com.neethu.gouxiang.data.GouXiangRepo
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 任务历史操作面(M0 收尾工作包 1):任务历史页的「重试失败任务」「取消进行中
 * 任务」与对账。只操作 `derivative_tasks` 已有记录,不新增状态机:
 *
 * - **重试**:按原记录重入队(同 requestId——文字任务未出结果则完整重跑,已出
 *   文字只补图),继承原 kind/会话/角色/来源。自动任务重试仍走 Worker 的开关/
 *   日期闸门,被闸即如实记 skipped,不假装成功。
 * - **取消**:cancelWorkById + 条件更新(只有 queued/running 行会被标
 *   cancelled/用户取消);Worker 收到取消后 NonCancellable 再落一次同值,幂等。
 */
internal object TaskHistoryController {

    /** 重试一条历史任务;@return 是否已重新入队(找不到记录/非法记录返回 false)。 */
    suspend fun retry(context: Context, workId: String): Boolean = withContext(Dispatchers.IO) {
        val db = GouXiangRepo.get(context).db
        val record = db.derivativeTaskDao().byId(workId) ?: return@withContext false
        val kind = runCatching { DerivativeKind.valueOf(record.kind) }.getOrNull()
            ?: return@withContext false
        val source = runCatching { DerivativeSource.valueOf(record.source) }.getOrNull()
            ?: DerivativeSource.MANUAL
        // 已有文字结果(任意状态)→ 走补图重试,不再请求文本模型;没有结果 →
        // 完整重跑。两种路径都沿用原 requestId(requestIdOverride),结果事务按
        // requestId 幂等——重试不会重复插卡。
        val hasResult = db.derivativeResultDao().byRequestId(record.requestId) != null
        val task = DerivativeTask(
            kind = kind,
            conversationId = record.conversationId,
            source = source,
            characterId = record.characterId,
            imageRetryRequestId = if (hasResult) record.requestId else null,
        )
        runCatching { DerivativeWorker.enqueue(context, task, requestIdOverride = record.requestId) }.isSuccess
    }

    /**
     * 取消一条任务:WorkManager 侧按 workId 取消(queued/running 均有效),
     * 持久化侧行条件更新。@return 是否有进行中的行被取消(供 UI 提示)。
     */
    suspend fun cancel(context: Context, workId: String): Boolean = withContext(Dispatchers.IO) {
        val db = GouXiangRepo.get(context).db
        val at = DerivativeClock.nowMillis()
        val cancelledRows = db.derivativeTaskDao().cancelIfActive(
            workId, "任务已取消", DerivativeFailureKind.USER_CANCEL.name, at,
        )
        runCatching {
            UUID.fromString(workId)
        }.getOrNull()?.let { WorkManager.getInstance(context).cancelWorkById(it) }
        cancelledRows > 0
    }
}
