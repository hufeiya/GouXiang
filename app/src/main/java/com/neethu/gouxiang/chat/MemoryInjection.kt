package com.neethu.gouxiang.chat

import com.neethu.gouxiang.data.MemoryEntity

/**
 * 记忆上下文注入(对齐 03-memory-upgrade.md §3.2d 分带注入 + 总分析 §6.3
 * prompt 骨架「事件记忆 TopN + 容量外降权拼接」):
 *
 * - 画像块:我的称呼 / 我的身份 各一行(身份性记忆,容量外也不降权);
 * - 关系块:relationship 行(常驻,不占容量);
 * - 事件块:事件按 at 倒序 TopN([NORMAL_MAX] 条容量内正常行);容量外条目
 *   照常注入但每行带「逐渐模糊」后缀 + 打分沉底(降权而非删除的用户可见面,
 *   faded 侧另取 [FADED_MAX] 条封顶)。
 *
 * 渐忘在检索侧的表现由 [MemoryCapacity.band] 的打标 + 打分驱动;
 * 这里负责把打标结果渲染成 prompt 行。
 */
object MemoryInjection {

    /** 事件 TopN 上限(与 ChatContextBuilder.MAX_EVENT_MEMORIES 同口径,防 prompt 膨胀)。 */
    const val NORMAL_MAX = 6

    /** 容量外的 faded 条目最多再带这么多进 prompt(防 prompt 无限膨胀)。 */
    const val FADED_MAX = 4

    /**
     * 组装 [记忆] 块;无任何可注入内容 → null(零注入,03 §3.2h 槽位约定)。
     * 阈值内条目正常行,容量外条目行尾加 [MemoryCapacity.FADED_SUFFIX]。
     */
    fun block(charName: String, memories: List<MemoryEntity>, query: String): String? {
        val (banded, state) = MemoryCapacity.band(memories)
        val profile = memories.filter { it.category == MemoryCategories.PROFILE }
        val relationship = memories.filter { it.category == MemoryCategories.RELATIONSHIP }
        val normalEvents = banded.filter { it.memory.category == MemoryCategories.EVENT && !it.faded }
            .take(NORMAL_MAX)
        val fadedEvents = banded.filter { it.memory.category == MemoryCategories.EVENT && it.faded }
            .take(FADED_MAX)

        if (profile.isEmpty() && relationship.isEmpty() && normalEvents.isEmpty() && fadedEvents.isEmpty()) {
            return null
        }
        return buildString {
            appendLine("[记忆] 你与用户的共同回忆(供自然引用,不要逐条复述):")
            profile.forEach { m ->
                appendLine("- (我的画像) ${renderContent(m)}")
            }
            relationship.forEach { m ->
                appendLine("- (关系) ${renderContent(m)}")
            }
            normalEvents.forEach { b ->
                appendLine("- ${b.memory.content}")
            }
            if (fadedEvents.isNotEmpty()) {
                appendLine("(以下记忆逐渐模糊,仅供参考,不确定时不要当作事实断言)")
                fadedEvents.forEach { b ->
                    appendLine("- ${b.memory.content}${MemoryCapacity.FADED_SUFFIX}")
                }
            }
        }.trim().ifEmpty { null }
    }

    /** 画像内容照原文;注入层不做二次清洗(种子行本身已是「我的称呼:老公」形态)。 */
    private fun renderContent(m: MemoryEntity): String = m.content.trim()
}
