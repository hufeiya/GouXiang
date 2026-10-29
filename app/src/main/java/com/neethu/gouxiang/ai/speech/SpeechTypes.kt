package com.neethu.gouxiang.ai.speech

/**
 * TTS/ASR 底座类型(语音通话 F6;移植自 AIAvatar-SDK avatar-ai-adapter:
 * model/ChatTypes.kt 的 TtsAudioFormat/TtsConfig/TtsResult/AsrConfig +
 * api/TtsAdapter.kt + api/AsrAdapter.kt,2026-10-29,包名改为
 * com.neethu.gouxiang.ai.speech;逻辑零改动)。
 */

/** Audio container/encoding returned by a TTS engine. */
enum class TtsAudioFormat {
    /** Headerless 16-bit little-endian mono PCM (OpenAI `pcm` response format). */
    RAW_PCM_16LE,
    WAV,
    MP3,
    OGG,
    UNKNOWN,
}

/** Voice/engine parameters for a speech synthesis request. */
data class TtsConfig(
    val model: String,
    val voice: String,
    val speed: Float = 1.0f,
    /**
     * `wav` (widely supported), `mp3`, `pcm` (OpenAI-only raw 16-bit LE).
     * The [TtsAdapter] echoes this back in [TtsResult.format].
     */
    val responseFormat: String = "wav",
    /**
     * Requested output sample rate in Hz (OpenAI-compatible extension, e.g.
     * SiliconFlow `sample_rate`); `null` = provider default.
     */
    val sampleRate: Int? = null,
    /** Sample rate of [TtsAudioFormat.RAW_PCM_16LE] results (OpenAI pcm is 24 kHz). */
    val rawPcmSampleRate: Int = 24_000,
    val extraParams: Map<String, String> = emptyMap(),
)

/** Synthesized audio bytes plus enough metadata to decode them. */
class TtsResult(
    val audio: ByteArray,
    val format: TtsAudioFormat,
    val rawPcmSampleRate: Int = 24_000,
)

/** Recognition parameters for a speech-to-text request. */
data class AsrConfig(
    /** Provider model id, e.g. `whisper-1` or SiliconFlow `Qwen/Qwen3-ASR-1.7B`. */
    val model: String,
    /**
     * ISO-639-1 language hint (whisper-style `language` form field, e.g. "zh");
     * `null` = let the provider auto-detect. Providers that don't take the
     * field simply ignore it, so leave it null unless the model needs it.
     */
    val language: String? = null,
    /** Optional vocabulary/terminology hint (whisper-style `prompt`); `null` = none. */
    val prompt: String? = null,
)

/** Pluggable text-to-speech backend. V1 contract is sentence-level synthesis. */
interface TtsAdapter {
    suspend fun synthesize(text: String, config: TtsConfig): TtsResult
}

/**
 * Pluggable speech-to-text backend: one suspend call, audio bytes in, plain
 * text out. The caller feeds it a complete recording (e.g. a WAV take of the
 * push-to-talk hold); the adapter implementation owns the wire protocol.
 */
interface AsrAdapter {
    /**
     * Transcribes [audio] (encoded bytes) into text.
     *
     * @param audio the complete audio file bytes
     * @param mime  the encoding's MIME type (e.g. `audio/wav`)
     * @param config model + optional recognition hints
     * @return the recognized text (may be empty for silence)
     * @throws IOException on transport/HTTP errors or unparseable responses
     */
    suspend fun transcribe(audio: ByteArray, mime: String, config: AsrConfig): String
}
