package com.neethu.gouxiang

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neethu.gouxiang.data.DerivativeTaskRecord
import com.neethu.gouxiang.data.GouXiangDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DerivativeTaskHistoryTest {
    @Test
    fun persistedStateTracksFailureAndNewManualRetry() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), GouXiangDatabase::class.java,
        ).build()
        try {
            val dao = db.derivativeTaskDao()
            dao.insertIfMissing(DerivativeTaskRecord("worker1", "daily-key", "MOMENT", "AUTOMATIC", 2, 1, "queued", null, 1, 1, null))
            dao.setState("worker1", "running", null, 2, null)
            dao.setState("worker1", "failed", "生成失败，请检查网络与模型配置后重试", 3, 3)
            val failed = dao.observeLatest(1, "MOMENT").first()!!
            assertEquals("failed", failed.state)
            assertEquals("daily-key", failed.requestId)
            assertEquals(3L, failed.finishedAt)
            assertNull(dao.observeLatest(2, "MOMENT").first())
            dao.insertIfMissing(DerivativeTaskRecord("worker2", "new-request", "MOMENT", "MANUAL", 2, 1, "queued", null, 4, 4, null))
            assertEquals("MANUAL", dao.observeLatest(1, "MOMENT").first()!!.source)
            dao.setState("worker2", "succeeded", null, 5, 5)
            assertEquals("succeeded", dao.byId("worker2")!!.state)
            assertEquals("failed", dao.byId("worker1")!!.state)
        } finally {
            db.close()
        }
    }
}
