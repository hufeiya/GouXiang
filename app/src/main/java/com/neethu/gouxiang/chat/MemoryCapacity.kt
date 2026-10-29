package com.neethu.gouxiang.chat

import com.neethu.gouxiang.data.MemoryEntity

/**
 * 容量渐忘(03-memory-upgrade.md §3.2d 的口径,收敛到 event 单类):
 *
 * - 容量只数 event(「经历过的事」);画像/称呼/身份/关系是身份性记忆,
 *   不占容量、永不 faded(与猫箱「容量文案只出现在事件区」语义一致)。
 * - capacity = base × multiplier(本地版无会员态,multiplier 为死配置)。
 * - 排序键 = at 倒序,前 capacity 条 normal,其后 faded(降权不删除——
 *   库内永远全量,分带在检索/渲染时现场算)。
 * - faded 条目仍在库、仍可被注入,但排序沉底 + 加「逐渐模糊」后缀(§3.2d:
 *   UI 显示「逐渐模糊」= prompt 里降权拼接的用户可见面)。
 */
object MemoryCapacity {

    /** 事件记忆容量上限(条)。猫箱数值服务端下发不可见,本地版默认 20。 */
    const val BASE = 20

    /** 翻倍系数(死配置,预留会员位;本地版恒 1f)。 */
    const val MULTIPLIER = 1f

    /** faded 条目的打分系数(仍在库,但排序沉底)。 */
    const val FADED_SCORE = 0.45f

    /** capacity(取整)。 */
    fun capacity(): Int = (BASE * MULTIPLIER).toInt().coerceAtLeast(1)

    /** 状态快照:used = event 条数;fadedCount = max(0, used-capacity)。 */
    data class CapacityState(val capacity: Int, val used: Int, val fadedCount: Int) {
        /** UI 容量条显示 used(钳制到 capacity,分界行由 fadedCount 触发)。 */
        val usedCapped: Int get() = used.coerceAtMost(capacity)
        val hasFaded: Boolean get() = fadedCount > 0
    }

    fun state(memories: List<MemoryEntity>): CapacityState {
        val used = memories.count { it.category == MemoryCategories.EVENT }
        val cap = capacity()
        return CapacityState(cap, used, (used - cap).coerceAtLeast(0))
    }

    /**
     * 分带打标:事件按 at 倒序,前 capacity 条 status=normal,其后 faded;
     * 输出顺序 = 画像 → 关系 → 事件(at 倒序)→ 其余,与输入列表顺序无关。
     * 按对象在排序列表中的下标分带,不依赖 id(手写行/入库前行的 id 可能同为 0)。
     * 返回 (带状态行, 容量状态)。
     */
    fun band(memories: List<MemoryEntity>): Pair<List<Banded>, CapacityState> {
        val cap = capacity()
        val events = memories.filter { it.category == MemoryCategories.EVENT }
            .sortedByDescending { it.at }
        val state = CapacityState(cap, events.size, (events.size - cap).coerceAtLeast(0))
        val bandedEvents = events.mapIndexed { index, m ->
            Banded(m, if (index >= cap) Status.FADED else Status.NORMAL)
        }
        val others = memories.filter { it.category != MemoryCategories.EVENT }
        val profile = others.filter { it.category == MemoryCategories.PROFILE }.map { Banded(it, Status.NORMAL) }
        val relationship = others.filter { it.category == MemoryCategories.RELATIONSHIP }.map { Banded(it, Status.NORMAL) }
        val rest = others.filter {
            it.category != MemoryCategories.PROFILE && it.category != MemoryCategories.RELATIONSHIP
        }.map { Banded(it, Status.NORMAL) }
        return (profile + relationship + bandedEvents + rest) to state
    }

    enum class Status { NORMAL, FADED }

    /** 带容量状态的记忆条(UI 与注入共用)。 */
    data class Banded(val memory: MemoryEntity, val status: Status) {
        val faded: Boolean get() = status == Status.FADED
    }

    /** 注入行后缀(faded 条目加,对应 parallel_memory_separator 的 prompt 侧镜像)。 */
    const val FADED_SUFFIX = "(这段记忆已经模糊)"
}
