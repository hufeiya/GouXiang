package com.neethu.gouxiang.ai.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AsrConfigTest {
    @Test fun `provider and local endpoint persist independently of llm`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = AiConfig(provider = AiProvider.VOLCANO, asrProvider = AsrProvider.OPENROUTER,
            asrLocalBaseUrl = "http://127.0.0.1:8080/v1", asrLocalModel = "local-model")
        AiConfigStore.save(context, config)
        val loaded = AiConfigStore.load(context)
        assertEquals(AsrProvider.OPENROUTER, loaded.asrProvider)
        assertEquals(AiProvider.OPENROUTER, loaded.resolvedAsrCloudProvider())
        assertEquals(config.asrLocalBaseUrl, loaded.asrLocalBaseUrl)
        assertEquals("local-model", loaded.asrLocalModel)
    }

    @Test fun `automatic cloud provider follows supported llm provider`() {
        assertEquals(AiProvider.OPENROUTER, AiConfig(provider = AiProvider.OPENROUTER).resolvedAsrCloudProvider())
        assertEquals(AiProvider.CUSTOM, AiConfig(provider = AiProvider.CUSTOM).resolvedAsrCloudProvider())
        assertEquals(AiProvider.SILICONFLOW, AiConfig(provider = AiProvider.BAILIAN).resolvedAsrCloudProvider())
        assertEquals(AiProvider.SILICONFLOW, AiConfig(provider = AiProvider.VOLCANO).resolvedAsrCloudProvider())
    }
}
