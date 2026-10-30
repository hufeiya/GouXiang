package com.neethu.gouxiang.data

import androidx.room.withTransaction

/**
 * 主动消息账本(D 工作包 D1/D2/D4 的事务收口层):
 *
 * 所有跨表/跨行的账本写入都在**单个会话级 Room 事务**内完成;网络请求一律
 * 不在本层(生成在 [com.neethu.gouxiang.proactive.ProactiveOutboxService] 的
 * 事务外进行,完成后再进 [finalizeBatch] 收口)——前台进页与后台周期扫描
 * 共用同一预约门禁([reserve] 的在途唯一性)与同一套阈值判定入参。
 */
class ProactiveLedger(private val db: GouXiangDatabase) {

    /** 预约快照:evaluate 的一切入参(D2 阈值判定只读它,不重扫)。 */
    data class Snapshot(
        val conversationId: Long,
        val characterId: Long,
        /** 用户活跃时间(最近一条用户消息;null=尚未发言)。 */
        val lastUserActivityAt: Long?,
        /** 最近一次批次完成时刻(null=从未发过主动消息)。 */
        val lastProactiveAt: Long?,
        /** 今日已完成批次数(与 [dailyStamp] 配对)。 */
        val dailyCount: Int,
        val dailyStamp: String,
        /** 未读主动消息条数。 */
        val unseenCount: Int,
    )

    /** 在途预约行(会话级,至多一个;null=空闲可预约)。 */
    suspend fun inFlightFor(conversationId: Long): ProactiveOutboxEntity? =
        db.proactiveDao().inFlightFor(conversationId)

    /**
     * 会话级事务预约:事务内先放行中断租约([recoverStaleFor]),再查在途
     * 门禁,最后插入 pending 行(requestId 唯一索引兜底幂等)。返回 null =
     * 已有在途批次(预约被拒,调用方不得再生成)。
     */
    suspend fun reserve(
        conversationId: Long,
        characterId: Long,
        kind: String,
        requestId: String,
        now: Long,
        localDay: String,
        staleLeaseMs: Long,
    ): ProactiveOutboxEntity? = db.withTransaction {
        recoverStaleFor(conversationId, staleLeaseMs, now)
        if (db.proactiveDao().inFlightFor(conversationId) != null) return@withTransaction null
        val row = ProactiveOutboxEntity(
            conversationId = conversationId,
            characterId = characterId,
            batchId = java.util.UUID.randomUUID().toString(),
            requestId = requestId,
            kind = kind,
            createdAt = now,
            updatedAt = now,
        )
        if (db.proactiveDao().insert(row) == -1L) return@withTransaction null
        ensureState(conversationId, characterId)
        row
    }

    /** 预约 → 生成中(状态机迁移校验;非法迁移抛出,不静默)。 */
    suspend fun markGenerating(requestId: String, now: Long) {
        db.withTransaction {
            val row = db.proactiveDao().byRequestId(requestId)
                ?: throw IllegalStateException("proactive reservation missing: $requestId")
            require(row.state == ProactiveBatchState.PENDING.name) {
                "proactive state ${row.state} cannot move to GENERATING"
            }
            db.proactiveDao().update(row.copy(state = ProactiveBatchState.GENERATING.name, updatedAt = now))
        }
    }

    /** 会话状态行(缺失即建空行,并回写消息表对账出的用户活跃时间)。 */
    suspend fun ensureState(conversationId: Long, characterId: Long): ProactiveConversationStateEntity =
        db.withTransaction {
            val lastUserAt = db.messageDao().lastUserMessageAt(conversationId) ?: 0L
            val existing = db.proactiveDao().stateFor(conversationId)
            val base = existing ?: ProactiveConversationStateEntity(
                conversationId = conversationId,
                characterId = characterId,
            )
            val reconciled = if (base.activityOverride == 1) {
                // 调试回拨生效中:不覆盖(用户真实发言经 noteUserActivity 清零)
                base.copy(characterId = characterId)
            } else {
                base.copy(
                    characterId = characterId,
                    // 活跃时间以消息表为准(重启/回溯后自动对账),只前进不回退
                    lastUserActivityAt = maxOf(base.lastUserActivityAt, lastUserAt),
                )
            }
            db.proactiveDao().upsertState(reconciled)
            reconciled
        }

