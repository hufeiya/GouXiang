package com.neethu.gouxiang.ai.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TtsQuoteContextStoreTest {
    @Test
    fun `quoted context defaults off and can be enabled and disabled`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("ai_config_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            assertFalse(AiConfigStore.load(context).ttsQuoteContext)
            AiConfigStore.save(context, AiConfig(ttsQuoteContext = true))
            assertTrue(AiConfigStore.load(context).ttsQuoteContext)
            AiConfigStore.save(context, AiConfigStore.load(context).copy(ttsQuoteContext = false))
            assertFalse(AiConfigStore.load(context).ttsQuoteContext)
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
