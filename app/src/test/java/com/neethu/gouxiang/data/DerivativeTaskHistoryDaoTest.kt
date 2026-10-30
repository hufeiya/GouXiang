package com.neethu.gouxiang.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 任务历史持久化行为(M0 收尾工作包 1):
 * - 历史行 = 任务 LEFT JOIN 结果(结果 ID 按 requestId 关联读出,不冗余);
 * - 失败分类列(failureKind)随状态一并落库;
 * - 取消是条件更新:只标 queued/running,终态行不被改写;
 * - 历史按创建时间倒序(最新在前),行数不因重复查询膨胀。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DerivativeTaskHistoryDaoTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), GouXiangDatabase::class.java,
    ).allowMainThreadQueries().build()

    @After fun close() = db.close()

    private fun task(workId: String, requestId: String, createdAt: Long, state: String = "queued") =
        DerivativeTaskRecord(
            workId = workId, requestId = requestId, kind = "MOMENT", source = "MANUAL",
            conversationId = 1, characterId = 1, state = state, failureReason = null,
            createdAt = createdAt, updatedAt = createdAt, finishedAt = null,
        )

    @Test fun historyJoinsResultIdsByRequestIdAndKeepsOrder() = runBlocking {
        db.derivativeTaskDao().insertIfMissing(task("w-old", "req-1", createdAt = 100))
        db.derivativeTaskDao().insertIfMissing(task("w-new", "req-2", createdAt = 200))
        db.derivativeResultDao().insert(DerivativeResultEntity("req-1", "MOMENT", 1, "7,9"))

        val rows = db.derivativeTaskDao().observeHistory(50).first()
        assertEquals(listOf("w-new", "w-old"), rows.map { it.workId })
        assertNull(rows.first { it.workId == "w-new" }.resultIds)
        assertEquals("7,9", rows.first { it.workId == "w-old" }.resultIds)
        // 重复查询是流读取,不是追加写入
        assertEquals(2, db.derivativeTaskDao().observeHistory(50).first().size)
    }

    @Test fun failureStateCarriesItsKind() = runBlocking {
        db.derivativeTaskDao().insertIfMissing(task("w1", "req-1", createdAt = 1))
        db.derivativeTaskDao().setStateWithKind("w1", "failed", "模型鉴权失败，请检查 API 配置", "AUTH", 5, 5)
        val row = db.derivativeTaskDao().observeHistory(10).first().single()
        assertEquals("failed", row.state)
        assertEquals("AUTH", row.failureKind)
        assertEquals("模型鉴权失败，请检查 API 配置", row.failureReason)
        db.derivativeTaskDao().setStateWithKind("w1", "cancelled", "任务已取消", "USER_CANCEL", 6, 6)
        assertEquals("USER_CANCEL", db.derivativeTaskDao().byId("w1")?.failureKind)
    }

    @Test fun cancelOnlyMarksActiveRecords() = runBlocking {
        db.derivativeTaskDao().insertIfMissing(task("w-active", "req-1", createdAt = 1))
        db.derivativeTaskDao().insertIfMissing(task("w-done", "req-2", createdAt = 2, state = "succeeded"))
        db.derivativeTaskDao().insertIfMissing(task("w-failed", "req-3", createdAt = 3, state = "failed"))

        assertEquals(1, db.derivativeTaskDao().cancelIfActive("w-active", "任务已取消", "USER_CANCEL", 9))
        assertEquals("cancelled", db.derivativeTaskDao().byId("w-active")?.state)
        assertEquals("USER_CANCEL", db.derivativeTaskDao().byId("w-active")?.failureKind)
        assertEquals(9L, db.derivativeTaskDao().byId("w-active")?.finishedAt)
        // 终态行不受取消影响;幂等重复取消返回 0
        assertEquals(0, db.derivativeTaskDao().cancelIfActive("w-active", "任务已取消", "USER_CANCEL", 10))
        assertEquals(0, db.derivativeTaskDao().cancelIfActive("w-done", "任务已取消", "USER_CANCEL", 10))
        assertEquals(0, db.derivativeTaskDao().cancelIfActive("w-failed", "任务已取消", "USER_CANCEL", 10))
        assertEquals("succeeded", db.derivativeTaskDao().byId("w-done")?.state)
        assertEquals("failed", db.derivativeTaskDao().byId("w-failed")?.state)
        assertTrue(db.derivativeTaskDao().byId("w-done")?.finishedAt == null)
    }

    @Test fun missingResultLeavesResultIdsNullWithoutBreakingRow() = runBlocking {
        db.derivativeTaskDao().insertIfMissing(task("w-only", "req-404", createdAt = 1))
        val row = db.derivativeTaskDao().observeHistory(10).first().single()
        assertEquals("w-only", row.workId)
        assertNull(row.resultIds)
        assertNull(row.failureKind)
    }
}
