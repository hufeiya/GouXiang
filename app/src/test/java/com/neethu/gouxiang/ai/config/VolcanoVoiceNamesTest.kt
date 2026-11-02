package com.neethu.gouxiang.ai.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VolcanoVoiceNamesTest {
    @Test
    fun everySelectableVolcanoVoiceHasAName() {
        AiProvider.VOLCANO.voices.forEach { id ->
            assertNotEquals("Missing display name for $id", id, volcanoVoiceName(id))
        }
        assertEquals("小何 2.0", volcanoVoiceName("zh_female_xiaohe_uranus_bigtts"))
        assertEquals("倾心少女 2.0", volcanoVoiceName("ICL_uranus_zh_female_qingxinshaonv_tob"))
    }

    @Test
    fun unknownVoiceRetainsItsId() {
        assertEquals("custom_voice", volcanoVoiceName("custom_voice"))
    }
}
