package com.neethu.gouxiang.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 主动消息批次账本(D 工作包 D1,一条 = 一次「预约→生成→收口」):
 *
 * 生成/展示/消费三段状态可区分:
 * - 生成:[state] pending(已预约)→ generating → ready(真实生成成功)/
 *   fallback(模板降级,非真实成功)/ failed(网络等失败,可重试)/
 *   cancelled(回溯或进程中断作废);
 * - 展示:[notified] = 通知是否已投递(幂等门禁,重试/恢复不重复提醒);
 * - 消费:[seen]/[seenAt] = 用户是否已在会话内读过(进页消费)。
 *
 * [requestId] 唯一索引 = 生成幂等键(同请求的重复预约被 IGNORE 拦截);
 * [messageIds] 记录本批落库的消息行(逗号分隔,回溯时按它对账)。
 */
@Entity(
    tableName = "proactive_outbox",
    indices = [
        Index("conversationId"),
        Index(value = ["requestId"], unique = true),
    ],
)
data class ProactiveOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 关联 [ConversationEntity.id]。 */
    val conversationId: Long,
    /** 关联 [CharacterEntity.id](会话删除前的归属快照)。 */
    val characterId: Long,
    /** 批次 id(uuid,一次生成回合)。 */
    val batchId: String,
    /** 生成请求幂等键(与 Worker requestId 同源)。 */
    val requestId: String,
    /** 场景 kind:FIRST_HELLO / CHECK_IN / DAILY_SHARE。 */
    val kind: String,
    /** 回合 id(生成成功后回填;预约阶段为空串)。 */
    val turnId: String = "",
    /** 本批消息行 id,逗号分隔(生成成功后回填;空串=尚未落库)。 */
    val messageIds: String = "",
    /** 本批消息条数(通知文案「N 条」的实际新消息数)。 */
    val count: Int = 0,
    /** 生成段状态,取值见 [ProactiveBatchState]。 */
    val state: String = ProactiveBatchState.PENDING.name,
    /** 失败/降级原因(fallback/failed 可读)。 */
    val failureReason: String = "",
    /** 通知投递幂等门(0=未投;1=已投或已判定不投)。 */
    val notified: Int = 0,
    /** 消费段状态:0=未读;1=用户已在会话内看过。 */
    val seen: Int = 0,
    /** 消费时刻(0=未读)。 */
    val seenAt: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/**
 * 主动消息批次生成段状态机(单测锁死 [ProactiveBatchState.canTransitionTo]):
 * pending → generating → ready | fallback | failed;任意在途态可 → cancelled。
 */
enum class ProactiveBatchState {
    /** 已预约(会话级事务占位,尚未开始请求)。 */
    PENDING,

    /** 生成中(网络请求已发出;请求不在任何数据库事务内)。 */
    GENERATING,

    /** 真实生成成功(LLM 输出按协议落库)。 */
    READY,

    /** 模板降级(流失败/空输出的兜底文本已落库;不算真实成功,不自动重试)。 */
    FALLBACK,

    /** 生成失败(无消息落库,可重试:Worker 退避或下轮扫描再预约)。 */
    FAILED,

    /** 作废(回溯清账或租约超时;不可再迁移)。 */
    CANCELLED,
    ;

    /** 合法迁移表:在途态(PENDING/GENERATING)可到任何其他态(允许跳过
     *  中间态——预约与 markGenerating 之间崩溃、或直接收口都合法);业务终态
     *  (READY/FALLBACK/FAILED)只允许被回溯清账作废(CANCELLED),CANCELLED
     *  不可再变(晚到的生成结果/通知不得复活已作废批次)。 */
    fun canTransitionTo(target: ProactiveBatchState): Boolean = when (this) {
        PENDING, GENERATING -> target != this
        READY, FALLBACK, FAILED -> target == CANCELLED
        CANCELLED -> false
    }
}

/**
 * 每会话一行的主动消息状态(D1:活跃时间/最近主动消息/批次/未读已读):
 * 频控(D2)与回归展示(D4)都从这一行读,不重扫消息表。
 */
@Entity(tableName = "proactive_conversation_state")
data class ProactiveConversationStateEntity(
    /** 会话 id(主键,一行一会话)。 */
    @PrimaryKey val conversationId: Long,
    val characterId: Long,
    /** 用户活跃时间(最近一条用户消息;0=尚未发言;快照时与消息表对账回写)。 */
    val lastUserActivityAt: Long = 0,
    /** 最近一次主动消息批次完成时刻(ready/fallback;0=从未)。 */
    val lastProactiveAt: Long = 0,
    /** 最近批次完成时刻(0=无)。 */
    val lastBatchAt: Long = 0,
    /** 最近批次消息条数(0=无)。 */
    val lastBatchCount: Int = 0,
    /** 最近批次消息行 id(逗号分隔;空串=无)。 */
    val lastBatchMessageIds: String = "",
    /** 未读主动消息数(已生成未消费)。 */
    val unseenCount: Int = 0,
    /** 最近一次消费(进页已读)时刻;0=从未。 */
    val lastSeenAt: Long = 0,
    /** 今日批次计数的本地日期(yyyy-MM-dd;与 [dailyCount] 配对)。 */
    val dailyStamp: String = "",
    /** 今日已完成批次数(ready/fallback 计入;failed 不计)。 */
    val dailyCount: Int = 0,
    /**
     * 活跃时间调试覆盖(0/1):1 = [lastUserActivityAt] 为人工回拨值,
     * 快照对账不覆盖;用户真实发言(noteUserActivity)即清零回正常对账。
     */
    @androidx.room.ColumnInfo(defaultValue = "0")
    val activityOverride: Int = 0,
)
