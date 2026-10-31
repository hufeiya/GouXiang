package com.neethu.gouxiang.work

import android.content.Context
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.neethu.gouxiang.ai.image.AvatarImageProvider
import com.neethu.gouxiang.chat.DerivativeKind
import com.neethu.gouxiang.chat.DerivativeTask
import com.neethu.gouxiang.data.GouXiangRepo
import com.neethu.gouxiang.derivatives.ChatDerivativeOutput
import kotlinx.coroutines.CancellationException

/** Explicit image retry does not regenerate the already saved text. */
class ContentCandidateImageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("candidate", -1)
        val db = GouXiangRepo.get(applicationContext).db
        val dao = db.contentDiscoveryDao()
        val candidate = dao.byId(id) ?: return Result.success()
        if (candidate.bodyStatus != "READY") return Result.success()
        try {
            captureCandidateSource(db, candidate)
            val request = candidate.requestId ?: error("missing request")
            val status = when (candidate.kind) {
                "INNER_VOICE", "MOMENT" -> {
                    val kind = DerivativeKind.valueOf(candidate.kind)
                    val task = DerivativeTask(kind, candidate.conversationId, characterId = candidate.characterId,
                        imageRetryRequestId = request)
                    val executor = DerivativeExecutor(applicationContext)
                    if (kind == DerivativeKind.INNER_VOICE) executor.executeInnerVoice(candidate.conversationId, task, request, candidate)
                    else executor.executeMoment(candidate.conversationId, task, request, candidate)
                    db.derivativeResultDao().byRequestId(request)?.imageStatus?.uppercase() ?: "FAILED"
                }
                "HANDBOOK" -> {
                    val record = candidate.resultId?.let { db.chatDerivativeDao().byId(it) } ?: error("missing record")
                    val character = db.characterDao().byId(candidate.characterId) ?: error("missing character")
                    val imagePrompt = ChatDerivativeOutput.handbookFromJson(record.bodyJson)?.imagePrompt.orEmpty()
                    val prompt = "${character.appearance}。${imagePrompt.ifBlank { "与用户相处的手帐回忆,温柔日常" }}。${character.appearanceStyle},柔和手帐光影,竖版构图"
                    val image = AvatarImageProvider(applicationContext.filesDir).getOrGeneratePhotoImage("handbook-${record.id}", prompt)
                    db.withTransaction {
                        requireCurrentDerivativeSource(db, candidate.conversationId, candidate.sourceVersion)
                        if (image != null) db.chatDerivativeDao().setImageFile(record.id, image.absolutePath, System.currentTimeMillis())
                        else db.chatDerivativeDao().setImageStatus(record.id, "failed", System.currentTimeMillis())
                    }
                    if (image == null) "FAILED" else "COMPLETE"
                }
                else -> "SKIPPED"
            }
            db.withTransaction {
                requireCurrentDerivativeSource(db, candidate.conversationId, candidate.sourceVersion)
                dao.setImageStatus(id, status)
            }
            return Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            dao.setImageStatus(id, "FAILED")
            return Result.failure()
        }
    }

    companion object {
        suspend fun enqueue(context: Context, id: Long) {
            val dao = GouXiangRepo.get(context).db.contentDiscoveryDao()
            val candidate = dao.byId(id) ?: return
            if (candidate.bodyStatus != "READY" || candidate.kind !in setOf("INNER_VOICE", "MOMENT", "HANDBOOK")) return
            dao.setImageStatus(id, "QUEUED")
            val work = OneTimeWorkRequestBuilder<ContentCandidateImageWorker>()
                .setInputData(workDataOf("candidate" to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("candidate-image:$id", ExistingWorkPolicy.KEEP, work)
        }
    }
}
