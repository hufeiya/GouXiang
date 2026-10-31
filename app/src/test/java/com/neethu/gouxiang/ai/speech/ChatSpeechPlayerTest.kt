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
        override fun play(result: TtsResult) { played += result }
        override fun stop() = Unit
    }

    private class FakeSystemSpeech(var succeeds: Boolean = true) : ChatSystemSpeech {
        val spoken = mutableListOf<String>()
        override suspend fun play(text: String, isCurrent: () -> Boolean): Boolean {
            if (!isCurrent()) return false
            spoken += text
            return succeeds
        }
        override fun stop() = Unit
    }

    private fun adapter(block: suspend (String, TtsConfig) -> TtsResult) = object : TtsAdapter {
        override suspend fun synthesize(text: String, config: TtsConfig) = block(text, config)
    }

    @Test
    fun `character voice is passed through and compatible fallback is tried on failure`() = runTest {
        val playback = FakePlayback()
        val voices = mutableListOf<String>()
        val player = ChatSpeechPlayer(this, { config }, {
            adapter { _, cfg ->
                voices += cfg.voice
                if (cfg.voice == "male-primary") throw IllegalStateException("primary failed")
                audio
            }
        }, playback, FakeSystemSpeech())
        player.speak(listOf(text("你好"))) { SpeechVoice("male-primary", "male-fallback") }
        advanceUntilIdle()
        assertEquals(listOf("male-primary", "male-fallback"), voices)
        assertSame(audio, playback.played.single())
    }

    @Test
    fun `successful playback reads text bubbles only`() = runTest {
        val playback = FakePlayback()
        var spoken = ""
        val player = ChatSpeechPlayer(this, { config }, {
            adapter { text, _ -> spoken = text; audio }
        }, playback, FakeSystemSpeech())
        player.speak(listOf(text("(微笑)你好"), Bubble(Bubble.Kind.IMAGE, "照片"), text("晚安"))) { SpeechVoice("voice") }
        advanceUntilIdle()
        assertEquals("你好。晚安", spoken)
        assertSame(audio, playback.played.single())
    }

    @Test
    fun `unknown character uses system voice instead of a fabricated character voice`() = runTest {
        val playback = FakePlayback()
        val system = FakeSystemSpeech()
        var calls = 0
        val player = ChatSpeechPlayer(this, { config }, { calls++; adapter { _, _ -> audio } }, playback, system)
        var notices = 0
        player.speak(listOf(text("你好")), onUnavailable = { notices++ }) { SpeechVoice(null) }
        advanceUntilIdle()
        assertEquals(0, calls)
        assertEquals(0, notices)
        assertEquals(listOf("你好"), system.spoken)
        assertTrue(playback.played.isEmpty())
    }

    @Test
    fun `unknown character uses explicitly selected global voice`() = runTest {
        val playback = FakePlayback()
        val voices = mutableListOf<String>()
        val player = ChatSpeechPlayer(this, { config.copy(ttsVoice = "manual-global") }, {
            adapter { _, cfg -> voices += cfg.voice; audio }
        }, playback, FakeSystemSpeech())
        player.speak(listOf(text("你好"))) { SpeechVoice(null) }
        advanceUntilIdle()
        assertEquals(listOf("manual-global"), voices)
        assertSame(audio, playback.played.single())
    }

    @Test
    fun `known character failure never uses explicit global or fixed female fallback`() = runTest {
        val playback = FakePlayback()
        val system = FakeSystemSpeech()
        val voices = mutableListOf<String>()
        val player = ChatSpeechPlayer(this, { config.copy(ttsVoice = "global-female") }, {
            adapter { _, cfg -> voices += cfg.voice; throw IllegalStateException("failed") }
        }, playback, system)
        player.speak(listOf(text("你好"))) { SpeechVoice("male", "male-backup") }
        advanceUntilIdle()
        assertEquals(listOf("male", "male-backup"), voices)
        assertEquals(listOf("你好"), system.spoken)
        assertTrue(playback.played.isEmpty())
    }

    @Test
    fun `stop during synthesis prevents playback even if backend returns late`() = runTest {
        val playback = FakePlayback()
        val gate = CompletableDeferred<Unit>()
        val player = ChatSpeechPlayer(this, { config }, {
            adapter { _, _ -> withContext(NonCancellable) { gate.await() }; audio }
        }, playback, FakeSystemSpeech())
        player.speak(listOf(text("你好"))) { SpeechVoice("voice") }
        runCurrent()
        player.stop()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(playback.played.isEmpty())
    }

    @Test
    fun `failure notice is shown only after system voice also fails`() = runTest {
        val system = FakeSystemSpeech(succeeds = false)
        val player = ChatSpeechPlayer(this, { config }, { adapter { _, _ -> audio } }, FakePlayback(), system)
        var notices = 0
        player.speak(listOf(text("你好")), onUnavailable = { notices++ }) { SpeechVoice(null) }
        advanceUntilIdle()
        assertEquals(listOf("你好"), system.spoken)
        assertEquals(1, notices)
    }

    @Test
    fun `stop during system speech prevents a stale failure notice`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val system = object : ChatSystemSpeech {
            override suspend fun play(text: String, isCurrent: () -> Boolean): Boolean {
                withContext(NonCancellable) { gate.await() }
                return false
            }
            override fun stop() = Unit
        }
        val player = ChatSpeechPlayer(this, { config }, { adapter { _, _ -> audio } }, FakePlayback(), system)
        var notices = 0
        player.speak(listOf(text("你好")), onUnavailable = { notices++ }) { SpeechVoice(null) }
        runCurrent()
        player.stop()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, notices)
    }
}
