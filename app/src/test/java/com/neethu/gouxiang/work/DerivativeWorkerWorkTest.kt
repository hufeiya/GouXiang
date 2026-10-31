package com.neethu.gouxiang.work

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.neethu.gouxiang.ai.GenerationPrefs
import com.neethu.gouxiang.chat.DerivativeKind
import com.neethu.gouxiang.chat.DerivativeSource
import com.neethu.gouxiang.chat.DerivativeTask
import com.neethu.gouxiang.data.DerivativeTaskRecord
import com.neethu.gouxiang.data.GouXiangRepo
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
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
 * 任务历史 × WorkManager 测试环境(M0 收尾工作包 1):SynchronousExecutor +
 * 可控时钟([DerivativeClock])下验证 取消/重试/进程恢复不重复——
 * - 手动失败落 failed + 失败分类(执行器不可达会话 → 未知类,无假成功);
 * - 自动任务失败先回 queued 重试,attempt 用尽才终态;开关关掉则 skipped;
 * - 任务页取消只影响 queued/running 行,分类记「用户取消」;
 * - 进程恢复对账把孤儿 running 标 failed,不产生重复行;
 * - 在队重复入队被 KEEP 去重不留幽灵行;历史重试沿用原 requestId,不产生第二条结果。
 *
 * 测试应用用裸 Application:生产 GouXiangApp.onCreate 会在测试初始化前触达
 * WorkManager(默认配置先到,TestInitHelper 无法再接管)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = DerivativeWorkerWorkTest.NoopApplication::class)
class DerivativeWorkerWorkTest {
    class NoopApplication : Application()

    private val context: Context = ApplicationProvider.getApplicationContext()

    companion object {
        /** 可控时钟固定值(早于真实时间,便于断言时间列确为注入值)。 */
        private const val CLOCK_MS = 1_770_000_000_000L
    }

    @Before
    fun setUp() {
        // WorkManager 测试环境(2.9.1:配置携带 SynchronousExecutor,任务同步跑完)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            androidx.work.Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        DerivativeClock.nowMillis = { CLOCK_MS }
    }

    @After
    fun tearDown() {
        DerivativeClock.nowMillis = { System.currentTimeMillis() }
    }

    private val dao get() = GouXiangRepo.get(context).db.derivativeTaskDao()

    private suspend fun history() = dao.observeHistory(200).first()

    private suspend fun rowsForConversation(conversationId: Long) =
        history().filter { it.conversationId == conversationId }

