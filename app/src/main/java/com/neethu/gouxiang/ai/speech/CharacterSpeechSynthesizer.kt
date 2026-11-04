package com.neethu.gouxiang.ai.speech

import android.util.Log
import com.neethu.gouxiang.ai.config.AiConfig
import com.neethu.gouxiang.ai.config.AiProvider
import com.neethu.gouxiang.ai.config.resolveVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** A character's chosen Volcano voices, in order. Null means the character is unconfirmed. */
data class SpeechVoice(val primary: String?, val fallback: String? = null)

/**
 * Chat, opening speech and calls share this policy. Volcano character voices use only
 * their explicitly supplied compatible fallback; SiliconFlow uses its own global voice.
 */
internal class CharacterSpeechSynthesizer(
    private val adapter: (String) -> TtsAdapter,
) {
    internal var siliconAdapter: (String) -> TtsAdapter = {
        OpenAiCompatibleTtsAdapter(AiProvider.SILICONFLOW.baseUrl, it)
    }

    suspend fun synthesize(text: String, config: AiConfig, voice: SpeechVoice, contextText: String = ""): TtsResult? {
        val key = config.effectiveTtsKey()
        if (key.isBlank()) return null
        if (config.ttsProvider == AiProvider.SILICONFLOW) {
            // 人物音色来自火山目录，不能作为硅基音色发送。
            return try {
                siliconAdapter(key).synthesize(text, TtsConfig(
                    model = AiProvider.SILICONFLOW.defaultTtsModel,
                    voice = resolveVoice(AiProvider.SILICONFLOW, config.siliconflowTtsVoice),
                    responseFormat = "wav", sampleRate = 24_000, rawPcmSampleRate = 24_000,
                ))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("CharacterSpeech", "SiliconFlow TTS failed", e)
                null
            }
        }
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
