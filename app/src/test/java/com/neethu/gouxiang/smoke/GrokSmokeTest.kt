package com.neethu.gouxiang.smoke

import com.neethu.gouxiang.BuildConfig
import com.neethu.gouxiang.ai.adapter.ChatMessage
import com.neethu.gouxiang.ai.adapter.ChatRole
import com.neethu.gouxiang.ai.adapter.LlmConfig
import com.neethu.gouxiang.ai.adapter.LlmStreamEvent
import com.neethu.gouxiang.ai.adapter.OpenAiCompatibleLlmAdapter
import com.neethu.gouxiang.ai.config.AiConfig
import com.neethu.gouxiang.ai.config.AiProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Grok(xAI 官方)连通性冒烟测试:对 api.x.ai/v1 发一条最小真实请求(chat/
 * completions 流式,max_tokens=16,reasoning_effort=low 压思考 token),断言流
 * 返回非空文本。真实国际网络请求,总时限 60 秒(**仅 PC 端跑**——CI/真机无国际
 * 网络时 skip,不算失败)。
 *
 * 密钥来源:构建注入 BuildConfig.GROK_API_KEY(secrets.properties,由
 * app/build.gradle.kts 读取根目录文件填入)。Key 缺失时显式跳过。
 *
 * 2026-10-30 手工 curl 实测口径:grok-4.5 默认思考 low,reasoning_content 走
 * 适配器兜底通道;SSE 形状与 Ark 同构,delta.content 直出。
 */
class GrokSmokeTest {

    private companion object {
        const val BASE_URL = "https://api.x.ai/v1"
        const val MODEL = "grok-4.5"
        const val MAX_TOKENS = 16
        const val TIMEOUT_MS = 60_000L
    }

    @Test
    fun grokStreamChatReturnsNonEmptyText() {
        val apiKey = BuildConfig.GROK_API_KEY
        assumeTrue("GROK_API_KEY 未配置,冒烟测试跳过", apiKey.isNotBlank())

        val adapter = OpenAiCompatibleLlmAdapter(baseUrl = BASE_URL, apiKey = apiKey)
        val events = runBlocking {
            withTimeout(TIMEOUT_MS) {
                adapter.streamChat(
                    messages = listOf(
                        ChatMessage(role = ChatRole.USER, content = "只回复两个字:收到"),
                    ),
                    config = LlmConfig(
                        baseUrl = BASE_URL,
                        apiKey = apiKey,
                        model = MODEL,
                        temperature = 0.8f,
                        topP = 1.0f,
                        maxTokens = MAX_TOKENS,
                        // 4.5 发 reasoning_effort=none 会 400,压到最低档 low 省思考
                        extraBody = kotlinx.serialization.json.buildJsonObject {
                            put("reasoning_effort", kotlinx.serialization.json.JsonPrimitive("low"))
                        },
                    ),
                ).toList()
            }
        }

        val errors = events.filterIsInstance<LlmStreamEvent.Error>()
        org.junit.Assert.assertTrue(
            "流式请求失败: ${errors.joinToString { it.throwable.message.orEmpty() }}",
            errors.isEmpty(),
        )
        val text = events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text }
        org.junit.Assert.assertTrue(
            "Grok 流式返回为空(事件数=${events.size})",
            text.isNotBlank(),
        )
        val finish = events.filterIsInstance<LlmStreamEvent.Finish>().singleOrNull()
        org.junit.Assert.assertNotNull("缺少 Finish 事件", finish)
        println("GrokSmokeTest 返回文本: $text")
        println("usage: ${finish?.usage}")
    }

    /** 配置页全链路冒烟:AiConfig(GROK) → llmEndpoint() 现组参数直打真实端点。 */
    @Test
    fun grokEndpointAssemblyEndToEnd() {
        val apiKey = BuildConfig.GROK_API_KEY
        assumeTrue("GROK_API_KEY 未配置,冒烟测试跳过", apiKey.isNotBlank())

        val cfg = AiConfig(provider = AiProvider.GROK)
        val ep = cfg.llmEndpoint()
        org.junit.Assert.assertEquals(BASE_URL, ep.baseUrl)
        org.junit.Assert.assertEquals(MODEL, ep.model)
        // 4.5 不发关思考(默认 low 已最优),extraBody 必须为 null
        org.junit.Assert.assertNull("grok-4.5 不应发 reasoning_effort", ep.extraBody)

        val adapter = OpenAiCompatibleLlmAdapter(baseUrl = ep.baseUrl, apiKey = ep.apiKey)
        val events = runBlocking {
            withTimeout(TIMEOUT_MS) {
                adapter.streamChat(
                    messages = listOf(ChatMessage(role = ChatRole.USER, content = "1+1=?只回复数字")),
                    config = LlmConfig(
                        baseUrl = ep.baseUrl,
                        apiKey = ep.apiKey,
                        model = ep.model,
                        maxTokens = MAX_TOKENS,
                        extraBody = ep.extraBody,
                    ),
                ).toList()
            }
        }
        val text = events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text }
        org.junit.Assert.assertTrue("配置链路端到端返回为空", text.isNotBlank())
        println("GrokSmokeTest(endpoint) 返回文本: $text")
    }
}
