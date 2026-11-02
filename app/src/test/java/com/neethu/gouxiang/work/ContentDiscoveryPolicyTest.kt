package com.neethu.gouxiang.work

import org.junit.Assert.*
import org.junit.Test

class ContentDiscoveryPolicyTest {
    @Test fun milestonesAndIdleDoNotDuplicateOrShift() {
        assertFalse(shouldEvaluateContent(7, 7, 0, 0, false))
        assertTrue(shouldEvaluateContent(8, 8, 0, 0, false))
        assertFalse(shouldEvaluateContent(8, 8, 8, 8, true))
        assertFalse(shouldEvaluateContent(10, 10, 8, 8, false))
        assertTrue(shouldEvaluateContent(10, 10, 8, 8, true))
        assertFalse(shouldEvaluateContent(11, 11, 10, 8, false))
        assertTrue(shouldEvaluateContent(12, 12, 10, 8, false))
        assertFalse(shouldEvaluateContent(12, 12, 12, 12, true))
        assertTrue(shouldEvaluateContent(16, 16, 12, 12, false))
    }

    @Test fun parsesMultipleKindsAndLegacyAndFiltersInvalidEntries() {
        val allowed = setOf("HANDBOOK", "NOVEL", "INNER_VOICE", "MOMENT")
        val multi = parseDiscoveryDecision("""{ "decision":"create", "end_turn_index":8,
            "candidates":[{"kind":"HANDBOOK","title":"旧时光"},
            {"kind":"INNER_VOICE","title":"心事"}, {"kind":"INNER_VOICE","title":"重复"},
            {"kind":"MOMENT","title":"  "}, {"kind":"UNKNOWN","title":"无效"}] }""", 10, allowed)
        assertEquals(DiscoveryDecision.Create(8, listOf(DiscoveryTitle("HANDBOOK", "旧时光"), DiscoveryTitle("INNER_VOICE", "心事"))), multi)
        assertEquals(DiscoveryDecision.Create(5, listOf(DiscoveryTitle("NOVEL", "故事"))),
            parseDiscoveryDecision("""```json
                {"decision":"create","end_turn_index":5,"kind":"NOVEL","title":"故事"}
                ```""", 8, allowed))
        assertEquals(DiscoveryDecision.Wait, parseDiscoveryDecision("""{"decision":"wait"}""", 8, allowed))
    }

    @Test fun malformedResponseIsAnErrorRatherThanWait() {
        val allowed = setOf("HANDBOOK")
        assertTrue(runCatching { parseDiscoveryDecision("""{"decision":"create","end_turn_index":8,"candidates":[]}""", 8, allowed) }.isFailure)
        assertTrue(runCatching { parseDiscoveryDecision("""{"decision":"create","end_turn_index":9,"kind":"HANDBOOK","title":"标题"}""", 8, allowed) }.isFailure)
        assertTrue(runCatching { parseDiscoveryDecision("broken", 8, allowed) }.isFailure)
    }

    @Test fun selectionPrefersUnderrepresentedKindsAndRespectsAllSlots() {
        val choices = listOf(DiscoveryTitle("HANDBOOK", "一"), DiscoveryTitle("NOVEL", "二"), DiscoveryTitle("MOMENT", "三"))
        val counts = mapOf("HANDBOOK" to 4, "NOVEL" to 0, "MOMENT" to 0)
        assertEquals(listOf("NOVEL", "MOMENT"), selectDiscoveryKinds(choices, counts, discoveryCapacity(0)).map { it.kind })
        assertEquals(listOf("NOVEL"), selectDiscoveryKinds(choices, counts, discoveryCapacity(19)).map { it.kind })
        assertTrue(selectDiscoveryKinds(choices, counts, discoveryCapacity(20)).isEmpty())
        assertEquals(1, discoveryCapacity(19))
        assertEquals(0, discoveryCapacity(20))
    }

    @Test fun backlogOnlySlidesAfterFullWindowWaitAndFourMoreTurns() {
        val turns = (1L..43L).toList()
        assertEquals(0, discoveryWindowOffset(turns, 0)) // Initial work keeps turns 1..20.
        assertEquals(0, discoveryWindowOffset(turns.take(23), 20))
        assertEquals(12, discoveryWindowOffset(turns, 20)) // WAIT on 1..20; next is 13..32.
        assertEquals((13L..32L).toList(), turns.drop(discoveryWindowOffset(turns, 20)).take(20))
        assertEquals(12, discoveryWindowOffset(turns.drop(12), 32)) // Then 25..43.
        assertEquals(0, discoveryWindowOffset(turns.drop(24), 43)) // No repeat without new material.
        assertTrue(shouldEvaluateContent(12, 20, 0, 0, false)) // CREATE resets evaluation waterline for backlog.
        assertFalse(shouldEvaluateContent(20, 20, 20, 20, true))
    }

    @Test fun waitAtSeventeenThroughNineteenReachesNextMilestoneBeyondWindow() {
        for (lastWait in 17..19) {
            // The window remains capped at turn 20, but the milestone counts every
            // unconsumed turn, including turns 21 and later.
            for (newTurns in 1..3) {
                val total = lastWait + newTurns
                assertFalse(shouldEvaluateContent(total, minOf(total, 20).toLong(), lastWait.toLong(), lastWait, false))
            }
            val total = lastWait + 4
            assertTrue(shouldEvaluateContent(total, minOf(total, 20).toLong(), lastWait.toLong(), lastWait, false))
            // Once the capped window itself returned WAIT, it is not called twice;
            // 24 turns allow the separately controlled overlapping slide.
            assertFalse(shouldEvaluateContent(total + 1, 20, 20, total, true))
            assertEquals(12, discoveryWindowOffset((1L..24L).toList(), 20))
        }
    }
}
