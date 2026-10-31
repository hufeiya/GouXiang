package com.neethu.gouxiang.ai.config

import android.content.Context
import android.content.SharedPreferences
import com.neethu.gouxiang.BuildConfig
import kotlinx.serialization.json.JsonObject

/**
 * 一次大模型请求的端点参数(由 [AiConfig.llmEndpoint] 组装,消费方
 * ChatOrchestrator / MemoryExtractor 按回合现取——配置页改完即生效,无需重启)。
 */
data class LlmEndpoint(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /** 服务商专属参数(关思考方言,见 [llmExtraBody]);原样并入请求体。 */
    val extraBody: JsonObject? = null,
)

/**
 * AI 配置(「我的 → AI 服务配置」页的数据模型,移植自 AIAvatar-SDK 的
 * AiChatPrefs/VoicePrefs,按 GouXiang 的实际消费面裁剪):
 *
 * - **大模型**:服务商/Key/模型/自定义端点全量跟随(聊天/记忆/心声/朋友圈生文
 *   同路,见 [llmEndpoint]);
 * - **语音合成(TTS)**:存聊天朗读与语音通话使用的火山豆包语音 Key 与硅基流动 Key
 *   (引擎内置「火山 seed-tts-2.0 优先、硅基 CosyVoice 兜底」双链,不做
 *   AIAvatar 的多引擎矩阵——Edge-TTS/百炼原生协议适配器未移植);
 * - **语音识别(ASR)**:云端识别的模型名跟随大模型服务商(回落规则见
 *   [asrProviderFor]),Key 共用对应服务商那份;AIAvatar 的系统识别引擎与
 *   「语音直接发送」在 GouXiang 无消费面(通话是按住说话、松手即发),不移植。
 *
 * **Key 兜底语义(与现状兼容的关键)**:每把 key 的生效值 = 用户在本页填写的值,
 * 留空回落仓库 secrets.properties 经 BuildConfig 注入的同名默认(见
 * [defaultKeyFor])——不配置时行为与「密钥烘进 BuildConfig」时代完全一致,
 * 本页的价值是**换服务商/换模型/覆盖密钥**三件事即时生效。
 */
