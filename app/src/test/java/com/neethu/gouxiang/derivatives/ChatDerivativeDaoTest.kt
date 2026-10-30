package com.neethu.gouxiang.derivatives

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.neethu.gouxiang.data.ChatDerivativeEntity
import com.neethu.gouxiang.data.GouXiangDatabase
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
 * G1-G3 记录状态机单测(Room in-memory):条件更新只在 generating 生效
 * (重复收口/迟到旧任务不覆盖终态)、失败重试、删除、回溯失效、按类型计数、
 * 列表倒序。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatDerivativeDaoTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), GouXiangDatabase::class.java,
    ).allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    private fun record(
        id: Long = 0,
        kind: String = ChatDerivativeEntity.KIND_HANDBOOK,
        state: String = ChatDerivativeEntity.STATE_GENERATING,
        endMessageId: Long? = null,
        isStale: Boolean = false,
    ) = ChatDerivativeEntity(
        id = id,
        kind = kind,
        characterId = 1,
        conversationId = 2,
        optionJson = "{}",
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
        val id = db.chatDerivativeDao().insert(record())
        assertEquals(1, db.chatDerivativeDao().markSucceededIfGenerating(id, "第一次", "{}", 500L))
        // 重复收口(迟到旧任务)不再改写标题/正文
        assertEquals(0, db.chatDerivativeDao().markSucceededIfGenerating(id, "第二次", "{}", 501L))
        val row = db.chatDerivativeDao().byId(id)!!
        assertEquals("第一次", row.title)
        assertEquals(ChatDerivativeEntity.STATE_SUCCEEDED, row.state)
        assertEquals(500L, row.updatedAt)
    }

    @Test
    fun `failed marking only touches generating rows and retry reopens`() = runBlocking {
        val id = db.chatDerivativeDao().insert(record())
        assertTrue(db.chatDerivativeDao().markFailedIfGenerating(id, "网络失败", 500L) > 0)
        // 终态 failed 不可被成功/再次失败改写(Worker 重复收口安全)
        assertEquals(0, db.chatDerivativeDao().markSucceededIfGenerating(id, "迟到", "{}", 501L))
        assertEquals(0, db.chatDerivativeDao().markFailedIfGenerating(id, "二次失败", 502L))
        // 重试:failed → generating,清失败原因
        assertEquals(1, db.chatDerivativeDao().markGeneratingForRetry(id, 600L))
        val reopened = db.chatDerivativeDao().byId(id)!!
        assertEquals(ChatDerivativeEntity.STATE_GENERATING, reopened.state)
        assertEquals("", reopened.failureReason)
        assertEquals(1, db.chatDerivativeDao().markSucceededIfGenerating(id, "重试成功", "{}", 601L))
    }

    @Test
    fun `list is newest first filtered rows and delete removes the row`() = runBlocking {
        val older = db.chatDerivativeDao().insert(record(id = 11))
        val newer = db.chatDerivativeDao().insert(record(id = 22, kind = ChatDerivativeEntity.KIND_PHOTO))
        assertEquals(listOf(newer, older), db.chatDerivativeDao().observeForConversation(2).first().map { it.id })
        // 按类型计数(角色主页尾注)
        assertEquals(1, db.chatDerivativeDao().countForKind(1, ChatDerivativeEntity.KIND_HANDBOOK))
        assertEquals(1, db.chatDerivativeDao().countForKind(1, ChatDerivativeEntity.KIND_PHOTO))
        assertEquals(0, db.chatDerivativeDao().countForKind(1, ChatDerivativeEntity.KIND_NOVEL))
        assertEquals(1, db.chatDerivativeDao().deleteById(older))
        assertEquals(listOf(newer), db.chatDerivativeDao().observeForConversation(2).first().map { it.id })
    }

    @Test
    fun `rewind marks sourced rows stale without deleting them`() = runBlocking {
        val hit = db.chatDerivativeDao().insert(record(endMessageId = 9))
        val miss = db.chatDerivativeDao().insert(record(endMessageId = 3))
        assertEquals(1, db.chatDerivativeDao().markStaleFrom(conversationId = 2, messageId = 5))
        assertTrue(db.chatDerivativeDao().byId(hit)!!.isStale)
        assertTrue(!db.chatDerivativeDao().byId(miss)!!.isStale)
        // 幂等:重复标记不再变化
        assertEquals(0, db.chatDerivativeDao().markStaleFrom(conversationId = 2, messageId = 5))
        assertEquals(2, db.chatDerivativeDao().forConversation(2).size)
    }

    @Test
    fun `image writeback requires succeeded state`() = runBlocking {
        val id = db.chatDerivativeDao().insert(record())
        // generating 行拒绝配图状态回写(配图只跟随已收口的文字成品)
        assertEquals(0, db.chatDerivativeDao().setImageStatus(id, ChatDerivativeEntity.IMAGE_COMPLETE, 500L))
        db.chatDerivativeDao().markSucceededIfGenerating(id, "标题", "{}", 501L)
        assertEquals(1, db.chatDerivativeDao().setImageStatus(id, ChatDerivativeEntity.IMAGE_FAILED, 502L))
        db.chatDerivativeDao().setImageFile(id, "/tmp/cover.jpg", 503L)
        val row = db.chatDerivativeDao().byId(id)!!
        assertEquals(ChatDerivativeEntity.IMAGE_COMPLETE, row.imageStatus)
        assertEquals("/tmp/cover.jpg", row.imageFile)
    }
}
