package com.neethu.gouxiang.sidestory

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.neethu.gouxiang.data.GouXiangDatabase
import com.neethu.gouxiang.data.SidestoryRecordEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F1 统一内容记录的状态机单测(Room in-memory):条件更新只在 generating 生效
 * (重复收口/迟到旧任务不覆盖终态)、失败重试、删除、回溯失效、列表倒序。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SidestoryRecordDaoTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), GouXiangDatabase::class.java,
    ).allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    private fun record(
        id: Long = 0,
        state: String = SidestoryRecordEntity.STATE_GENERATING,
        title: String = "",
        endMessageId: Long? = null,
        isStale: Boolean = false,
    ) = SidestoryRecordEntity(
        id = id,
        kind = SidestoryRecordEntity.KIND_SIDESTORY,
        templateKey = "Drunk",
        templateVersion = 1,
        title = title,
        characterId = 1,
        conversationId = 2,
        sourceStartMessageId = 1,
        sourceEndMessageId = endMessageId,
        sourceVersion = 1,
        requestId = "req-$id",
        state = state,
        isStale = isStale,
        createdAt = 100 + id,
        updatedAt = 100 + id,
    )

    @Test
    fun `success commit is idempotent and never overwrites a terminal state`() = runBlocking {
        val id = db.sidestoryRecordDao().insert(record())
        val now = 500L
        assertEquals(1, db.sidestoryRecordDao().markSucceededIfGenerating(id, "第一次", "{\"pages\":[]}", now))
        // 重复收口(迟到旧任务)不再改写标题/正文
        assertEquals(0, db.sidestoryRecordDao().markSucceededIfGenerating(id, "第二次", "{\"pages\":[]}", now + 1))
        val row = db.sidestoryRecordDao().byId(id)!!
        assertEquals("第一次", row.title)
        assertEquals(SidestoryRecordEntity.STATE_SUCCEEDED, row.state)
        assertEquals(now, row.updatedAt)
    }

    @Test
    fun `failed marking only touches generating rows and retry reopens`() = runBlocking {
        val id = db.sidestoryRecordDao().insert(record())
        assertTrue(db.sidestoryRecordDao().markFailedIfGenerating(id, "网络失败", 500L) > 0)
        // 终态 failed 不可被成功/再次失败改写(Worker 重复收口安全)
        assertEquals(0, db.sidestoryRecordDao().markSucceededIfGenerating(id, "迟到", "{}", 501L))
        assertEquals(0, db.sidestoryRecordDao().markFailedIfGenerating(id, "二次失败", 502L))
        // 重试:failed → generating,清失败原因
        assertEquals(1, db.sidestoryRecordDao().markGeneratingForRetry(id, 600L))
        val reopened = db.sidestoryRecordDao().byId(id)!!
        assertEquals(SidestoryRecordEntity.STATE_GENERATING, reopened.state)
        assertEquals("", reopened.failureReason)
        assertEquals(1, db.sidestoryRecordDao().markSucceededIfGenerating(id, "重试成功", "{}", 601L))
    }

    @Test
    fun `list is newest first and delete removes the row`() = runBlocking {
        val older = db.sidestoryRecordDao().insert(record(id = 11)) // createdAt=100+id 递增
        val newer = db.sidestoryRecordDao().insert(record(id = 22))
        assertEquals(listOf(newer, older), db.sidestoryRecordDao().observeForConversation(2).first().map { it.id })
        assertEquals(1, db.sidestoryRecordDao().deleteById(older))
        assertEquals(listOf(newer), db.sidestoryRecordDao().observeForConversation(2).first().map { it.id })
    }

    @Test
    fun `rewind marks sourced rows stale without deleting them`() = runBlocking {
        val hit = db.sidestoryRecordDao().insert(record(endMessageId = 9))
        val miss = db.sidestoryRecordDao().insert(record(endMessageId = 3))
        assertEquals(1, db.sidestoryRecordDao().markStaleFrom(conversationId = 2, messageId = 5))
        assertTrue(db.sidestoryRecordDao().byId(hit)!!.isStale)
        assertTrue(!db.sidestoryRecordDao().byId(miss)!!.isStale)
        // 幂等:重复标记不再变化
        assertEquals(0, db.sidestoryRecordDao().markStaleFrom(conversationId = 2, messageId = 5))
        assertEquals(2, db.sidestoryRecordDao().forConversation(2).size)
    }

    @Test
    fun `image writeback requires succeeded state`() = runBlocking {
        val id = db.sidestoryRecordDao().insert(record())
        // generating 行拒绝配图状态回写(配图只跟随已收口的文字成品)
        assertEquals(0, db.sidestoryRecordDao().setImageStatus(id, SidestoryRecordEntity.IMAGE_COMPLETE, 500L))
        db.sidestoryRecordDao().markSucceededIfGenerating(id, "标题", "{}", 501L)
        assertEquals(1, db.sidestoryRecordDao().setImageStatus(id, SidestoryRecordEntity.IMAGE_FAILED, 502L))
        db.sidestoryRecordDao().setImageFile(id, "/tmp/cover.jpg", 503L)
        val row = db.sidestoryRecordDao().byId(id)!!
        assertEquals(SidestoryRecordEntity.IMAGE_COMPLETE, row.imageStatus)
        assertEquals("/tmp/cover.jpg", row.imageFile)
    }
}
