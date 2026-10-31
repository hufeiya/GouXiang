package com.neethu.gouxiang.ai.speech

import android.content.Context
import android.util.Log
import com.neethu.gouxiang.ai.config.AiConfig
import com.neethu.gouxiang.protocol.Bubble
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

    /** 音色随会话异步读取，取消时不会播放过期的回复。 */
    fun speak(bubbles: List<Bubble>, onUnavailable: () -> Unit = {}, voiceSource: suspend () -> SpeechVoice) {
        stop()
        val token = generation.get()
        val text = CallSpeech.composeSpeechText(bubbles).first
        if (text.isBlank()) return
        synthesis = scope.launch {
            val voice = voiceSource()
            val result = CharacterSpeechSynthesizer(adapter).synthesize(text, configSource(), voice)
            coroutineContext.ensureActive()
            if (generation.get() != token) return@launch
            if (result != null) playback.play(result)
            else {
                Log.i(TAG, "Character speech unavailable; trying system speech")
                val spoken = fallback.play(text) { generation.get() == token }
                coroutineContext.ensureActive()
                if (!spoken && generation.get() == token) {
                    Log.w(TAG, "Chat speech unavailable: both character and system speech failed")
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
    fun play(result: TtsResult)
    fun stop()
}

private class TtsChatPlayback : ChatSpeechPlayback {
    private val speaker = TtsSpeaker()
    override fun play(result: TtsResult) = speaker.play(result)
    override fun stop() = speaker.stop()
}
