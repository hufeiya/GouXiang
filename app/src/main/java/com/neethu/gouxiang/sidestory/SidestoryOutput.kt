package com.neethu.gouxiang.sidestory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 番外/心动输出的 schema 校验与清洗(F 工作包 F2;公共约束:LLM 输出必须按
 * schema 校验后才落库,解析失败不污染正式记录)。
 *
 * 输出契约(单行 JSON):
 * ```
 * {"title":"短标题","pages":[{"title":"分区/格标题","body":"正文","imagePrompt":"配图描述(可选)"}]}
 * ```
 *
 * 校验规则:
 * - 截首个平衡 `{...}` → lenient 解析(照 [com.neethu.gouxiang.chat.InnerVoiceGenerator]
 *   的宽松链口径,容忍模型在 JSON 前后的客套话/围栏);
 * - `body` 为空的格丢弃;剩余条数必须落在模板边界 [SidestoryCatalog.Entry.pageMin,
 *   pageMax] 内,越界判失败(返回 null,调用方不落库);
 * - 固定分区形态(SECTIONS):条数必须等于分区清单长度,且分区标题按清单**盖章**
 *   (模型标题漂移不算失败,标题以目录为准;条数不符才算失败);
 * - 字段清洗:title ≤ 16 字、body ≤ 400 字、imagePrompt ≤ 60 字(超长截断);
 * - 全链路不抛异常:任何非法输入返回 null(可解释失败)。
 */
object SidestoryOutput {

    const val TITLE_MAX_CHARS = 16
    const val BODY_MAX_CHARS = 400
    const val IMAGE_PROMPT_MAX_CHARS = 60

    /** 一格内容(已清洗;序列化为记录 bodyJson 的 pages 元素)。 */
    data class Page(
        val title: String,
        val body: String,
        val imagePrompt: String = "",
    )

    /** 一次校验通过的完整产物(title 供记录回填,pages 供结构化落库)。 */
    data class Validated(val title: String, val pages: List<Page>)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 解析并按模板 schema 校验。
     *
     * @return 校验通过的产物;null = 无法解析 / 条数越界(调用方不落库)。
     */
    fun parse(raw: String, entry: SidestoryCatalog.Entry): Validated? {
        val fragment = firstJsonObject(raw) ?: return null
        val root = try {
            json.parseToJsonElement(fragment).jsonObject
        } catch (_: Exception) {
            return null
        }
        val title = clamp(root.strMember("title"), TITLE_MAX_CHARS).orEmpty()
        val pages = (root["pages"] as? JsonArray ?: return null)
            .asSequence()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { obj ->
                val body = clamp(obj.strMember("body"), BODY_MAX_CHARS) ?: return@mapNotNull null
                Page(
                    title = clamp(obj.strMember("title"), TITLE_MAX_CHARS).orEmpty(),
                    body = body,
                    imagePrompt = clamp(obj.strMember("imagePrompt"), IMAGE_PROMPT_MAX_CHARS).orEmpty(),
                )
            }
            .toList()
        if (pages.isEmpty()) return null
        return when (entry.pageStyle) {
            SidestoryCatalog.PageStyle.THEATER ->
                if (pages.size < entry.pageMin) null
                else Validated(title.ifBlank { entry.name }, pages.take(entry.pageMax))

            // 固定分区:条数必须精确等于分区清单;标题按清单盖章。
            SidestoryCatalog.PageStyle.SECTIONS -> {
                val sections = entry.sections
                if (pages.size != sections.size) return null
                Validated(
                    title = title.ifBlank { entry.name },
                    pages = pages.mapIndexed { index, page -> page.copy(title = sections[index]) },
                )
            }
        }
    }

    /** 结构化文本 → 记录 bodyJson(紧凑单行;DAO 层原样存取)。 */
    fun toJson(validated: Validated): String {
        fun esc(s: String): String = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
        val pages = validated.pages.joinToString(",") { p ->
            buildString {
                append("{\"title\":\"").append(esc(p.title)).append("\",\"body\":\"")
                append(esc(p.body)).append("\",\"imagePrompt\":\"").append(esc(p.imagePrompt)).append("\"}")
            }
        }
        return "{\"title\":\"${esc(validated.title)}\",\"pages\":[$pages]}"
    }

    /** 记录 bodyJson → 分区列表(详情渲染;坏 JSON 返回空列表,不崩)。 */
    fun fromJson(bodyJson: String): List<Page> {
        if (bodyJson.isBlank()) return emptyList()
        return try {
            val root = json.parseToJsonElement(bodyJson).jsonObject
            (root["pages"] as? JsonArray ?: return emptyList())
                .mapNotNull { it as? JsonObject }
                .mapNotNull { obj ->
                    val body = clamp(obj.strMember("body"), BODY_MAX_CHARS) ?: return@mapNotNull null
                    Page(
                        title = clamp(obj.strMember("title"), TITLE_MAX_CHARS).orEmpty(),
                        body = body,
                        imagePrompt = clamp(obj.strMember("imagePrompt"), IMAGE_PROMPT_MAX_CHARS).orEmpty(),
                    )
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 记录 bodyJson → 标题(空回落调用方给的兜底)。 */
    fun titleFromJson(bodyJson: String): String = try {
        val root = json.parseToJsonElement(bodyJson).jsonObject
        clamp(root.strMember("title"), TITLE_MAX_CHARS).orEmpty()
    } catch (_: Exception) {
        ""
    }

    /** 首个平衡 `{...}` 片段(照 InnerVoiceGenerator.firstJsonObject 同款扫描)。 */
    internal fun firstJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** 超长截断;空白串归一为 null(必填字段判缺失用)。 */
    internal fun clamp(raw: String?, max: Int): String? =
        raw?.trim()?.take(max)?.takeIf { it.isNotEmpty() }

    private fun JsonObject.strMember(key: String): String? {
        val v = this[key] as? JsonPrimitive ?: return null
        if (v is JsonNull) return null
        return runCatching { v.content }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    }
}
