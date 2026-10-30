package com.neethu.gouxiang.chat

import com.neethu.gouxiang.data.MemoryEntity

/**
 * 相关记忆召回(E 工作包 E1):角色隔离前提下,以**话题相关性 + 重要性 + 时间**
 * 对事件记忆做有界召回。
 *
 * 可解释的本地方案(路线图 §6:记忆相关度先用本地可解释方案,embedding 不是
 * 第一版依赖):query 切词(中文二元组 + 拉丁/数字词)→ 逐条记忆匹配命中词 →
 * 加权得分。得分构成全程可打印:
 *
 *     score = 2.0 × 命中词数 + 时间分 + 0.2 × importance
 *
 * - 时间分:≤3 天 +1.5 / ≤14 天 +1.0 / ≤60 天 +0.4 / 更早 0(可解释的时间档,
 *   不用指数衰减;零命中话题时排序自动退化为「时间+重要性」,仍是有效回退);
 * - 排序:score 降序 → at 降序 → id 降序(确定性,平分不抖动);
 * - 预算:画像/关系(身份行)单独 [IDENTITY_BUDGET] 条,事件与渐忘分别沿用
 *   [MemoryInjection.NORMAL_MAX]/[MemoryInjection.FADED_MAX](分预算注入)。
 *
 * 角色隔离:召回只对调用方传入的该角色记忆列表排序(注入链路
 * `MemoryDao.forCharacter(characterId)` 已按角色分片),不在本层做跨角色合并。
 * 向量检索为可选增强:后续若接入 embedding 服务,只需替换本层的得分实现,
 * 注入预算与渲染层不变;检索服务不可用/零命中时本层退化为时间+重要性排序,
 * 不阻断聊天。
 */
object MemoryRecall {

    /** 画像 + 关系行(身份性记忆)的注入预算(条);事件走注入层既有预算。 */
    const val IDENTITY_BUDGET = 4

    /** 单条记忆的打分结果(带命中词,面板/调试可解释)。 */
    data class Scored(
        val memory: MemoryEntity,
        val score: Double,
        /** 命中的 query 词(可解释排序依据;空 = 纯时间+重要性排序)。 */
        val matchedTerms: List<String>,
    )

    /** 召回产出:身份行(画像+关系)、事件(normal 带)、渐忘事件(faded 带)。 */
    data class Recall(
        val identity: List<MemoryEntity>,
        val events: List<MemoryEntity>,
        val faded: List<MemoryEntity>,
        val scoredEvents: List<Scored>,
    ) {
        val isEmpty: Boolean get() = identity.isEmpty() && events.isEmpty() && faded.isEmpty()
    }

    /**
     * query 切词:中文连续段滑窗成二元组(单字段保留单字),拉丁/数字连续段
     * 成小写词;去重保序。无重依赖、可解释、JVM 可测。
     */
    fun extractTerms(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val terms = LinkedHashSet<String>()
        val runs = Regex("[\\p{L}\\p{N}]+").findAll(query)
        for (run in runs) {
            val text = run.value
            if (text.any { it.code > 0x2E7F }) {
                // 含 CJK 段:滑窗二元组(单字段保留单字)
                if (text.length == 1) {
                    terms += text
                } else {
                    for (i in 0 until text.length - 1) terms += text.substring(i, i + 2)
                }
            } else {
                terms += text.lowercase()
            }
        }
        return terms.toList()
    }

    /**
     * 打分排序(全量输入,不做预算截断;预算由 [recall] 分带执行):
     * [nowMs] 注入便于单测控制时间档。
     */
    fun score(memories: List<MemoryEntity>, query: String, nowMs: Long): List<Scored> {
        val terms = extractTerms(query)
        return memories.asSequence().map { m ->
            val content = m.content.lowercase()
            val matched = terms.filter { content.contains(it) }
            val ageDays = ((nowMs - m.at).coerceAtLeast(0L)) / DAY_MS
            val recency = when {
                ageDays <= 3 -> 1.5
                ageDays <= 14 -> 1.0
                ageDays <= 60 -> 0.4
                else -> 0.0
            }
            Scored(
                memory = m,
                score = matched.size * TOPIC_WEIGHT + recency + m.importance * IMPORTANCE_WEIGHT,
                matchedTerms = matched,
            )
        }.sortedWith(
            compareByDescending<Scored> { it.score }
                .thenByDescending { it.memory.at }
                .thenByDescending { it.memory.id },
        ).toList()
    }

    /**
     * 有界召回:身份行(画像+关系,原相对顺序保序)取前 [IDENTITY_BUDGET] 条;
     * 事件按 [MemoryCapacity.band] 分带后,normal 带取前 [MemoryInjection.NORMAL_MAX]
     * 条、faded 带取前 [MemoryInjection.FADED_MAX] 条(均按得分序)。
     */
    fun recall(memories: List<MemoryEntity>, query: String, nowMs: Long = System.currentTimeMillis()): Recall {
        val identity = memories.filter {
            it.category == MemoryCategories.PROFILE || it.category == MemoryCategories.RELATIONSHIP
        }.take(IDENTITY_BUDGET)
        val (banded, _) = MemoryCapacity.band(memories)
        val scored = score(banded.map { it.memory }, query, nowMs)
        // faded 带按对象身份回标(手写行/入库前行 id 可能同为 0,不按 id 判)
        val fadedByIdentity = java.util.IdentityHashMap<MemoryEntity, Boolean>()
        banded.filter { it.faded }.forEach { fadedByIdentity[it.memory] = true }
        // 事件带内打分排序(normal/faded 各自取预算);画像/关系行已在
        // identity 单独取走,这里按分类滤掉避免混入事件预算。
        val events = scored.filter {
            it.memory.category == MemoryCategories.EVENT && !fadedByIdentity.containsKey(it.memory)
        }
        val faded = scored.filter {
            it.memory.category == MemoryCategories.EVENT && fadedByIdentity.containsKey(it.memory)
        }
        return Recall(
            identity = identity,
            events = events.take(MemoryInjection.NORMAL_MAX).map { it.memory },
            faded = faded.take(MemoryInjection.FADED_MAX).map { it.memory },
            scoredEvents = events,
        )
    }

    private const val TOPIC_WEIGHT = 2.0
    private const val IMPORTANCE_WEIGHT = 0.2
    private const val DAY_MS = 24 * 60 * 60 * 1000L
}
