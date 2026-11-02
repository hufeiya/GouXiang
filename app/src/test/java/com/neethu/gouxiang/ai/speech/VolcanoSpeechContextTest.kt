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

class VolcanoSpeechContextTest {
    private val adapter = VolcanoEngineTtsAdapter("test")
    private val voice = "zh_female_vv_uranus_bigtts"

    private fun params(instruction: String, context: String, speaker: String = voice) =
        Json.parseToJsonElement(adapter.startSessionPayload(speaker, 16000, instruction, context).decodeToString())
            .jsonObject.getValue("req_params").jsonObject

    private fun contextTexts(instruction: String, context: String) =
        Json.parseToJsonElement(params(instruction, context).getValue("additions").jsonPrimitive.content)
            .jsonObject.getValue("context_texts").jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `quoted context takes precedence over instruction and is encoded safely`() {
        val context = "你说\"北京\"怎么样？\n我很想去。"
        assertEquals(listOf(context), contextTexts("用温柔的语气说", "  $context  "))
    }

    @Test
    fun `empty context falls back to instruction`() {
        assertEquals(listOf("轻声说"), contextTexts("  轻声说  ", " \n "))
        assertFalse(params("  ", " \n ").containsKey("additions"))
    }

    @Test
    fun `unsupported or unknown voice does not receive context`() {
        assertFalse(params("轻声说", "你好", "ICL_uranus_zh_female_chunzhenshaonv_tob").containsKey("additions"))
        assertFalse(params("", "你好", "unknown_uranus_bigtts").containsKey("additions"))
    }

    @Test
    fun `character synthesis forwards context only when enabled and never reads it aloud`() = runTest {
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
        val config = AiConfig(apiKeyVolcanoTts = "key", ttsInstruction = "轻声说", ttsQuoteContext = true)
        synthesizer.synthesize("这是回复", config, SpeechVoice(voice), contextText = "这是上文")
        assertEquals("这是回复", seenText)
        assertEquals("这是上文", seenConfig?.contextText)
        assertEquals("轻声说", seenConfig?.instruction)

        synthesizer.synthesize("这是回复", config.copy(ttsQuoteContext = false), SpeechVoice(voice), contextText = "这是上文")
        assertEquals("这是回复", seenText)
        assertEquals("", seenConfig?.contextText)
        assertEquals("轻声说", seenConfig?.instruction)
    }
}
