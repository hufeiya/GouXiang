package com.neethu.gouxiang.proactive

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.neethu.gouxiang.chat.BurstParams
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.GouXiangDatabase
import com.neethu.gouxiang.data.MessageEntity
import com.neethu.gouxiang.data.ProactiveBatchState
import com.neethu.gouxiang.data.ProactiveLedger
import com.neethu.gouxiang.protocol.ProactiveMessages
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D 工作包 D2/D3/D4:主动消息出站服务编排单测(内存 Room + 假生成器):
 * 开关门禁、三态区分、失败可重试不静默记成功、通知按会话实际计数、
 * 进页消费、最小间隔拦截、未读积压不重复轰炸。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProactiveOutboxServiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(
        context, GouXiangDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val ledger = ProactiveLedger(db)

    private var now = 1_800_000_000_000L
    private var switchOn = true
    private var convId = 0L
    private var charId = 0L
    private val notifications = mutableListOf<ProactiveOutboxService.Notification>()
    private val requests = mutableListOf<ProactiveOutboxService.GenerationRequest>()

    /** 假生成器:按 [nextResult] 出牌;真实落库(写 messages 表)模拟 Ready/Fallback。 */
    private var nextResult: suspend (ProactiveOutboxService.GenerationRequest) -> ProactiveOutboxService.GenerationResult = {
        fakeReady(it, degraded = false)
    }

    private suspend fun fakeReady(
        request: ProactiveOutboxService.GenerationRequest,
        degraded: Boolean,
        n: Int = 2,
    ): ProactiveOutboxService.GenerationResult {
        val turnId = "proactive-fake-${request.requestId}"
        val ids = (1..n).map { k ->
            db.messageDao().insert(
                MessageEntity(
                    conversationId = request.conversationId,
                    turnId = turnId,
                    role = "assistant",
                    kind = "text",
                    content = "泡$k",
                    at = now + k,
                ),
            )
        }
        val messages = ids.mapNotNull { db.messageDao().byId(it) }
        return if (degraded) ProactiveOutboxService.GenerationResult.Fallback(turnId, messages, "模板降级")
        else ProactiveOutboxService.GenerationResult.Ready(turnId, messages)
    }

    private fun service(): ProactiveOutboxService = ProactiveOutboxService(
        context = context,
        db = db,
        ledger = ledger,
        generate = { request ->
            requests += request
            nextResult(request)
        },
        notify = { notifications += it },
        switch = { switchOn },
        clock = { now },
        localDay = { "2026-10-31" },
        localHour = { 14 },
    )

    @Before
    fun seed(): Unit = runBlocking {
        val saved = db.seedDao().saveCharacterAtomic(
            character = CharacterEntity(
                name = "非小唐",
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

    // ── 开关门禁(验收 #1)────────────────────────────────────────

    @Test
    fun switchOffSkipsScanAndEntryGeneration() = runBlocking {
        switchOn = false
        val scan = service().scanOnce()
        assertEquals(0, scan.reports.size)
        service().onConversationOpened(convId, generateIfAllowed = true)
        assertEquals(0, requests.size)
        assertNull(db.proactiveDao().inFlightFor(convId))
        assertEquals(0, db.proactiveDao().forConversation(convId).size)
    }

    @Test
    fun scanOnceGeneratesPerConversationWithDirectedNotifications() = runBlocking {
        // 第二个角色/会话(名字不同,验证通知不串会话不串名)
        val saved2 = db.seedDao().saveCharacterAtomic(
            character = CharacterEntity(
                name = "另一个人",
                profile = "设定",
                intro = "",
                exampleLines = "",
                openingLine = "嗨",
                appearance = "",
                appearanceStyle = "",
                createdAt = 2L,
            ),
            openingContent = "嗨",
            openingActions = emptyList(),
            openingAt = 2L,
            appearanceChanged = false,
        )
        val report = service().scanOnce()
        // 每个角色建了 im+phone 两条会话(种子原子保存),共 4 条,全部首开命中
        assertEquals(4, report.generated.size)
        assertEquals(4, notifications.size)
        assertEquals(4, notifications.map { it.conversationId }.toSet().size)
        assertEquals("通知不串名", setOf("非小唐", "另一个人"), notifications.map { it.characterName }.toSet())
        assertEquals(2, db.proactiveDao().forConversation(convId).single().count)
    }

    // ── 真实成功(D3/验收 #2)─────────────────────────────────────

    @Test
    fun backgroundReadyBatchPersistsNotifiesWithActualCountAndMarksNotified() = runBlocking {
        val report = service().autoGenerate(convId, background = true)
        val generated = report as ProactiveOutboxService.Report.Generated
        assertEquals(ProactiveMessages.Kind.FIRST_HELLO, generated.kind)
        assertEquals(false, generated.degraded)
        assertEquals(2, generated.count)
        // 通知:实际新消息数 + 会话定向
        assertEquals(1, notifications.size)
        assertEquals(convId, notifications[0].conversationId)
        assertEquals("非小唐", notifications[0].characterName)
        assertEquals(2, notifications[0].count)
        // 账本:READY、notified=1、未读=2
        val row = db.proactiveDao().forConversation(convId).single()
        assertEquals(ProactiveBatchState.READY.name, row.state)
        assertEquals(1, row.notified)
        assertEquals(2, db.proactiveDao().stateFor(convId)!!.unseenCount)
        assertEquals(2, db.conversationDao().byId(convId)!!.unread)
        // 批次上限进入生成请求(回归批次上限钳制连发)
        assertEquals(3, requests.single().batchMax)
    }

    @Test
    fun secondFireWithinMinIntervalIsBlockedNotDuplicated() = runBlocking {
        service().autoGenerate(convId, background = true)
        // 先消费未读(进页不补发),让「未读积压」门不拦,单独验证最小间隔门
        service().onConversationOpened(convId, generateIfAllowed = false)
        val second = service().autoGenerate(convId, background = true)
        assertTrue("第二发应被最小间隔拦下: $second", second is ProactiveOutboxService.Report.Blocked)
        assertTrue((second as ProactiveOutboxService.Report.Blocked).reason.contains("最小间隔"))
        assertEquals(1, db.proactiveDao().forConversation(convId).size)
    }

    @Test
    fun unseenBacklogBlocksNextBatchEvenAfterIntervalPasses() = runBlocking {
        service().autoGenerate(convId, background = true)
        now += 3 * 3_600_000L // 越过最小间隔
        val blocked = service().autoGenerate(convId, background = true)
        assertTrue((blocked as ProactiveOutboxService.Report.Blocked).reason.contains("未读"))
        assertEquals("未读积压期间不堆新批次", 1, db.proactiveDao().forConversation(convId).size)
    }

    // ── 失败可重试(D3:不静默记成功)─────────────────────────────

    @Test
    fun generationFailureMarksFailedWithoutNotificationAndAllowsRetry() = runBlocking {
        nextResult = { throw java.io.IOException("network down") }
        val report = service().autoGenerate(convId, background = true)
        assertTrue(report is ProactiveOutboxService.Report.Failed)
        val row = db.proactiveDao().forConversation(convId).single()
        assertEquals(ProactiveBatchState.FAILED.name, row.state)
        assertTrue(row.failureReason.contains("network down"))
        assertEquals("失败不投通知", 0, notifications.size)
        assertEquals("失败不记未读", 0, db.proactiveDao().stateFor(convId)!!.unseenCount)
        // 重试:新一轮预约成功并可出真实结果
        nextResult = { fakeReady(it, degraded = false) }
        val retry = service().autoGenerate(convId, background = true)
        assertTrue(retry is ProactiveOutboxService.Report.Generated)
        assertEquals(2, db.proactiveDao().forConversation(convId).size)
    }

    @Test
    fun degradedBatchIsRecordedAsFallbackNotSuccess() = runBlocking {
        nextResult = { fakeReady(it, degraded = true, n = 1) }
        val report = service().autoGenerate(convId, background = true)
        assertTrue((report as ProactiveOutboxService.Report.Generated).degraded)
        assertEquals(ProactiveBatchState.FALLBACK.name, db.proactiveDao().forConversation(convId).single().state)
        // 降级消息已落库:照常通知(数量=实际落库条数),但不标 READY
        assertEquals(1, notifications.single().count)
    }

    // ── 进页消费(D4)─────────────────────────────────────────────

    @Test
    fun entryConsumesUnreadBatchesAndEntryGenerationIsSeenSilently() = runBlocking {
        // 后台先产一批(未读)
        service().autoGenerate(convId, background = true)
        now += 30_000
        // 进页:消费未读 + 当场补一批(generateIfAllowed)
        val consumed = service().onConversationOpened(convId, generateIfAllowed = true)
        assertEquals(1, consumed.size)
        assertEquals(2, consumed[0].count)
        // 消费后未读清零;当场补的一批因最小间隔被拦(上一批刚完成)
        assertEquals(0, db.proactiveDao().stateFor(convId)!!.unseenCount)
        assertEquals(0, db.conversationDao().byId(convId)!!.unread)
        assertTrue(db.proactiveDao().forConversation(convId).all { it.seen == 1 })
    }

    @Test
    fun entryGenerationPostsNoNotification() = runBlocking {
        // 无未读、门禁通过(从未发过 → FIRST_HELLO):进页当场生成一批
        val consumed = service().onConversationOpened(convId, generateIfAllowed = true)
        assertEquals(0, consumed.size)
        assertEquals(1, requests.size)
        assertEquals("进页生成不投通知", 0, notifications.size)
        assertEquals("当场生成即刻已读", 1, db.proactiveDao().forConversation(convId).single().seen)
    }

    // ── 预约幂等门(D2)───────────────────────────────────────────

    @Test
    fun inFlightReservationBlocksSecondEntry() = runBlocking {
        // 直接制造在途预约(模拟另一入口正在生成)
        val reserved = ledger.reserve(convId, charId, "DAILY_SHARE", "req-held", now, "2026-10-31", 600_000L)
        assertNotNull(reserved)
        val report = service().autoGenerate(convId, background = true)
        assertTrue(report is ProactiveOutboxService.Report.Skipped)
        assertEquals("已有在途批次", 0, requests.size)
    }

    // ── 安静时段(服务级接线)────────────────────────────────────

    @Test
    fun quietHourBlocksScan() = runBlocking {
        val quiet = ProactiveOutboxService(
            context = context,
            db = db,
            ledger = ledger,
            generate = { requests += it; fakeReady(it, degraded = false) },
            notify = { notifications += it },
            switch = { switchOn },
            clock = { now },
            localDay = { "2026-10-31" },
            localHour = { 3 },
        )
        val report = quiet.autoGenerate(convId, background = true)
        assertTrue((report as ProactiveOutboxService.Report.Blocked).reason.contains("安静"))
        assertEquals(0, requests.size)
    }

    // ── 批次上限钳制(纯函数)────────────────────────────────────

    @Test
    fun burstClampKeepsBatchMax() {
        val clamped = ProactiveGeneration.clampBurst(BurstParams(2, 4, 30), 3)
        assertEquals(3, clamped.maxBubbles)
        assertEquals(2, clamped.minBubbles)
        assertEquals(30, clamped.maxBubbleChars)
        val tight = ProactiveGeneration.clampBurst(BurstParams(3, 3, 30), 2)
        assertEquals(2, tight.maxBubbles)
        assertEquals("min 不得超 max", 2, tight.minBubbles)
    }
}
