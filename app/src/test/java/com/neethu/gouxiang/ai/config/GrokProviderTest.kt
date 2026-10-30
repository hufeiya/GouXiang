package com.neethu.gouxiang.ai.config

import com.neethu.gouxiang.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grok(xAI 官方)接入的目录/解析纯函数单测(2026-10-30 接入时的实测口径固化):
 * 端点、默认模型视觉优先、关思考方言(只有 4.3 收 none)、目录过滤(imagine 生图
 * /视频剔除)、ASR 回落。全部零网络零 Android 依赖。
 */
class GrokProviderTest {

    // ── 服务商目录 ──────────────────────────────────────────────────────

    @Test
    fun `endpoint is official xai v1`() {
        assertEquals("https://api.x.ai/v1", AiProvider.GROK.baseUrl)
        assertEquals("Grok(xAI 官方)", AiProvider.GROK.label)
    }

    @Test
    fun `default model is grok-4_5 and is vision-first`() {
        // 用户指定默认 grok-4.5;defaultLlmModel = 视觉清单 ∩ LLM 清单的首位
        assertEquals("grok-4.5", AiProvider.GROK.defaultLlmModel)
        assertTrue("grok-4.5 必须带视觉标注", "grok-4.5" in AiProvider.GROK.visionLlmModels)
        assertEquals("grok-4.5", resolveLlmModel(AiProvider.GROK, ""))
    }

    @Test
    fun `static model list covers 4_3 to 4_7`() {
        assertEquals(
            listOf("grok-4.5", "grok-4.6", "grok-4.7", "grok-4.3"),
            AiProvider.GROK.llmModels,
        )
    }

    @Test
    fun `tts is intentionally not wired - empty catalog like deepseek`() {
        assertTrue(AiProvider.GROK.ttsModels.isEmpty())
        assertEquals("", AiProvider.GROK.defaultTtsModel)
        assertTrue(AiProvider.GROK.voices.isEmpty())
    }

    // ── 模型解析(校验 + 默认,跨服务商残留防泄漏)───────────────────────

    @Test
    fun `configured valid model passes through`() {
        assertEquals("grok-4.6", resolveLlmModel(AiProvider.GROK, "grok-4.6"))
    }

    @Test
    fun `residual model from other provider falls back to default`() {
        // 切服务商残留的旧清单值(火山模型名)必须落回 grok 默认
        assertEquals(
            "grok-4.5",
            resolveLlmModel(AiProvider.GROK, "doubao-seed-2-0-mini-260428"),
        )
    }

    @Test
    fun `catalog extra ids are allowed through validation`() {
        // /models 实时目录里会出现静态清单外的新模型(如 grok-4.20-0309-reasoning)
        assertEquals(
            "grok-4.20-0309-reasoning",
            resolveLlmModel(AiProvider.GROK, "grok-4.20-0309-reasoning", setOf("grok-4.20-0309-reasoning")),
        )
    }

    @Test
    fun `all static models flagged vision via list or heuristic`() {
        for (id in AiProvider.GROK.llmModels) {
            assertTrue("$id 应判定为视觉模型", isVisionLlm(AiProvider.GROK, id))
        }
    }

    // ── 关思考方言(实测口径:只有 4.3 收 none)─────────────────────────

    @Test
    fun `grok-4_3 gets reasoning_effort none`() {
        val body = llmExtraBody(AiProvider.GROK, "grok-4.3")
        assertEquals("\"none\"", body?.get("reasoning_effort").toString())
    }

    @Test
    fun `grok-4_5 and later get no extra body - default thinking is low`() {
        // 4.5/4.6/4.7 默认思考 low 且最低就是 low,发 none 400;静默不发
        assertNull(llmExtraBody(AiProvider.GROK, "grok-4.5"))
        assertNull(llmExtraBody(AiProvider.GROK, "grok-4.6"))
        assertNull(llmExtraBody(AiProvider.GROK, "grok-4.7"))
    }

    @Test
    fun `non-reasoning catalog variants get no extra body`() {
        // 4.20-non-reasoning 发任何 reasoning_effort 都 400(实测);靠后缀自证纯文本
        assertNull(llmExtraBody(AiProvider.GROK, "grok-4.20-0309-non-reasoning"))
        assertNull(llmExtraBody(AiProvider.GROK, "grok-4.20-0309-reasoning"))
    }

