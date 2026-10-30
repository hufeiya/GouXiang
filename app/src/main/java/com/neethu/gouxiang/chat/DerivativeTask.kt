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

    /** 番外剧场/心动事件(F 工作包 F1-F4):一条统一内容记录,recordId 定位。 */
    SIDESTORY,

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
enum class DerivativeSource { MANUAL, AUTOMATIC }

data class DerivativeTask(
    val kind: DerivativeKind,
    val conversationId: Long,
    val source: DerivativeSource = DerivativeSource.MANUAL,
    val characterId: Long = -1L,
    val localDay: String = localDay(),
    val automaticEpoch: Long? = null,
    val imageRetryRequestId: String? = null,
    /** 番外/心动记录 id(F 工作包;仅 SIDESTORY 消费,任务由此定位目标记录)。 */
    val recordId: Long = -1L,
    /**
     * 是否要求网络约束(默认 true)。番外/心动手动任务置 false:断网时立即
     * 请求失败并把记录拨成明确失败态(可重试),而不是挂在「需要网络」的
     * 排队态让用户看不出结果——与「无假成功/失败可解释」的公共约束一致。
     */
    val requiresNetwork: Boolean = true,
) {
    companion object {

        /** Worker inputData 键(kind)。 */
        const val KEY_KIND = "derivative_kind"

        /** Worker inputData 键(conversationId)。 */
        const val KEY_CONVERSATION_ID = "derivative_conversation_id"

        const val KEY_SOURCE = "derivative_source"
        const val KEY_CHARACTER_ID = "derivative_character_id"
        const val KEY_LOCAL_DAY = "derivative_local_day"
        const val KEY_REQUEST_ID = "derivative_request_id"
        const val KEY_IMAGE_RETRY_REQUEST_ID = "derivative_image_retry_request_id"

        /** Worker inputData 键(sidestory 记录 id)。 */
        const val KEY_RECORD_ID = "derivative_record_id"

        fun localDay(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
            .format(java.util.Date())

        fun automaticKey(task: DerivativeTask): String =
            "${task.kind.name}:${task.characterId}:${task.localDay}"

        /**
         * 入队唯一名(§2.2 幂等约定):同会话同类任务 KEEP 去重,不同会话互不
         * 冲突;-1 批扫任务(如 PROACTIVE_OUTBOX)全局单例名;番外/心动按
         * 记录粒度去重(同会话可同时挂多个模板任务,互不 KEEP 掉)。
         */
        fun uniqueName(task: DerivativeTask): String =
            if (task.kind == DerivativeKind.SIDESTORY && task.recordId > 0L) {
                "deriv:sidestory:${task.recordId}"
            } else if (task.imageRetryRequestId != null) "deriv:image:${task.imageRetryRequestId}"
            else if (task.source == DerivativeSource.AUTOMATIC) {
                if (task.characterId > 0L && task.kind in setOf(DerivativeKind.INNER_VOICE, DerivativeKind.MOMENT))
                    "deriv:auto:${automaticKey(task)}"
                else "deriv:auto:${task.kind.name}:${task.conversationId}"
            } else "deriv:${task.kind.name}:${task.conversationId}"
    }
}