    /**
     * 用户真实发言回写(D1/D2):活跃时间前移到 [at],并解除调试覆盖
     * (覆盖值只在该用户再次开口前有效)。单事务。
     */
    suspend fun noteUserActivity(conversationId: Long, characterId: Long, at: Long) =
        db.withTransaction {
            val base = db.proactiveDao().stateFor(conversationId)
                ?: ProactiveConversationStateEntity(conversationId, characterId)
            db.proactiveDao().upsertState(
                base.copy(
                    characterId = characterId,
                    lastUserActivityAt = maxOf(base.lastUserActivityAt, at),
                    activityOverride = 0,
                ),
            )
        }

    /** 快照(评估入参;顺带回写活跃时间对账)。 */
    suspend fun snapshot(conversationId: Long, characterId: Long): Snapshot {
        val state = ensureState(conversationId, characterId)
        val unseen = db.proactiveDao().unseenTotal(conversationId)
        return Snapshot(
            conversationId = conversationId,
            characterId = characterId,
            lastUserActivityAt = state.lastUserActivityAt.takeIf { it > 0 },
            lastProactiveAt = state.lastProactiveAt.takeIf { it > 0 },
            dailyCount = state.dailyCount,
            dailyStamp = state.dailyStamp,
            unseenCount = unseen,
        )
    }

    /**
     * 生成收口(成功/降级共用):批次行落终态 + 会话状态行记账(最近批次/
     * 日计数/未读)+ 会话行未读角标累加,单事务。终态由状态机校验:只有
     * 在途行能收口(晚到/已作废的生成结果被拒绝,返回 false)。
     */
    suspend fun finalizeBatch(
        requestId: String,
        degraded: Boolean,
        turnId: String,
        messageIds: List<Long>,
        now: Long,
        localDay: String,
    ): Boolean = db.withTransaction {
        val row = db.proactiveDao().byRequestId(requestId) ?: return@withTransaction false
        val target = if (degraded) ProactiveBatchState.FALLBACK else ProactiveBatchState.READY
        if (!ProactiveBatchState.valueOf(row.state).canTransitionTo(target)) return@withTransaction false
        val ids = messageIds.joinToString(",")
        if (db.proactiveDao().finalize(
                requestId, target.name, turnId, ids, messageIds.size, "", now,
            ) == 0
        ) return@withTransaction false
        val existing = db.proactiveDao().stateFor(row.conversationId)
            ?: ProactiveConversationStateEntity(row.conversationId, row.characterId)
        val stamp = if (existing.dailyStamp == localDay) existing.dailyCount else 0
        db.proactiveDao().upsertState(
            existing.copy(
                lastProactiveAt = now,
                lastBatchAt = now,
                lastBatchCount = messageIds.size,
                lastBatchMessageIds = ids,
                unseenCount = existing.unseenCount + messageIds.size,
                dailyStamp = localDay,
                dailyCount = stamp + 1,
            ),
        )
        db.conversationDao().bumpUnread(row.conversationId, messageIds.size)
        true
    }

    /** 生成失败收口:只翻批次行,不记日计数/未读(无消息落库)。 */
    suspend fun markFailed(requestId: String, reason: String, now: Long): Boolean =
        db.withTransaction {
            val row = db.proactiveDao().byRequestId(requestId) ?: return@withTransaction false
            if (!ProactiveBatchState.valueOf(row.state).canTransitionTo(ProactiveBatchState.FAILED)) {
                return@withTransaction false
            }
            db.proactiveDao().finalize(requestId, ProactiveBatchState.FAILED.name, "", "", 0, reason, now) > 0
        }

    /** 通知投递幂等门:0→1,返回 true = 本次调用方负责真正投递。 */
    suspend fun claimNotification(requestId: String, now: Long): Boolean =
        db.proactiveDao().markNotified(requestId, now) > 0

