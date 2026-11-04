package com.neethu.gouxiang.ai.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/** 系统识别器独占麦克风；所有调用及回调均在主线程。 */
class DeviceSpeechRecognizer internal constructor(
    private val factory: DeviceRecognizerFactory,
) {
    constructor(context: Context) : this(AndroidDeviceRecognizerFactory(context))
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: DeviceRecognizerBackend? = null
    private var generation = 0
    private var running = false
    private var stopping = false
    private var continuous = false
    private var useOnDevice = false
    private var fastEmptyCount = 0
    private var segmentStartedAt = 0L
    private val segments = mutableListOf<String>()
    private var resultCallback: (String) -> Unit = {}
    private var errorCallback: (String) -> Unit = {}
    private var levelCallback: (Float) -> Unit = {}

    /** continuous 用于按住说话：系统提前断句后继续，松手时一次返回各段结果。 */
    fun start(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onLevel: (Float) -> Unit = {},
        continuous: Boolean = false,
    ) {
        cancel()
        this.continuous = continuous
        resultCallback = onResult
        errorCallback = onError
        levelCallback = onLevel
        segments.clear()
        fastEmptyCount = 0
        useOnDevice = factory.isOnDeviceAvailable()
        if (!useOnDevice && !factory.isSystemAvailable()) {
            onError("设备没有可用的语音识别服务")
            return
        }
        running = true
        stopping = false
        startSegment(generation)
    }

    private fun startSegment(token: Int) {
        if (!running || stopping || token != generation) return
        val instance = try {
            factory.create(useOnDevice)
        } catch (e: Exception) {
            Log.w(TAG, "Could not create speech recognizer (onDevice=$useOnDevice)", e)
            if (fallbackToSystem(token)) return
            finishError("无法启动设备语音识别")
            return
        }
        recognizer = instance
        segmentStartedAt = SystemClock.elapsedRealtime()
        instance.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) { if (recognizer === instance) levelCallback(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) {
                if (recognizer !== instance || token != generation) return
                Log.w(TAG, "Speech recognition error=$error onDevice=$useOnDevice")
                destroy(instance)
                if (!stopping && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS &&
                    error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT &&
                    fallbackToSystem(token)) return
                if (continuous && !stopping &&
                    (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) &&
                    canRetryEmpty()) {
                    scheduleNext(token)
                    return
                }
                if (stopping && segments.isNotEmpty() &&
                    (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                    finishResult()
                    return
                }
                finishError(when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "请允许使用麦克风"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "系统语音识别连接超时，请检查网络后重试"
                    SpeechRecognizer.ERROR_NETWORK -> "系统语音识别网络连接失败，请检查网络后重试"
                    SpeechRecognizer.ERROR_SERVER -> "系统语音识别服务出错，请稍后重试"
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听清，再试一次"
                    else -> "设备语音识别不可用，请检查系统语音服务"
                })
            }
            override fun onResults(results: Bundle?) {
                if (recognizer !== instance || token != generation) return
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                destroy(instance)
                if (text.isNotBlank()) {
                    segments.add(text)
                    fastEmptyCount = 0
                }
                if (continuous && !stopping && (text.isNotBlank() || canRetryEmpty())) scheduleNext(token)
                else if (segments.isNotEmpty() || !continuous) finishResult()
                else finishError("没听清，再试一次")
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        try {
            instance.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                if (useOnDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
        } catch (e: Exception) {
            Log.w(TAG, "Could not start speech recognizer (onDevice=$useOnDevice)", e)
            destroy(instance)
            if (!fallbackToSystem(token)) finishError("无法启动设备语音识别")
        }
    }

    private fun fallbackToSystem(token: Int): Boolean {
        if (!useOnDevice || !factory.isSystemAvailable()) return false
        useOnDevice = false
        if (!stopping) scheduleNext(token)
        else finishError("设备语音识别不可用，请检查系统语音服务")
        return true
    }

    // 快速连续空结果通常表示服务损坏，不能在后台无限重试。
    private fun canRetryEmpty(): Boolean {
        fastEmptyCount = if (SystemClock.elapsedRealtime() - segmentStartedAt < 1500) fastEmptyCount + 1 else 0
        return fastEmptyCount < 3
    }

    private fun scheduleNext(token: Int) {
        handler.postDelayed({ startSegment(token) }, 200)
    }

    private fun destroy(instance: DeviceRecognizerBackend) {
        if (recognizer === instance) recognizer = null
        instance.destroy()
    }

    private fun finishResult() {
        val text = segments.joinToString("")
        running = false
        stopping = false
        resultCallback(text)
    }

    private fun finishError(message: String) {
        running = false
        stopping = false
        errorCallback(message)
    }

    fun stop() {
        if (!running || stopping) return
        stopping = true
        val current = recognizer
        if (current == null) {
            if (segments.isNotEmpty()) finishResult() else finishError("没听清，再试一次")
        } else {
            try {
                current.stopListening()
                val token = generation
                handler.postDelayed({
                    if (token == generation && running && stopping && recognizer === current) {
                        Log.w(TAG, "Speech recognizer did not finish after stop")
                        destroy(current)
                        finishError("设备语音识别响应超时，请重试")
                    }
                }, STOP_TIMEOUT_MS)
            } catch (e: Exception) {
                Log.w(TAG, "Could not stop speech recognizer", e)
                destroy(current)
                finishError("设备语音识别不可用，请检查系统语音服务")
            }
        }
    }

    fun cancel() {
        generation++
        running = false
        stopping = false
        val old = recognizer
        recognizer = null
        old?.cancel()
        old?.destroy()
    }

    private companion object {
        const val TAG = "DeviceSpeechRecognizer"
        const val STOP_TIMEOUT_MS = 5000L
    }
}


internal interface DeviceRecognizerBackend {
    fun setRecognitionListener(listener: RecognitionListener)
    fun startListening(intent: Intent)
    fun stopListening()
    fun cancel()
    fun destroy()
}

internal interface DeviceRecognizerFactory {
    fun isOnDeviceAvailable(): Boolean
    fun isSystemAvailable(): Boolean
    fun create(onDevice: Boolean): DeviceRecognizerBackend
}

private class AndroidDeviceRecognizerFactory(private val context: Context) : DeviceRecognizerFactory {
    override fun isOnDeviceAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    override fun isSystemAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)
    override fun create(onDevice: Boolean): DeviceRecognizerBackend {
        val instance = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        return object : DeviceRecognizerBackend {
            override fun setRecognitionListener(listener: RecognitionListener) = instance.setRecognitionListener(listener)
            override fun startListening(intent: Intent) = instance.startListening(intent)
            override fun stopListening() = instance.stopListening()
            override fun cancel() = instance.cancel()
            override fun destroy() = instance.destroy()
        }
    }
}
