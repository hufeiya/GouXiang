package com.neethu.gouxiang.phone

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 手机聊天语音条播放器(Android TTS)。
 *
 * 范围说明:本地 TTS 可用即「•)N″」语音条可点击播放(转写文本);TTS 初始化
 * 失败/无语音数据时语音条保持占位可点但不发声(不崩溃,验收范围 1 明确
 * 「可 TTS 播放或占位」)。
 *
 * 生命周期:单例缓存在进程内;[play]/[stop] 由语音条点击驱动;同一时刻只播
 * 一条(新播放打断旧播放,微信语音条语义)。
 */
class VoicePlayer(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext

    /** TTS 就绪态(初始化异步;失败保持 false,语音条退化为纯占位)。 */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** 正在播放的语音条消息 id(null=空闲)。 */
    private val _playingId = MutableStateFlow<Long?>(null)
    val playingId: StateFlow<Long?> = _playingId.asStateFlow()

    private var tts: TextToSpeech? = try {
        TextToSpeech(appContext, this)
    } catch (_: Exception) {
        null
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS && tts != null) {
            // 中文优先;不支持中文的系统引擎仍可用(utterance 会静音但不崩)
            val result = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
            _ready.value = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
        } else {
            _ready.value = false
        }
    }

    /** 播放一条语音条(messageId 即 utteranceId);打断当前播放。 */
    fun play(messageId: Long, text: String) {
        val engine = tts ?: return
        stop()
        if (text.isBlank()) return
        val utteranceId = messageId.toString()
        _playingId.value = messageId
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { /* 状态已置 */ }
            override fun onDone(utteranceId: String?) {
                if (utteranceId != null && _playingId.value.toString() == utteranceId) {
                    _playingId.value = null
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId != null && _playingId.value.toString() == utteranceId) {
                    _playingId.value = null
                }
            }
        })
        try {
            engine.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                utteranceId,
            )
        } catch (_: Exception) {
            _playingId.value = null
        }
    }

    /** 停止播放(退出页面/点击新语音条时)。 */
    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {
            // 引擎已死也无需恢复
        }
        _playingId.value = null
    }

    fun shutdown() {
        stop()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        _ready.value = false
    }
}
