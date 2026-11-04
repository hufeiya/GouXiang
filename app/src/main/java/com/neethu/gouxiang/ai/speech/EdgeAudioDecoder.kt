package com.neethu.gouxiang.ai.speech

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Decode Edge's MP3 into the mono PCM expected by the shared speech players. */
internal suspend fun decodeEdgeAudio(mp3: ByteArray): TtsResult {
    val source = object : MediaDataSource() {
        override fun getSize(): Long = mp3.size.toLong()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= mp3.size) return -1
            val count = minOf(size, mp3.size - position.toInt())
            mp3.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }
        override fun close() = Unit
    }
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    try {
        extractor.setDataSource(source)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IOException("Edge TTS 音频没有音轨")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("Edge TTS 音频格式未知")
        val decoder = MediaCodec.createDecoderByType(mime)
        codec = decoder
        decoder.configure(format, null, null, 0)
        decoder.start()
        var inputDone = false
        var outputDone = false
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val output = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        while (!outputDone) {
            currentCoroutineContext().ensureActive()
            if (!inputDone) {
                val index = decoder.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val input = decoder.getInputBuffer(index) ?: throw IOException("Edge TTS 解码输入不可用")
                    val size = extractor.readSampleData(input, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            when (val index = decoder.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val decoded = decoder.outputFormat
                    sampleRate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (decoded.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) != android.media.AudioFormat.ENCODING_PCM_16BIT
                    ) throw IOException("Edge TTS 解码格式不受支持")
                }
                else -> if (index >= 0) {
                    val buffer = decoder.getOutputBuffer(index) ?: throw IOException("Edge TTS 解码输出不可用")
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size)
                    buffer.get(bytes)
                    output.write(bytes)
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(index, false)
                }
            }
        }
        if (channels != 1) throw IOException("Edge TTS 解码结果不是单声道")
        val pcm = output.toByteArray()
        if (pcm.isEmpty()) throw IOException("Edge TTS 解码结果为空")
        return TtsResult(pcm, TtsAudioFormat.RAW_PCM_16LE, sampleRate)
    } finally {
        try { codec?.release() } catch (_: Exception) { }
        extractor.release()
        source.close()
    }
}
