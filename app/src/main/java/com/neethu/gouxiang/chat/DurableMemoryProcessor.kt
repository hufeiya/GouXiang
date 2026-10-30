package com.neethu.gouxiang.chat

import androidx.room.withTransaction
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.GouXiangDatabase
import com.neethu.gouxiang.data.MemoryConversationVersion
import com.neethu.gouxiang.data.MemoryTurn
import com.neethu.gouxiang.data.PendingMemoryTurnEntity
import java.util.UUID

/** Persisted leases coordinate foreground, manual and worker extraction across instances. */
class DurableMemoryProcessor(private val db: GouXiangDatabase, private val extractor: MemoryExtractor) {
    private val dao get() = db.memoryWindowDao()
    private val leaseMillis = 10 * 60 * 1000L

    suspend fun append(character: CharacterEntity, conversationId: Long, turnId: String, user: String, assistant: String, sourceUserMessageId: Long? = null) {
        db.withTransaction {
            val messages = db.messageDao().allForConversation(conversationId)
            val assistantRows = messages.filter { it.turnId == turnId }
            if (assistantRows.isEmpty()) return@withTransaction
            val first = assistantRows.minOf { it.id }
            val userId = sourceUserMessageId ?: first
            dao.insertTurn(PendingMemoryTurnEntity(conversationId = conversationId, characterId = character.id,
                turnId = turnId, startMessageId = userId, endMessageId = assistantRows.maxOf { it.id }, user = user, assistant = assistant, completed = true))
        }
    }

    suspend fun pending(conversationId: Long): List<PendingMemoryTurnEntity> = dao.turns(conversationId)

    private data class Claim(val token: String, val version: Long, val turns: List<PendingMemoryTurnEntity>)

    private suspend fun claim(conversationId: Long, characterId: Long, profile: Boolean): Claim? = db.withTransaction {
        val now = System.currentTimeMillis()
        val candidates = dao.turns(conversationId).filter { it.completed && it.characterId == characterId && if (profile) !it.profileDone else !it.eventDone }
        if (candidates.isEmpty()) return@withTransaction null
        // Preserve order: an active lease prevents consuming later turns in this channel.
        if (candidates.any { if (profile) it.profileLease.isNotEmpty() && now - it.profileLeaseAt < leaseMillis else it.eventLease.isNotEmpty() && now - it.eventLeaseAt < leaseMillis }) return@withTransaction null
        val token = UUID.randomUUID().toString()
        dao.updateTurns(candidates.map { if (profile) it.copy(profileLease = token, profileLeaseAt = now) else it.copy(eventLease = token, eventLeaseAt = now) })
        Claim(token, dao.version(conversationId) ?: 0L, candidates)
    }

    private suspend fun finish(conversationId: Long, claim: Claim, profile: Boolean, valid: suspend () -> Boolean = { true }, apply: suspend () -> Unit): Boolean = db.withTransaction {
        if ((dao.version(conversationId) ?: 0L) != claim.version) return@withTransaction false
        val rows = dao.turns(conversationId).filter { if (profile) it.profileLease == claim.token else it.eventLease == claim.token }
        if (rows.map { it.id } != claim.turns.map { it.id }) return@withTransaction false
        if (!valid()) return@withTransaction false
        apply()
        dao.updateTurns(rows.map { if (profile) it.copy(profileDone = true, profileLease = "", profileLeaseAt = 0) else it.copy(eventDone = true, eventLease = "", eventLeaseAt = 0) })
        true
    }

    private suspend fun release(conversationId: Long, claim: Claim, profile: Boolean) = db.withTransaction {
        val rows = dao.turns(conversationId).filter { if (profile) it.profileLease == claim.token else it.eventLease == claim.token }
        dao.updateTurns(rows.map { if (profile) it.copy(profileLease = "", profileLeaseAt = 0) else it.copy(eventLease = "", eventLeaseAt = 0) })
    }

    suspend fun run(character: CharacterEntity, conversationId: Long): Boolean {
        var success = true
        for (profile in listOf(true, false)) {
            val claim = claim(conversationId, character.id, profile)
            if (claim == null) {
                if (dao.turns(conversationId).any { it.completed && it.characterId == character.id && if (profile) !it.profileDone else !it.eventDone }) success = false
                continue
            }
            try {
                val turns = claim.turns.map { MemoryTurn(it.user, it.assistant) }
                val committed = if (profile) {
                    val candidate = extractor.profileCandidate(turns, character)
                    candidate != null && finish(conversationId, claim, true, valid = { extractor.profileUnchanged(character.id, candidate) }) { extractor.applyProfile(character.id, candidate) }
                } else {
                    val candidate = extractor.eventCandidate(turns, character)
                    candidate != null && finish(conversationId, claim, false) {
                        extractor.applyEvents(character.id, candidate, conversationId, claim.turns.maxOf { it.endMessageId })
                    }
                }
                if (!committed) {
                    success = false
                    if ((dao.version(conversationId) ?: 0L) != claim.version) break
                }
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release(conversationId, claim, profile) }
            }
        }
        return success
    }

    /** Atomically invalidate all in-flight requests and remove only sourced affected events. */
    suspend fun rewind(conversationId: Long, messageId: Long) = db.withTransaction {
        dao.setVersion(MemoryConversationVersion(conversationId, (dao.version(conversationId) ?: 0L) + 1))
        // Clear leases after invalidation so an old request cannot delay the new history.
        dao.clearLeases(conversationId)
        val affected = dao.allTurns(conversationId).filter { it.startMessageId < messageId && it.endMessageId >= messageId }
        if (affected.isNotEmpty()) {
            val messages = db.messageDao().allForConversation(conversationId)
            val retained = affected.mapNotNull { turn ->
                val bubbles = messages.filter { it.turnId == turn.turnId && it.role == "assistant" && it.id < messageId }
                    .sortedBy { it.id }
                if (bubbles.isEmpty()) return@mapNotNull null
                val prefix = if (turn.user.isEmpty() && turn.assistant.startsWith("(TA 主动发来)")) "(TA 主动发来)" else ""
                turn.copy(endMessageId = bubbles.last().id, assistant = prefix + bubbles.joinToString("\n") { it.content },
                    completed = true, profileDone = false, eventDone = false,
                    profileLease = "", eventLease = "", profileLeaseAt = 0, eventLeaseAt = 0)
            }
            dao.updateTurns(retained)
        }
        dao.deleteFrom(conversationId, messageId)
        db.memoryDao().deleteFromSource(conversationId, messageId)
        db.derivativeResultDao().markMomentsStale(conversationId, messageId)
        db.derivativeResultDao().markInnerVoicesStale(conversationId, messageId)
        db.messageDao().deleteFromMessage(conversationId, messageId)
    }
}
