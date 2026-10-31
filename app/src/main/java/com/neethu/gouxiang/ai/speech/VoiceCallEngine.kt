package com.neethu.gouxiang.ai.speech

import android.app.Application
import android.util.Log
import com.neethu.gouxiang.BuildConfig
import com.neethu.gouxiang.protocol.Bubble
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 语音通话 F6 的回合状态机(12 号页的"大脑"):
 *
 * ```
 * 聆听中(LISTENING) ──按住说话──▶ (松手) 识别中(RECOGNIZING)
 *     ▲                                │ ASR 出文本
 *     │                                ▼
 *     └──打断/播完◀── 回复中(SPEAKING)◀── 思考中(THINKING,LLM 回合)
 * ```
 *
 * - 进入即「聆听中」([start] 重置状态);
 * - 回合 = ASR 文本作为用户消息进聊天引擎([runChatRound] 由 MainViewModel
 *   接线,消息落库/会话行刷新/记忆收尾与 IM 完全同路)→ 回复泡转口语 →
 *   TTS 合成播放,字幕同步;
 * - 打断([interrupt])= 停止播放回到聆听;回合仍在跑(LLM 流式中)时打断
 *   只拦掉"开口说"这一步,消息照常落库(与挂断后消息已保存的语义一致);
 * - TTS 使用人物音色及同类备选音色;不可用时
 *   字幕照出,停留 4s 后回聆听(页面永不崩)。
 */
