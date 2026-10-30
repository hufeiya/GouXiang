package com.neethu.gouxiang.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The database must atomically bind each generated result to its stable request ID. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DerivativeResultPersistenceTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), GouXiangDatabase::class.java,
    ).allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    @Test
    fun duplicateRequestCannotCommitASecondMomentAndDistinctManualRequestsCan() = runBlocking {
        suspend fun commit(requestId: String, text: String): Long = db.withTransaction {
            val id = db.momentDao().insert(MomentEntity(characterId = 1, text = text))
            db.derivativeResultDao().insert(DerivativeResultEntity(requestId, "MOMENT", 1, id.toString()))
            id
        }

        val first = commit("manual-1", "first")
        assertTrue(runCatching { commit("manual-1", "retry") }.isFailure)
        assertEquals(listOf("first"), db.momentDao().forCharacter(1).map { it.text })
        assertEquals(first.toString(), db.derivativeResultDao().byRequestId("manual-1")?.resultIds)

        val second = commit("manual-2", "second")
        assertNotEquals(first, second)
        assertEquals(setOf("first", "second"), db.momentDao().forCharacter(1).map { it.text }.toSet())
        assertEquals(second.toString(), db.derivativeResultDao().byRequestId("manual-2")?.resultIds)
    }

    @Test
    fun duplicateRequestRollsBackAnEntireInnerVoiceBatch() = runBlocking {
        suspend fun commit(requestId: String, titles: List<String>): List<Long> = db.withTransaction {
            val ids = db.innerVoiceDao().insertAll(titles.map {
                InnerVoiceEntity(characterId = 1, title = it, echo = "内容")
            })
            db.derivativeResultDao().insert(DerivativeResultEntity(requestId, "INNER_VOICE", 1, ids.joinToString(",")))
            ids
        }

        val first = commit("manual-1", listOf("A", "B"))
        assertTrue(runCatching { commit("manual-1", listOf("retry A", "retry B")) }.isFailure)
        assertEquals(setOf("A", "B"), db.innerVoiceDao().forCharacter(1).map { it.title }.toSet())
        assertEquals(first.joinToString(","), db.derivativeResultDao().byRequestId("manual-1")?.resultIds)

        val second = commit("manual-2", listOf("C", "D"))
        assertEquals(4, db.innerVoiceDao().forCharacter(1).size)
        assertTrue(first.toSet().intersect(second.toSet()).isEmpty())
        assertEquals(second.joinToString(","), db.derivativeResultDao().byRequestId("manual-2")?.resultIds)
    }
}
