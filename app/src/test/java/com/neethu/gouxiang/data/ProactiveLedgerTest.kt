package com.neethu.gouxiang.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D 工作包 D1/D4:主动消息账本(会话级事务预约/状态机/未读已读/回溯清账/
 * 中断租约恢复)的 Room 行为单测(内存库,真实事务边界)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProactiveLedgerTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), GouXiangDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val ledger = ProactiveLedger(db)

    private var now = 1_800_000_000_000L
    private var convId = 0L
    private var charId = 0L

    @Before
    fun seed(): Unit = runBlocking {
        val dao = db.seedDao()
        val saved = dao.saveCharacterAtomic(
            character = CharacterEntity(
                name = "账本甲",
                profile = "设定",
                intro = "",
                exampleLines = "",
                openingLine = "嗨",
                appearance = "",
                appearanceStyle = "",
                createdAt = 1L,
            ),
            openingContent = "嗨",
            openingActions = emptyList(),
            openingAt = 1L,
            appearanceChanged = false,
        )
        charId = saved.characterId
        convId = saved.conversationId
    }

    @After fun close() = db.close()

    private suspend fun reserve(requestId: String = "req-$now"): ProactiveOutboxEntity? =
        ledger.reserve(convId, charId, "DAILY_SHARE", requestId, now, "2026-10-31", ProactiveOutboxServiceLease())

    /** 与服务层同款租约(10min)。 */
    private fun ProactiveOutboxServiceLease(): Long = 10 * 60_000L

    private fun userMessage(at: Long): MessageEntity = MessageEntity(
        conversationId = convId, turnId = "turn-u-$at", role = "user",
        content = "在吗", at = at,
    )

    private suspend fun insertBatch(turnId: String, n: Int, startAt: Long): List<Long> =
        (1..n).map { k ->
            db.messageDao().insert(
                MessageEntity(
                    conversationId = convId, turnId = turnId, role = "assistant",
                    kind = "text", content = "泡$k", at = startAt + k,
                ),
            )
        }

    // ── 预约(会话级事务门禁)──────────────────────────────────────

    @Test
    fun reserveCreatesPendingRowExactlyOncePerConversation() = runBlocking {
        val row = reserve()
        assertNotNull(row)
        assertEquals(ProactiveBatchState.PENDING.name, row!!.state)
        // 第二条入口同时预约:在途唯一性拦截
        assertNull(ledger.reserve(convId, charId, "CHECK_IN", "req-other", now, "2026-10-31", 600_000L))
        // 同 requestId 重复预约:唯一索引幂等,同样拒绝
        assertNull(ledger.reserve(convId, charId, "CHECK_IN", "req-$now", now, "2026-10-31", 600_000L))
    }

    @Test
    fun failedBatchReleasesReservationForRetry() = runBlocking {
        val row = reserve()!!
        ledger.markGenerating(row.requestId, now)
        assertTrue(ledger.markFailed(row.requestId, "网络失败", now))
        assertEquals(ProactiveBatchState.FAILED.name, db.proactiveDao().byRequestId(row.requestId)!!.state)
        // 失败释放会话 → 可再预约(重试路径)
        assertNotNull(ledger.reserve(convId, charId, "DAILY_SHARE", "req-retry", now, "2026-10-31", 600_000L))
    }

    @Test
    fun staleInFlightLeaseIsCancelledAndReReservable() = runBlocking {
        val row = reserve()!!
        // 租约内(10min):不动
        assertEquals(0, ledger.recoverStaleFor(convId, 600_000L, now + 60_000L))
        // 超租约:作废并放行新预约(进程中断恢复)
        assertEquals(1, ledger.recoverStaleFor(convId, 600_000L, now + 601_000L))
        assertEquals(ProactiveBatchState.CANCELLED.name, db.proactiveDao().byRequestId(row.requestId)!!.state)
        assertNotNull(ledger.reserve(convId, charId, "DAILY_SHARE", "req-after-stale", now, "2026-10-31", 600_000L))
    }

    // ── 收口(状态机 + 日计数 + 未读)──────────────────────────────

    @Test
    fun finalizeReadyUpdatesStateUnseenAndUnreadBadge() = runBlocking {
        val ids = insertBatch("proactive-b1", 3, startAt = now)
        val row = reserve()!!
        assertTrue(ledger.finalizeBatch(row.requestId, degraded = false, turnId = "proactive-b1", messageIds = ids, now = now, localDay = "2026-10-31"))
        val updated = db.proactiveDao().byRequestId(row.requestId)!!
        assertEquals(ProactiveBatchState.READY.name, updated.state)
        assertEquals(ids.joinToString(","), updated.messageIds)
        assertEquals(3, updated.count)
        val state = db.proactiveDao().stateFor(convId)!!
        assertEquals(3, state.unseenCount)
        assertEquals(1, state.dailyCount)
        assertEquals("2026-10-31", state.dailyStamp)
        assertEquals(now, state.lastProactiveAt)
        assertEquals(3, db.conversationDao().byId(convId)!!.unread)
    }

    @Test
    fun finalizeFallbackIsDistinguishableFromReady() = runBlocking {
        val ids = insertBatch("proactive-b2", 1, startAt = now)
        val row = reserve()!!
        assertTrue(ledger.finalizeBatch(row.requestId, degraded = true, turnId = "proactive-b2", messageIds = ids, now = now, localDay = "2026-10-31"))
        val updated = db.proactiveDao().byRequestId(row.requestId)!!
        assertEquals("模板降级必须与真实成功可区分", ProactiveBatchState.FALLBACK.name, updated.state)
        assertEquals(ProactiveBatchState.READY.name != updated.state, true)
    }

    @Test
    fun finalizeOnCancelledRowIsRejected() = runBlocking {
        val row = reserve()!!
        ledger.recoverStaleFor(convId, 0L, now + 1) // 直接作废
        val ids = insertBatch("proactive-b3", 2, startAt = now)
        assertFalse("已作废批次不得被晚到的生成结果复活", ledger.finalizeBatch(row.requestId, false, "proactive-b3", ids, now, "2026-10-31"))
        assertEquals(ProactiveBatchState.CANCELLED.name, db.proactiveDao().byRequestId(row.requestId)!!.state)
        assertEquals(0, db.conversationDao().byId(convId)!!.unread)
    }

    @Test
    fun failedBatchDoesNotCountDailyOrUnseen() = runBlocking {
        val row = reserve()!!
        assertTrue(ledger.markFailed(row.requestId, "网络失败", now))
        val state = db.proactiveDao().stateFor(convId)!!
        assertEquals(0, state.dailyCount)
        assertEquals(0, state.unseenCount)
    }

    // ── 消费(未读/已读)──────────────────────────────────────────

    @Test
    fun consumeConversationMarksSeenAndClearsUnread() = runBlocking {
        val ids = insertBatch("proactive-b4", 2, startAt = now)
        val row = reserve()!!
        ledger.finalizeBatch(row.requestId, false, "proactive-b4", ids, now, "2026-10-31")
        val consumed = ledger.consumeConversation(convId, now + 1)
        assertEquals(1, consumed.size)
        assertEquals("proactive-b4", consumed[0].turnId)
        assertEquals(0, db.proactiveDao().stateFor(convId)!!.unseenCount)
        assertEquals(0, db.conversationDao().byId(convId)!!.unread)
        assertEquals(1, db.proactiveDao().byRequestId(row.requestId)!!.seen)
    }

    @Test
    fun userActivityNoteAdvancesLedgerAndClearsOverride() = runBlocking {
        ledger.ensureState(convId, charId)
        ledger.debugBackdateActivity(convId, now - 3 * 3_600_000L)
        assertEquals(1, db.proactiveDao().stateFor(convId)!!.activityOverride)
        db.messageDao().insert(userMessage(now))
        ledger.noteUserActivity(convId, charId, now)
        val state = db.proactiveDao().stateFor(convId)!!
        assertEquals(now, state.lastUserActivityAt)
        assertEquals("真实发言解除调试覆盖", 0, state.activityOverride)
    }

    // ── 回溯清账(D4)──────────────────────────────────────────────

    @Test
    fun rewindCancelsBatchesWithDeletedMessagesAndRecounts() = runBlocking {
        val keepIds = insertBatch("proactive-keep", 2, startAt = now)
        val goneIds = insertBatch("proactive-gone", 3, startAt = now + 10)
        val r1 = reserve(requestId = "req-keep")!!
        ledger.finalizeBatch(r1.requestId, false, "proactive-keep", keepIds, now, "2026-10-31")
        val r2 = ledger.reserve(convId, charId, "CHECK_IN", "req-gone", now, "2026-10-31", 600_000L)!!
        ledger.finalizeBatch(r2.requestId, false, "proactive-gone", goneIds, now, "2026-10-31")
        assertEquals(5, db.proactiveDao().stateFor(convId)!!.unseenCount)
        // 回溯到 gone 批次首条:gone 批次整批作废,keep 批次(更早)保留
        val anchor = goneIds.first()
        db.messageDao().deleteFromMessage(convId, anchor)
        val cancelled = ledger.rewindFrom(convId, anchor)
        assertEquals(1, cancelled)
        assertEquals(ProactiveBatchState.READY.name, db.proactiveDao().byRequestId("req-keep")!!.state)
        assertEquals(ProactiveBatchState.CANCELLED.name, db.proactiveDao().byRequestId("req-gone")!!.state)
        assertEquals(2, db.proactiveDao().stateFor(convId)!!.unseenCount)
        assertEquals(2, db.conversationDao().byId(convId)!!.unread)
    }

    @Test
    fun rewindCancelsInFlightReservation() = runBlocking {
        val row = reserve()!!
        val n = ledger.rewindFrom(convId, 1L)
        assertEquals(1, n)
        assertEquals(ProactiveBatchState.CANCELLED.name, db.proactiveDao().byRequestId(row.requestId)!!.state)
        assertNull("作废后无在途行", db.proactiveDao().inFlightFor(convId))
    }

    // ── 全库租约恢复(扫描入口)────────────────────────────────────

    @Test
    fun recoverStaleAllCancelsAcrossConversations() = runBlocking {
        reserve()
        assertEquals(1, ledger.recoverStaleAll(600_000L, now + 601_000L))
        assertEquals(0, ledger.recoverStaleAll(600_000L, now + 602_000L))
    }
}
