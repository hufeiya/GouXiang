package com.neethu.gouxiang.derivatives

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 手帐/小说输出的 schema 校验与清洗(G1/G2;公共约束:LLM 输出必须按
 * schema 校验后才落库,解析失败不污染正式记录;解析扫描与清洗口径与
 * [com.neethu.gouxiang.sidestory.SidestoryOutput] 同款)。
 *
 * 手帐契约(单行 JSON):
 * ```
 * {"title":"篇名","mood":"心情","subtitle":"短副标题",
 *  "paragraphs":[{"body":"正文段","highlight":"高亮短句","circle":"圈画短句"}],"imagePrompt":"配图描述"}
 * ```
 * - paragraphs 3~8 段(每段是一句到几句的手帐体正文);title/mood/imagePrompt 可空,
 *   空时由调用方回落;标记必须是对应 body 的子串,否则丢弃。body 为空的段丢弃,
 *   剩余 <3 段判失败(返回 null,不落库)。旧记录缺少新字段时回落为空。
 *
 * 小说契约(单行 JSON):
 * ```
 * {"title":"书名","introduction":"简介","chapters":[{"title","overview","content"}],"imagePrompt":"插图描述"}
 * ```
 * - chapters 条数必须落在长度档 [NovelLength.chapters] 边界内(越界判失败,
 *   不静默裁剪——「长度控制」是用户明确选择,宁可失败重试);
 * - content 为空的章丢弃;title/overview 可空。
 *
 * 全链路不抛异常:任何非法输入返回 null(可解释失败,调用方重试一次后判失败)。
 */
object ChatDerivativeOutput {

    const val HANDBOOK_TITLE_MAX = 24
    const val HANDBOOK_MOOD_MAX = 12
    const val HANDBOOK_SUBTITLE_MAX = 50
    const val HANDBOOK_MARK_MAX = 30
    const val HANDBOOK_PARAGRAPH_MIN = 3
    const val HANDBOOK_PARAGRAPH_MAX = 8
    const val HANDBOOK_BODY_MAX = 300
    const val IMAGE_PROMPT_MAX = 80

    const val NOVEL_TITLE_MAX = 24
    const val NOVEL_INTRODUCTION_MAX = 120
    const val NOVEL_CHAPTER_TITLE_MAX = 20
    const val NOVEL_CHAPTER_OVERVIEW_MAX = 60
    const val NOVEL_CHAPTER_CONTENT_MAX = 300

    /** 手帐一段(已清洗)。 */
    data class Paragraph(val body: String, val highlight: String = "", val circle: String = "")

    /** 手帐校验通过的完整产物。 */
    data class Handbook(
        val title: String,
        val mood: String,
        val paragraphs: List<Paragraph>,
        val imagePrompt: String,
        val subtitle: String = "",
    )

    /** 小说一章(已清洗)。 */
    data class Chapter(
        val title: String,
        val overview: String,
        val content: String,
    )