    /**
     * 进页消费:会话内全部未读批次 → 已读,未读数与角标清零,单事务。
     * 返回被消费的批次行(供 UI 在其首个消息组前插「离线分隔」)。
     */
    suspend fun consumeConversation(conversationId: Long, now: Long): List<ProactiveOutboxEntity> =
        db.withTransaction {
            val unseen = db.proactiveDao().unseenRows(conversationId)
            db.proactiveDao().markConversationSeen(conversationId, now)
            val existing = db.proactiveDao().stateFor(conversationId)
            if (existing != null) {
                db.proactiveDao().upsertState(existing.copy(unseenCount = 0, lastSeenAt = now))
            }
            db.conversationDao().setUnread(conversationId, 0)
            unseen
        }

    /**
     * 回溯清账(D4):锚点(含)之后被删除的消息涉及的批次行作废;部分命中
     * (批内仍有存活消息但锚点之后有删除)同样整批作废——批次完整性破坏后
     * 不再参与未读/分隔展示。最后按存活未读行重算未读数与角标。单事务。
     */
    suspend fun rewindFrom(conversationId: Long, anchorMessageId: Long): Int = db.withTransaction {
        val remaining = db.messageDao().allForConversation(conversationId).map { it.id }.toSet()
        var cancelled = 0
        for (row in db.proactiveDao().forConversation(conversationId)) {
            val stale = row.state == ProactiveBatchState.PENDING.name ||
                row.state == ProactiveBatchState.GENERATING.name
            val ids = row.messageIds.split(',').mapNotNull(String::toLongOrNull)
            val gone = ids.isNotEmpty() && (ids.min() >= anchorMessageId || ids.any { it !in remaining })
            if (stale || gone) {
                if (ProactiveBatchState.valueOf(row.state).canTransitionTo(ProactiveBatchState.CANCELLED)) {
                    db.proactiveDao().update(
                        row.copy(state = ProactiveBatchState.CANCELLED.name, updatedAt = System.currentTimeMillis()),
                    )
                    cancelled++
                }
            }
        }
        recountUnseen(conversationId)
        cancelled
    }

    /** 按存活未读行重算未读数与角标(收口/回溯后的对账出口)。 */
    suspend fun recountUnseen(conversationId: Long) = db.withTransaction {
        val total = db.proactiveDao().unseenTotal(conversationId)
        val existing = db.proactiveDao().stateFor(conversationId)
        if (existing != null) db.proactiveDao().upsertState(existing.copy(unseenCount = total))
        db.conversationDao().setUnread(conversationId, total)
    }

    /**
     * 中断租约恢复(D4 进程重启):pending/generating 行超过 [leaseMs] 未更新
     * → 作废(failed 语义:进程在生成/收口间被杀)。返回作废行数。
     */
    suspend fun recoverStaleFor(conversationId: Long, leaseMs: Long, now: Long): Int =
        db.withTransaction {
            val row = db.proactiveDao().inFlightFor(conversationId) ?: return@withTransaction 0
            if (now - row.updatedAt <= leaseMs) return@withTransaction 0
            db.proactiveDao().update(
                row.copy(state = ProactiveBatchState.CANCELLED.name, failureReason = "进程中断,批次作废", updatedAt = now),
            )
            1
        }

    /** 全库租约恢复(扫描入口调;返回作废行数)。 */
    suspend fun recoverStaleAll(leaseMs: Long, now: Long): Int = db.withTransaction {
        var n = 0
        for (row in db.proactiveDao().inFlight()) {
            if (now - row.updatedAt > leaseMs) {
                db.proactiveDao().update(
                    row.copy(state = ProactiveBatchState.CANCELLED.name, failureReason = "进程中断,批次作废", updatedAt = now),
                )
                n++
            }
        }
        n
    }

    /** 调试/验收:把活跃时间回拨到 [toMs](只动账本,不碰消息数据;置覆盖位防对账回写)。 */
    suspend fun debugBackdateActivity(conversationId: Long, toMs: Long) = db.withTransaction {
        val existing = db.proactiveDao().stateFor(conversationId) ?: return@withTransaction
        db.proactiveDao().upsertState(existing.copy(lastUserActivityAt = toMs, activityOverride = 1))
    }
}
