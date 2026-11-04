package com.neethu.gouxiang.ai.speech

import android.app.Application
import android.util.Log
import com.neethu.gouxiang.BuildConfig
import com.neethu.gouxiang.protocol.Bubble
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 语音通话 F6 的回合状态机(12 号页的"大脑",2026-11-04 全双工改造):
 *
 * ```
 * 聆听中(LISTENING) ──说完话停顿 N1 秒(断句滑块)──▶ 识别中(RECOGNIZING)
 *     ▲  ▲  │静默 N2 秒(主动接话滑块)→ 人物主动开口        │ ASR 出文本
 *     │  │  ▼                                          ▼
 *     └──┼─打断/播完◀── 回复中(SPEAKING)◀────── 思考中(THINKING,LLM 回合)
 *        └── SPEAKING 中用户开口(VAD+去抖)= barge-in,停播继续听
 * ```
 *
 * - **全双工**:麦克风全程常开([CallMicStreamer]:VOICE_COMMUNICATION 音源 +
 *   系统 AEC + WebRTC VAD),不再有按住说话;事件驱动本状态机;
 * - **打断**:SPEAKING 播放中 VAD 判定用户开口(滑窗多数帧去抖,AEC 削回声)
 *   → [interrupt] 停播回聆听,收集不中断;整页点按仍是兜底打断;
 * - **断句**:用户说完安静 N1 秒([UtteranceEndpointer] 判定,N1=断句滑块,
 *   0=说完立刻送识别)→ 整段 PCM 包 WAV 走 ASR → 回合与 IM 完全同路
 *   ([runChatRound] 由 MainViewModel 接线,消息落库/记忆收尾一致);
 * - **主动接话**:LISTENING 且用户持续沉默 N2 秒(主动接话滑块)→
 *   [proactiveRound](MainViewModel 接到编排器主动消息链路,无用户消息落库);
 *   连续主动 [MAX_PROACTIVE_STREAK] 次后停,用户再开口即重置(防独白);
 * - 回合进行中新 utterance 进单槽 pending,回合收口后接力(最新一句优先);
 * - TTS 使用人物音色及同类备选音色;不可用时字幕照出,停留 4s 后回聆听
 *   (页面永不崩)。
 */
