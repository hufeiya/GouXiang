package com.neethu.gouxiang.ai.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate

/**
 * 通话全双工麦克风流(2026-11-04:去按住说话,麦克风全程常开)。
 *
 * - 音源 [MediaRecorder.AudioSource.VOICE_COMMUNICATION] + 系统 AEC/NS
 *   (Android 内置的 WebRTC 同源回声消除):TTS 外放期间麦克风继续采集,
 *   回声先在采集层削掉,上层 VAD 才不至于把「人物在说」当成「用户在说」;
 * - WebRTC VAD(gkonovalov/android-vad webrtc 模块)逐 20ms 帧分类,
 *   语义事件(起句/断句/打断)全部由纯逻辑 [UtteranceEndpointer] 判定,
 *   本类只负责采集与喂帧;
 * - 事件回调在音频线程触发,消费方(VoiceCallEngine)自行投递到自己的协程域;
 *   音量回调节流到约 60ms 一拍(与按住说话波形同频)。
 */
class CallMicStreamer(private val sampleRate: Int = 16_000) {

    /** 断句语义事件(见 [UtteranceEndpointer.Event])。 */
    var onEvent: ((UtteranceEndpointer.Event) -> Unit)? = null

    /** 实时音量(0..1,约 60ms 一拍;音频线程触发,UI 波形用)。 */
    var onLevel: ((Float) -> Unit)? = null

    /** 断句器(暴露给引擎调参:N1 断句停顿/SPEAKING 态标记)。 */
    val endpointer = UtteranceEndpointer()

    private var record: AudioRecord? = null
    private var vad: VadWebRTC? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var thread: Thread? = null

    @Volatile private var running = false