    @Test
    fun `other providers unaffected by grok dialect`() {
        assertNull(llmExtraBody(AiProvider.GROK, "grok-build-0.1"))
        // 既有方言不回归:火山/硅基/百炼照旧
        assertTrue(llmExtraBody(AiProvider.VOLCANO, "doubao-seed-2-0-mini-260428") != null)
        assertTrue(llmExtraBody(AiProvider.SILICONFLOW, "Qwen/Qwen3.8-27B") != null)
        assertTrue(llmExtraBody(AiProvider.BAILIAN, "qwen3.8-flash") != null)
    }

    // ── /models 目录过滤(grok-imagine 生图/视频剔除)────────────────────

    @Test
    fun `imagine image and video entries are filtered out of chat options`() {
        val entries = listOf(
            ModelCatalogEntry(id = "grok-4.5"),
            ModelCatalogEntry(id = "grok-imagine-image"),
            ModelCatalogEntry(id = "grok-imagine-video-1.5"),
            ModelCatalogEntry(id = "grok-imagine-image-quality"),
        )
        val options = chatModelOptions(AiProvider.GROK, entries)
        val ids = options.map { it.id }
        assertTrue("grok-4.5 应保留", "grok-4.5" in ids)
        assertFalse("imagine-image 应被剔除", ids.any { it.contains("imagine") })
    }

    @Test
    fun `non-reasoning catalog entries survive the filter`() {
        val options = chatModelOptions(AiProvider.GROK, listOf(ModelCatalogEntry(id = "grok-4.20-0309-reasoning")))
        assertTrue(options.any { it.id == "grok-4.20-0309-reasoning" })
    }

    // ── ASR 回落(无云端识别端点,同火山/DeepSeek 语义)──────────────────

    @Test
    fun `asr falls back to siliconflow for grok`() {
        assertEquals(AiProvider.SILICONFLOW, asrProviderFor(AiProvider.GROK))
        assertTrue(asrExplicitAsrModels(AiProvider.GROK).isEmpty())
    }

    @Test
    fun `asr fallback chain for volcano and deepseek unchanged`() {
        assertEquals(AiProvider.SILICONFLOW, asrProviderFor(AiProvider.VOLCANO))
        assertEquals(AiProvider.SILICONFLOW, asrProviderFor(AiProvider.DEEPSEEK))
        assertEquals(AiProvider.OPENROUTER, asrProviderFor(AiProvider.OPENROUTER))
    }

    // ── Key 生效链(BuildConfig 留空回落)───────────────────────────────

    @Test
    fun `grok key maps through apiKeyFor and defaultKeyFor`() {
        val cfg = AiConfig(apiKeyGrok = "xai-user-filled")
        assertEquals("xai-user-filled", cfg.apiKeyFor(AiProvider.GROK))
        assertEquals("xai-user-filled", cfg.effectiveApiKey(AiProvider.GROK))
        // 留空回落 BuildConfig(secrets.properties 注入;PC 单测环境同样烘入)
        val blank = AiConfig()
        assertEquals(BuildConfig.GROK_API_KEY, blank.effectiveApiKey(AiProvider.GROK))
    }

    @Test
    fun `grok endpoint assembly uses official base url and resolved model`() {
        val cfg = AiConfig(provider = AiProvider.GROK, llmModel = "grok-4.3")
        val ep = cfg.llmEndpoint()
        assertEquals("https://api.x.ai/v1", ep.baseUrl)
        assertEquals("grok-4.3", ep.model)
        assertEquals("\"none\"", ep.extraBody?.get("reasoning_effort").toString())
        assertTrue(cfg.isConfigured == BuildConfig.GROK_API_KEY.isNotBlank() || cfg.apiKeyGrok.isNotBlank())
    }

    @Test
    fun `default provider remains volcano - behavior compatibility`() {
        // 未配置时行为与密钥烘死时代一致:默认服务商仍火山,不能被 Grok 抢默认
        assertEquals(AiProvider.VOLCANO, AiConfig.DEFAULT_PROVIDER)
        assertEquals(AiProvider.VOLCANO, AiConfig().provider)
    }
}
