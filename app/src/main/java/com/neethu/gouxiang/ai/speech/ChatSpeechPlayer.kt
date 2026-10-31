package com.neethu.gouxiang.ai.speech

import android.content.Context
import android.util.Log
import com.neethu.gouxiang.ai.config.AiConfig
import com.neethu.gouxiang.protocol.Bubble
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import java.util.concurrent.atomic.AtomicLong

/** 普通聊天回复的自动朗读。调用方在静音或离开聊天时调用 [stop]。 */
class ChatSpeechPlayer internal constructor(
    private val scope: CoroutineScope,
    private val configSource: () -> AiConfig,
    private val adapter: (String) -> TtsAdapter,
    private val playback: ChatSpeechPlayback,
    private val fallback: ChatSystemSpeech,
) {
    constructor(context: Context, scope: CoroutineScope, configSource: () -> AiConfig) : this(
        scope, configSource, { VolcanoEngineTtsAdapter(it) }, TtsChatPlayback(),
        SystemChatSpeech(context.applicationContext),
    )

    private var synthesis: Job? = null
    private val generation = AtomicLong()

    private val _speaking = MutableStateFlow(false)

    /** 是否正在准备或朗读，准备期间也允许用户取消。 */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** 音色随会话异步读取，取消时不会播放过期的回复。 */
    fun speak(bubbles: List<Bubble>, onUnavailable: () -> Unit = {}, voiceSource: suspend () -> SpeechVoice) {
        speakText(CallSpeech.composeSpeechText(bubbles).first, onUnavailable, voiceSource)
    }

    /**
     * 朗读一段整句文本(心声详情等非聊天泡场景):同一合成/播放/系统兜底链,
     * 复用 generation token 防过期播放。准备时置 [speaking],播完/打断回零。
     */
    fun speakText(text: String, onUnavailable: () -> Unit = {}, voiceSource: suspend () -> SpeechVoice) {
        stop()
        val token = generation.get()
        if (text.isBlank()) return
        _speaking.value = true
        synthesis = scope.launch {
            try {
                val voice = voiceSource()
                val result = CharacterSpeechSynthesizer(adapter).synthesize(text, configSource(), voice)
                coroutineContext.ensureActive()
                if (generation.get() != token) return@launch
                if (result != null) {
                    playback.play(result) { if (generation.get() == token) _speaking.value = false }
                } else {
                    Log.i(TAG, "Character speech unavailable; trying system speech")
                    val spoken = fallback.play(text) { generation.get() == token }
                    coroutineContext.ensureActive()
                    if (generation.get() == token) _speaking.value = false
                    if (!spoken && generation.get() == token) {
                        Log.w(TAG, "Chat speech unavailable: both character and system speech failed")
                        onUnavailable()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Chat speech failed", e)
                if (generation.get() == token) {
                    _speaking.value = false
                    onUnavailable()
                }
            }
        }
    }

    fun stop() {
        generation.incrementAndGet()
        synthesis?.cancel()
        synthesis = null
        fallback.stop()
        playback.stop()
        _speaking.value = false
    }

    private companion object { const val TAG = "ChatSpeech" }
}

internal interface ChatSystemSpeech {
    suspend fun play(text: String, isCurrent: () -> Boolean): Boolean
    fun stop()
}

private class SystemChatSpeech(context: Context) : ChatSystemSpeech {
    private val player = HomeSystemSpeechPlayer(context)
    override suspend fun play(text: String, isCurrent: () -> Boolean): Boolean =
        player.play(text, isCurrent) { }
    override fun stop() = player.stop()
}

internal interface ChatSpeechPlayback {
    /** [onFinished] 在音频自然播完后回调(打断走 [stop],不回调)。 */
    fun play(result: TtsResult, onFinished: () -> Unit)
    fun stop()
}

private class TtsChatPlayback : ChatSpeechPlayback {
    private val speaker = TtsSpeaker()
    override fun play(result: TtsResult, onFinished: () -> Unit) {
        speaker.onPlaybackFinished = onFinished
        speaker.play(result)
        // 早退分支(格式不支持/空 PCM/AudioTrack 建失败)不会走完成回调,就地收态
        if (!speaker.playing) onFinished()
    }
    override fun stop() = speaker.stop()
}