    /** 启动采集(重复调用无副作用);无权限/被占用抛 IOException。 */
    @SuppressLint("MissingPermission") // 运行时权限由通话页申请,这里只管开麦
    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) throw java.io.IOException("AudioRecord.getMinBufferSize failed: $minBuf")
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (e: Exception) {
            throw java.io.IOException("AudioRecord create failed", e)
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw java.io.IOException("AudioRecord not initialized (permission denied?)")
        }
        // 系统 AEC/NS(WebRTC 同源):按 session 挂,设备不支持就静默降级
        aec = if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(recorder.audioSessionId)?.also { it.enabled = true }
        } else null
        ns = if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(recorder.audioSessionId)?.also { it.enabled = true }
        } else null
        record = recorder
        endpointer.reset()
        recorder.startRecording()
        running = true
        val v = VadWebRTC(
            sampleRate = SampleRate.SAMPLE_RATE_16K,
            frameSize = FrameSize.FRAME_SIZE_320,
            mode = Mode.VERY_AGGRESSIVE,
            silenceDurationMs = 0, // 时序全由 UtteranceEndpointer 管,这里只要逐帧分类
            speechDurationMs = 0,
        )
        vad = v
        thread = Thread {
            val buf = ShortArray(FRAME_SAMPLES)
            val carry = ArrayDeque<Short>()
            var levelTick = 0
            while (running) {
                val n = try {
                    recorder.read(buf, 0, FRAME_SAMPLES)
                } catch (_: Exception) {
                    break
                }
                if (n <= 0) continue
                // 拼帧:read 可能返回不足一帧,攒够 320 采样再喂 VAD
                if (n < FRAME_SAMPLES) {
                    for (i in 0 until n) carry.add(buf[i])
                    if (carry.size < FRAME_SAMPLES) continue
                    for (i in 0 until FRAME_SAMPLES) buf[i] = carry.removeFirst()
                }
                val isSpeech = try {
                    // VAD 之外加绝对 RMS 门限:部分机型(如 MIUI)VOICE_COMMUNICATION
                    // 音源带周期性微幅电噪声(实测 RMS≈0.0003),VERY_AGGRESSIVE 也会
                    // 把它当语音,垃圾 ASR 刷「没听清」;真人近讲远高于此门限
                    v.isSpeech(buf) && frameRms(buf) >= MIN_SPEECH_RMS
                } catch (_: Exception) {
                    false
                }
                endpointer.onFrame(buf, isSpeech)?.let { e -> try { onEvent?.invoke(e) } catch (_: Exception) { } }
                if (++levelTick % LEVEL_EVERY_FRAMES == 0) {
                    frameLevel(buf)?.let { l -> try { onLevel?.invoke(l) } catch (_: Exception) { } }
                }
            }
        }.also { it.start() }
    }

    /** 停止并阻塞到采集线程退出(同步);VAD/音效随会话释放。 */
    fun stop() {
        val r = record ?: return
        running = false
        try { r.stop() } catch (_: Exception) { }
        thread?.let { t -> try { t.join(1000) } catch (_: InterruptedException) { } }
        thread = null
        try { r.release() } catch (_: Exception) { }
        record = null
        try { aec?.release() } catch (_: Exception) { }
        aec = null
        try { ns?.release() } catch (_: Exception) { }
        ns = null
        try { vad?.close() } catch (_: Exception) { }
        vad = null
    }

    companion object {
        /** 20ms 帧 @16k(WebRTC VAD 的 FRAME_SIZE_320)。 */
        const val FRAME_SAMPLES = 320

        /** 音量节流:每 3 帧发一拍 ≈ 60ms(与 HoldWaveform 采样同频)。 */
        const val LEVEL_EVERY_FRAMES = 3

        /** 判「语音」的绝对 RMS 下限(满幅 0..1;电噪声/房间底噪 <0.005,近讲语音 >0.03)。 */
        const val MIN_SPEECH_RMS = 0.012

        /** 帧 RMS(0..1 满幅归一)。 */
        private fun frameRms(frame: ShortArray): Float {
            var sum = 0.0
            for (s in frame) {
                val v = s.toDouble()
                sum += v * v
            }
            return (kotlin.math.sqrt(sum / frame.size) / 32768.0).toFloat()
        }

        /** 帧 RMS → 0..1(满幅归一 ×2.0 抬升,波形更活)。 */
        private fun frameLevel(frame: ShortArray): Float {
            var sum = 0.0
            for (s in frame) {
                val v = s.toDouble()
                sum += v * v
            }
            val rms = kotlin.math.sqrt(sum / frame.size) / 32768.0
            return (rms * 2.0).coerceIn(0.0, 1.0).toFloat()
        }
    }
}

/**
 * 断句器(纯逻辑,不碰 Android,可 JVM 单测):消费「20ms 帧 + VAD 分类」,
 * 产出通话引擎需要的语义事件。
 *
 * 口径(2026-11-04):
 * - 起句:空闲态连续 [START_SPEECH_FRAMES] 帧语音(60ms,抗单帧毛刺),
 *   连同 [PRE_ROLL_FRAMES] 预滚(300ms,首字不丢)一起开始收集;
 * - SPEAKING 态(TTS 朗读中)起句 = barge-in:除起句条件外加滑窗去抖
 *   ([BARGE_WINDOW_FRAMES] 内 ≥[BARGE_SPEECH_FRAMES] 帧语音 ≈200ms 多数),
 *   防 AEC 残余把朗读误判成用户开口;
 * - 断句:收集态静音累计 ≥ max(引擎设置的断句停顿 N1,[MIN_ENDPOINT_SILENCE_MS])
 *   —— N1=0 表示「说完立刻」,由内置 400ms 滞回兜底防切字;
 * - 单句上限 [MAX_UTTERANCE_MS] 强制断句(防 ASR 超大上传)。
 */
class UtteranceEndpointer {

    sealed class Event {
        /** 用户开始说话(收集开始;[isBargeIn]=true 时 TTS 正在朗读,需打断)。 */
        data class SpeechStarted(val isBargeIn: Boolean) : Event()

        /** 一句话说完:16-bit LE 单声道 PCM(引擎包 WAV 头送 ASR)。 */
        data class Utterance(val pcm: ByteArray) : Event()
    }

