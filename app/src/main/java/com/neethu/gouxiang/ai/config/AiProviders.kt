package com.neethu.gouxiang.ai.config

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * AI 服务商目录(移植自 AIAvatar-SDK 的 ChatProviders + AiProviders 两层,合并为
 * 单文件自包含版——GouXiang 没有 avatar-orchestrator 模块,事实数据直接落这里):
 * 端点、可选模型、音色清单与默认值全部收口在这一个枚举。配置页除 API Key 外
 * 全部下拉框选择,模型/音色留空即用服务商默认(见 [resolveLlmModel]/
 * [resolveTtsModel]/[resolveVoice]),杜绝手输模型名。
 *
 * 多厂商(2026-10 AIAvatar 实测口径):硅基流动 + 火山引擎 + OpenRouter(面向海外,
 * 一把 key 通吃大模型/TTS/ASR)+ DeepSeek 官方(只有 chat,TTS 走语音通话内置
 * 链路、ASR 回落硅基流动)+ 阿里云百炼(一把 key 通吃,chat 专属端点)。
 * 模型清单均经真实接口核验(/v1/models 在册);默认模型视觉优先。
 *
 * GouXiang 的消费面(与 AIAvatar 的差异,见 AiConfigStore KDoc):
 * - 大模型链路(聊天/记忆/心声/朋友圈生文)全量跟随本目录;
 * - TTS 服务聊天朗读和语音通话,ASR 服务语音输入和通话;消费火山豆包语音 Key / 硅基流动 Key /
 *   ASR 模型三项,不做 Edge-TTS/百炼原生协议的多引擎矩阵(未移植那些适配器)。
 */
