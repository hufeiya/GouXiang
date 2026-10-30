package com.neethu.gouxiang.work

/**
 * 衍生任务通知文案(§2.3;常量收敛一处,红线单测扫描全量覆盖——
 * 本地版无付费体系,文案严禁出现敏感商业字样(红线清单见 DerivativeTaskTest))。
 */
internal object DerivativeCopy {

    /** 通道一:衍生内容完成(心声/朋友圈)。 */
    const val CHANNEL_DERIVATIVE = "derivative"

    /** 通道二:离线主动消息。 */
    const val CHANNEL_PROACTIVE = "proactive"

    /** 通道一展示名。 */
    const val CHANNEL_DERIVATIVE_NAME = "内容生成"

    /** 通道一描述。 */
    const val CHANNEL_DERIVATIVE_DESC = "心声、朋友圈等衍生内容生成完成"

    /** 通道二展示名。 */
    const val CHANNEL_PROACTIVE_NAME = "主动消息"

    /** 通道二描述。 */
    const val CHANNEL_PROACTIVE_DESC = "TA 在离线期间发来的消息"

    // ── 完成通知文案(标题带角色名,正文短句不带「生成」的后台语感堆叠)──

    fun innerVoiceTitle(name: String): String = "${name}的心声已生成"

    fun momentTitle(name: String): String = "${name}发布了新的朋友圈"

    /** 番外/心动完成标题(F 工作包;心动事件同通道,不再细分以免文案发散)。 */
    fun sidestoryTitle(name: String): String = "${name}的番外内容已生成"

    /** 通用兜底标题(M2 kind 接入前不触达;保证任意 kind 有合法文案)。 */
    fun genericTitle(name: String): String = "${name}有新内容"

    /** 通知正文(点进查看)。 */
    const val BODY_TAP_TO_VIEW = "点开看看吧"

    /** 主动消息通知(§2.3:「离线期间发来 N 条」)。 */
    fun proactiveTitle(name: String): String = "${name}给你发来消息"

    fun proactiveBody(count: Int): String = "离线期间发来 $count 条消息"

    /** outbox 扫描命中多条会话时的正文变体。 */
    fun proactiveBodyMulti(): String = "离线期间发来了新消息"
}
