package com.neethu.gouxiang.chat

import com.neethu.gouxiang.data.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E 工作包 E1:相关记忆召回(话题相关性+重要性+时间的可解释本地打分)单测。
 * 覆盖:切词 / 相关性优先于时间 / 零命中退化时间+重要性 / 分预算(画像、
 * 事件、渐忘)/ 可解释命中词。
 */
class MemoryRecallTest {

    private val now = 1_800_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    private fun event(id: Long, content: String, at: Long, importance: Int = 5, charId: Long = 1L) =
        MemoryEntity(
            id = id, characterId = charId, category = "event",
            content = content, importance = importance, at = at,
        )

    // ── 切词 ────────────────────────────────────────────────────────

    @Test
    fun `extract terms builds cjk bigrams and latin words`() {
        assertEquals(listOf("周末", "看新", "新房"), MemoryRecall.extractTerms("周末看新房").filter { it in setOf("看新", "新房", "周末") })
        // 单字中文段保留单字;拉丁转小写;去重保序
        val terms = MemoryRecall.extractTerms("看!Movie")
        assertTrue("看" in terms)
        assertTrue("movie" in terms)
        assertEquals(terms.size, terms.toSet().size)
    }

    @Test
    fun `extract terms empty for blank query`() {
        assertTrue(MemoryRecall.extractTerms("  ").isEmpty())
    }

    // ── 相关性优先 ──────────────────────────────────────────────────

    @Test
    fun `topically relevant memory outranks newer irrelevant one`() {
        // A 更新但不相关;B 更旧但话题命中(新房)→ B 排前
        val events = listOf(
            event(1, "一起讨论了搬家计划", at = now - day),
            event(2, "周末一起去看新房,看了三个楼盘", at = now - 10 * day),
        )
        val recall = MemoryRecall.recall(events, "新房看得怎么样", now)
        assertEquals("周末一起去看新房,看了三个楼盘", recall.events.first().content)
        assertEquals(listOf("新房"), MemoryRecall.recall(events, "新房", now).scoredEvents.first().matchedTerms)
    }

    @Test
    fun `no match falls back to recency and importance ordering`() {
        val events = listOf(
            event(1, "甲事件", at = now - 2 * day, importance = 5),
            event(2, "乙事件", at = now - 40 * day, importance = 9),
            event(3, "丙事件", at = now - 1 * day, importance = 5),
        )
        // query 与内容零命中:排序退化 —— 最近两天(1.5) > 40 天但重要(0.4+1.8=2.2)
        // 计算事件3=1.5+1.0=2.5 > 事件2=0.4+1.8=2.2 > 事件1 同事件3 平分按 at 降序
        val recall = MemoryRecall.recall(events, "完全无关的话题词", now)
        assertEquals(listOf(3L, 1L, 2L), recall.events.map { it.id })
    }

    @Test
    fun `matched terms are exposed for explainability`() {
        val events = listOf(event(1, "两人约定周五去美术馆看展", at = now - day))
        val scored = MemoryRecall.score(events, "美术馆看展", now).single()
        assertEquals(setOf("美术", "术馆", "馆看", "看展"), scored.matchedTerms.toSet())
        assertTrue(scored.score > 0)
    }

    // ── 分预算注入 ──────────────────────────────────────────────────

    @Test
    fun `identity budget caps profile and relationship lines`() {
        val memories = buildList {
            repeat(6) { i ->
                add(MemoryEntity(id = i.toLong(), characterId = 1, category = "profile", content = "画像$i", importance = 8, at = i.toLong()))
            }
            add(MemoryEntity(id = 90, characterId = 1, category = "relationship", content = "关系很近", importance = 8, at = 99))
            add(MemoryEntity(id = 91, characterId = 1, category = "relationship", content = "关系升温", importance = 8, at = 98))
        }
        val recall = MemoryRecall.recall(memories, "画像", now)
        assertEquals(MemoryRecall.IDENTITY_BUDGET, recall.identity.size)
        // 事件预算空(全是身份行)
        assertTrue(recall.events.isEmpty())
        // 注入块:画像行封顶,关系行也被预算覆盖(共 4 条)
        val block = MemoryInjection.block("苏馨玥", memories, "画像", now)!!
        assertEquals(MemoryRecall.IDENTITY_BUDGET, block.lineSequence().count { it.startsWith("- ") })
    }

    @Test
    fun `event and faded budgets applied separately`() {
        // 25 条事件(容量 20):normal 6 条 + faded 4 条(分预算,不互相挤占)
        val memories = (1..25).map { event(it.toLong(), "事件$it", at = it.toLong()) }
        val recall = MemoryRecall.recall(memories, "", now)
        assertEquals(MemoryInjection.NORMAL_MAX, recall.events.size)
        assertEquals(MemoryInjection.FADED_MAX, recall.faded.size)
        // normal 全部来自容量内(最新 6 条),faded 全部来自容量外(最旧 4 条)
        assertEquals(
            listOf("事件25", "事件24", "事件23", "事件22", "事件21", "事件20"),
            recall.events.map { it.content },
        )
        assertEquals(listOf("事件5", "事件4", "事件3", "事件2"), recall.faded.map { it.content })
    }

    @Test
    fun `injection block keeps relevant event first and faded suffix`() {
        val memories = buildList {
            repeat(10) { i -> add(event(i.toLong() + 1, "无关事件${i + 1}", at = now - i * day)) }
            add(event(100, "两人约好周末一起去看新房", at = now - 5 * day))
        }
        val block = MemoryInjection.block("苏馨玥", memories, "新房", now)!!
        val eventLines = block.lineSequence().filter { it.startsWith("- ") && !it.startsWith("- (") }.toList()
        assertEquals("- 两人约好周末一起去看新房", eventLines.first())
        assertEquals(MemoryInjection.NORMAL_MAX, eventLines.size)
    }

    @Test
    fun `recall never leaks profile rows into event budget`() {
        val memories = listOf(
            MemoryEntity(id = 1, characterId = 1, category = "profile", content = "我的称呼:老公", importance = 8, at = now),
            event(2, "事件行", at = now - day),
        )
        val recall = MemoryRecall.recall(memories, "老公", now)
        assertEquals(1, recall.identity.size)
        assertEquals(1, recall.events.size)
        assertTrue(recall.events.none { it.category != "event" })
    }
}