data class AiConfig(
    val provider: AiProvider = DEFAULT_PROVIDER,
    val llmModel: String = "",
    val apiKeySiliconflow: String = "",
    /** 火山方舟(Ark)API Key——火山大模型链路。 */
    val apiKeyVolcano: String = "",
    /** 豆包语音控制台 API Key——语音合成链路(与方舟互不通用)。 */
    val apiKeyVolcanoTts: String = "",
    /** OpenRouter API Key——大模型一把通用。 */
    val apiKeyOpenrouter: String = "",
    /** DeepSeek 官方 API Key——只有 chat。 */
    val apiKeyDeepseek: String = "",
    /** 阿里云百炼 API Key——chat 专属端点一把通吃。 */
    val apiKeyBailian: String = "",
    /** xAI Grok API Key——官方 OpenAI 兼容 /v1,只有 chat(无 ASR 端点)。 */
    val apiKeyGrok: String = "",
    /** 自定义端点 baseUrl(OpenAI 兼容;非空 = 模型名透传不走目录校验)。 */
    val customBaseUrl: String = "",
    /** 自定义端点的 API Key(中转站需要;本地模型随意占位)。 */
    val apiKeyCustom: String = "",
    val ttsModel: String = "",
    /** 语音音色(火山链路消费;留空 = 适配器内置默认)。 */
    val ttsVoice: String = "",
    /** 云端识别模型名;留空 = 按端点自动推断(见 [resolveAsrModel])。 */
    val asrModel: String = "",
) {

    /** 大模型服务的 API Key(用户填写值;调用方要用生效值走 [effectiveApiKey])。 */
    fun apiKeyFor(p: AiProvider): String = when (p) {
        AiProvider.SILICONFLOW -> apiKeySiliconflow
        AiProvider.VOLCANO -> apiKeyVolcano
        AiProvider.OPENROUTER -> apiKeyOpenrouter
        AiProvider.DEEPSEEK -> apiKeyDeepseek
        AiProvider.BAILIAN -> apiKeyBailian
        AiProvider.GROK -> apiKeyGrok
        AiProvider.CUSTOM -> apiKeyCustom
    }

    /**
     * 大模型 key 的生效值:本页填写优先,留空回落 BuildConfig 烘入的仓库默认。
     * 火山默认即方舟那把(豆包语音 key 是 TTS 专用,见 [effectiveTtsKey])。
     */
    fun effectiveApiKey(p: AiProvider): String =
        apiKeyFor(p).ifBlank { defaultKeyFor(p) }

    /** 语音合成的生效 key:豆包语音那把优先,留空回落 BuildConfig 默认。 */
    fun effectiveTtsKey(): String =
        apiKeyVolcanoTts.ifBlank { BuildConfig.VOLCANO_TTS_API_KEY }

    /** 语音通话识别的生效 key:跟随 ASR 服务商(回落规则同 AIAvatar)。 */
    fun effectiveAsrKey(): String = effectiveApiKey(asrProviderFor(provider))

    /** 大模型解析后的模型名(自定义端点透传;其余经目录校验落默认)。 */
    fun resolvedLlmModel(catalogExtra: Set<String> = emptySet()): String =
        resolveLlmModel(provider, llmModel, catalogExtra)

    /** 大模型端点的生效 baseUrl(自定义端点用手填值,留空配置页会拦)。 */
    fun effectiveBaseUrl(): String =
        if (provider == AiProvider.CUSTOM) customBaseUrl.trim() else provider.baseUrl

    /** 是否已可发大模型请求(自定义端点必须填了 baseUrl)。 */
    val isConfigured: Boolean
        get() = effectiveApiKey(provider).isNotBlank() &&
            (provider != AiProvider.CUSTOM || customBaseUrl.isNotBlank()) &&
            resolvedLlmModel().isNotBlank()

    /**
     * 聊天/记忆/心声/朋友圈生文共用的大模型端点(每回合现组,改配置即生效)。
     * temperature/maxTokens 等回合参数由调用方按场景覆盖。
     */
    fun llmEndpoint(catalogExtra: Set<String> = emptySet()): LlmEndpoint = LlmEndpoint(
        baseUrl = effectiveBaseUrl(),
        apiKey = effectiveApiKey(provider),
        model = resolvedLlmModel(catalogExtra),
        extraBody = llmExtraBody(provider, resolvedLlmModel(catalogExtra)),
    )

    companion object {
        /**
         * 缺省服务商 = 火山引擎:与「密钥烘进 BuildConfig」时代的默认链路一致
         * (Ark doubao-seed-2-0-mini,冒烟实测连通),未配置时行为不变。
         */
        val DEFAULT_PROVIDER = AiProvider.VOLCANO

        /** 仓库 secrets.properties → BuildConfig 注入的默认 key(留空回落语义)。 */
        fun defaultKeyFor(p: AiProvider): String = when (p) {
            AiProvider.SILICONFLOW -> BuildConfig.SILICONFLOW_API_KEY
            AiProvider.VOLCANO -> BuildConfig.VOLCANO_ARK_API_KEY
            AiProvider.OPENROUTER -> BuildConfig.OPENROUTER_API_KEY
            AiProvider.DEEPSEEK -> BuildConfig.DEEPSEEK_OFFICIAL_API_KEY
            AiProvider.BAILIAN -> BuildConfig.ALIYUNBAILIAN_API_KEY
            AiProvider.GROK -> BuildConfig.GROK_API_KEY
            AiProvider.CUSTOM -> ""
        }
    }
}

// ── SharedPreferences 键(与 AIAvatar-SDK 的 push-api-keys 技能同款命名,便于
//    排查与脚本对账;.agents/skills/push-api-keys 的 MAPPING 与这里一一对应)──
private const val KEY_PROVIDER = "ai_provider"
private const val KEY_LLM_MODEL = "ai_llm_model"
private const val KEY_API_KEY_SILICONFLOW = "ai_api_key_siliconflow"
private const val KEY_API_KEY_VOLCANO = "ai_api_key_volcano"
private const val KEY_API_KEY_VOLCANO_TTS = "ai_api_key_volcano_tts"
private const val KEY_API_KEY_OPENROUTER = "ai_api_key_openrouter"
private const val KEY_API_KEY_DEEPSEEK = "ai_api_key_deepseek"
private const val KEY_API_KEY_BAILIAN = "ai_api_key_bailian"
private const val KEY_API_KEY_GROK = "ai_api_key_grok"
private const val KEY_CUSTOM_BASE_URL = "ai_custom_base_url"
private const val KEY_API_KEY_CUSTOM = "ai_api_key_custom"
private const val KEY_TTS_MODEL = "ai_tts_model"
private const val KEY_TTS_VOICE = "ai_tts_voice"
private const val KEY_ASR_MODEL = "ai_asr_model"

