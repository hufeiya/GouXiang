package com.neethu.gouxiang.ai.speech

import android.util.Log
import com.neethu.gouxiang.ai.config.AiConfig
import com.neethu.gouxiang.ai.config.AsrProvider
import com.neethu.gouxiang.ai.config.resolveAsrModel
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** 同一识别入口供通话和语音输入使用；自动模式只尝试用户配置的本地服务。 */
object ConfiguredAsr {
    private val localClient = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).build()

    suspend fun transcribe(wav: ByteArray, config: AiConfig): String {
        val localUrl = config.asrLocalBaseUrl.trim()
        if (config.asrProvider == AsrProvider.LOCAL ||
            (config.asrProvider == AsrProvider.AUTO && localUrl.isNotBlank())) {
            try {
                require(localUrl.isNotBlank()) { "请先填写本地 ASR 服务地址" }
                val model = resolveAsrModel(localUrl, config.asrLocalModel)
                return OpenAiCompatibleAsrAdapter(localUrl, "", localClient)
                    .transcribe(wav, "audio/wav", AsrConfig(model = model, language = "zh"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (config.asrProvider == AsrProvider.LOCAL) throw e
                Log.w("ConfiguredAsr", "Local ASR unavailable; falling back to cloud", e)
            }
        }
        val provider = config.resolvedAsrCloudProvider()
        val baseUrl = config.asrBaseUrl(provider)
        require(baseUrl.isNotBlank()) { "请先填写自定义 ASR 端点地址" }
        val model = resolveAsrModel(baseUrl, config.asrModel)
        return OpenAiCompatibleAsrAdapter(baseUrl, config.effectiveApiKey(provider))
            .transcribe(wav, "audio/wav", AsrConfig(model = model, language = "zh"))
    }
}
