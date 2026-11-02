package com.neethu.gouxiang.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
class ContentDiscoveryDaoTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), GouXiangDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val dao = db.contentDiscoveryDao()

    @After fun close() = db.close()

    private fun turn(conversationId: Long = 1, userId: Long = 10, reply: String = "r1") =
        ContentTurnEntity(conversationId = conversationId, characterId = 2, replyTurnId = reply,
            userMessageId = userId, endMessageId = userId + 1, sourceVersion = 0,
            user = "你好", assistant = "你好呀", completedAt = 100)

    private fun candidate(key: String = "conversation-1-segment-1", end: Long = 20) =
        ContentCandidateEntity(conversationId = 1, characterId = 2, kind = "HANDBOOK", title = "约定",
            startTurnId = 1, endTurnId = 5, startMessageId = 10, endMessageId = end,
            sourceVersion = 0, createdAt = 100, candidateKey = key, autoEligible = true)

    @Test fun `completed turn is deduplicated by user and reply within conversation`() = runBlocking {
        val first = dao.insertTurn(turn())
        assertTrue(first > 0)
        assertEquals(-1L, dao.insertTurn(turn()))
        assertEquals(-1L, dao.insertTurn(turn(reply = "retry")))
        assertEquals(-1L, dao.insertTurn(turn(userId = 12)))
        val second = dao.insertTurn(turn(userId = 12, reply = "r2"))
        val other = dao.insertTurn(turn(conversationId = 3))
        assertTrue(second > 0 && other > 0)
        assertEquals(listOf(first, second), dao.turnsAfter(1, 0).map { it.id })
        assertEquals(listOf(second), dao.turnsAfter(1, first).map { it.id })
        assertEquals(listOf(other), dao.turnsAfter(3, 0).map { it.id })
    }

    @Test fun `candidate key and conditional queue transition reject duplicate claims`() = runBlocking {
        val id = dao.insertCandidate(candidate())
        assertTrue(id > 0)
        assertEquals(-1L, dao.insertCandidate(candidate()))
        val claims = (1..2).map { async(Dispatchers.Default) { dao.setBodyState(id, "TITLE_READY", "BODY_QUEUED") } }.awaitAll()
        assertEquals(1, claims.sum())
        assertEquals("BODY_QUEUED", dao.byId(id)?.bodyStatus)
    }

    @Test fun `user request prevents automatic deferral after switch is turned off`() = runBlocking {
        val id = dao.insertCandidate(candidate())
        dao.setBodyState(id, "TITLE_READY", "BODY_QUEUED")
        // A click wins before the worker checks the disabled switch.
        assertEquals(1, dao.markUserRequested(id))
        assertEquals(0, dao.deferAutomatic(id, "BODY_QUEUED"))
        assertTrue(dao.byId(id)!!.userRequested)
        assertEquals("BODY_QUEUED", dao.byId(id)?.bodyStatus)

        val deferred = dao.insertCandidate(candidate(key = "other"))
        dao.setBodyState(deferred, "TITLE_READY", "BODY_QUEUED")
        assertEquals(1, dao.deferAutomatic(deferred, "BODY_QUEUED"))
        assertFalse(dao.byId(deferred)!!.autoEligible)
        assertEquals("TITLE_READY", dao.byId(deferred)?.bodyStatus)
        // A later explicit click can still queue the same title.
        dao.markUserRequested(deferred)
        assertEquals(1, dao.setBodyState(deferred, "TITLE_READY", "BODY_QUEUED"))
    }

    @Test fun `rewind invalidates unfinished candidates and rejects late results`() = runBlocking {
        val stale = dao.insertCandidate(candidate())
        val unaffected = dao.insertCandidate(candidate(key = "unaffected", end = 12))
        dao.setBodyState(stale, "TITLE_READY", "BODY_GENERATING")
        dao.invalidateFrom(1, 15)
        assertEquals("STALE", dao.byId(stale)?.bodyStatus)
        assertEquals("DISMISSED", dao.byId(stale)?.reminderStatus)
        assertEquals(0, dao.setResult(stale, "READY", 123, "late"))
        assertEquals(0, dao.setImageStatus(stale, "READY"))
        assertEquals(0, dao.markUserRequested(stale))
        assertEquals("TITLE_READY", dao.byId(unaffected)?.bodyStatus)
        assertEquals(1, dao.unseenCount(1))
    }

    @Test fun `per-kind counts ignore stale results and isolate characters`() = runBlocking {
        dao.insertCandidate(candidate(key = "h1"))
        dao.insertCandidate(candidate(key = "h2").copy(bodyStatus = "STALE"))
        dao.insertCandidate(candidate(key = "h3").copy(sourceStale = true))
        dao.insertCandidate(candidate(key = "other-kind").copy(kind = "NOVEL"))
        dao.insertCandidate(candidate(key = "other-character").copy(characterId = 3))
        assertEquals(1, dao.countForKind(2, "HANDBOOK"))
        assertEquals(1, dao.countForKind(2, "NOVEL"))
        assertEquals(1, dao.countForKind(3, "HANDBOOK"))
        assertEquals(0, dao.countForKind(2, "INNER_VOICE"))
    }
}
