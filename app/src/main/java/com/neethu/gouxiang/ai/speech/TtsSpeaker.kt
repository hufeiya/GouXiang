package com.neethu.gouxiang.ai.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 语音通话 F6 的 TTS 播放器:合成(火山 seed-tts-2.0 优先,硅基 CosyVoice 兜底)
 * → 16-bit PCM 直接喂 [AudioTrack](前台通话页,无需系统 TTS 引擎)。
 *
 * 打断语义:同一时刻只播一段;[stop] 立即 pause+flush 并置 playing=false,
 * 正在合成的上游协程由调用方(引擎)取消。
 */
class TtsSpeaker {

    private var track: AudioTrack? = null
    private var writeJob: Job? = null

    /** 当前是否在播放(字幕页「点击打断」的可见性)。 */
    @Volatile var playing: Boolean = false
        private set

    /** 播放完成回调(回聆听态;同一线程置位 playing=false 后同步触发)。 */
    var onPlaybackFinished: (() -> Unit)? = null

    /** 播放一段 TTS 结果(RAW_PCM_16LE 或 WAV;先停掉上一段)。 */
    fun play(result: TtsResult) {
        stop()
        val pcm = when (result.format) {
            TtsAudioFormat.RAW_PCM_16LE -> result.audio
            TtsAudioFormat.WAV -> stripWavHeader(result.audio)
            else -> {
                Log.w(TAG, "unsupported TTS audio format: ${result.format}")
                return
            }
        }
        if (pcm.isEmpty()) return
        val sampleRate = result.rawPcmSampleRate
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.w(TAG, "AudioTrack.getMinBufferSize failed: $minBuf")
            return
        }
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf * 2)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack create failed", e)
            return
        }
        track = t
        t.play()
        playing = true
        // 写 PCM 放后台:长句百 ms 级写入不卡主线程;完成后同步回调
        writeJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                t.write(pcm, 0, pcm.size)
            } catch (_: Exception) {
            }
            playing = false
            val cb = onPlaybackFinished
            cb?.invoke()
        }
    }

    /** 立即打断(停止播放,回聆听态由调用方驱动状态机)。 */
    fun stop() {
        writeJob?.cancel()
        writeJob = null
        val t = track ?: run { playing = false; return }
        track = null
        try { t.pause() } catch (_: Exception) { }
        try { t.flush() } catch (_: Exception) { }
        try { t.release() } catch (_: Exception) { }
        playing = false
    }

    /** WAV(RIFF)容器 → 纯 PCM 载荷(44 字节头;魔数不匹配原样返回)。 */
    private fun stripWavHeader(bytes: ByteArray): ByteArray {
        if (bytes.size <= 44) return bytes
        val head = bytes.sliceArray(0 until 4).decodeToString()
        return if (head == "RIFF") bytes.copyOfRange(44, bytes.size) else bytes
    }

    companion object {
        private const val TAG = "VoiceCall"
    }
}
