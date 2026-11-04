package com.neethu.gouxiang.ai.speech

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceSpeechRecognizerTest {
    private class Backend : DeviceRecognizerBackend {
        lateinit var listener: RecognitionListener
        lateinit var intent: Intent
        var stopped = false
        var destroyed = false
        override fun setRecognitionListener(listener: RecognitionListener) { this.listener = listener }
        override fun startListening(intent: Intent) { this.intent = intent }
        override fun stopListening() { stopped = true }
        override fun cancel() = Unit
        override fun destroy() { destroyed = true }
        fun result(text: String) = listener.onResults(Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
        })
        fun error(code: Int) = listener.onError(code)
    }

    private class Factory(var onDevice: Boolean = false) : DeviceRecognizerFactory {
        val backends = mutableListOf<Backend>()
        val modes = mutableListOf<Boolean>()
        override fun isOnDeviceAvailable() = onDevice
        override fun isSystemAvailable() = true
        override fun create(onDevice: Boolean): DeviceRecognizerBackend {
            modes.add(onDevice)
            return Backend().also(backends::add)
        }
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun continuousSegmentsAndSilenceAreCombinedOnlyAfterRelease() {
        val factory = Factory()
        val results = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val recognizer = DeviceSpeechRecognizer(factory)
        recognizer.start(results::add, errors::add, continuous = true)
        factory.backends[0].result("第一句。")
        assertTrue(results.isEmpty())
        advance(200)
        factory.backends[1].error(SpeechRecognizer.ERROR_NO_MATCH)
        advance(200)
        assertEquals(3, factory.backends.size)
        factory.backends[2].result("第二句。")
        advance(200)
        recognizer.stop()
        assertTrue(factory.backends[3].stopped)
        factory.backends[3].error(SpeechRecognizer.ERROR_NO_MATCH)
        assertEquals(listOf("第一句。第二句。"), results)
        assertTrue(errors.isEmpty())
        advance(5000)
        assertEquals(1, results.size)
    }

    @Test fun releaseOrCancelBetweenSegmentsDoesNotRestart() {
        val factory = Factory()
        val results = mutableListOf<String>()
        val recognizer = DeviceSpeechRecognizer(factory)
        recognizer.start(results::add, {}, continuous = true)
        factory.backends[0].result("一句")
        recognizer.stop()
        advance(1000)
        assertEquals(listOf("一句"), results)
        assertEquals(1, factory.backends.size)

        recognizer.start(results::add, {}, continuous = true)
        factory.backends[1].result("丢弃")
        recognizer.cancel()
        advance(1000)
        factory.backends[1].result("过期回调")
        assertEquals(2, factory.backends.size)
        assertEquals(listOf("一句"), results)
    }

    @Test fun offlineFailureFallsBackToDefaultService() {
        val factory = Factory(onDevice = true)
        val recognizer = DeviceSpeechRecognizer(factory)
        val results = mutableListOf<String>()
        recognizer.start(results::add, {})
        assertTrue(factory.backends[0].intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        factory.backends[0].error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        advance(200)
        assertEquals(listOf(true, false), factory.modes)
        assertFalse(factory.backends[1].intent.hasExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE))
        factory.backends[1].result("识别成功")
        assertEquals(listOf("识别成功"), results)
    }

    @Test fun systemNetworkAndServerErrorsHaveActionableMessages() {
        val factory = Factory()
        val recognizer = DeviceSpeechRecognizer(factory)
        val errors = mutableListOf<String>()
        val cases = listOf(
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT to "系统语音识别连接超时，请检查网络后重试",
            SpeechRecognizer.ERROR_NETWORK to "系统语音识别网络连接失败，请检查网络后重试",
            SpeechRecognizer.ERROR_SERVER to "系统语音识别服务出错，请稍后重试",
        )
        cases.forEachIndexed { index, (code, message) ->
            recognizer.start({}, errors::add, continuous = true)
            factory.backends[index].error(code)
            assertEquals(message, errors.last())
        }
        advance(1000)
        assertEquals(cases.size, factory.backends.size)
    }

    @Test fun networkFailureDoesNotReturnPartialTranscript() {
        val factory = Factory()
        val results = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val recognizer = DeviceSpeechRecognizer(factory)
        recognizer.start(results::add, errors::add, continuous = true)
        factory.backends[0].result("前半句")
        advance(200)
        factory.backends[1].error(SpeechRecognizer.ERROR_NETWORK_TIMEOUT)
        assertTrue(results.isEmpty())
        assertEquals(listOf("系统语音识别连接超时，请检查网络后重试"), errors)
        recognizer.stop()
        assertTrue(results.isEmpty())
    }

    @Test fun repeatedImmediateEmptyErrorsStopAndReleaseTimeoutReportsError() {
        val factory = Factory()
        val results = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val recognizer = DeviceSpeechRecognizer(factory)
        recognizer.start(results::add, errors::add, continuous = true)
        repeat(3) { index ->
            factory.backends[index].error(SpeechRecognizer.ERROR_NO_MATCH)
            advance(200)
        }
        assertEquals(3, factory.backends.size)
        assertEquals(listOf("没听清，再试一次"), errors)

        recognizer.start(results::add, errors::add, continuous = true)
        factory.backends[3].result("保留内容")
        advance(200)
        recognizer.stop()
        advance(5000)
        assertTrue(factory.backends[4].destroyed)
        assertTrue(results.isEmpty())
        assertEquals("设备语音识别响应超时，请重试", errors.last())
        factory.backends[4].result("迟到结果")
        assertTrue(results.isEmpty())
    }
}
