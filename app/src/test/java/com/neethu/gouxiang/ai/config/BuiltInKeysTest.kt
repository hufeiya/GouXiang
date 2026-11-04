package com.neethu.gouxiang.ai.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BuiltInKeysTest {
    @Test
    fun `skipping setup disables bundled keys and explicit keys still work`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AiConfigStore.save(context, AiConfig(provider = AiProvider.VOLCANO, disableBuiltInKeys = true))
        val skipped = AiConfigStore.load(context)
        assertTrue(skipped.disableBuiltInKeys)
        assertFalse(skipped.isConfigured)
        assertEquals("", skipped.llmEndpoint().apiKey)
        assertEquals("", skipped.effectiveTtsKey())

        val configured = skipped.copy(apiKeyVolcano = "user-key")
        assertTrue(configured.isConfigured)
        assertEquals("user-key", configured.llmEndpoint().apiKey)
    }
}