    private suspend fun awaitUntil(timeoutMs: Long = 20_000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("condition not met within ${timeoutMs}ms")
            kotlinx.coroutines.delay(50)
        }
    }

    /**
     * 测试环境的调度驱动:网络约束(NetworkType.CONNECTED)在 Robolectric 里不
     * 自动满足,入队后用 TestDriver 把该唯一名的未完成任务标记约束达成 →
     * SynchronousExecutor 立即同步执行(生产上由真实网络状态驱动)。
     */
    private fun drivePendingWork(uniqueName: String) {
        val driver = WorkManagerTestInitHelper.getTestDriver(context) ?: return
        val manager = WorkManager.getInstance(context)
        manager.getWorkInfosForUniqueWork(uniqueName).get()
            .filter { !it.state.isFinished }
            .forEach { runCatching { driver.setAllConstraintsMet(it.id) } }
    }

    @Test
    fun manualFailureRecordsClassifiedReasonWithControlledClock() = runBlocking {
        val conv = 424_101L
        DerivativeWorker.enqueue(context, DerivativeTask(DerivativeKind.MOMENT, conv, DerivativeSource.MANUAL))
        drivePendingWork(DerivativeTask.uniqueName(DerivativeTask(DerivativeKind.MOMENT, conv)))
        awaitUntil { rowsForConversation(conv).firstOrNull()?.state == "failed" }
        val row = rowsForConversation(conv).single()
        // 会话不存在 → 执行器显式失败,不假成功;时间列为注入时钟
        assertEquals("failed", row.state)
        assertEquals("UNKNOWN", row.failureKind)
        assertEquals("生成失败，请检查网络与模型配置后重试", row.failureReason)
        assertEquals(CLOCK_MS, row.createdAt)
        assertEquals(CLOCK_MS, row.finishedAt)
        assertNull(row.resultIds)
    }

    @Test
    fun legacyAutomaticMomentIsSkippedEvenWhenBodyPreferenceIsOn() = runBlocking {
        val conv = 424_102L
        GenerationPrefs.setMomentsDaily(context, true)
        try {
            val worker = TestListenableWorkerBuilder<DerivativeWorker>(context, workDataOf(
                DerivativeTask.KEY_KIND to DerivativeKind.MOMENT.name,
                DerivativeTask.KEY_CONVERSATION_ID to conv,
                DerivativeTask.KEY_SOURCE to DerivativeSource.AUTOMATIC.name,
                DerivativeTask.KEY_CHARACTER_ID to 1L,
                DerivativeTask.KEY_LOCAL_DAY to DerivativeTask.localDay(),
                DerivativeTask.KEY_REQUEST_ID to "legacy-auto-request",
                KEY_AUTOMATIC_EPOCH to automaticEpoch(context, DerivativeKind.MOMENT),
            )).build()
            val result = worker.doWork() as ListenableWorker.Result.Success
            assertEquals("skipped", result.outputData.getString("disposition"))
            assertEquals("skipped", dao.byId(worker.id.toString())?.state)
        } finally {
            GenerationPrefs.setMomentsDaily(context, false)
        }
    }

    @Test
    fun switchedOffAutomaticWorkIsSkippedInsteadOfRun() = runBlocking {
        val conv = 424_103L
        GenerationPrefs.setMomentsDaily(context, false)
        val worker = TestListenableWorkerBuilder<DerivativeWorker>(
            context,
            workDataOf(
                DerivativeTask.KEY_KIND to DerivativeKind.MOMENT.name,
                DerivativeTask.KEY_CONVERSATION_ID to conv,
                DerivativeTask.KEY_SOURCE to DerivativeSource.AUTOMATIC.name,
                DerivativeTask.KEY_CHARACTER_ID to 1L,
                DerivativeTask.KEY_LOCAL_DAY to DerivativeTask.localDay(),
                DerivativeTask.KEY_REQUEST_ID to "auto-gated-req",
                KEY_AUTOMATIC_EPOCH to automaticEpoch(context, DerivativeKind.MOMENT),
            ),
        ).build()
        val result = worker.doWork() as ListenableWorker.Result.Success
        assertEquals("skipped", result.outputData.getString("disposition"))
        val row = dao.byId(worker.id.toString())
        assertEquals("skipped", row?.state)
        assertEquals("自动生成已关闭或请求已过期", row?.failureReason)
        assertNull(row?.failureKind)
    }

    @Test
    fun cancelFromHistoryOnlyTouchesActiveRecords() = runBlocking {
        val queued = UUID.randomUUID().toString()
        val done = UUID.randomUUID().toString()
        dao.insertIfMissing(record(queued, "req-cancel-1", "queued"))
        dao.insertIfMissing(record(done, "req-cancel-2", "succeeded"))
        // WorkManager 已按测试环境初始化,未知 id 的取消是安全空操作

        assertTrue(TaskHistoryController.cancel(context, queued))
        val cancelled = dao.byId(queued)
        assertEquals("cancelled", cancelled?.state)
        assertEquals("USER_CANCEL", cancelled?.failureKind)
        assertEquals(CLOCK_MS, cancelled?.finishedAt)
        // 终态行取消无效;重复取消幂等
        assertFalse(TaskHistoryController.cancel(context, done))
        assertFalse(TaskHistoryController.cancel(context, queued))
        assertEquals("succeeded", dao.byId(done)?.state)
        assertNull(dao.byId(done)?.finishedAt)
    }

    @Test
    fun processRecoveryMarksOrphanedRecordFailedWithoutDuplication() = runBlocking {
        val orphan = UUID.randomUUID().toString()
        dao.insertIfMissing(record(orphan, "req-orphan", "running"))
        reconcileDerivativeTasks(context)
        val rows = history().filter { it.workId == orphan }
        assertEquals(1, rows.size)
        assertEquals("failed", rows.single().state)
        assertEquals("UNKNOWN", rows.single().failureKind)
        assertEquals("任务未完成，请重新生成", rows.single().failureReason)
        assertEquals(CLOCK_MS, rows.single().finishedAt)
    }

    @Test
    fun duplicateEnqueueWhilePendingIsDeduplicatedWithoutGhostRows() = runBlocking {
        val conv = 424_104L
        val uniqueName = DerivativeTask.uniqueName(DerivativeTask(DerivativeKind.MOMENT, conv))
        val manager = WorkManager.getInstance(context)
        val driver = WorkManagerTestInitHelper.getTestDriver(context)
        try {
            // 占住唯一名的未完成任务 → 后续同名列的入队被 KEEP 拒绝
            manager.enqueueUniqueWork(uniqueName, androidx.work.ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<GateWorker>().build())
            manager.getWorkInfosForUniqueWork(uniqueName).get().forEach { driver?.setAllConstraintsMet(it.id) }
            awaitUntil {
                manager.getWorkInfosForUniqueWork(uniqueName).get().any { !it.state.isFinished }
            }
            DerivativeWorker.enqueue(context, DerivativeTask(DerivativeKind.MOMENT, conv, DerivativeSource.MANUAL))
            // KEEP 拒绝 → 暂存的任务历史行被删除,不留幽灵排队行
            assertEquals(0, rowsForConversation(conv).size)
        } finally {
            GateWorker.gate.complete(Unit)
            awaitUntil {
                manager.getWorkInfosForUniqueWork(uniqueName).get().all { it.state.isFinished }
            }
        }
    }

    @Test
    fun separateManualExecutionsKeepDistinctHistoryRowsWithoutResults() = runBlocking {
        val conv = 424_105L
        // 记忆总结对不存在会话显式成功:无请求、无结果行——确定性的成功路径
        val memoryName = DerivativeTask.uniqueName(DerivativeTask(DerivativeKind.MEMORY_SUMMARY, conv))
        DerivativeWorker.enqueue(context, DerivativeTask(DerivativeKind.MEMORY_SUMMARY, conv, DerivativeSource.MANUAL))
        drivePendingWork(memoryName)
        awaitUntil { rowsForConversation(conv).count { it.state == "succeeded" } >= 1 }
        DerivativeWorker.enqueue(context, DerivativeTask(DerivativeKind.MEMORY_SUMMARY, conv, DerivativeSource.MANUAL))
        drivePendingWork(memoryName)
        awaitUntil { rowsForConversation(conv).count { it.state == "succeeded" } == 2 }
        val rows = rowsForConversation(conv)
        assertEquals(2, rows.size)
        // 每次执行都是独立的历史行(requestId 互不相同),不合并也不重复
        assertEquals(2, rows.map { it.requestId }.toSet().size)
        rows.forEach { assertNull(it.resultIds) }
    }

    @Test
    fun retryFromHistoryReusesRequestIdWithoutDuplicatingResults() = runBlocking {
        val conv = 424_106L
        DerivativeWorker.enqueue(context, DerivativeTask(DerivativeKind.MOMENT, conv, DerivativeSource.MANUAL))
        drivePendingWork(DerivativeTask.uniqueName(DerivativeTask(DerivativeKind.MOMENT, conv)))
        awaitUntil { rowsForConversation(conv).firstOrNull()?.state == "failed" }
        val original = rowsForConversation(conv).single()

        assertTrue(TaskHistoryController.retry(context, original.workId))
        drivePendingWork(DerivativeTask.uniqueName(DerivativeTask(DerivativeKind.MOMENT, conv)))
        awaitUntil { rowsForConversation(conv).count { it.state == "failed" } == 2 }
        val retried = rowsForConversation(conv).first { it.workId != original.workId }
        // 同 requestId:即便这次成功,结果事务也只会写一条结果,不重复插卡
        assertEquals(original.requestId, retried.requestId)
        assertNull(GouXiangRepo.get(context).db.derivativeResultDao().byRequestId(original.requestId))
        assertEquals(2, rowsForConversation(conv).size)
    }

    private fun record(workId: String, requestId: String, state: String) = DerivativeTaskRecord(
        workId = workId, requestId = requestId, kind = "MOMENT", source = "MANUAL",
        conversationId = 1, characterId = 1, state = state, failureReason = null,
        createdAt = 1, updatedAt = 1, finishedAt = null,
    )
}

/** 占住唯一名的测试 Worker(挂起直到放行;顶层可见类,WorkManager 反射实例化用)。 */
internal class GateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): ListenableWorker.Result {
        gate.await()
        return ListenableWorker.Result.success()
    }

    companion object {
        val gate = CompletableDeferred<Unit>()
    }
}
