package com.neethu.gouxiang.protocol

/**
 * 主动消息触发判定(05-proactive-messages.md §3.2 的本地收敛):
 *
 * 猫箱三通道(服务端堆积/在线索取/回归补偿)收敛为本地版两条:
 * - 首次进入:该角色还没有主动消息史 → 先连发 1~[maxInitial] 条主动消息;
 * - 距上次对话超过 [offlineThresholdMs] → 按 12h 冷/热分档产出 1~[maxCatchUp] 条。
 *
 * 条数与 kind 是纯函数判定(单测锁死),生成走既有 ChatOrchestrator(同一
 * LLM/记忆底座);响应式回复不计数、不配额 —— 本地版无猫粮(04 §3.4)。
 */
object ProactiveMessages {

    /** 触发阈值:距上次对话超过该时长才判「离线回归」(默认 2 小时,可配)。 */
    const val DEFAULT_OFFLINE_THRESHOLD_MS: Long = 2L * 60 * 60 * 1000

    /** 冷档边界:超过 12 小时算「被冷落」,倾向关心型开场。 */
    const val COLD_OFFLINE_MS: Long = 12L * 60 * 60 * 1000

    /** 首次进入的主动消息条数区间。 */
    const val MAX_INITIAL = 3

    /** 离线回归的补发条数区间。 */
    const val MAX_CATCH_UP = 3

    /** 主动消息的 kind(场景指令进 prompt,渲染层不区分)。 */
    enum class Kind { FIRST_HELLO, CHECK_IN, DAILY_SHARE }

    /**
     * 回归场景指令(拼进 user 前,模型按它主动开口,不接用户的话茬)。
     * [mark] 一律以()动作描述开场(对齐开场白规范)。
     */
    fun sceneLine(kind: Kind, offlineMs: Long?): String = when (kind) {
        Kind.FIRST_HELLO ->
            "(你刚点开和小唐的手机聊天)" +
                "你先主动打招呼:连发 1~3 条开场消息,可以是分享你刚在做的事、想到TA了、" +
                "随口一句吐槽。这是你的手机聊天开场,不要等待用户先说话。"
        Kind.CHECK_IN ->
            "(你已经很久没收到回复了,有点想你)" +
                "距上次聊天已经超过 ${offlineMsDescription(offlineMs)}。你先主动发消息关心TA:" +
                "连发 1~3 条,语气从撒娇质问到软下来,问问TA在忙什么。不要等用户先说。"
        Kind.DAILY_SHARE ->
            "(你刷着手机,想和TA分享点什么)" +
                "距上次聊天已经 ${offlineMsDescription(offlineMs)}。你主动分享 1~3 条日常:" +
                "刚看到的东西、刚做完的事、或者突然想到TA。不要等用户先说。"
    }

    fun offlineMsDescription(offlineMs: Long?): String {
        val ms = offlineMs ?: 0L
        val minutes = ms / 60_000
        return when {
            minutes < 60 -> "${minutes}分钟"
            minutes < 60 * 24 -> "${minutes / 60}小时"
            else -> "${minutes / 60 / 24}天"
        }
    }

    /**
     * 触发判定(纯函数):
     * - [everProactive] == false:该角色从未有过主动消息 → FIRST_HELLO;
     * - lastActivityAt 为空(无任何消息)→ FIRST_HELLO;
     * - 距上次对话 ≥ 阈值:≥12h 冷档 CHECK_IN,否则 DAILY_SHARE;
     * - 其余(刚刚聊过)→ null,不触发。
     */
    fun pickTrigger(
        everProactive: Boolean,
        lastActivityAt: Long?,
        now: Long,
        offlineThresholdMs: Long = DEFAULT_OFFLINE_THRESHOLD_MS,
    ): Kind? {
        if (!everProactive) return Kind.FIRST_HELLO
        if (lastActivityAt == null) return Kind.FIRST_HELLO
        val offline = now - lastActivityAt
        if (offline < offlineThresholdMs) return null
        return if (offline >= COLD_OFFLINE_MS) Kind.CHECK_IN else Kind.DAILY_SHARE
    }
}
