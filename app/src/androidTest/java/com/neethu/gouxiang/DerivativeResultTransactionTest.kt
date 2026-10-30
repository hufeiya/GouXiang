package com.neethu.gouxiang

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neethu.gouxiang.data.DerivativeResultEntity
import com.neethu.gouxiang.data.ConversationEntity
import com.neethu.gouxiang.data.MessageEntity
import com.neethu.gouxiang.data.MemoryConversationVersion
import com.neethu.gouxiang.work.captureDerivativeSource
import com.neethu.gouxiang.work.requireCurrentDerivativeSource
import com.neethu.gouxiang.work.StaleDerivativeSourceException
import com.neethu.gouxiang.data.GouXiangDatabase
import com.neethu.gouxiang.data.MomentEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DerivativeResultTransactionTest {
    @Test
    fun contentAndRequestAssociationCommitAndRollbackTogether() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), GouXiangDatabase::class.java,
        ).build()
        try {
            runCatching {
                db.withTransaction {
                    val id = db.momentDao().insert(MomentEntity(characterId = 1, text = "draft"))
                    db.derivativeResultDao().insert(DerivativeResultEntity("failed", "MOMENT", 1, id.toString()))
                    error("simulate interruption")
                }
            }
            assertNull(db.derivativeResultDao().byRequestId("failed"))
            assertEquals(0, db.momentDao().forCharacter(1).size)
            db.withTransaction {
                val id = db.momentDao().insert(MomentEntity(characterId = 1, text = "saved"))
                db.derivativeResultDao().insert(DerivativeResultEntity("saved", "MOMENT", 1, id.toString()))
            }
            assertEquals(1, db.momentDao().forCharacter(1).size)
            assertEquals(db.momentDao().forCharacter(1).single().id.toString(),
                db.derivativeResultDao().byRequestId("saved")?.resultIds)
        } finally {
            db.close()
        }
    }
    @Test
    fun rewindRejectsLateGeneratedResultButNewMessagesDoNotInvalidateSnapshot() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), GouXiangDatabase::class.java,
        ).build()
        try {
            val conversationId = db.conversationDao().upsert(ConversationEntity(characterId = 1))
            val first = db.messageDao().insert(MessageEntity(conversationId = conversationId, turnId = "a", role = "user", content = "hello"))
            val snapshot = captureDerivativeSource(db, conversationId, 1)
            assertEquals(first, snapshot.firstMessageId)
            assertEquals(first, snapshot.lastMessageId)
            db.messageDao().insert(MessageEntity(conversationId = conversationId, turnId = "b", role = "user", content = "later"))
            db.withTransaction { requireCurrentDerivativeSource(db, conversationId, snapshot.version) }
            db.memoryWindowDao().setVersion(MemoryConversationVersion(conversationId, snapshot.version + 1))
            val failure = runCatching {
                db.withTransaction {
                    requireCurrentDerivativeSource(db, conversationId, snapshot.version)
                    db.momentDao().insert(MomentEntity(characterId = 1, text = "obsolete"))
                }
            }.exceptionOrNull()
            assertTrue(failure is StaleDerivativeSourceException)
            assertTrue(db.momentDao().forCharacter(1).isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun rewindMarksOnlyResultsWhoseSourceOverlaps() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), GouXiangDatabase::class.java,
        ).build()
        try {
            val affected = db.momentDao().insert(MomentEntity(characterId = 1, text = "affected"))
            val older = db.momentDao().insert(MomentEntity(characterId = 1, text = "older"))
            val other = db.momentDao().insert(MomentEntity(characterId = 1, text = "other conversation"))
            db.derivativeResultDao().insert(DerivativeResultEntity("a", "MOMENT", 1, affected.toString(), sourceConversationId = 1, sourceEndMessageId = 20))
            db.derivativeResultDao().insert(DerivativeResultEntity("b", "MOMENT", 1, older.toString(), sourceConversationId = 1, sourceEndMessageId = 9))
            db.derivativeResultDao().insert(DerivativeResultEntity("c", "MOMENT", 1, other.toString(), sourceConversationId = 2, sourceEndMessageId = 20))
            db.derivativeResultDao().markMomentsStale(1, 10)
            assertTrue(db.momentDao().byId(affected)!!.isStale)
            assertTrue(!db.momentDao().byId(older)!!.isStale)
            assertTrue(!db.momentDao().byId(other)!!.isStale)
        } finally {
            db.close()
        }
    }

}
