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
}
