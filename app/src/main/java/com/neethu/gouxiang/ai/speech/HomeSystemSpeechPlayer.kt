package com.neethu.gouxiang.ai.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** 系统语音兜底：每次播放独立持有引擎，停止时同时结束初始化和播放等待。 */
internal class HomeSystemSpeechPlayer(private val context: Context) {
    private class Session {
        val initialized = CompletableDeferred<Boolean>()
        val finished = CompletableDeferred<Boolean>()
        var engine: TextToSpeech? = null
        var announced = false
    }

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private var active: Session? = null

    suspend fun play(text: String, isCurrent: () -> Boolean, onStart: () -> Unit): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val session = Session()
            synchronized(lock) {
                if (!isCurrent()) return@withContext false
                active = session
                try {
                    // post 保证构造函数返回并记录 engine 后才处理初始化回调。
                    session.engine = TextToSpeech(context) { status ->
                        main.post {
                            if (isActive(session)) session.initialized.complete(status == TextToSpeech.SUCCESS)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "System speech initialization failed", e)
                    session.initialized.complete(false)
                }
            }
            try {
                val initialized = withTimeoutOrNull(INIT_TIMEOUT_MS) { session.initialized.await() }
                if (!isActive(session) || !isCurrent()) return@withContext false
                if (initialized != true) {
                    Log.w(TAG, if (initialized == null) "System speech initialization timed out" else "System speech initialization unavailable")
                    return@withContext false
                }
                val engine = session.engine ?: return@withContext false
                val language = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
                if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "System speech does not support Chinese (status=$language)")
                    return@withContext false
                }
                val chunks = splitSystemSpeech(text, TextToSpeech.getMaxSpeechInputLength())
                if (chunks.isEmpty()) return@withContext false
                val finalId = "home-${System.identityHashCode(session)}-${chunks.lastIndex}"
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        main.post {
                            if (isActive(session) && isCurrent() && !session.announced) {
                                session.announced = true
                                Log.i(TAG, "System opening speech started")
                                onStart()
                            }
                        }
                    }
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == finalId) main.post {
                            if (isActive(session) && isCurrent()) {
                                Log.i(TAG, "System opening speech completed")
                                session.finished.complete(true)
                            }
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        main.post { Log.w(TAG, "System speech playback error"); session.finished.complete(false) }
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        main.post { Log.w(TAG, "System speech playback error (code=$errorCode)"); session.finished.complete(false) }
                    }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        main.post { session.finished.complete(false) }
                    }
                })
                chunks.forEachIndexed { index, chunk ->
                    if (!isActive(session) || !isCurrent() || session.finished.isCompleted) return@withContext false
                    val id = "home-${System.identityHashCode(session)}-$index"
                    val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    if (engine.speak(chunk, mode, null, id) != TextToSpeech.SUCCESS) {
                        Log.w(TAG, "System speech request rejected")
                        return@withContext false
                    }
                }
                val timeout = (30_000L + text.length.toLong() * 500L).coerceAtMost(600_000L)
                val completed = withTimeoutOrNull(timeout) { session.finished.await() }
                if (completed == null) Log.w(TAG, "System speech playback timed out")
                completed == true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "System speech playback failed", e)
                false
            } finally {
                release(session)
            }
        }

    fun stop() {
        val session = synchronized(lock) { active } ?: return
        release(session)
    }

    private fun isActive(session: Session): Boolean = synchronized(lock) { active === session }

    private fun release(session: Session) {
        synchronized(lock) {
            if (active !== session) return
            active = null
            session.initialized.complete(false)
            session.finished.complete(false)
            try { session.engine?.stop() } catch (_: Exception) { }
            try { session.engine?.shutdown() } catch (_: Exception) { }
            session.engine = null
        }
    }

    private companion object {
        const val TAG = "HomeOpeningSpeech"
        const val INIT_TIMEOUT_MS = 12_000L
    }
}

/** 系统引擎有单次文本长度限制，避免切开 UTF-16 代理对。 */
internal fun splitSystemSpeech(text: String, limit: Int): List<String> {
    if (text.isBlank() || limit < 2) return emptyList()
    val result = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + limit, text.length)
        if (end < text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
        result.add(text.substring(start, end))
        start = end
    }
    return result
}
