package com.neethu.gouxiang.ai.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.neethu.gouxiang.ai.config.AiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** 首页开场白播放器。返回时音频已播完或无法播放；取消或 [stop] 会打断播放。 */
class HomeOpeningSpeechPlayer(context: Context, private val configSource: () -> AiConfig) {
    private val systemPlayer = HomeSystemSpeechPlayer(context.applicationContext)
    private val generation = AtomicLong()
    private val lock = Any()
    private var track: AudioTrack? = null

    suspend fun play(opening: String, voice: SpeechVoice, onSystemFallback: () -> Unit = {}): Boolean {
        stop()
        val token = generation.get()
        val text = CallSpeech.stripActions(opening)
        // 全是旁白时无需播放，也不应提示语音服务故障。
        if (text.isBlank()) return true
        val result = CharacterSpeechSynthesizer { VolcanoEngineTtsAdapter(it) }
            .synthesize(text, configSource(), voice)
        currentCoroutineContext().ensureActive()
        if (generation.get() != token) return false
        if (result != null && withContext(Dispatchers.IO) { playAudio(result, token) }) return true
        currentCoroutineContext().ensureActive()
        if (generation.get() != token) return false
        return systemPlayer.play(text, { generation.get() == token }, onSystemFallback)
    }

    fun stop() {
        generation.incrementAndGet()
        systemPlayer.stop()
        synchronized(lock) {
            releaseTrack(track)
            track = null
        }
    }

    private suspend fun playAudio(result: TtsResult, token: Long): Boolean {
        val pcm = when (result.format) {
            TtsAudioFormat.RAW_PCM_16LE -> result.audio
            TtsAudioFormat.WAV -> if (result.audio.size > 44 && result.audio.copyOfRange(0, 4).decodeToString() == "RIFF") result.audio.copyOfRange(44, result.audio.size) else result.audio
            else -> return false
        }
        if (pcm.isEmpty()) return false
        val rate = result.rawPcmSampleRate
        val size = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (size <= 0) return false
        val audioTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(size * 2)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to create opening audio track", e)
            return false
        }
        synchronized(lock) {
            if (generation.get() != token) {
                releaseTrack(audioTrack)
                return false
            }
            track = audioTrack
        }
        try {
            audioTrack.play()
            var offset = 0
            while (offset < pcm.size) {
                currentCoroutineContext().ensureActive()
                if (generation.get() != token) return false
                val written = audioTrack.write(pcm, offset, pcm.size - offset)
                if (written <= 0) return false
                offset += written
            }
            // write() 返回时音频可能仍在缓冲区，等实际播放头到达末尾再展开介绍。
            val frames = pcm.size / 2L
            val deadline = SystemClock.elapsedRealtime() + frames * 1000L / rate + 5_000L
            while ((audioTrack.playbackHeadPosition.toLong() and 0xffffffffL) < frames) {
                currentCoroutineContext().ensureActive()
                if (generation.get() != token || SystemClock.elapsedRealtime() >= deadline) return false
                delay(30)
            }
            return true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Opening audio playback failed", e)
            return false
        } finally {
            synchronized(lock) {
                if (track === audioTrack) {
                    track = null
                    releaseTrack(audioTrack)
                }
            }
        }
    }

    private fun releaseTrack(value: AudioTrack?) {
        if (value == null) return
        try { value.pause() } catch (_: Exception) { }
        try { value.flush() } catch (_: Exception) { }
        try { value.release() } catch (_: Exception) { }
    }

    private companion object { const val TAG = "HomeOpeningSpeech" }
}