class VoiceCallEngine(
    @Suppress("unused") private val app: Application,
    private val scope: CoroutineScope,
    /** 聊天回合接线(MainViewModel 注入:用户消息落库 + 编排器整回合)。 */
    private val runChatRound: suspend (userText: String) -> List<Bubble>,
    /**
     * AI 配置源(「我的 → AI 服务配置」可改,每回合现取):消费 通话合成 Key
     * (豆包语音,留空回落 BuildConfig)/ 识别的硅基流动 Key / 通话音色 /
     * ASR 模型名。缺省 = 全默认(与配置页未接线时代行为一致)。
     */
    private val aiConfigSource: () -> com.neethu.gouxiang.ai.config.AiConfig = {
        com.neethu.gouxiang.ai.config.AiConfig()
    },
    private val voiceSource: suspend () -> SpeechVoice = { SpeechVoice(null) },
) {

    enum class Phase { LISTENING, RECOGNIZING, THINKING, SPEAKING }

    data class UiState(
        val phase: Phase = Phase.LISTENING,
        /** 大字幕:TTS 正在念/刚念完的口语词(空=还没开过口)。 */
        val caption: String = "",
        /** 本轮回复"进了哪些泡"的字幕摘要(贴纸/图片等不可念段给描述)。 */
        val seenLines: List<String> = emptyList(),
        /** 按住说话进行中(按住条高亮 + 文案「松开 发送」)。 */
        val holdActive: Boolean = false,
        /** 底部一行小字提示(没听清/麦克风被占用/回合失败),空=不显示。 */
        val notice: String = "",
    ) {
        /** 提示条文案(tv_tips):按阶段切换,对齐参考页「聆听中/点击打断」。 */
        val tipsText: String
            get() = when (phase) {
                Phase.LISTENING -> "聆听中"
                Phase.RECOGNIZING -> "识别中"
                Phase.THINKING -> "思考中"
                Phase.SPEAKING -> "点击打断"
            }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val speaker = TtsSpeaker()
    private val recorder = WavRecorder()

    /** 播放完成信号(正常播完 / 打断都会 complete,回合协程在此收口)。 */
    private var playDone = CompletableDeferred<Unit>()

    /** 回合进行中(SPEAKING 播放也计入,防重入)。 */
    private var turnJob: Job? = null

    /** 打断标记:回合收口时若已打断则不再回 SPEAKING。 */
    @Volatile private var interruptRequested = false

    init {
        speaker.onPlaybackFinished = { playDone.complete(Unit) }
    }

    /** 进入通话页:重置为「聆听中」。 */
    fun start() {
        turnJob?.cancel()
        turnJob = null
        interruptRequested = false
        speaker.stop()
        playDone = CompletableDeferred()
        _state.value = UiState()
    }

    /** 按住说话:按下开录(麦克风被占用时给提示,不崩)。 */
    fun holdStart() {
        if (turnJob?.isActive == true) return
        try {
            recorder.start()
            _state.update { it.copy(holdActive = true, notice = "") }
        } catch (e: Exception) {
            Log.w(TAG, "mic start failed", e)
            _state.update { it.copy(holdActive = false, notice = "麦克风不可用,检查录音权限") }
        }
    }

    /** 松手:停录 → 识别 → 回合 → 开口说。 */
    fun holdRelease() {
        if (_state.value.holdActive.not()) return
        _state.update { it.copy(holdActive = false) }
        runTurn {
            val wav = withContext(Dispatchers.IO) {
                recorder.stop()
                recorder.takeWav()
            }
            if (wav.size <= MIN_WAV_BYTES) "" else transcribe(wav)
        }
    }

    /** 调试文本输入(验收用):跳过 ASR,直接进回合。 */
    fun sendDebugText(text: String) {
        if (turnJob?.isActive == true) return
        val t = text.trim()
        if (t.isEmpty()) return
        runTurn { t }
    }

    /** 打断:停止播放回到聆听。回合还在跑时只拦掉"开口说",消息照常落库。 */
    fun interrupt() {
        if (_state.value.phase != Phase.SPEAKING) return
        interruptRequested = true
        speaker.stop()
        playDone.complete(Unit)
        _state.update { it.copy(phase = Phase.LISTENING) }
    }

    /** 挂断/退出页面:停播放、停录音、取消回合(已落库消息保留)。 */
    fun close() {
        turnJob?.cancel()
        turnJob = null
        interruptRequested = false
        speaker.stop()
        if (_state.value.holdActive) {
            _state.value = _state.value.copy(holdActive = false)
            try { recorder.stop() } catch (_: Exception) { }
        }
        _state.value = UiState()
    }

    /** 回合公共体:[audioSource] 产出用户口语文本(ASR 或调试直填)。 */
    private fun runTurn(userTextSource: suspend () -> String) {
        if (turnJob?.isActive == true) return
        interruptRequested = false
        turnJob = scope.launch {
            try {
                _state.update { it.copy(phase = Phase.RECOGNIZING, notice = "") }
                val userText = userTextSource().trim()
                if (userText.isEmpty()) {
                    _state.update { it.copy(phase = Phase.LISTENING, notice = "没听清,再试一次?") }
                    return@launch
                }
                _state.update { it.copy(phase = Phase.THINKING) }
                val bubbles = runChatRound(userText)
                val (speech, seen) = CallSpeech.composeSpeechText(bubbles)
                val caption = CallSpeech.speechOrFallback(speech)
                _state.update { it.copy(caption = caption, seenLines = seen) }
                if (interruptRequested) {
                    // 打断落在回合进行中:开口说这步拦掉,字幕保留
                    _state.update { it.copy(phase = Phase.LISTENING) }
                    return@launch
                }
                _state.update { it.copy(phase = Phase.SPEAKING) }
                playDone = CompletableDeferred()
                if (speak(caption).not()) {
                    // TTS 全挂:字幕照出,停 4s 回聆听(期间可打断/挂断)
                    withTimeoutOrNull(TTS_FAIL_HOLD_MS) { playDone.await() }
                    if (interruptRequested.not()) {
                        _state.update { it.copy(phase = Phase.LISTENING, notice = "音色未设置或语音合成失败，请在人物编辑中选择音色并检查语音服务配置") }
                    }
                    return@launch
                }
                playDone.await()
                if (interruptRequested.not()) {
                    _state.update { it.copy(phase = Phase.LISTENING) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "voice turn failed", e)
                _state.update { it.copy(phase = Phase.LISTENING, notice = "这轮没接上,再试一次?") }
            }
        }
    }

    /** 识别:WAV → 文本;失败返回空串(上层走「没听清」),页面永不崩。 */
    private suspend fun transcribe(wav: ByteArray): String = withContext(Dispatchers.IO) {
        try {
            // 模型留空按端点推断(硅基 → Qwen3-ASR-1.7B,与历史行为一致);
            // Key = 配置页硅基流动生效值(留空回落 BuildConfig)
            val cfg = aiConfigSource()
            val key = cfg.effectiveAsrKey()
            val model = com.neethu.gouxiang.ai.config.resolveAsrModel(
                BuildConfig.SILICONFLOW_BASE_URL, cfg.asrModel,
            )
            asr(key, model).transcribe(wav, "audio/wav", AsrConfig(model = model, language = "zh"))
        } catch (e: Exception) {
            Log.w(TAG, "ASR failed", e)
            ""
        }
    }

    /** 开口说:合成 + 播放。true = 已开始播(完成/打断由 [playDone] 收口)。 */
    private suspend fun speak(text: String): Boolean {
        val result = CharacterSpeechSynthesizer(::volcanoTts)
            .synthesize(text, aiConfigSource(), voiceSource()) ?: return false
        speaker.play(result)
        return true
    }

    // 适配器按 (Key[, 模型]) 缓存:配置页改 Key 后下次回合用新适配器,
    // 不用杀通话页(旧适配器随重建被替换)
    private var asrAdapter: AsrAdapter? = null
    private var asrCacheKey: Pair<String, String>? = null
    private var volcanoAdapter: TtsAdapter? = null
    private var volcanoCacheKey: String? = null

    private fun asr(key: String, model: String): AsrAdapter {
        val cached = asrCacheKey
        if (asrAdapter != null && cached == key to model) return asrAdapter!!
        return OpenAiCompatibleAsrAdapter(
            BuildConfig.SILICONFLOW_BASE_URL,
            key,
        ).also { asrAdapter = it; asrCacheKey = key to model }
    }

    private fun volcanoTts(key: String): TtsAdapter {
        if (volcanoAdapter != null && volcanoCacheKey == key) return volcanoAdapter!!
        return VolcanoEngineTtsAdapter(key).also { volcanoAdapter = it; volcanoCacheKey = key }
    }

    companion object {
        private const val TAG = "VoiceCall"

        /** ASR:硅基流动 Qwen3-ASR(audios/transcriptions,wav 实测稳)。 */
        const val ASR_MODEL = "Qwen/Qwen3-ASR-1.7B"

        /** 短于 0.1s 的录音直接当误触丢弃(44B 头 + 1600B = 50ms@16k×2B)。 */
        const val MIN_WAV_BYTES = 44 + 1600

        /** TTS 全挂时字幕停留时长(毫秒)。 */
        const val TTS_FAIL_HOLD_MS = 4_000L
    }
}
