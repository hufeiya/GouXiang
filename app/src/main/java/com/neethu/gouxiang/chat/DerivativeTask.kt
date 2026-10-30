package com.neethu.gouxiang.chat

/**
 * 衍生任务抽象(docs/background-generation-plan-20261030.md §2.2,纯 Kotlin
 * 零 Android 依赖——工程红线:断言逻辑可单测)。
 *
 * 一个 [DerivativeWorker] 分发多种衍生任务,不搞每任务一个 Worker:
 * [Kind] 枚举驱动 [DerivativeExecutor] 分支,复用现有生成缝
 * (InnerVoiceChannel/MomentsChannel/MemoryExtractor 均为独立小类)。
 */
enum class DerivativeKind {
    /** 心声卡(F8):批量 2~4 条 + 逐张配图。 */
    INNER_VOICE,

    /** 朋友圈动态(F9):一条图文。 */
    MOMENT,

    /** 手帐(M2 接入;开关与枚举先行落地,分支 M2 施工)。 */
    HANDBOOK,

    /** 小说(M2 接入)。 */
    NOVEL,

    /** 合照(M2 接入)。 */
    PHOTO,

    /** 记忆隔夜总结兜底(A16):闲置 ≥8h 补提炼。 */
    MEMORY_SUMMARY,

    /** 离线主动消息 outbox(B9):扫全部会话 pickTrigger。 */
    PROACTIVE_OUTBOX,
}

/**
 * 一条衍生任务(经 Worker inputData 序列化;conversationId 供生成素材回放,
 * -1 = 不挂具体会话的批扫任务,由 Executor 自行展开)。
 */
data class DerivativeTask(
    val kind: DerivativeKind,
    val conversationId: Long,
) {
    companion object {

        /** Worker inputData 键(kind)。 */
        const val KEY_KIND = "derivative_kind"

        /** Worker inputData 键(conversationId)。 */
        const val KEY_CONVERSATION_ID = "derivative_conversation_id"

        /**
         * 入队唯一名(§2.2 幂等约定):同会话同类任务 KEEP 去重,不同会话互不
         * 冲突;-1 批扫任务(如 PROACTIVE_OUTBOX)全局单例名。
         */
        fun uniqueName(task: DerivativeTask): String =
            "deriv:${task.kind.name}:${task.conversationId}"
    }
}
