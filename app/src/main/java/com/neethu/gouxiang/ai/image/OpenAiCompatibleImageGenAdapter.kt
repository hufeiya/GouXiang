package com.neethu.gouxiang.ai.image

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容 `POST {base}/images/generations` 生图适配器,一实例一端点。
 *
 * 两家实测差异(2026-10-28 真实 key curl 验证):
 * - 火山 Ark(doubao-seedream-5-0-flash-260915):请求尺寸键 `size`,响应 `data[].url`;
 * - 硅基流动(Kwai-Kolors/Kolors):请求尺寸键 `image_size`,响应 `images[].url`。
 * 两个尺寸键都发(未识别的键服务商忽略),两种响应形态都解析——实测双方对
 * 未知键宽容,避免维护两份实现。另外,即使请求固定 `response_format="url"`,
 * 兼容服务商也可能不理会返回 `b64_json`:解析时 url 优先,无 url 则把
 * `b64_json` 包装成 `data:image/png;base64,<payload>` 伪 URL(07 文档 §3.3
 * 的 b64_json/url 双响应形态兼容,下载方识别 `data:` 前缀直接本地解码)。
 *
 * 非 2xx 返回 [ImageGenResult.Failure] 带响应预览(调用方走本地占位图兜底,
 * 不抛异常——头像生成失败是可预期的降级路径,不是崩溃路径)。
 *
 * 参考图([ImageGenRequest.referenceImage] 非空 = 图生图)两家封装形态不同:
 * 火山 Ark seedream 要求 JSON 数组(`"image":["data:…"]`),硅基 Kolors 接受
 * 字符串——由 [referenceAsArray] 按端点装配(两家对无参考图请求均不带该键)。
 */
class OpenAiCompatibleImageGenAdapter(
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val referenceAsArray: Boolean = false,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
) : ImageGenAdapter {

    private val endpoint = baseUrl.trimEnd('/') + "/images/generations"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun generate(request: ImageGenRequest): ImageGenResult = withContext(Dispatchers.IO) {
        try {
            val body = buildRequestBody(request)
            val httpRequest = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(httpRequest).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use ImageGenResult.Failure("HTTP ${response.code}: ${raw.take(300)}")
                }
                val urls = parseUrls(raw)
                if (urls.isEmpty()) ImageGenResult.Failure("响应中无图片 URL: ${raw.take(200)}")
                else ImageGenResult.Success(urls)
            }
        } catch (t: Throwable) {
            ImageGenResult.Failure(t.message ?: t.javaClass.simpleName)
        }
    }

    /** 单函数构建请求体,便于单测断言两把尺寸键同时在场(有参考图再断 image 键)。 */
    internal fun buildRequestBody(request: ImageGenRequest): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("model", JsonPrimitive(model))
                put("prompt", JsonPrimitive(request.prompt))
                put("size", JsonPrimitive(request.size))
                put("image_size", JsonPrimitive(request.size))
                put("n", JsonPrimitive(1))
                put("response_format", JsonPrimitive("url"))
                request.referenceImage?.takeIf { it.isNotBlank() }?.let { ref ->
                    if (referenceAsArray) {
                        put("image", buildJsonArray { add(JsonPrimitive(ref)) })
                    } else {
                        put("image", JsonPrimitive(ref))
                    }
                }
            },
        )

    /** 解析火山 `data[].url` 与硅基 `images[].url` 两种响应形态。 */
    internal fun parseUrls(raw: String): List<String> = try {
        val root = json.parseToJsonElement(raw)
        val arr = (root as? JsonObject)?.get("data") as? JsonArray
            ?: (root as? JsonObject)?.get("images") as? JsonArray
            ?: return emptyList()
        // 逐元素统一解析:url 优先;无 url 但有 b64_json 时产出 data: 伪 URL,
        // 下载方(AvatarImageProvider)识别前缀直接本地解码,不发起 HTTP。
        arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            obj.urlString() ?: obj.b64DataUrl()
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** 元素内 `url` 字段(非空字符串),否则 null。 */
    private fun JsonObject.urlString(): String? =
        this["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    /** 元素内 `b64_json` 字段(非空字符串),包装成 [DATA_URL_PREFIX] 伪 URL。 */
    private fun JsonObject.b64DataUrl(): String? {
        val payload = this["b64_json"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        return "$DATA_URL_PREFIX$payload"
    }
}