enum class AiProvider(
    val label: String,
    /** OpenAI 兼容端点(火山即方舟 Ark v3,chat/completions 同构)。 */
    val baseUrl: String,
    val llmModels: List<String>,
    /** 清单里支持图片输入(多模态)的子集——「视觉」标签的静态判定清单。 */
    val visionLlmModels: List<String>,
    /** TTS 请求体里的 model id(语音通话火山链路固定 seed-tts-2.0,仅供展示)。 */
    val ttsModels: List<String>,
    val defaultTtsModel: String,
    /**
     * 音色引用前缀:硅基流动请求音色格式是 `FunAudioLLM/CosyVoice2-0.5B:<短名>`,
     * 且 MOSS-TTSD 也接受该引用(两模型音色互认);火山音色 id 原样发送(null)。
     */
    val voicePrefix: String?,
    val voices: List<String>,
    val defaultVoice: String,
    /** 大模型 API Key 输入框标签/占位(火山的语音与大模型是两把互不通用的 key)。 */
    val llmKeyLabel: String,
    val llmKeyHint: String,
    /** 火山豆包语音 Key 的标签/占位(其余服务商 TTS 共用大模型那份 key)。 */
    val ttsKeyLabel: String = "",
    val ttsKeyHint: String = "",
) {
    SILICONFLOW(
        label = "硅基流动",
        baseUrl = "https://api.siliconflow.cn/v1",
        llmModels = listOf(
            "deepseek-ai/DeepSeek-V4-Flash",
            "deepseek-ai/DeepSeek-V3",
            "Qwen/Qwen3.8-27B",
            "Qwen/Qwen3-VL-32B-Instruct",
        ),
        visionLlmModels = listOf(
            // VL-32B 刻意在前(视觉优先取首位):非思考模型、工具调用矩阵 3/3;
            // Qwen3.8-27B 关思考下工具可靠性 ~4/10,排后
            "Qwen/Qwen3-VL-32B-Instruct",
            "Qwen/Qwen3.8-27B",
        ),
        ttsModels = listOf(
            "FunAudioLLM/CosyVoice2-0.5B",
            "fnlp/MOSS-TTSD-v0.5",
        ),
        defaultTtsModel = "FunAudioLLM/CosyVoice2-0.5B",
        voicePrefix = "FunAudioLLM/CosyVoice2-0.5B",
        voices = listOf("alex", "anna", "bella", "benjamin", "charles", "claire"),
        defaultVoice = "anna",
        // 一家一把 key,LLM/TTS/ASR 共用
        llmKeyLabel = "硅基流动 API Key",
        llmKeyHint = "sk-...",
        ttsKeyLabel = "硅基流动 API Key(语音合成/识别用)",
        ttsKeyHint = "sk-...",
    ),
    VOLCANO(
        label = "火山引擎",
        baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
        llmModels = listOf(
            "doubao-seed-2-0-mini-260428",
            "doubao-seed-2-1-turbo-260628",
            "doubao-seedream-5-0-pro-260628",
            "doubao-seed-2-1-pro-260915",
            "deepseek-v4-flash-ga-260731",
            "doubao-seed-character-260628",
        ),
        visionLlmModels = listOf(
            "doubao-seed-2-0-mini-260428",
            "doubao-seed-2-1-turbo-260628",
            "doubao-seed-2-1-pro-260915",
            "doubao-seed-character-260628",
        ),
        ttsModels = listOf("seed-tts-2.0"),
        defaultTtsModel = "seed-tts-2.0",
        voicePrefix = null,
        // 官方「豆包语音合成模型2.0、S2S-O2.0、S2S-全双工」音色表全量 294 条,
        // 与展示名同源(见 VolcanoVoiceNames.kt),按官方表序
        voices = VOLCANO_VOICE_CATALOG.keys.toList(),
        defaultVoice = "zh_female_vv_uranus_bigtts",
        // 两把钥匙(2026-10-03 实测互不通用):大模型走方舟 Ark 控制台,
        // 语音合成走豆包语音控制台——同选火山也必须各填各的
        llmKeyLabel = "火山方舟 API Key(大模型用)",
        llmKeyHint = "ark-...",
        ttsKeyLabel = "豆包语音 API Key(语音合成用)",
        ttsKeyHint = "粘贴豆包语音控制台 Key",
    ),
    OPENROUTER(
        label = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        // 2026-10-05 实测核验:gemini-3.8/gpt-6-luna 系 reasoning 强制开启、请求
        // 关思考 400,flash 系自适应思考很快不发参数即可;qwen3.7-flash 关思考有效
        llmModels = listOf(
            "google/gemini-3.8-flash",
            "openai/gpt-6-luna",
            "qwen/qwen3.7-flash",
            "anthropic/claude-sonnet-4.6",
            "openrouter/free",
        ),
        visionLlmModels = listOf(
            "google/gemini-3.8-flash",
            "openai/gpt-6-luna",
            "qwen/qwen3.7-flash",
            "anthropic/claude-sonnet-4.6",
            "openrouter/free",
        ),
        // 只收一个默认模型防音色跨模型泄漏(同火山单模型模式)
        ttsModels = listOf("mistralai/voxtral-mini-tts-2603"),
        defaultTtsModel = "mistralai/voxtral-mini-tts-2603",
        voicePrefix = null,
        voices = listOf(
            "en_paul_neutral",
            "en_paul_happy",
            "en_paul_excited",
            "en_paul_sad",
            "en_paul_confident",
            "gb_oliver_neutral",
            "gb_oliver_cheerful",
            "gb_oliver_excited",
            "gb_jane_neutral",
            "gb_jane_curious",
            "gb_jane_sarcasm",
            "fr_marie_neutral",
        ),
        defaultVoice = "en_paul_neutral",
        // 一把 key 通吃 大模型/TTS/ASR(同硅基流动模式)
        llmKeyLabel = "OpenRouter API Key",
        llmKeyHint = "sk-or-v1-...",
    ),
    DEEPSEEK(
        label = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        // /audio/speech、/audio/transcriptions、/embeddings 实测全 404:
        // 只有 chat;语音通话 TTS 走内置火山链路、ASR 回落硅基流动
        llmModels = listOf(
            "deepseek-flash",
            "deepseek-v4-pro",
        ),
        visionLlmModels = listOf("deepseek-flash"),
        ttsModels = emptyList(),
        defaultTtsModel = "",
        voicePrefix = null,
        voices = emptyList(),
        defaultVoice = "",
        llmKeyLabel = "DeepSeek API Key",
        llmKeyHint = "sk-...",
    ),
    BAILIAN(
        label = "阿里云百炼",
        baseUrl = "https://ws-xej6bspqc7ct974u.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
        // chat 走企业专属端点(与公共 dashscope compatible-mode 同 key 双通);
        // 静态清单仅收 chat/视觉实测过的模型,全量目录经 /models 实时拉取
        llmModels = listOf(
            "qwen3.8-flash",
            "qwen3-vl-plus",
            "qwen-flash-character",
            "deepseek-v4.1-flash",
            "kimi-k3",
            "qwen-plus",
        ),
        visionLlmModels = listOf(
            "qwen3.8-flash",
            "qwen3-vl-plus",
        ),
        ttsModels = listOf("qwen3-tts-flash"),
        defaultTtsModel = "qwen3-tts-flash",
        voicePrefix = null,
        voices = listOf("Cherry", "Serena", "Ethan", "Chelsie", "Jennifer", "Ryan"),
        defaultVoice = "Cherry",
        // 一把 key 通吃 大模型/TTS/ASR(同硅基流动模式)
        llmKeyLabel = "阿里云百炼 API Key",
        llmKeyHint = "sk-...",
    ),
    GROK(
        label = "Grok(xAI 官方)",
        baseUrl = "https://api.x.ai/v1",
        // 2026-10-30 实测核验(api.x.ai/v1/models 在册 + chat/流式/视觉逐个真调):
        // grok-4.x 全系 OpenAI 兼容,流式 SSE + stream_options + 视觉(image_url)
        // 实测通过;思考默认 low(reasoning_content 走适配器已有兜底通道)。全量
        // 目录经 /models 实时拉取(grok-imagine-image/video 等生图/视频条目由
        // NON_CHAT_PATTERNS 的 image|video 规则剔除)。
        llmModels = listOf(
            "grok-4.5",
            "grok-4.6",
            "grok-4.7",
            "grok-4.3",
        ),
        visionLlmModels = listOf(
            // 4.5/4.6/4.7 图片输入实测通过(usage.image_tokens>0);4.3 同形实测
            // 描述出红图。全系数值模型均视觉,视觉清单=LLM 清单(默认模型视觉优先)
            "grok-4.5",
            "grok-4.6",
            "grok-4.7",
            "grok-4.3",
        ),
        // 官方有 /v1/audio/speech 端点但当前 key 团队未授权(403 "Team is not
        // authorized"),TTS 暂不接;清单留空与 DeepSeek 同款语义(语音通话走
        // 内置火山优先+硅基兜底链路)
        ttsModels = emptyList(),
        defaultTtsModel = "",
        voicePrefix = null,
        voices = emptyList(),
        defaultVoice = "",
        llmKeyLabel = "xAI Grok API Key",
        llmKeyHint = "xai-...",
    ),
    CUSTOM(
        label = "自定义端点(OpenAI 兼容)",
        baseUrl = "",
        // 中转站/本地模型(ollama/llama.cpp 等)的 BYOK 入场券:baseUrl/模型名
        // 手填(配置页表单+连通性测试),目录性字段全部留空/占位
        llmModels = listOf(""),
        visionLlmModels = emptyList(),
        ttsModels = listOf(""),
        defaultTtsModel = "",
        voicePrefix = null,
        voices = emptyList(),
        defaultVoice = "",
        llmKeyLabel = "API Key",
        llmKeyHint = "sk-...(本地模型可留空占位任意字符)",
    ),
    ;

    /** 大模型默认模型:**视觉优先**——取视觉清单中第一个在 LLM 清单里的模型。 */
    val defaultLlmModel: String
        get() = visionLlmModels.firstOrNull { it in llmModels } ?: llmModels.first()

    /** TTS 模型展示名:剥掉组织前缀(fnlp/MOSS-TTSD-v0.5 → MOSS-TTSD-v0.5)。 */
    fun ttsModelLabel(id: String): String = id.substringAfterLast('/')

    /** 音色显示名:剥掉引用前缀(…:anna → anna);火山 id 本身就是显示名。 */
    fun voiceLabel(value: String): String = value.substringAfterLast(':')
}

