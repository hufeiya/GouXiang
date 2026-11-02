package com.neethu.gouxiang.work

import android.content.Context
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.neethu.gouxiang.ai.GenerationPrefs
import com.neethu.gouxiang.chat.DerivativeKind
import com.neethu.gouxiang.chat.DerivativeFailure
import com.neethu.gouxiang.chat.DerivativeTask
import com.neethu.gouxiang.data.ChatDerivativeEntity
import com.neethu.gouxiang.data.ContentCandidateEntity
import com.neethu.gouxiang.data.GouXiangRepo
import com.neethu.gouxiang.derivatives.ChatDerivativeGenerator
import com.neethu.gouxiang.derivatives.DerivativeOptions
import com.neethu.gouxiang.derivatives.NovelLength
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** One durable generation job per discovered title, shared by automatic and explicit opens. */
class ContentCandidateBodyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1)
        val db = GouXiangRepo.get(applicationContext).db
        val dao = db.contentDiscoveryDao()
        var candidate = dao.byId(id) ?: return Result.success()
        if (candidate.bodyStatus in setOf("READY", "STALE")) return Result.success()
        if (!candidate.userRequested && !automaticEnabledFor(applicationContext, candidate.kind)) {
            val deferred = db.withTransaction {
                val changed = dao.deferAutomatic(id, candidate.bodyStatus) > 0
                if (changed) {
                    db.chatDerivativeDao().byRequestId(candidate.requestId ?: "candidate-$id")?.let {
                        db.chatDerivativeDao().markFailedIfGenerating(it.id, "自动生成已暂停，点开标题后可继续", System.currentTimeMillis())
                    }
                }
                changed
            }
            if (deferred) return Result.success()
            candidate = dao.byId(id) ?: return Result.success()
            if (!candidate.userRequested) return Result.success()
        }
        try {
            captureCandidateSource(db, candidate)
            dao.setBodyState(id, candidate.bodyStatus, "BODY_GENERATING")
            candidate = dao.byId(id) ?: return Result.success()
            if (candidate.bodyStatus != "BODY_GENERATING") return Result.success()
            val requestId = candidate.requestId ?: "candidate-$id"
            val resultId: Long
            val imageStatus: String
            when (candidate.kind) {
                "INNER_VOICE", "MOMENT" -> {
                    val kind = DerivativeKind.valueOf(candidate.kind)
                    val task = DerivativeTask(kind, candidate.conversationId, characterId = candidate.characterId)
                    val executor = DerivativeExecutor(applicationContext)
                    val ok = if (kind == DerivativeKind.INNER_VOICE)
                        executor.executeInnerVoice(candidate.conversationId, task, requestId, candidate)
                    else executor.executeMoment(candidate.conversationId, task, requestId, candidate)
                    if (!ok) error("生成失败")
                    val result = db.derivativeResultDao().byRequestId(requestId) ?: error("生成结果不存在")
                    resultId = result.resultIds.substringBefore(',').toLong()
                    imageStatus = result.imageStatus.uppercase()
                }
                "HANDBOOK", "NOVEL" -> {
                    val record = db.withTransaction {
                        requireCurrentDerivativeSource(db, candidate.conversationId, candidate.sourceVersion)
                        val current = dao.byId(id) ?: error("候选已删除")
                        if (current.bodyStatus != "BODY_GENERATING") throw StaleDerivativeSourceException()
                        val existing = db.chatDerivativeDao().byRequestId(requestId)
                        if (existing != null) {
                            if (existing.sourceVersion != candidate.sourceVersion) {
                                db.chatDerivativeDao().rebaseUnfinished(existing.id, candidate.sourceVersion)
                            }
                            if (existing.state == ChatDerivativeEntity.STATE_FAILED) {
                                db.chatDerivativeDao().markGeneratingForRetry(existing.id, System.currentTimeMillis())
                            }
                            existing.id
                        } else {
                            val type = if (candidate.kind == "HANDBOOK") ChatDerivativeEntity.KIND_HANDBOOK else ChatDerivativeEntity.KIND_NOVEL
                            val options = if (candidate.kind == "HANDBOOK") DerivativeOptions.HandbookOption("turn10").toJson()
                                else DerivativeOptions.NovelOption(NovelLength.SHORT.key).toJson()
                            val now = System.currentTimeMillis()
                            db.chatDerivativeDao().insert(ChatDerivativeEntity(kind = type, title = candidate.title,
                                characterId = candidate.characterId, conversationId = candidate.conversationId,
                                optionJson = options, sourceStartMessageId = candidate.startMessageId,
                                sourceEndMessageId = candidate.endMessageId, sourceVersion = candidate.sourceVersion,
                                requestId = requestId, createdAt = now, updatedAt = now))
                        }
                    }
                    if (!ChatDerivativeGenerator.production(applicationContext).generate(record, requestId, candidate)) error("生成失败")
                    val saved = db.chatDerivativeDao().byId(record) ?: error("生成结果不存在")
                    if (!saved.isSucceeded) error(saved.failureReason.ifBlank { "生成失败" })
                    resultId = record
                    imageStatus = saved.imageStatus.uppercase()
                }
                else -> return Result.failure()
            }
            db.withTransaction {
                requireCurrentCandidate(db, candidate)
                dao.setResult(id, "READY", resultId, requestId)
                dao.setImageStatus(id, imageStatus)
            }
            return Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: StaleDerivativeSourceException) {
            val current = dao.byId(id)
            if (current != null && current.bodyStatus != "STALE" && current.sourceVersion != candidate.sourceVersion) {
                return Result.retry()
            }
            dao.setBodyState(id, "BODY_GENERATING", "STALE")
            dao.setBodyState(id, "BODY_QUEUED", "STALE")
            return Result.failure()
        } catch (error: Exception) {
            val retry = settleCandidateBodyFailure(db, id, error, runAttemptCount)
            if (retry) return Result.retry()
            // A user tap can have appended explicit work to this running job. The database
            // carries the failure state; success here lets that queued request run.
            return Result.success()
        }
    }

    companion object {
        private const val KEY_ID = "candidate_id"

        private fun automaticEnabledFor(context: Context, kind: String): Boolean = when (kind) {
            "INNER_VOICE" -> GenerationPrefs.innerVoiceAuto(context)
            "MOMENT" -> GenerationPrefs.momentsDaily(context)
            "HANDBOOK" -> GenerationPrefs.handbookAuto(context)
            "NOVEL" -> GenerationPrefs.novelAuto(context)
            else -> false
        }

        /** Recover a database commit or a worker interrupted before its next enqueue. */
        suspend fun reconcile(context: Context) {
            val dao = GouXiangRepo.get(context).db.contentDiscoveryDao()
            for (candidate in dao.pendingBodies()) {
                if (candidate.userRequested) enqueue(context, candidate.id, true)
                else if (candidate.autoEligible) {
                    if (automaticEnabledFor(context, candidate.kind)) enqueueAutomatic(context, candidate.id)
                    else dao.deferAutomatic(candidate.id, candidate.bodyStatus)
                }
            }
        }

        suspend fun enqueueAutomatic(context: Context, id: Long) {
            val dao = GouXiangRepo.get(context).db.contentDiscoveryDao()
            val candidate = dao.byId(id) ?: return
            if (!candidate.autoEligible || !automaticEnabledFor(context, candidate.kind)) return
            enqueue(context, id, false)
        }

        suspend fun enqueueManual(context: Context, id: Long) = enqueue(context, id, true)

        private suspend fun enqueue(context: Context, id: Long, manual: Boolean) {
            val dao = GouXiangRepo.get(context).db.contentDiscoveryDao()
            if (manual) dao.markUserRequested(id)
            val current = dao.byId(id) ?: return
            if (current.bodyStatus in setOf("READY", "STALE")) return
            if (current.bodyStatus == "TITLE_READY" || current.bodyStatus == "BODY_FAILED") {
                dao.setBodyState(id, current.bodyStatus, "BODY_QUEUED")
            }
            val request = OneTimeWorkRequestBuilder<ContentCandidateBodyWorker>()
                .setInputData(workDataOf(KEY_ID to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // A tap can race with an automatic worker that is about to return after a switch-off.
            // Append the explicit request so KEEP cannot discard that intent during the handoff.
            WorkManager.getInstance(context).enqueueUniqueWork("candidate-body:$id",
                if (manual) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
        }
    }
}

/** Three total automatic attempts; manual requests and permanent errors fail immediately. */
internal fun shouldRetryCandidateBody(error: Throwable, userRequested: Boolean, runAttemptCount: Int): Boolean =
    !userRequested && runAttemptCount < 2 && DerivativeFailure.from(error).retryable

/** Keep the candidate and any handbook/novel record in agreement at the terminal attempt. */
internal suspend fun settleCandidateBodyFailure(
    db: com.neethu.gouxiang.data.GouXiangDatabase,
    id: Long,
    error: Throwable,
    runAttemptCount: Int,
): Boolean = db.withTransaction {
    val dao = db.contentDiscoveryDao()
    val current = dao.byId(id) ?: return@withTransaction false
    if (current.bodyStatus !in setOf("BODY_GENERATING", "BODY_QUEUED")) return@withTransaction false
    if (shouldRetryCandidateBody(error, current.userRequested, runAttemptCount)) return@withTransaction true
    dao.setBodyState(id, current.bodyStatus, "BODY_FAILED")
    db.chatDerivativeDao().byRequestId(current.requestId ?: "candidate-$id")?.let {
        db.chatDerivativeDao().markFailedIfGenerating(it.id, DerivativeFailure.from(error).reason, System.currentTimeMillis())
    }
    false
}
