package com.neethu.gouxiang.ai.speech

import android.util.Log
import com.neethu.gouxiang.BuildConfig
import com.neethu.gouxiang.ai.config.AiConfig
import com.neethu.gouxiang.ai.config.AiProvider
import com.neethu.gouxiang.ai.config.resolveVoice
import com.neethu.gouxiang.protocol.Bubble
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** 普通聊天回复的自动朗读。调用方在静音或离开聊天时调用 [stop]。 */
class ChatSpeechPlayer internal constructor(
    private val scope: CoroutineScope,
    private val configSource: () -> AiConfig,
    private val volcanoAdapter: (String) -> TtsAdapter,
    private val siliconAdapter: (String) -> TtsAdapter,
    private val playback: ChatSpeechPlayback,
) {
    constructor(scope: CoroutineScope, configSource: () -> AiConfig) : this(
        scope, configSource,
        { VolcanoEngineTtsAdapter(it) },
        { OpenAiCompatibleTtsAdapter(BuildConfig.SILICONFLOW_BASE_URL, it) },
        TtsChatPlayback(),
    )

    private var synthesis: Job? = null

    /** 播放本轮文本；新回复会停止上一轮的合成和播放。 */
    fun speak(bubbles: List<Bubble>) {
        stop()
        val text = CallSpeech.composeSpeechText(bubbles).first
        if (text.isBlank()) return
        synthesis = scope.launch {
            val config = configSource()
            val volcanoKey = config.effectiveTtsKey()
            val siliconKey = config.effectiveApiKey(AiProvider.SILICONFLOW)
            val audio = if (volcanoKey.isNotBlank()) {
                synthesize {
                    volcanoAdapter(volcanoKey).synthesize(
                        text,
                        TtsConfig(
                            model = "",
                            voice = resolveVoice(AiProvider.VOLCANO, config.ttsVoice),
                            sampleRate = VolcanoEngineTtsAdapter.DEFAULT_SAMPLE_RATE,
                        ),
                    )
                }
            } else null
            val result = audio ?: if (siliconKey.isNotBlank()) {
                synthesize {
                    siliconAdapter(siliconKey).synthesize(
                        text,
                        TtsConfig(
                            model = VoiceCallEngine.SILICONFLOW_TTS_MODEL,
                            voice = VoiceCallEngine.SILICONFLOW_VOICE,
                            responseFormat = "wav",
                            sampleRate = VolcanoEngineTtsAdapter.DEFAULT_SAMPLE_RATE,
                            rawPcmSampleRate = VolcanoEngineTtsAdapter.DEFAULT_SAMPLE_RATE,
                        ),
                    )
                }
            } else null
            // 合成结束与静音/离页可能同时发生，取消后不得再开始播放。
            coroutineContext.ensureActive()
            if (result != null) playback.play(result)
            else Log.w(TAG, "chat speech unavailable: no configured or working TTS service")
        }
    }

    fun stop() {
        synthesis?.cancel()
        synthesis = null
        playback.stop()
    }

    private suspend fun synthesize(block: suspend () -> TtsResult): TtsResult? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "chat speech synthesis failed", e)
        null
    }

    private companion object {
        const val TAG = "ChatSpeech"
    }
}

internal interface ChatSpeechPlayback {
    fun play(result: TtsResult)
    fun stop()
}

private class TtsChatPlayback : ChatSpeechPlayback {
    private val speaker = TtsSpeaker()
    override fun play(result: TtsResult) = speaker.play(result)
    override fun stop() = speaker.stop()
}
