package com.neethu.gouxiang.ai.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** 系统识别器独占麦克风；所有调用及回调均在主线程。 */
class DeviceSpeechRecognizer(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    fun start(onResult: (String) -> Unit, onError: (String) -> Unit, onLevel: (Float) -> Unit = {}) {
        cancel()
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("设备没有可用的语音识别服务")
            return
        }
        val instance = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = instance
        instance.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) { onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) {
                if (recognizer !== instance) return
                recognizer = null
                instance.destroy()
                onError(when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "请允许使用麦克风"
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听清，再试一次"
                    else -> "设备语音识别不可用，请检查系统语音服务"
                })
            }
            override fun onResults(results: Bundle?) {
                if (recognizer !== instance) return
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                recognizer = null
                instance.destroy()
                onResult(text)
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        try {
            instance.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
        } catch (e: Exception) {
            cancel()
            onError("无法启动设备语音识别")
        }
    }

    fun stop() { recognizer?.stopListening() }
    fun cancel() {
        val old = recognizer
        recognizer = null
        old?.cancel()
        old?.destroy()
    }
}
