package com.neethu.gouxiang.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImageRecoveryTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), GouXiangDatabase::class.java,
    ).allowMainThreadQueries().build()

    @After fun close() = db.close()

    private fun task(id: String, request: String, state: String) = DerivativeTaskRecord(
        workId = id, requestId = request, kind = "MOMENT", source = "MANUAL", conversationId = 1,
        characterId = 1, state = state, failureReason = null, createdAt = 0, updatedAt = 0, finishedAt = null,
    )

    @Test fun terminalWorkRestoresRetryWhileActiveAndCompletedImagesRemainUnchanged() = runBlocking {
        db.derivativeResultDao().insert(DerivativeResultEntity("cancelled", "MOMENT", 1, "1", imageStatus = "pending"))
        db.derivativeResultDao().insert(DerivativeResultEntity("active", "MOMENT", 1, "2", imageStatus = "pending"))
        db.derivativeResultDao().insert(DerivativeResultEntity("complete", "MOMENT", 1, "3", imageStatus = "complete"))
        db.derivativeTaskDao().insertIfMissing(task("w1", "cancelled", "cancelled"))
        db.derivativeTaskDao().insertIfMissing(task("w2", "active", "failed"))
        db.derivativeTaskDao().insertIfMissing(task("w3", "active", "running"))
        db.derivativeTaskDao().insertIfMissing(task("w4", "complete", "failed"))
        assertEquals(1, db.derivativeResultDao().recoverInterruptedImages())
        assertEquals("failed", db.derivativeResultDao().byRequestId("cancelled")?.imageStatus)
        assertEquals("pending", db.derivativeResultDao().byRequestId("active")?.imageStatus)
        assertEquals("complete", db.derivativeResultDao().byRequestId("complete")?.imageStatus)

        db.derivativeTaskDao().setState("w3", "failed", "stopped", 1, 1)
        assertEquals(1, db.derivativeResultDao().recoverInterruptedImages())
        assertEquals("failed", db.derivativeResultDao().byRequestId("active")?.imageStatus)
        assertEquals(0, db.derivativeResultDao().recoverInterruptedImages())
    }
}
