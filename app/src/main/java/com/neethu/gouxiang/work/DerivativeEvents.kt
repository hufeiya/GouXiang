package com.neethu.gouxiang.work

import com.neethu.gouxiang.chat.DerivativeKind

/**
 * 衍生任务进程级事件 bus(docs/background-generation-plan-20261030.md §2.4):
 * Worker(同进程)与 UI(VM)之间的轻量收口通道——点击路径的乐观 generating
 * 态由完成/失败事件收口;内容落库本身走 Room Flow(UI 不感知后台)。
 *
 * 单槽 LatestStateFlow 语义:多次完成只留最新一条,UI 消费后清空;进程被杀
 * 期间完成的任务无事件,但下次进页 Room 流已带最新内容(事件只是 UI 态收口,
 * 不承载数据)。
 */
object DerivativeEvents {

    /** 一次衍生任务收口事件。 */
    data class Event(
        val kind: DerivativeKind,
        val conversationId: Long,
        val success: Boolean,
    )

    /** 最近一条事件(单槽;null=无)。 */
    @Volatile
    var latest: Event? = null
        private set

    /** Worker 收口调用(成功/失败都发;批扫任务 conversationId=-1 不发,无 UI 态可收)。 */
    fun post(kind: DerivativeKind, conversationId: Long, success: Boolean) {
        if (conversationId <= 0L) return
        latest = Event(kind, conversationId, success)
    }

    /** UI 消费(读后清)。 */
    fun take(): Event? {
        val e = latest
        latest = null
        return e
    }
}

/**
 * 设置页快照(§3.3:进页即读一次的 mutableStateOf 语义;切换即写 + 即时
 * enqueue/cancel,不引入全局 StateFlow)。六枚开关与 [GenerationPrefs] 一一对应。
 */
data class GenerationSettingsSnapshot(
    val innerVoiceAuto: Boolean = false,
    val momentsDaily: Boolean = false,
    val memoryOvernight: Boolean = false,
    val proactiveOutbox: Boolean = true,
    val derivativeAuto: Boolean = false,
    val notifyEnabled: Boolean = true,
    val innerVoiceImages: Boolean = true,
    val momentsImages: Boolean = true,
    /** 番外配图(F 工作包接入;默认关,文本先出图片可选)。 */
    val sidestoryImages: Boolean = false,
)
