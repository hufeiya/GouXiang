package com.neethu.gouxiang.ai.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** 模型下拉的一个选项:[vision] = 是否带「视觉」标签(支持图片输入)。 */
data class ModelOption(val id: String, val vision: Boolean)

/**
 * `/models` 目录的一个条目:[id] 必有,其余元数据各家给什么收什么——火山 Ark 带
 * [domain](LLM/VLM/Embedding…)与 [status](Shutdown/Retiring),OpenRouter 带
 * input/outputModalities(architecture 内嵌),其余只有 id。
 */
data class ModelCatalogEntry(
    val id: String,
    val domain: String? = null,
    val status: String? = null,
    val inputModalities: List<String>? = null,
    val outputModalities: List<String>? = null,
)

/**
 * OpenAI 兼容网关的大模型清单(GET {base}/models):配置页模型下拉的实时获取
 * 路径(移植自 AIAvatar-SDK 的 OpenAiCompatibleModelCatalog)。**永不抛异常**
 * ——网络/鉴权/解析任何一步失败都返回空列表,调用方回退静态清单。
 */
class OpenAiCompatibleModelCatalog(private val client: OkHttpClient = OkHttpClient()) {

    suspend fun fetch(baseUrl: String, apiKey: String): List<ModelCatalogEntry> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(baseUrl.trimEnd('/') + "/models")
                    .header("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string() ?: return@withContext emptyList()
                    parse(body)
                }
            } catch (_: IOException) {
                emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }

    /** 防御式解析:结构对不上就空列表,绝不向上抛。 */
    internal fun parse(body: String): List<ModelCatalogEntry> = try {
        val data = Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
            ?: return emptyList()
        data.asSequence()
            .mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = (obj["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                ModelCatalogEntry(
                    id = id,
                    domain = obj.stringOf("domain"),
                    status = obj.stringOf("status"),
                    inputModalities = obj.stringListOf("architecture", "input_modalities"),
                    outputModalities = obj.stringListOf("architecture", "output_modalities"),
                )
            }
            .distinctBy { it.id }
            .toList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun JsonObject.stringOf(key: String): String? {
        // JsonNull 也是 JsonPrimitive(显式 "status":null)——先排除再取 content
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.content.takeIf { it.isNotBlank() }
    }

    /** architecture.input_modalities / output_modalities(嵌套一层)防御式读取。 */
    private fun JsonObject.stringListOf(parent: String, key: String): List<String>? =
        ((this[parent] as? JsonObject)?.get(key) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?.takeIf { it.isNotEmpty() }
}

/**
 * 硅基流动音色清单(/audio/voice/list):TTS 音色下拉的实时获取路径(尽力而为,
 * 失败/为空由调用方落回静态清单)。移植自 AIAvatar-SDK 的 SiliconFlowVoiceCatalog。
 */
class SiliconFlowVoiceCatalog(private val client: OkHttpClient = OkHttpClient()) {

    data class VoiceOption(val value: String, val label: String)

    suspend fun fetch(baseUrl: String, apiKey: String): List<VoiceOption> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(baseUrl.trimEnd('/') + "/audio/voice/list")
                    .header("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string() ?: return@withContext emptyList()
                    parse(body)
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    internal fun parse(body: String): List<VoiceOption> = try {
        // 返回 {"result":[…]},条目为 {"uri":"<model>:<名>","name":"<名>"} 或字符串;
        // 空账号返回 {"result":[]},空列表是常态而非异常
        val result = Json.parseToJsonElement(body).jsonObject["result"]?.jsonArray
            ?: return emptyList()
        result.asSequence()
            .mapNotNull { element ->
                when (element) {
                    is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }?.let { VoiceOption(it, it) }
                    is JsonObject -> {
                        val value = element.stringOf("uri") ?: element.stringOf("voice")
                            ?: element.stringOf("id") ?: return@mapNotNull null
                        VoiceOption(value, element.stringOf("name") ?: value)
                    }
                    else -> null
                }
            }
            .distinctBy { it.value }
            .filter { it.value.isNotBlank() }
            .toList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun JsonObject.stringOf(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
}

/**
 * 大模型实时目录的**进程内缓存**:配置页模型下拉展开时若无缓存则拉 GET /models,
 * 拉到的全量目录按服务商暂存。刻意不落 prefs——模型版本实时更新,重启即失效。
 */
object ModelCatalogCache {
    private val cache = java.util.concurrent.ConcurrentHashMap<AiProvider, List<ModelCatalogEntry>>()

    /** 该服务商是否已有缓存(有则下拉直接用,不重复请求)。 */
    fun has(provider: AiProvider): Boolean = cache.containsKey(provider)

    fun entriesFor(provider: AiProvider): List<ModelCatalogEntry> = cache[provider].orEmpty()

    /** 实时目录 id 集(喂给 [resolveLlmModel] 的校验放行)。 */
    fun idsFor(provider: AiProvider): Set<String> = cache[provider]?.map { it.id }?.toSet().orEmpty()

    fun put(provider: AiProvider, entries: List<ModelCatalogEntry>) {
        cache[provider] = entries
    }

    /** 拉取(网络在 IO 线程,失败/为空返回空列表由调用方回退静态清单)。 */
    suspend fun fetch(provider: AiProvider, apiKey: String): List<ModelCatalogEntry> {
        if (apiKey.isBlank()) return emptyList()
        val entries = OpenAiCompatibleModelCatalog().fetch(provider.baseUrl, apiKey)
        if (entries.isNotEmpty()) put(provider, entries)
        return entries
    }
}

/**
 * 非对话模型的目录过滤(匹配前转小写;移植自 AIAvatar-SDK,2026-10-18 按百炼
 * 176 模型目录逐族实测校准):/models 目录混着 embedding/rerank/生图/语音/
 * ocr/翻译/实时音频/内部测试桩等非 chat 条目,逐一剔除;可调被误杀风险高的族
 * (gui-plus/qwen-flash-character/coder/math)刻意不在表里。
 */
private val NON_CHAT_PATTERNS = listOf(
    Regex("embedding|rerank|bge-"),
    Regex("tts|asr|speech|cosyvoice|sambert|paraformer|sensevoice|whisper|unisound|moss-"),
    Regex("image|diffusion|flux|seedream|seedance|seed3d|wan[0-9.]|-3d|3d-|^hyper3d|hitem3d|video"),
    Regex("ocr|^qwen-mt|translat|research|deep-search|search-planning|smart-router"),
    Regex("realtime|-s2s-|livetranslate"),
    Regex("^test-|^sre-|^cmaas-"),
    Regex("^qwen1|^qwen2|^qwen-[0-9]"),
)

/**
 * 全量目录 → 模型下拉选项(纯函数):元数据过滤(火山 domain 只留 LLM/VLM,
 * OpenRouter 只留 text 输出)→ 名字过滤兜底 → 视觉标注(元数据 → 静态清单 →
 * 命名启发式)→ 静态清单兜底并集(去重保序,目录序在前)。
 */
fun chatModelOptions(provider: AiProvider, entries: List<ModelCatalogEntry>): List<ModelOption> {
    val options = mutableListOf<ModelOption>()
    val seen = mutableSetOf<String>()
    for (entry in entries) {
        if (!isChatCandidate(provider, entry)) continue
        if (seen.add(entry.id)) {
            options += ModelOption(entry.id, isVisionCandidate(provider, entry))
        }
    }
    for (id in provider.llmModels) {
        if (seen.add(id)) {
            options += ModelOption(id, id in provider.visionLlmModels)
        }
    }
    return options
}

/** 单条目录是否当作对话模型保留(元数据优先,名字兜底)。 */
internal fun isChatCandidate(provider: AiProvider, entry: ModelCatalogEntry): Boolean {
    if (provider == AiProvider.VOLCANO) {
        // Ark 元数据权威:非 LLM/VLM 域与已下架(Shutdown)都不是可调对话模型
        val domain = entry.domain?.uppercase()
        if (!domain.isNullOrEmpty() && domain != "LLM" && domain != "VLM") return false
        if (entry.status.equals("Shutdown", ignoreCase = true)) return false
    }
    if (provider == AiProvider.OPENROUTER) {
        val output = entry.outputModalities
        if (output != null && !output.any { it.equals("text", ignoreCase = true) }) return false
    }
    val id = entry.id.lowercase()
    return NON_CHAT_PATTERNS.none { it.containsMatchIn(id) }
}

/** 单条目录的视觉标注(元数据 → 静态清单 → 命名启发式)。 */
internal fun isVisionCandidate(provider: AiProvider, entry: ModelCatalogEntry): Boolean {
    if (provider == AiProvider.VOLCANO) {
        // Ark 的 domain=VLM 是官方口径(LLM 域=官方说纯文本,不误标)
        entry.domain?.uppercase()?.let { return it == "VLM" }
    }
    if (provider == AiProvider.OPENROUTER) {
        entry.inputModalities?.let { return it.any { m -> m.equals("image", ignoreCase = true) } }
    }
    if (entry.id in provider.visionLlmModels) return true
    return looksLikeVisionModel(entry.id)
}
