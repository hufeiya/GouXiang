package com.neethu.gouxiang.work

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.neethu.gouxiang.chat.DerivativeFailure
import com.neethu.gouxiang.data.ChatDerivativeEntity
import com.neethu.gouxiang.data.ContentCandidateEntity
import com.neethu.gouxiang.data.GouXiangDatabase
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ContentCandidateBodyRetryTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), GouXiangDatabase::class.java,
    ).allowMainThreadQueries().build()

    @After fun close() = db.close()

    private suspend fun candidate(kind: String = "HANDBOOK"): Long =
        db.contentDiscoveryDao().insertCandidate(ContentCandidateEntity(
            conversationId = 1, characterId = 1, kind = kind, title = "约定",
            startTurnId = 1, endTurnId = 8, startMessageId = 1, endMessageId = 16,
            sourceVersion = 0, createdAt = 1, candidateKey = kind,
            bodyStatus = "BODY_GENERATING", autoEligible = true,
        ))

    @Test fun automaticTransientFailureHasAtMostThreeAttemptsAndFinishesBothRows() = runBlocking {
        val id = candidate()
        val recordId = db.chatDerivativeDao().insert(ChatDerivativeEntity(
            kind = ChatDerivativeEntity.KIND_HANDBOOK, characterId = 1, conversationId = 1,
            requestId = "candidate-$id",
        ))
        val network = IOException("private network error")
        for (attempt in 0..1) {
            assertTrue(settleCandidateBodyFailure(db, id, network, attempt))
            assertEquals("BODY_GENERATING", db.contentDiscoveryDao().byId(id)?.bodyStatus)
            assertEquals(ChatDerivativeEntity.STATE_GENERATING, db.chatDerivativeDao().byId(recordId)?.state)
        }
        assertFalse(settleCandidateBodyFailure(db, id, network, 2))
        assertEquals("BODY_FAILED", db.contentDiscoveryDao().byId(id)?.bodyStatus)
        val record = db.chatDerivativeDao().byId(recordId)!!
        assertEquals(ChatDerivativeEntity.STATE_FAILED, record.state)
        assertEquals("网络连接失败，请检查网络后重试", record.failureReason)
    }

    @Test fun manualRequestFailsImmediatelyAndKeepsRetryAvailable() = runBlocking {
        val id = candidate("INNER_VOICE")
        db.contentDiscoveryDao().markUserRequested(id)
        assertFalse(settleCandidateBodyFailure(db, id, SocketTimeoutException(), 0))
        assertEquals("BODY_FAILED", db.contentDiscoveryDao().byId(id)?.bodyStatus)
        assertEquals(1, db.contentDiscoveryDao().setBodyState(id, "BODY_FAILED", "BODY_QUEUED"))
    }

    @Test fun onlyTransientErrorsAreRetried() {
        val transient = listOf(IOException(), SocketTimeoutException(),
            IllegalStateException("LLM HTTP 408: timeout"), IllegalStateException("LLM HTTP 429: busy"),
            IllegalStateException("LLM HTTP 503: busy"))
        val permanent = listOf(DerivativeFailure.format(), DerivativeFailure.empty(),
            IllegalStateException("LLM HTTP 401: secret"), IllegalStateException("LLM HTTP 403: secret"),
            IllegalStateException("LLM HTTP 400: invalid"), IllegalStateException("unknown"))
        transient.forEach { assertTrue(shouldRetryCandidateBody(it, userRequested = false, runAttemptCount = 0)) }
        permanent.forEach { assertFalse(shouldRetryCandidateBody(it, userRequested = false, runAttemptCount = 0)) }
    }

    @Test fun lateFailureCannotOverwriteACompletedOrStaleCandidate() = runBlocking {
        val id = candidate("MOMENT")
        db.contentDiscoveryDao().setBodyState(id, "BODY_GENERATING", "READY")
        assertFalse(settleCandidateBodyFailure(db, id, IOException(), 0))
        assertEquals("READY", db.contentDiscoveryDao().byId(id)?.bodyStatus)
    }
}