    /** 小说校验通过的完整产物。 */
    data class Novel(
        val title: String,
        val introduction: String,
        val chapters: List<Chapter>,
        val imagePrompt: String,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── 手帐 ─────────────────────────────────────────────────────────

    /** 解析手帐输出;null = 无法解析 / 段数越界。 */
    fun parseHandbook(raw: String): Handbook? {
        val root = rootObject(raw) ?: return null
        val paragraphs = (root["paragraphs"] as? JsonArray ?: return null)
            .asSequence()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { obj -> handbookParagraph(obj) }
            .toList()
        if (paragraphs.size < HANDBOOK_PARAGRAPH_MIN) return null
        return Handbook(
            title = clamp(root.strMember("title"), HANDBOOK_TITLE_MAX).orEmpty(),
            mood = clamp(root.strMember("mood"), HANDBOOK_MOOD_MAX).orEmpty(),
            paragraphs = paragraphs.take(HANDBOOK_PARAGRAPH_MAX),
            imagePrompt = clamp(root.strMember("imagePrompt"), IMAGE_PROMPT_MAX).orEmpty(),
            subtitle = clamp(root.strMember("subtitle"), HANDBOOK_SUBTITLE_MAX).orEmpty(),
        )
    }

    // ── 小说 ─────────────────────────────────────────────────────────

    /** 解析小说输出;null = 无法解析 / 章数越出长度档边界。 */
    fun parseNovel(raw: String, length: NovelLength): Novel? {
        val root = rootObject(raw) ?: return null
        val chapters = (root["chapters"] as? JsonArray ?: return null)
            .asSequence()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { obj ->
                val content = clamp(obj.strMember("content"), NOVEL_CHAPTER_CONTENT_MAX)
                    ?: return@mapNotNull null
                Chapter(
                    title = clamp(obj.strMember("title"), NOVEL_CHAPTER_TITLE_MAX).orEmpty(),
                    overview = clamp(obj.strMember("overview"), NOVEL_CHAPTER_OVERVIEW_MAX).orEmpty(),
                    content = content,
                )
            }
            .toList()
        if (chapters.size !in length.chapters) return null
        return Novel(
            title = clamp(root.strMember("title"), NOVEL_TITLE_MAX).orEmpty(),
            introduction = clamp(root.strMember("introduction"), NOVEL_INTRODUCTION_MAX).orEmpty(),
            chapters = chapters,
            imagePrompt = clamp(root.strMember("imagePrompt"), IMAGE_PROMPT_MAX).orEmpty(),
        )
    }

    // ── 序列化(记录 bodyJson;紧凑单行,详情层 fromJson 渲染)────────

    fun handbookToJson(h: Handbook): String = buildString {
        append("{\"title\":\"").append(esc(h.title))
        append("\",\"mood\":\"").append(esc(h.mood))
        append("\",\"subtitle\":\"").append(esc(h.subtitle))
        append("\",\"paragraphs\":[")
        append(h.paragraphs.joinToString(",") {
            "{\"body\":\"${esc(it.body)}\",\"highlight\":\"${esc(it.highlight)}\",\"circle\":\"${esc(it.circle)}\"}"
        })
        append("],\"imagePrompt\":\"").append(esc(h.imagePrompt)).append("\"}")
    }

    fun handbookFromJson(bodyJson: String): Handbook? = try {
        val root = json.parseToJsonElement(bodyJson).jsonObject
        val paragraphs = (root["paragraphs"] as? JsonArray ?: return null)
            .mapNotNull { it as? JsonObject }
            .mapNotNull { obj -> handbookParagraph(obj) }
        if (paragraphs.isEmpty()) null else Handbook(
            title = clamp(root.strMember("title"), HANDBOOK_TITLE_MAX).orEmpty(),
            mood = clamp(root.strMember("mood"), HANDBOOK_MOOD_MAX).orEmpty(),
            paragraphs = paragraphs,
            imagePrompt = clamp(root.strMember("imagePrompt"), IMAGE_PROMPT_MAX).orEmpty(),
            subtitle = clamp(root.strMember("subtitle"), HANDBOOK_SUBTITLE_MAX).orEmpty(),
        )
    } catch (_: Exception) {
        null
    }

    fun novelToJson(n: Novel): String = buildString {
        append("{\"title\":\"").append(esc(n.title))
        append("\",\"introduction\":\"").append(esc(n.introduction))
        append("\",\"chapters\":[")
        append(
            n.chapters.joinToString(",") { c ->
                "{\"title\":\"${esc(c.title)}\",\"overview\":\"${esc(c.overview)}\",\"content\":\"${esc(c.content)}\"}"
            },
        )
        append("],\"imagePrompt\":\"").append(esc(n.imagePrompt)).append("\"}")
    }

    fun novelFromJson(bodyJson: String): Novel? = try {
        val root = json.parseToJsonElement(bodyJson).jsonObject
        val chapters = (root["chapters"] as? JsonArray ?: return null)
            .mapNotNull { it as? JsonObject }
            .mapNotNull { obj ->
                val content = clamp(obj.strMember("content"), NOVEL_CHAPTER_CONTENT_MAX)
                    ?: return@mapNotNull null
                Chapter(
                    title = clamp(obj.strMember("title"), NOVEL_CHAPTER_TITLE_MAX).orEmpty(),
                    overview = clamp(obj.strMember("overview"), NOVEL_CHAPTER_OVERVIEW_MAX).orEmpty(),
                    content = content,
                )
            }
        if (chapters.isEmpty()) null else Novel(
            title = clamp(root.strMember("title"), NOVEL_TITLE_MAX).orEmpty(),
            introduction = clamp(root.strMember("introduction"), NOVEL_INTRODUCTION_MAX).orEmpty(),
            chapters = chapters,
            imagePrompt = clamp(root.strMember("imagePrompt"), IMAGE_PROMPT_MAX).orEmpty(),
        )
    } catch (_: Exception) {
        null
    }

    /** 合照记录 bodyJson(用户描述 + 最终 prompt;无 LLM 文本)。 */
    fun photoToJson(description: String, finalPrompt: String): String =
        "{\"description\":\"${esc(description)}\",\"prompt\":\"${esc(finalPrompt)}\"}"

    /** 合照 bodyJson → 描述(坏 JSON 回落空串,详情层不崩)。 */
    fun photoDescriptionFromJson(bodyJson: String): String = try {
        val root = json.parseToJsonElement(bodyJson).jsonObject
        clamp(root.strMember("description"), DerivativeOptions.PHOTO_DESCRIPTION_MAX_CHARS).orEmpty()
    } catch (_: Exception) {
        ""
    }

    // ── 内部工具(与 SidestoryOutput 同款)────────────────────────────

    private fun rootObject(raw: String): JsonObject? {
        val fragment = com.neethu.gouxiang.sidestory.SidestoryOutput.firstJsonObject(raw) ?: return null
        return try {
            json.parseToJsonElement(fragment).jsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun esc(s: String): String = JsonPrimitive(s).toString().let { it.substring(1, it.length - 1) }

    /** 超长截断;空白串归一为 null(必填字段判缺失用)。 */
    internal fun clamp(raw: String?, max: Int): String? =
        raw?.trim()?.take(max)?.takeIf { it.isNotEmpty() }

    private fun handbookParagraph(obj: JsonObject): Paragraph? {
        val body = clamp(obj.strMember("body"), HANDBOOK_BODY_MAX) ?: return null
        fun mark(key: String): String = clamp(obj.strMember(key), HANDBOOK_MARK_MAX)
            ?.takeIf { body.contains(it) }.orEmpty()
        return Paragraph(body, mark("highlight"), mark("circle"))
    }

    private fun JsonObject.strMember(key: String): String? {
        val v = this[key] as? JsonPrimitive ?: return null
        if (v is JsonNull) return null
        return runCatching { v.content }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    }
}
