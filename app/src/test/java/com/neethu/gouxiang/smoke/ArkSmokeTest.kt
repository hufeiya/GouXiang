package com.neethu.gouxiang.smoke

import com.neethu.gouxiang.BuildConfig
import com.neethu.gouxiang.ai.adapter.ChatMessage
import com.neethu.gouxiang.ai.adapter.ChatRole
import com.neethu.gouxiang.ai.adapter.LlmConfig
import com.neethu.gouxiang.ai.adapter.LlmStreamEvent
import com.neethu.gouxiang.ai.adapter.OpenAiCompatibleLlmAdapter
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 连通性冒烟测试:直接对火山引擎 Ark 发一条最小真实请求(chat/completions 流式,
 * max_tokens=16),断言流返回非空文本。真实网络请求,总时限 30 秒。
 *
 * 密钥来源:构建注入 BuildConfig.VOLCANO_ARK_API_KEY(secrets.properties,
 * 由 app/build.gradle.kts 读取根目录文件填入)。Key 缺失时测试显式跳过
 * (assumeTrue),不算失败。
 */
class ArkSmokeTest {

    private companion object {
        /** 与 range 说明一致:<30 秒、低 max_tokens 的最小真实请求。 */
        const val MODEL = "doubao-seed-2-0-mini-260428"
        const val MAX_TOKENS = 16
        const val TIMEOUT_MS = 30_000L
    }

    @Test
    fun arkStreamChatReturnsNonEmptyText() {
        val apiKey = BuildConfig.VOLCANO_ARK_API_KEY
        assumeTrue("VOLCANO_ARK_API_KEY 未配置,冒烟测试跳过", apiKey.isNotBlank())

        val adapter = OpenAiCompatibleLlmAdapter(
            baseUrl = BuildConfig.VOLCANO_ARK_BASE_URL,
            apiKey = apiKey,
        )
        val events = runBlocking {
            withTimeout(TIMEOUT_MS) {
                adapter.streamChat(
                    messages = listOf(
                        ChatMessage(role = ChatRole.USER, content = "只回复两个字:收到"),
                    ),
                    config = LlmConfig(
                        baseUrl = BuildConfig.VOLCANO_ARK_BASE_URL,
                        apiKey = apiKey,
                        model = MODEL,
                        temperature = 0.8f,
                        topP = 1.0f,
                        maxTokens = MAX_TOKENS,
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
            "Ark 流式返回为空(事件数=${events.size})",
            text.isNotBlank(),
        )
        val finish = events.filterIsInstance<LlmStreamEvent.Finish>().singleOrNull()
        org.junit.Assert.assertNotNull("缺少 Finish 事件", finish)
        println("ArkSmokeTest 返回文本: $text")
        println("usage: ${finish?.usage}")
    }
}
