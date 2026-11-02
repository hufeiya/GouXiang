package com.neethu.gouxiang.ai.speech

import com.neethu.gouxiang.ai.config.AiConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VolcanoSpeechInstructionTest {
    private val adapter = VolcanoEngineTtsAdapter("test")
    private fun params(instruction: String, voice: String = "zh_female_vv_uranus_bigtts") = Json.parseToJsonElement(
        adapter.startSessionPayload(voice, 16000, instruction).decodeToString()
    ).jsonObject.getValue("req_params").jsonObject

    @Test
    fun `instruction is encoded as a JSON string inside additions`() {
        val instruction = "用\"温柔\"的语气说话"
        val additions = Json.parseToJsonElement(params("  $instruction  ").getValue("additions").jsonPrimitive.content).jsonObject
        assertEquals(instruction, additions.getValue("context_texts").jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `blank instruction keeps the original request`() {
        assertFalse(params("  ").containsKey("additions"))
    }

    @Test
    fun `unsupported and unknown voices omit the instruction`() {
        assertFalse(params("轻声说", "ICL_uranus_zh_female_chunzhenshaonv_tob").containsKey("additions"))
        assertFalse(params("轻声说", "unknown_uranus_bigtts").containsKey("additions"))
    }

    @Test
    fun `character synthesis forwards the configured instruction without reading it aloud`() = runTest {
        var seenText = ""
        var seenConfig: TtsConfig? = null
        val synthesizer = CharacterSpeechSynthesizer {
            object : TtsAdapter {
                override suspend fun synthesize(text: String, config: TtsConfig): TtsResult {
                    seenText = text
                    seenConfig = config
                    return TtsResult(byteArrayOf(1), TtsAudioFormat.RAW_PCM_16LE)
                }
            }
        }
        synthesizer.synthesize("你好", AiConfig(apiKeyVolcanoTts = "key", ttsInstruction = "轻声说"), SpeechVoice("voice"))
        assertEquals("你好", seenText)
        assertEquals("轻声说", seenConfig?.instruction)
    }
}
