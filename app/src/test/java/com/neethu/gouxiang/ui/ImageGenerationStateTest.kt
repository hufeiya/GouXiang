package com.neethu.gouxiang.ui

import com.neethu.gouxiang.data.DerivativeResultEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageGenerationStateTest {
    @Test fun `batch image state maps every card and ignores invalid ids`() {
        val states = imageStates(listOf(DerivativeResultEntity(
            requestId = "request", kind = "INNER_VOICE", characterId = 7,
            resultIds = "12,13,invalid", imageStatus = "failed", sourceConversationId = 3,
        )))
        assertEquals(setOf(12L, 13L), states.keys)
        assertEquals(ImageGenerationState("request", "failed", 3), states[12L])
        assertEquals(states[12L], states[13L])
    }
}
