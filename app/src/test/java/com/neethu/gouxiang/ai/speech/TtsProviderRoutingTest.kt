package com.neethu.gouxiang.ai.speech

import com.neethu.gouxiang.ai.config.AiConfig
import com.neethu.gouxiang.ai.config.AiProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TtsProviderRoutingTest {
    @Test
    fun `siliconflow selection uses its own key and voice instead of volcano character voice`() = runTest {
        val audio = TtsResult(byteArrayOf(1), TtsAudioFormat.WAV)
        var seenKey = ""
        var seenConfig: TtsConfig? = null
        val synthesizer = CharacterSpeechSynthesizer { error("Volcano must not be called") }
        synthesizer.siliconAdapter = { key ->
            seenKey = key
            object : TtsAdapter {
                override suspend fun synthesize(text: String, config: TtsConfig): TtsResult {
                    seenConfig = config
                    return audio
                }
            }
        }
        val result = synthesizer.synthesize("你好", AiConfig(
            provider = AiProvider.VOLCANO, ttsProvider = AiProvider.SILICONFLOW,
            apiKeySiliconflow = "silicon-key", apiKeyVolcanoTts = "volcano-key",
            siliconflowTtsVoice = "benjamin", ttsInstruction = "轻声说", ttsQuoteContext = true,
        ), SpeechVoice("volcano-primary", "volcano-fallback"), "上文")
        assertSame(audio, result)
        assertEquals("silicon-key", seenKey)
        assertEquals("FunAudioLLM/CosyVoice2-0.5B", seenConfig?.model)
        assertEquals("FunAudioLLM/CosyVoice2-0.5B:benjamin", seenConfig?.voice)
        assertEquals(24_000, seenConfig?.sampleRate)
        assertEquals(24_000, seenConfig?.rawPcmSampleRate)
        assertEquals("", seenConfig?.instruction)
        assertEquals("", seenConfig?.contextText)
    }
}