    /** 断句停顿(毫秒,0=说完立刻;实际生效取与 [MIN_ENDPOINT_SILENCE_MS] 较大者)。 */
    @Volatile var endpointSilenceMs: Long = 2_000

    /** TTS 是否正在朗读(SPEAKING 态置 true,起句按 barge-in 口径去抖)。 */
    @Volatile var ttsPlaying: Boolean = false

    /** 当前是否处于收集态(用户在说话;UI 的「聆听中…」高亮用)。 */
    var collecting: Boolean = false
        private set

    private val preRoll = ArrayDeque<ShortArray>()
    private val collected = ArrayList<ShortArray>()
    private val bargeWindow = ArrayDeque<Boolean>()
    private var speechRun = 0
    private var silenceMs = 0L
    private var collectedMs = 0L

    /** 清空全部状态(进页/重启流时调)。 */
    fun reset() {
        synchronized(this) {
            preRoll.clear()
            collected.clear()
            bargeWindow.clear()
            speechRun = 0
            silenceMs = 0
            collectedMs = 0
            collecting = false
        }
    }

    /**
     * 喂一帧。返回语义事件(每帧至多一个;null=无事件)。
     * [frame] 长度须为 20ms@16k(320 采样),本类不校验长度。
     */
    fun onFrame(frame: ShortArray, isSpeech: Boolean): Event? {
        val e = synchronized(this) {
            // barge-in 滑窗:最近 N 帧里语音帧数(只在 TTS 朗读时消费)
            bargeWindow.addLast(isSpeech)
            while (bargeWindow.size > BARGE_WINDOW_FRAMES) bargeWindow.removeFirst()
            val bargeCount = bargeWindow.count { it }

            if (!collecting) {
                preRoll.addLast(frame.copyOf())
                while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                speechRun = if (isSpeech) speechRun + 1 else 0
                val enough = if (ttsPlaying) bargeCount >= BARGE_SPEECH_FRAMES
                else speechRun >= START_SPEECH_FRAMES
                if (enough) {
                    collecting = true
                    collected.clear()
                    collected.addAll(preRoll)
                    collectedMs = (collected.size * FRAME_MS).toLong()
                    silenceMs = 0
                    Event.SpeechStarted(isBargeIn = ttsPlaying)
                } else null
            } else {
                collected.add(frame.copyOf())
                collectedMs += FRAME_MS
                silenceMs = if (isSpeech) 0 else silenceMs + FRAME_MS
                val threshold = maxOf(endpointSilenceMs, MIN_ENDPOINT_SILENCE_MS)
                if (silenceMs >= threshold || collectedMs >= MAX_UTTERANCE_MS) {
                    collecting = false
                    speechRun = 0
                    preRoll.clear()
                    Event.Utterance(pcmOf(collected))
                } else null
            }
        }
        if (e is Event.Utterance) collected.clear()
        return e
    }

    /** 收集帧拼成 16-bit LE 字节流。 */
    private fun pcmOf(frames: List<ShortArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream(frames.size * CallMicStreamer.FRAME_SAMPLES * 2)
        for (f in frames) {
            for (s in f) {
                val v = s.toInt()
                out.write(v and 0xFF)
                out.write((v shr 8) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    companion object {
        const val FRAME_MS = 20L

        /** 起句需连续语音帧数(3×20ms=60ms)。 */
        const val START_SPEECH_FRAMES = 3

        /** 预滚帧数(15×20ms=300ms,首字不丢)。 */
        const val PRE_ROLL_FRAMES = 15

        /** N1=0 时断句静音下限(滞回防切字)。 */
        const val MIN_ENDPOINT_SILENCE_MS = 400L

        /** barge-in 去抖滑窗(10×20ms=200ms)。 */
        const val BARGE_WINDOW_FRAMES = 10

        /** 滑窗内判定「真在说」所需语音帧数(6/10 = 多数)。 */
        const val BARGE_SPEECH_FRAMES = 6

        /** 单句上限(60s,防 ASR 超大上传,超时强制断句)。 */
        const val MAX_UTTERANCE_MS = 60_000L
    }
}
