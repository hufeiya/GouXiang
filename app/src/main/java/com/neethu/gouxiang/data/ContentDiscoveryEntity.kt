package com.neethu.gouxiang.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** A successful user/reply pair, recorded atomically with the final reply bubble. */
@Entity(tableName = "content_turns", indices = [Index(value = ["conversationId", "replyTurnId"], unique = true), Index(value = ["conversationId", "userMessageId"], unique = true), Index("conversationId")])
data class ContentTurnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long, val characterId: Long, val replyTurnId: String,
    val userMessageId: Long, val endMessageId: Long, val sourceVersion: Long,
    val user: String, val assistant: String, val completedAt: Long,
)

@Entity(tableName = "content_discovery_state")
data class ContentDiscoveryState(
    @PrimaryKey val conversationId: Long,
    val consumedTurnId: Long = 0, val lastEvaluatedTurnId: Long = 0,
    val lastEvaluatedCount: Int = 0, val failures: Int = 0, val retryAfter: Long = 0,
)

@Entity(tableName = "content_candidates", indices = [Index(value = ["candidateKey"], unique = true), Index("conversationId"), Index("characterId")])
data class ContentCandidateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long, val characterId: Long, val kind: String, val title: String,
    val startTurnId: Long, val endTurnId: Long, val startMessageId: Long, val endMessageId: Long,
    val sourceVersion: Long, val createdAt: Long, val candidateKey: String,
    val truncated: Boolean = false, val bodyStatus: String = "TITLE_READY", val imageStatus: String = "SKIPPED",
    val resultId: Long? = null, val requestId: String? = null, val reminderStatus: String = "UNSEEN",
    val userRequested: Boolean = false, val autoEligible: Boolean = false, val sourceStale: Boolean = false,
)

@Dao
interface ContentDiscoveryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTurn(turn: ContentTurnEntity): Long
    @Query("SELECT * FROM content_turns WHERE conversationId = :conversationId AND id > :after ORDER BY id")
    suspend fun turnsAfter(conversationId: Long, after: Long): List<ContentTurnEntity>
    @Query("SELECT DISTINCT conversationId FROM content_turns") suspend fun conversationIds(): List<Long>
    @Query("DELETE FROM content_turns WHERE conversationId = :conversationId AND endMessageId >= :messageId")
    suspend fun deleteTurnsFrom(conversationId: Long, messageId: Long)
    @Query("UPDATE content_turns SET sourceVersion = :version WHERE conversationId = :conversationId") suspend fun updateSurvivingTurnVersion(conversationId: Long, version: Long)
    @Query("SELECT * FROM content_discovery_state WHERE conversationId = :conversationId") suspend fun state(conversationId: Long): ContentDiscoveryState?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveState(state: ContentDiscoveryState)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertCandidate(candidate: ContentCandidateEntity): Long
    @Query("SELECT * FROM content_candidates WHERE id = :id") suspend fun byId(id: Long): ContentCandidateEntity?
    @Query("SELECT * FROM content_candidates WHERE conversationId = :conversationId ORDER BY createdAt DESC, id DESC") fun observeForConversation(conversationId: Long): Flow<List<ContentCandidateEntity>>
    @Query("UPDATE content_candidates SET bodyStatus = :state WHERE id = :id AND bodyStatus = :expected") suspend fun setBodyState(id: Long, expected: String, state: String): Int
    @Query("UPDATE content_candidates SET bodyStatus = :state, resultId = :resultId, requestId = :requestId WHERE id = :id AND bodyStatus != 'STALE'") suspend fun setResult(id: Long, state: String, resultId: Long?, requestId: String?): Int
    @Query("UPDATE content_candidates SET reminderStatus = :state WHERE id = :id AND (:state != 'SEEN' OR reminderStatus = 'UNSEEN')") suspend fun setReminder(id: Long, state: String): Int
    @Query("UPDATE content_candidates SET bodyStatus = 'TITLE_READY', autoEligible = 0 WHERE id = :id AND userRequested = 0 AND bodyStatus = :expected") suspend fun deferAutomatic(id: Long, expected: String): Int
    @Query("UPDATE content_candidates SET userRequested = 1 WHERE id = :id AND bodyStatus != 'STALE'") suspend fun markUserRequested(id: Long): Int
    @Query("UPDATE content_candidates SET imageStatus = :status WHERE id = :id AND bodyStatus != 'STALE'") suspend fun setImageStatus(id: Long, status: String): Int
    @Query("SELECT * FROM content_candidates WHERE bodyStatus IN ('BODY_QUEUED', 'BODY_GENERATING') OR (bodyStatus = 'TITLE_READY' AND autoEligible = 1)") suspend fun pendingBodies(): List<ContentCandidateEntity>
    @Query("SELECT COUNT(*) FROM content_candidates WHERE conversationId = :conversationId AND reminderStatus = 'UNSEEN' AND bodyStatus != 'STALE'") suspend fun unseenCount(conversationId: Long): Int
    @Query("SELECT COUNT(*) FROM content_candidates WHERE characterId = :characterId AND kind = :kind AND bodyStatus != 'STALE' AND sourceStale = 0") suspend fun countForKind(characterId: Long, kind: String): Int
    @Query("UPDATE content_candidates SET sourceVersion = :version WHERE conversationId = :conversationId AND endMessageId < :messageId AND sourceStale = 0") suspend fun updateUnaffectedVersion(conversationId: Long, messageId: Long, version: Long)
    @Query("UPDATE content_candidates SET sourceStale = 1 WHERE conversationId = :conversationId AND endMessageId >= :messageId AND bodyStatus = 'READY'") suspend fun markCompletedStaleFrom(conversationId: Long, messageId: Long)
    @Query("UPDATE content_candidates SET bodyStatus = 'STALE', reminderStatus = 'DISMISSED' WHERE conversationId = :conversationId AND endMessageId >= :messageId AND bodyStatus != 'READY'") suspend fun invalidateFrom(conversationId: Long, messageId: Long)
}
