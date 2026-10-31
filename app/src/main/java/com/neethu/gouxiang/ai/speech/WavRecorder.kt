package com.neethu.gouxiang.ai.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * 按住说话的录音器(语音通话 F6):按住期间后台线程持续采集,松手产出
 * 完整 WAV 字节(16-bit PCM 单声道,16 kHz —— 与 ASR 探针/合成侧同采样率,
 * 喂硅基 `/audio/transcriptions` 实测稳)。
 *
 * 为保证 [stop] 同步返回可用音频:release 是同步停止,音频累积在缓冲里,
 * 调用方在音频线程上 join 该线程拿字节(超时 1s,超时视为本次丢弃)。
 */
class WavRecorder(private val sampleRate: Int = 16_000) {

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    /** 采集线程把每次 read 的 PCM 追加进这里(release 后 join 读出)。 */
    @Volatile private var running = false
    private val chunks = java.util.Collections.synchronizedList(ArrayDeque<ByteArray>())

    /** 最近一块 PCM 的峰值音量(0..1,按 int16 满幅归一;音频线程更新)。 */
    @Volatile var level: Float = 0f
        private set

    /** 音量回调(音频线程触发;UI 侧用于按住说话的实时波形)。 */
    var onLevel: ((Float) -> Unit)? = null

    /** 开始录音(重复调用无副作用)。麦克风被占用/无权限时抛 IOException。 */
    @SuppressLint("MissingPermission") // 运行时权限由页面申请,这里只管开录
    fun start() {
        if (running) return
        chunks.clear()
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) throw java.io.IOException("AudioRecord.getMinBufferSize failed: $minBuf")
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
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
        record = recorder
        recorder.startRecording()
        running = true
        level = 0f
        thread = Thread {
            val buf = ByteArray(2048)
            while (running) {
                val n = try {
                    recorder.read(buf, 0, buf.size)
                } catch (_: Exception) {
                    break
                }
                if (n > 0) {
                    chunks.add(buf.copyOf(n))
                    peakLevel(buf, n)?.let { peak ->
                        level = peak
                        try { onLevel?.invoke(peak) } catch (_: Exception) { }
                    }
                }
            }
        }.also { it.start() }
    }

    /** 停止并阻塞到采集线程退出(同步);之后 [takeWav] 可拿完整音频。 */
    fun stop() {
        val r = record ?: return
        running = false
        level = 0f
        try { r.stop() } catch (_: Exception) { }
        thread?.let { t -> try { t.join(1000) } catch (_: InterruptedException) { } }
        thread = null
        try { r.release() } catch (_: Exception) { }
        record = null
    }

    /** 采集到的原始 PCM 字节数(0 = 本次没录到东西,如秒松手)。 */
    fun pcmBytes(): Int = synchronized(chunks) { chunks.sumOf { it.size } }

    /**
     * 封存自上次封存以来的录音为一段 WAV 并清空该缓冲,录音不停(按住说话·
     * 侧移动作场景的分段口径:手势态原位/侧移互切时调用,段=当前态说出的话;
     * 2026-10-31 用户口径「话一(动作)话二」三段合一)。本次没录到东西返回 null。
     */
    fun sealSegment(): ByteArray? {
        val pcm = synchronized(chunks) {
            if (chunks.isEmpty()) return null
            val out = java.io.ByteArrayOutputStream()
            chunks.forEach { out.write(it) }
            chunks.clear()
            out.toByteArray()
        }
        return pcm.toWav(sampleRate)
    }

    /** 取出全部录音并打包为 WAV(取走后内部缓冲清空)。 */
    fun takeWav(): ByteArray {
        val pcm = synchronized(chunks) {
            val out = java.io.ByteArrayOutputStream()
            chunks.forEach { out.write(it) }
            chunks.clear()
            out.toByteArray()
        }
        return pcm.toWav(sampleRate)
    }

    companion object {
        /** 16-bit LE 单声道 PCM → WAV(RIFF 头 44 字节,SiliconFlow ASR 实测收)。 */
        fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray = pcm.toWav(sampleRate)

        private fun ByteArray.toWav(sampleRate: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val channels = 1
            val bitsPerSample = 16
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            writeIntLe(out, 36 + size)
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            writeIntLe(out, 16)
            writeShortLe(out, 1) // PCM
            writeShortLe(out, channels)
            writeIntLe(out, sampleRate)
            writeIntLe(out, byteRate)
            writeShortLe(out, blockAlign)
            writeShortLe(out, bitsPerSample)
            out.write("data".toByteArray(Charsets.US_ASCII))
            writeIntLe(out, size)
            out.write(this)
            return out.toByteArray()
        }

        private fun writeIntLe(out: java.io.ByteArrayOutputStream, v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }

        private fun writeShortLe(out: java.io.ByteArrayOutputStream, v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }

        /** 16-bit LE PCM 峰值 → 0..1(满幅归一,带轻度感知抬升让波形更活);n 不足一采样返回 null。 */
        private fun peakLevel(pcm: ByteArray, n: Int): Float? {
            if (n < 2) return null
            var peak = 0
            var i = 0
            val end = n - 1
            while (i < end) {
                val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
                val abs = if (sample < 0) -sample else sample
                if (abs > peak) peak = abs
                i += 2
            }
            val norm = peak / 32768f
            return (norm * 1.6f).coerceIn(0f, 1f)
        }
    }
}
