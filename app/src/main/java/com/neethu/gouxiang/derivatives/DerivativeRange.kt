package com.neethu.gouxiang.derivatives

import com.neethu.gouxiang.data.MessageEntity

/**
 * 手帐的聊天范围选择(G1;纯 Kotlin 零 Android 依赖,单测锁行为):
 *
 * 「回合」= 同 turnId 的一组消息(与聊天页回溯/重生成按回合操作的粒度一致,
 * 主动消息 `proactive-*` 回合同样计入)。预设按「最近 N 个回合」取区间:
 * 区间的 (startId, endId) = 命中回合内消息 id 的最小/最大值,创建记录时
 * 快照进 `chat_derivative_records.sourceStart/EndMessageId`,生成时按同
 * 区间重读素材(消息 id 单调自增,回溯删行不影响区间语义——被删部分自然
 * 变空,版本号 +1 会拦截在途生成)。
 */
object DerivativeRange {

    /** 一段选中的聊天来源(闭区间 + 概览文案)。 */
    data class Range(
        val startMessageId: Long,
        val endMessageId: Long,
        /** 命中的回合数(列表/确认文案展示)。 */
        val turnCount: Int,
        /** 命中的消息条数(生成素材规模提示)。 */
        val messageCount: Int,
    )

    /** 预设(入口 chips 与 ai_cmd 的参数;[label] 为 UI 展示文案)。 */
    enum class Preset(val key: String, val label: String) {
        TURN_5("turn5", "最近 5 轮"),
        TURN_10("turn10", "最近 10 轮"),
        ALL("all", "全部记录"),
        ;

        companion object {
            /** key → 预设(未知/空回落最近 10 轮,不抛)。 */
            fun fromKey(key: String?): Preset =
                entries.firstOrNull { it.key == key } ?: TURN_10
        }
    }

    /**
     * 按预设从消息序列(时间正序)解析选中区间。
     *
     * @return 命中区间;消息为空返回 null(调用方拒绝创建,提示先聊天)。
     */
    fun resolve(messages: List<MessageEntity>, preset: Preset): Range? {
        if (messages.isEmpty()) return null
        val ordered = messages.sortedWith(compareBy({ it.at }, { it.id }))
        val turnIds = LinkedHashSet<String>()
        for (m in ordered) turnIds.add(m.turnId)
        val selectedTurns: Set<String> = when (preset) {
            Preset.ALL -> turnIds
            else -> turnIds.toList().takeLast(preset.turns).toSet()
        }
        val selected = ordered.filter { it.turnId in selectedTurns }
        if (selected.isEmpty()) return null
        return Range(
            startMessageId = selected.minOf { it.id },
            endMessageId = selected.maxOf { it.id },
            turnCount = selectedTurns.size,
            messageCount = selected.size,
        )
    }

    /** 预设的回合数(ALL 忽略)。 */
    private val Preset.turns: Int
        get() = when (this) {
            Preset.TURN_5 -> 5
            Preset.TURN_10 -> 10
            Preset.ALL -> Int.MAX_VALUE
        }
}
