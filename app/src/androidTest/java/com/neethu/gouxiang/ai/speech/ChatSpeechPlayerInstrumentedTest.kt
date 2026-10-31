package com.neethu.gouxiang.ai.speech

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import com.neethu.gouxiang.ai.config.AiConfig
import com.neethu.gouxiang.protocol.Bubble
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真实设备上的系统 TTS 冒烟测试；不依赖聊天服务，也不会写入会话。 */
@RunWith(AndroidJUnit4::class)
class ChatSpeechPlayerInstrumentedTest {
    @Test
    fun noCharacterVoiceFallsBackToSystemSpeechAndCompletes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val succeeded = AtomicBoolean(false)
        val unavailable = AtomicBoolean(false)
        val systemPlayer = HomeSystemSpeechPlayer(context)
        val fallback = object : ChatSystemSpeech {
            override suspend fun play(text: String, isCurrent: () -> Boolean): Boolean {
                val result = systemPlayer.play(text, isCurrent) { started.countDown() }
                succeeded.set(result)
                finished.countDown()
                return result
            }
            override fun stop() = systemPlayer.stop()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val player = ChatSpeechPlayer(
            scope, { AiConfig(ttsVoice = "") },
            { error("A character voice must not be synthesized when none is configured") },
            object : ChatSpeechPlayback {
                override fun play(result: TtsResult) = error("Unexpected character voice playback")
                override fun stop() = Unit
            },
            fallback,
        )
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                player.speak(listOf(Bubble(Bubble.Kind.TEXT, "你好，很高兴认识你。")),
                    onUnavailable = { unavailable.set(true) }) { SpeechVoice(null) }
            }
            assertTrue("System TTS did not start", started.await(20, TimeUnit.SECONDS))
            assertTrue("System TTS did not complete", finished.await(30, TimeUnit.SECONDS))
            assertTrue("System TTS reported a playback failure", succeeded.get())
            assertFalse("A successful fallback should not report speech unavailable", unavailable.get())
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { player.stop(); scope.cancel() }
        }
    }
}
