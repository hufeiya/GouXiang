package com.neethu.gouxiang.ai.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TtsProviderStoreTest {
    @Test
    fun `tts choice and voice persist independently of llm provider`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = AiConfig(provider = AiProvider.DEEPSEEK, ttsProvider = AiProvider.SILICONFLOW, ttsEngine = TtsEngine.EDGE,
            siliconflowTtsVoice = "benjamin", ttsVoice = "volcano-voice")
        AiConfigStore.save(context, config)
        val loaded = AiConfigStore.load(context)
        assertEquals(AiProvider.DEEPSEEK, loaded.provider)
        assertEquals(AiProvider.SILICONFLOW, loaded.ttsProvider)
        assertEquals(TtsEngine.EDGE, loaded.ttsEngine)
        assertEquals("benjamin", loaded.siliconflowTtsVoice)
        assertEquals("volcano-voice", loaded.ttsVoice)
        AiConfigStore.save(context, loaded.copy(ttsProvider = AiProvider.VOLCANO))
        assertEquals("benjamin", AiConfigStore.load(context).siliconflowTtsVoice)
    }
}