class VoiceCallEngine(
    @Suppress("unused") private val app: Application,
    private val scope: CoroutineScope,
    /** 聊天回合接线(MainViewModel 注入:用户消息落库 + 编排器整回合)。 */
    private val runChatRound: suspend (userText: String) -> List<Bubble>,
    /**
     * 主动接话回合接线(MainViewModel 注入:编排器 respondProactively,
     * 无用户消息落库)。生成失败/降级返回空列表,引擎静默回聆听。
     */
    private val proactiveRound: suspend () -> List<Bubble> = { emptyList() },
    /**
     * AI 配置源(「我的 → AI 服务配置」可改,现取):消费 通话合成 Key
     * (豆包语音,留空回落 BuildConfig)/ 识别的硅基流动 Key / 通话音色 /
     * ASR 模型名 / 断句停顿 N1 / 静默主动接话 N2。缺省 = 全默认。
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
        /** VAD 判定用户正在说话(收集中,提示条高亮「聆听中…」)。 */
        val userSpeaking: Boolean = false,
        /** 底部一行小字提示(没听清/麦克风被占用/回合失败),空=不显示。 */
        val notice: String = "",
    ) {
        /** 提示条文案:按阶段切换,全双工口径「说话即可打断」。 */
        val tipsText: String
            get() = when (phase) {
                Phase.LISTENING -> if (userSpeaking) "聆听中…" else "聆听中"
                Phase.RECOGNIZING -> "识别中"
                Phase.THINKING -> "思考中"
                Phase.SPEAKING -> "说话即可打断"
            }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 实时麦克风电平(0..1,约 60ms 一拍;独立流避免 UiState 高频重组)。 */
    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val speaker = TtsSpeaker()
    private val mic = CallMicStreamer()

    /** 播放完成信号(正常播完 / 打断都会 complete,回合协程在此收口)。 */
    private var playDone = CompletableDeferred<Unit>()

    /** ASR 回合进行中(SPEAKING 播放也计入)。 */
    private var turnJob: Job? = null

    /** 主动接话回合进行中(与 ASR 回合互斥,共用 setPhase/打断语义)。 */
    private var proactiveJob: Job? = null

    /** 静默主动接话计时(LISTENING 态武装,N2 秒后 fire)。 */
    private var idleJob: Job? = null

    /** 断句事件汇集(音频线程 → 引擎主线程串行处理)。 */
    private val micEvents = Channel<UtteranceEndpointer.Event>(Channel.UNLIMITED)
    private var eventsJob: Job? = null

    /** 回合进行中收到的最新 utterance(回合收口后接力,最新一句优先)。 */
    private var pendingPcm: ByteArray? = null

    /** 连续主动接话次数(用户开口即清零;≥[MAX_PROACTIVE_STREAK] 停)。 */
    private var proactiveStreak = 0

    /** 打断标记:回合收口时若已打断则不再回 SPEAKING。 */
    @Volatile private var interruptRequested = false

    init {
        speaker.onPlaybackFinished = { playDone.complete(Unit) }
        mic.onEvent = { e -> micEvents.trySend(e) }
        mic.onLevel = { level -> _micLevel.value = level }
    }

    /** 进入通话页:重置状态、开麦(幂等)、武装主动接话计时。 */
    fun start() {
        turnJob?.cancel()
        proactiveJob?.cancel()
        idleJob?.cancel()
        interruptRequested = false
        speaker.stop()
        playDone = CompletableDeferred()
        pendingPcm = null
        proactiveStreak = 0
        _state.value = UiState()
        _micLevel.value = 0f
        if (eventsJob?.isActive != true) {
            eventsJob = scope.launch {
                micEvents.receiveAsFlow().collect { onMicEvent(it) }
            }
        }
        try {
            mic.start()
        } catch (e: Exception) {
            Log.w(TAG, "mic start failed", e)
            _state.update { it.copy(notice = "麦克风不可用,检查录音权限") }
        }
        armIdle()
    }

    /** 挂断/退出页面:停播放、停麦、取消回合与计时(已落库消息保留)。 */
    fun close() {
        turnJob?.cancel()
        proactiveJob?.cancel()
        idleJob?.cancel()
        eventsJob?.cancel()
        eventsJob = null
        interruptRequested = false
        speaker.stop()
        try { mic.stop() } catch (_: Exception) { }
        pendingPcm = null
        _state.value = UiState()
        _micLevel.value = 0f
    }

    /** 调试文本输入(验收用):跳过 ASR,直接进回合。 */
    fun sendDebugText(text: String) {
        if (turnJob?.isActive == true || proactiveJob?.isActive == true) return
        val t = text.trim()
        if (t.isEmpty()) return
        idleJob?.cancel()
        runTurn { t }
    }

    /**
     * 打断:停止播放回到聆听。回合还在跑时只拦掉"开口说",消息照常落库。
     * [fromBargeIn]=true 由 VAD 判定用户开口触发(计时交给后续断句链路),
     * 手动点按打断则重新武装主动接话计时。
     */
    fun interrupt(fromBargeIn: Boolean = false) {
        if (_state.value.phase != Phase.SPEAKING) return
        interruptRequested = true
        speaker.stop()
        playDone.complete(Unit)
        setPhase(Phase.LISTENING)
        if (!fromBargeIn) drainPendingOrArmIdle()
    }

    // ── 断句事件(主线程串行) ────────────────────────────────────────

    private fun onMicEvent(e: UtteranceEndpointer.Event) {
        Log.d(TAG, "mic event: $e")
        when (e) {
            is UtteranceEndpointer.Event.SpeechStarted -> {
                // 不在这里取消 idle 计时:噪声毛刺的起句事件会不停重置计时,
                // 主动接话永不触发;真正让路靠 fireProactive 的 collecting 守卫
                proactiveStreak = 0
                // 断句停顿 N1 每句现取(配置页滑块改完下一句即生效);
                // barge-in 口径跟随当前相位(SPEAKING 中开口按滑窗多数去抖)
                mic.endpointer.endpointSilenceMs = aiConfigSource().callEndSilenceS * 1000L
                if (e.isBargeIn) interrupt(fromBargeIn = true)
                _state.update { it.copy(userSpeaking = true, notice = "") }
            }
            is UtteranceEndpointer.Event.Utterance -> {
                _state.update { it.copy(userSpeaking = false) }
                _micLevel.value = 0f
                if (e.pcm.size <= MIN_WAV_BYTES) {
                    // 毛刺/极短噪声静默丢弃(不刷「没听清」),计时照常武装
                    Log.d(TAG, "utterance too short, dropped (${e.pcm.size}B)")
                    drainPendingOrArmIdle()
                    return
                }
                val busy = turnJob?.isActive == true || proactiveJob?.isActive == true ||
                    _state.value.phase != Phase.LISTENING
                if (busy) pendingPcm = e.pcm else startUtteranceTurn(e.pcm)
            }
        }
    }

    /** 一句说完 → 包 WAV 送 ASR。 */
    private fun startUtteranceTurn(pcm: ByteArray) {
        idleJob?.cancel()
        runTurn { transcribe(WavRecorder.pcmToWav(pcm, SAMPLE_RATE)) }
    }

    /** 回合收口公共体:有 pending 先接力最新一句,否则重新武装接话计时。 */
    private fun drainPendingOrArmIdle() {
        if (pendingPcm == null) {
            armIdle()
            return
        }
        if (_state.value.phase != Phase.LISTENING) return // 回合还忙,留给下次收口
        val pending = pendingPcm ?: return
        pendingPcm = null
        // 收口窗口内 turnJob 仍标记 active,runTurn 的 join 接力会兜住
        startUtteranceTurn(pending)
    }

    /** 武装静默主动接话计时(LISTENING 态,N2 秒后 [fireProactive])。 */
    private fun armIdle() {
        idleJob?.cancel()
        if (_state.value.phase != Phase.LISTENING) return
        val seconds = aiConfigSource().callIdleSpeakS
        Log.d(TAG, "armIdle: $seconds s (streak=$proactiveStreak)")
        idleJob = scope.launch {
            delay(seconds * 1000L)
            fireProactive()
        }
    }

    /** 静默超时:人物主动接话(无用户消息,编排器 respondProactively 链路)。 */
    private fun fireProactive() {
        if (_state.value.phase != Phase.LISTENING) return logSkip("phase=${_state.value.phase}")
        if (turnJob?.isActive == true || proactiveJob?.isActive == true) return logSkip("turn busy")
        if (mic.endpointer.collecting) return logSkip("user collecting")
        if (proactiveStreak >= MAX_PROACTIVE_STREAK) return logSkip("streak cap") // 防独白,等用户开口
        Log.d(TAG, "fireProactive: engaging (streak=${proactiveStreak + 1})")
        proactiveStreak++
        proactiveJob = scope.launch {
            try {
                setPhase(Phase.THINKING)
                val bubbles = proactiveRound()
                if (bubbles.isEmpty()) {
                    // 生成失败/降级:回聆听,下一轮计时再试(streak 已计,封顶兜底)
                    finishToListening()
                    return@launch
                }
                val (speech, seen) = CallSpeech.composeSpeechText(bubbles)
                val caption = CallSpeech.speechOrFallback(speech)
                _state.update { it.copy(caption = caption, seenLines = seen) }
                if (interruptRequested) {
                    finishToListening()
                    return@launch
                }
                setPhase(Phase.SPEAKING)
                playDone = CompletableDeferred()
                if (speak(caption, "").not()) {
                    withTimeoutOrNull(TTS_FAIL_HOLD_MS) { playDone.await() }
                    if (interruptRequested.not()) {
                        finishToListening(TTS_FAIL_NOTICE)
                    }
                    return@launch
                }
                playDone.await()
                if (interruptRequested.not()) finishToListening()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "proactive turn failed", e)
                finishToListening()
            }
        }
    }

    // ── ASR 回合 ─────────────────────────────────────────────────────

    /** 回合公共体:[userTextSource] 产出用户口语文本(ASR 或调试直填)。 */
    private fun runTurn(userTextSource: suspend () -> String) {
        val ongoing = turnJob
        if (ongoing?.isActive == true) {
            // 朗读被打断后的收尾窗口(超快点按才撞上):接力跑,录的音不丢;
            // 识别/思考中的回合照旧不接受新回合
            if (_state.value.phase != Phase.LISTENING) return
            scope.launch { ongoing.join(); runTurn(userTextSource) }
            return
        }
        interruptRequested = false
        turnJob = scope.launch {
            try {
                setPhase(Phase.RECOGNIZING)
                _state.update { it.copy(notice = "") }
                val userText = userTextSource().trim()
                if (userText.isEmpty()) {
                    finishToListening("没听清,再试一次?")
                    return@launch
                }
                setPhase(Phase.THINKING)
                val bubbles = runChatRound(userText)
                val (speech, seen) = CallSpeech.composeSpeechText(bubbles)
                val caption = CallSpeech.speechOrFallback(speech)
                _state.update { it.copy(caption = caption, seenLines = seen) }
                if (interruptRequested) {
                    // 打断落在回合进行中:开口说这步拦掉,字幕保留
                    finishToListening()
                    return@launch
                }
                setPhase(Phase.SPEAKING)
                playDone = CompletableDeferred()
                if (speak(caption, userText).not()) {
                    // TTS 全挂:字幕照出,停 4s 回聆听(期间可打断/挂断)
                    withTimeoutOrNull(TTS_FAIL_HOLD_MS) { playDone.await() }
                    if (interruptRequested.not()) {
                        finishToListening(TTS_FAIL_NOTICE)
                    }
                    return@launch
                }
                playDone.await()
                if (interruptRequested.not()) finishToListening()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "voice turn failed", e)
                finishToListening("这轮没接上,再试一次?")
            }
        }
    }

    /** 回 LISTENING 并收口(顺带接力 pending / 重新武装接话计时)。 */
    private fun finishToListening(notice: String? = null) {
        setPhase(Phase.LISTENING)
        if (notice != null) _state.update { it.copy(notice = notice) }
        drainPendingOrArmIdle()
    }

    /** 相位切换统一走这里:SPEAKING 与否同步给断句器(barge-in 口径)。 */
    private fun setPhase(phase: Phase) {
        mic.endpointer.ttsPlaying = phase == Phase.SPEAKING
        _state.update { it.copy(phase = phase) }
    }

    /** fireProactive 的静默跳过记录(诊断用,不进 UI)。 */
    private fun logSkip(reason: String): Unit {
        Log.d(TAG, "fireProactive skipped: $reason")
    }

    /** 识别:WAV → 文本;失败返回空串(上层走「没听清」),页面永不崩。 */
    private suspend fun transcribe(wav: ByteArray): String = withContext(Dispatchers.IO) {
        try {
            ConfiguredAsr.transcribe(wav, aiConfigSource())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "ASR failed", e)
            ""
        }
    }

    /** 开口说:合成 + 播放。true = 已开始播(完成/打断由 [playDone] 收口)。 */
    private suspend fun speak(text: String, contextText: String): Boolean {
        val result = CharacterSpeechSynthesizer(::volcanoTts)
            .synthesize(text, aiConfigSource(), voiceSource(), contextText) ?: return false
        speaker.play(result)
        return true
    }

    // 适配器按 (Key[, 模型]) 缓存:配置页改 Key 后下次回合用新适配器,
    // 不用杀通话页(旧适配器随重建被替换)
    private var volcanoAdapter: TtsAdapter? = null
    private var volcanoCacheKey: String? = null

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

        /** 连续主动接话上限(用户开口即重置;防人物自说自话成播客)。 */
        const val MAX_PROACTIVE_STREAK = 3

        /** 断句 PCM 的采样率(与 [CallMicStreamer]/WAV 头一致)。 */
        const val SAMPLE_RATE = 16_000

        private const val TTS_FAIL_NOTICE =
            "音色未设置或语音合成失败，请在人物编辑中选择音色并检查语音服务配置"
    }
}
