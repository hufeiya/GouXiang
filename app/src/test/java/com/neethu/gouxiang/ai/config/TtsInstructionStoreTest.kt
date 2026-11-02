package com.neethu.gouxiang.ai.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TtsInstructionStoreTest {
    @Test
    fun `instruction persists and can be cleared`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AiConfigStore.save(context, AiConfig(ttsInstruction = "用温柔的语气说话"))
        assertEquals("用温柔的语气说话", AiConfigStore.load(context).ttsInstruction)
        AiConfigStore.save(context, AiConfigStore.load(context).copy(ttsInstruction = ""))
        assertEquals("", AiConfigStore.load(context).ttsInstruction)
    }
}
