package com.neethu.gouxiang.ai.speech

import com.neethu.gouxiang.ai.config.AiConfig
import com.neethu.gouxiang.protocol.Bubble
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSpeechPlayerTest {
    private val config = AiConfig(apiKeyVolcanoTts = "volcano-test", apiKeySiliconflow = "silicon-test")
    private val audio = TtsResult(byteArrayOf(1, 2), TtsAudioFormat.RAW_PCM_16LE)
    private fun text(value: String) = Bubble(Bubble.Kind.TEXT, value)

    private class FakePlayback : ChatSpeechPlayback {
        val played = mutableListOf<TtsResult>()
        var stops = 0
        override fun play(result: TtsResult) { played += result }
        override fun stop() { stops++ }
    }

    private fun adapter(block: suspend (String, TtsConfig) -> TtsResult) = object : TtsAdapter {
        override suspend fun synthesize(text: String, config: TtsConfig) = block(text, config)
    }

    @Test
    fun `successful synthesis reads text bubbles with primary service only`() = runTest {
        val playback = FakePlayback()
        var requestedText = ""
        var fallbackCalls = 0
        val player = ChatSpeechPlayer(
            this, { config },
            { key ->
                assertEquals("volcano-test", key)
                adapter { text, _ -> requestedText = text; audio }
            },
            { fallbackCalls++; adapter { _, _ -> audio } },
            playback,
        )

        player.speak(listOf(text("(微笑)你好"), Bubble(Bubble.Kind.IMAGE, "照片"), text("晚安")))
        advanceUntilIdle()

        assertEquals("你好。晚安", requestedText)
        assertEquals(0, fallbackCalls)
        assertEquals(1, playback.played.size)
        assertSame(audio, playback.played.single())
    }

    @Test
    fun `failed primary synthesis uses fallback audio`() = runTest {
        val playback = FakePlayback()
        var fallbackText = ""
        var fallbackConfig: TtsConfig? = null
        val player = ChatSpeechPlayer(
            this, { config },
            { adapter { _, _ -> throw IllegalStateException("primary failed") } },
            { key ->
                assertEquals("silicon-test", key)
                adapter { text, ttsConfig -> fallbackText = text; fallbackConfig = ttsConfig; audio }
            },
            playback,
        )

        player.speak(listOf(text("你好")))
        advanceUntilIdle()

        assertEquals("你好", fallbackText)
        assertEquals(VoiceCallEngine.SILICONFLOW_TTS_MODEL, fallbackConfig?.model)
        assertEquals("wav", fallbackConfig?.responseFormat)
        assertSame(audio, playback.played.single())
    }

    @Test
    fun `stop during synthesis prevents playback and fallback even if backend returns late`() = runTest {
        val playback = FakePlayback()
        val gate = CompletableDeferred<Unit>()
        var fallbackCalls = 0
        val player = ChatSpeechPlayer(
            this, { config },
            { adapter { _, _ -> withContext(NonCancellable) { gate.await() }; audio } },
            { fallbackCalls++; adapter { _, _ -> audio } },
            playback,
        )

        player.speak(listOf(text("你好")))
        runCurrent()
        player.stop()
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(playback.played.isEmpty())
        assertEquals(0, fallbackCalls)
        assertEquals(2, playback.stops) // Starting a new utterance also stops existing playback.
    }

    @Test
    fun `blank speech does not invoke synthesis`() = runTest {
        val playback = FakePlayback()
        var calls = 0
        val factory: (String) -> TtsAdapter = { calls++; adapter { _, _ -> audio } }
        val player = ChatSpeechPlayer(this, { config }, factory, factory, playback)

        player.speak(listOf(text("(微笑)"), Bubble(Bubble.Kind.IMAGE, "照片")))
        advanceUntilIdle()

        assertEquals(0, calls)
        assertTrue(playback.played.isEmpty())
    }
}