/** 读枚举偏好;名字失效(改过枚举名)或未存过返回 null。 */
private inline fun <reified T : Enum<T>> prefsEnum(sp: SharedPreferences, key: String): T? =
    sp.getString(key, null)?.let { name -> runCatching { enumValueOf<T>(name) }.getOrNull() }

/**
 * AI 配置仓:「我的 → AI 服务配置」页的持久化(SharedPreferences,进程内缓存
 * 即时可见——ChatOrchestrator 每回合 load 一次,改完即生效无需重启)。
 */
object AiConfigStore {

    private const val FILE = "ai_config_prefs"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): AiConfig {
        val sp = prefs(context)
        return AiConfig(
            provider = prefsEnum<AiProvider>(sp, KEY_PROVIDER) ?: AiConfig.DEFAULT_PROVIDER,
            llmModel = sp.getString(KEY_LLM_MODEL, "").orEmpty(),
            apiKeySiliconflow = sp.getString(KEY_API_KEY_SILICONFLOW, "").orEmpty(),
            apiKeyVolcano = sp.getString(KEY_API_KEY_VOLCANO, "").orEmpty(),
            apiKeyVolcanoTts = sp.getString(KEY_API_KEY_VOLCANO_TTS, "").orEmpty(),
            apiKeyOpenrouter = sp.getString(KEY_API_KEY_OPENROUTER, "").orEmpty(),
            apiKeyDeepseek = sp.getString(KEY_API_KEY_DEEPSEEK, "").orEmpty(),
            apiKeyBailian = sp.getString(KEY_API_KEY_BAILIAN, "").orEmpty(),
            apiKeyGrok = sp.getString(KEY_API_KEY_GROK, "").orEmpty(),
            customBaseUrl = sp.getString(KEY_CUSTOM_BASE_URL, "").orEmpty(),
            apiKeyCustom = sp.getString(KEY_API_KEY_CUSTOM, "").orEmpty(),
            ttsModel = sp.getString(KEY_TTS_MODEL, "").orEmpty(),
            ttsVoice = sp.getString(KEY_TTS_VOICE, "").orEmpty(),
            asrModel = sp.getString(KEY_ASR_MODEL, "").orEmpty(),
        )
    }

    fun save(context: Context, c: AiConfig) {
        prefs(context).edit()
            .putString(KEY_PROVIDER, c.provider.name)
            .putString(KEY_LLM_MODEL, c.llmModel.trim())
            .putString(KEY_API_KEY_SILICONFLOW, c.apiKeySiliconflow.trim())
            .putString(KEY_API_KEY_VOLCANO, c.apiKeyVolcano.trim())
            .putString(KEY_API_KEY_VOLCANO_TTS, c.apiKeyVolcanoTts.trim())
            .putString(KEY_API_KEY_OPENROUTER, c.apiKeyOpenrouter.trim())
            .putString(KEY_API_KEY_DEEPSEEK, c.apiKeyDeepseek.trim())
            .putString(KEY_API_KEY_BAILIAN, c.apiKeyBailian.trim())
            .putString(KEY_API_KEY_GROK, c.apiKeyGrok.trim())
            .putString(KEY_CUSTOM_BASE_URL, c.customBaseUrl.trim())
            .putString(KEY_API_KEY_CUSTOM, c.apiKeyCustom.trim())
            .putString(KEY_TTS_MODEL, c.ttsModel.trim())
            .putString(KEY_TTS_VOICE, c.ttsVoice.trim())
            .putString(KEY_ASR_MODEL, c.asrModel.trim())
            .apply()
    }
}
