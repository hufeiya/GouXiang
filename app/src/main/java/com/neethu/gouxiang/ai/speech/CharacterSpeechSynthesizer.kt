package com.neethu.gouxiang.ai.speech

import android.util.Log
import com.neethu.gouxiang.ai.config.AiConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** A character's chosen Volcano voices, in order. Null means the character is unconfirmed. */
data class SpeechVoice(val primary: String?, val fallback: String? = null)

/**
 * Both chat and calls use the same policy. A known character only falls back to the
 * explicitly supplied compatible voice; an unknown character may use an explicitly
 * selected global voice. An empty setting must never silently become a female voice.
 */
internal class CharacterSpeechSynthesizer(
    private val adapter: (String) -> TtsAdapter,
) {
    suspend fun synthesize(text: String, config: AiConfig, voice: SpeechVoice, contextText: String = ""): TtsResult? {
        val key = config.effectiveTtsKey()
        if (key.isBlank()) return null
        val candidates = if (!voice.primary.isNullOrBlank()) {
            listOfNotNull(voice.primary, voice.fallback)
        } else {
            listOf(config.ttsVoice)
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        for (candidate in candidates) {
            coroutineContext.ensureActive()
            try {
                return adapter(key).synthesize(
                    text,
                    TtsConfig(model = "", voice = candidate, sampleRate = VolcanoEngineTtsAdapter.DEFAULT_SAMPLE_RATE,
                        instruction = config.ttsInstruction, contextText = if (config.ttsQuoteContext) contextText else ""),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("CharacterSpeech", "TTS voice failed: $candidate", e)
            }
        }
        return null
    }
}
