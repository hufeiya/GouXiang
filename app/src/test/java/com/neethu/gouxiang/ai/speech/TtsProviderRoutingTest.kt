package com.neethu.gouxiang.ai.speech

import com.neethu.gouxiang.ai.config.AiConfig
import com.neethu.gouxiang.ai.config.AiProvider
import com.neethu.gouxiang.ai.config.TtsEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TtsProviderRoutingTest {
    @Test
    fun `edge selection needs no key and uses decoded audio`() = runTest {
        val synthesizer = CharacterSpeechSynthesizer { error("Volcano must not be called") }
        val decoded = TtsResult(byteArrayOf(2), TtsAudioFormat.RAW_PCM_16LE, 24_000)
        synthesizer.edgeAdapter = { object : TtsAdapter {
            override suspend fun synthesize(text: String, config: TtsConfig): TtsResult {
                assertEquals("你好", text)
                assertEquals("", config.voice)
                return TtsResult(byteArrayOf(1), TtsAudioFormat.MP3)
            }
        } }
        synthesizer.edgeDecoder = { bytes ->
            assertEquals(1, bytes.single().toInt())
            decoded
        }
        assertSame(decoded, synthesizer.synthesize("你好", AiConfig(ttsEngine = TtsEngine.EDGE), SpeechVoice("volcano")))
    }

    @Test
    fun `edge authentication token matches known answer`() {
        assertEquals(
            "2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2",
            EdgeTtsDrm.generateSecMsGec(1_700_000_000),
        )
    }

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