/** 语音合成引擎(GouXiang 只消费 OpenAI 兼容一条链路,枚举为目录完整性保留)。 */
enum class TtsEngine { OPENAI_COMPATIBLE, EDGE }

/** ASR 引擎:云端(OpenAI 兼容 multipart)或系统内置(语音通话暂未接系统识别)。 */
enum class AsrEngine { CLOUD, SYSTEM }

/**
 * 把存储的音色值拼成请求用完整引用:已含 `:`(完整引用/自定义克隆 URI)原样,
 * 短名补前缀,火山原样。空串返回空串(由 [resolveVoice] 落到默认值)。
 */
fun composeVoiceRef(provider: AiProvider, voice: String): String = when {
    voice.isBlank() -> ""
    voice.contains(':') -> voice
    provider.voicePrefix != null -> "${provider.voicePrefix}:$voice"
    else -> voice
}

/**
 * LLM 模型解析 = **校验 + 默认**:配置值必须在本服务商清单里,否则(含留空、
 * 切服务商后残留的旧清单值)一律落回服务商默认。配置页全部下拉框,校验兜住
 * 跨服务商残留泄漏;自定义端点透传不走校验(模型名手填)。
 * [extraAllowed] = /models 实时目录 id(内存缓存,重启失效)。
 */
fun resolveLlmModel(provider: AiProvider, configured: String, extraAllowed: Set<String> = emptySet()): String {
    val v = configured.trim()
    return if (provider == AiProvider.CUSTOM) {
        // 自定义端点模型名透传;就绪判定由 isConfigured 的 isNotBlank 兜住「没填」
        v
    } else if (v in provider.llmModels || v in extraAllowed) v else provider.defaultLlmModel
}

