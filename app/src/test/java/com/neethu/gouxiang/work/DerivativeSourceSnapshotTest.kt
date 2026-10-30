package com.neethu.gouxiang.work

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.neethu.gouxiang.data.ConversationEntity
import com.neethu.gouxiang.data.DerivativeResultEntity
import com.neethu.gouxiang.data.GouXiangDatabase
import com.neethu.gouxiang.data.MemoryConversationVersion
import com.neethu.gouxiang.data.MessageEntity
import com.neethu.gouxiang.data.MomentEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DerivativeSourceSnapshotTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), GouXiangDatabase::class.java,
    ).allowMainThreadQueries().build()

    @After fun close() = db.close()

    @Test
    fun rewindDuringGenerationRejectsStaleResultInItsCommitTransaction() = runBlocking {
        db.conversationDao().upsert(ConversationEntity(id = 1, characterId = 1))
        val messageId = db.messageDao().insert(MessageEntity(conversationId = 1, turnId = "old", role = "user", content = "old"))
        val source = captureDerivativeSource(db, 1, 1)
        assertEquals(messageId, source.firstMessageId)
        assertEquals(messageId, source.lastMessageId)

        // This models the version increment and message deletion in rewind's single transaction.
        db.withTransaction {
            db.memoryWindowDao().setVersion(MemoryConversationVersion(1, source.version + 1))
            db.messageDao().deleteFromMessage(1, messageId)
        }
        val attempt = runCatching {
            db.withTransaction {
                requireCurrentDerivativeSource(db, 1, source.version)
                val id = db.momentDao().insert(MomentEntity(characterId = 1, text = "stale"))
                db.derivativeResultDao().insert(DerivativeResultEntity("old", "MOMENT", 1, id.toString()))
            }
        }
        assertTrue(attempt.exceptionOrNull() is StaleDerivativeSourceException)
        assertTrue(db.momentDao().forCharacter(1).isEmpty())
        assertNull(db.derivativeResultDao().byRequestId("old"))

        val fresh = captureDerivativeSource(db, 1, 1)
        db.withTransaction {
            requireCurrentDerivativeSource(db, 1, fresh.version)
            val id = db.momentDao().insert(MomentEntity(characterId = 1, text = "fresh"))
            db.derivativeResultDao().insert(DerivativeResultEntity("fresh", "MOMENT", 1, id.toString()))
        }
        assertEquals(listOf("fresh"), db.momentDao().forCharacter(1).map { it.text })
    }
}