/** TTS 模型解析 = 校验 + 默认(同 [resolveLlmModel] 的跨服务商防泄漏语义)。 */
fun resolveTtsModel(provider: AiProvider, configured: String): String {
    val v = configured.trim()
    return if (v in provider.ttsModels) v else provider.defaultTtsModel
}

/** 音色解析 = 校验 + 默认;自定义克隆 URI(speech:…)原样放行。 */
fun resolveVoice(provider: AiProvider, configured: String): String {
    val v = configured.trim()
    if (v.isBlank()) return composeVoiceRef(provider, provider.defaultVoice)
    val composed = composeVoiceRef(provider, v)
    val known = provider.voices.any { composeVoiceRef(provider, it) == composed }
    return if (known || composed.startsWith("speech:")) composed
    else composeVoiceRef(provider, provider.defaultVoice)
}

/**
 * LLM 请求体的服务商专属参数(各家「关思考」方言,移植自 ChatProviders.llmExtraBody)。
 * 思考 token 走 reasoning_content 流式返回而适配器只读 delta.content,聊天场景
 * 下全是首句前的纯等待,按 服务商+模型族 显式关掉:
 * - 火山 doubao-seed 系:thinking: {"type":"disabled"};
 * - 硅基流动 Qwen3 系 / 百炼 qwen 系:enable_thinking: false(百炼只对 qwen* 发
 *   ——glm 系「restricted to True」400,kimi/deepseek 系不认识);
 * - OpenRouter Qwen 系:reasoning: {"enabled": false}(gemini/gpt 系强制思考,
 *   发关思考参数直接 400,不发);
 * - Grok:仅 grok-4.3 收 reasoning_effort: "none"(2026-10-30 实测);4.5/4.6/4.7
 *   默认思考 low 且最低就是 low(发 none 直接 400「does not support value none」),
 *   默认已是聊天场景最优点,一律不发;4.20-non-reasoning 发 reasoning_effort 任意
 *   值都 400,靠 /models 目录的 -non-reasoning 后缀自证纯文本,同样不发。
 * 其余模型返回 null 不发,避免严格端点 400。
 */
fun llmExtraBody(provider: AiProvider, model: String): JsonObject? {
    val m = model.trim()
    return when {
        provider == AiProvider.VOLCANO && m.startsWith("doubao-seed") ->
            buildJsonObject { put("thinking", buildJsonObject { put("type", "disabled") }) }
        provider == AiProvider.SILICONFLOW && m.startsWith("Qwen/Qwen3") ->
            buildJsonObject { put("enable_thinking", false) }
        provider == AiProvider.OPENROUTER && m.startsWith("qwen/") ->
            buildJsonObject { put("reasoning", buildJsonObject { put("enabled", false) }) }
        provider == AiProvider.BAILIAN && m.startsWith("qwen") ->
            buildJsonObject { put("enable_thinking", false) }
        // 只有 4.3 支持显式关思考;清单里的 4.5/4.6/4.7 与目录里的 non-reasoning
        // 变体发任何 reasoning_effort 都 400,保持静默(思考流进适配器兜底通道)
        provider == AiProvider.GROK && m.startsWith("grok-4.3") ->
            buildJsonObject { put("reasoning_effort", "none") }
        else -> null
    }
}

/**
 * 语音识别(ASR)实际生效的服务商:OpenRouter 的 /audio/transcriptions 与硅基
 * 流动同构(共用 key);百炼走自家 chat+input_audio 形态(GouXiang 语音通话未接,
 * 按同商直连展示);火山、DeepSeek 官方与 Grok 无云端 ASR 端点(Grok 的
 * /audio/transcriptions 实测 404),回落硅基流动。
 */
fun asrProviderFor(llmProvider: AiProvider): AiProvider =
    if (llmProvider == AiProvider.VOLCANO || llmProvider == AiProvider.DEEPSEEK ||
        llmProvider == AiProvider.GROK
    ) {
        AiProvider.SILICONFLOW
    } else {
        llmProvider
    }

/** ASR 模型的显式候选(「自动」之外);留空时按端点推断见 [resolveAsrModel]。 */
fun asrExplicitAsrModels(provider: AiProvider): List<String> = when (provider) {
    AiProvider.SILICONFLOW -> listOf("Qwen/Qwen3-ASR-1.7B")
    AiProvider.OPENROUTER -> listOf("openai/whisper-large-v3")
    AiProvider.BAILIAN -> listOf("qwen3-asr-flash")
    AiProvider.VOLCANO, AiProvider.DEEPSEEK, AiProvider.GROK -> emptyList()
    // 自定义端点按 OpenAI 兼容 multipart 直打同端点;不支持时无候选可选
    AiProvider.CUSTOM -> emptyList()
}

/**
 * ASR 模型留空时按端点推断:硅基流动 → Qwen3-ASR-1.7B(语音通话 F6 同款实测稳),
 * OpenRouter → whisper-large-v3,百炼 → qwen3-asr-flash,其他 → whisper-1。
 */
fun resolveAsrModel(baseUrl: String, configured: String): String =
    configured.trim().ifBlank {
        when {
            baseUrl.contains("siliconflow", ignoreCase = true) -> "Qwen/Qwen3-ASR-1.7B"
            baseUrl.contains("openrouter.ai", ignoreCase = true) -> "openai/whisper-large-v3"
            baseUrl.contains("aliyuncs.com", ignoreCase = true) -> "qwen3-asr-flash"
            else -> "whisper-1"
        }
    }

/** 模型是否支持图片输入(「视觉」标签):静态清单 + 命名启发式。 */
fun isVisionLlm(provider: AiProvider, configured: String): Boolean {
    val resolved = resolveLlmModel(provider, configured)
    if (resolved in provider.visionLlmModels) return true
    return looksLikeVisionModel(resolved)
}

/** 视觉命名启发式(qwen3-vl-/vision-/omni 词元实测真视觉;误标宁缺)。 */
fun looksLikeVisionModel(modelId: String): Boolean =
    Regex("(^|[-./_])vl([-_@./]|$|[0-9])|vision|omni", RegexOption.IGNORE_CASE)
        .containsMatchIn(modelId.lowercase())
