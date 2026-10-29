package com.neethu.gouxiang.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 多泡输出协议 · 消息段(对齐 maoxiang-feature-analysis.md §6.3)。
 *
 * 一次回复 = [MultiBubbleReply](1~4 条 [Bubble]),段类型:
 * - [Bubble.Kind.TEXT]    普通文本气泡(动作文字已剥离为 [Bubble.actions])
 * - [Bubble.Kind.STICKER] 表情包占位(渲染为贴图占位卡)
 * - [Bubble.Kind.IMAGE]   图片占位(渲染为图片占位卡,后续接生图)
 * - [Bubble.Kind.VOICE]   语音条占位((语音:…) / [语音:…] 均收,→ 语音条 UI)
 */
@Serializable
data class Bubble(
    val kind: Kind,
    /** 文本内容:TEXT=正文;STICKER/IMAGE=占位描述;VOICE=语音条转写文本。 */
    val text: String,
    /** TEXT 段内嵌的 () 场景动作(顺序保留,渲染时以淡色斜体/小字展示)。 */
    val actions: List<String> = emptyList(),
) {
    @Serializable
    enum class Kind { TEXT, STICKER, IMAGE, VOICE }
}

/** 一次 LLM 回复解析出的完整分段结果。 */
@Serializable
data class MultiBubbleReply(
    /** 1~4 条泡(空数组=无有效内容,调用方兜底)。 */
    val bubbles: List<Bubble>,
    /** 与当前话题无关的日常分享(协议允许每次回复附 0~1 条,独立一泡渲染)。 */
    val proactivity: Bubble? = null,
    /** 原始文本(记录/调试用)。 */
    val raw: String,
)

/**
 * 结构化协议解析器。
 *
 * LLM 约定输出(两条通路,解析器都对齐 §6.3):
 *
 * A. JSON 数组(首选,严格结构):
 * ```json
 * [
 *   {"kind":"text","text":"老公,","actions":["靠在你肩头"]},
 *   {"kind":"sticker","text":"歪头撒娇"},
 *   {"kind":"text","text":"那房子真的好喜欢,什么时候能搬进去呀?"}
 * ]
 * ```
 * 顶层也可为对象 `{"bubbles":[…],"proactivity":{…}}`。
 *
 * B. 分隔符兜底(模型没按 JSON 时):换行或 `|||` 分段 + 行内约定
 * `[sticker:描述]` `[img:描述]` `(动作)`。
 *
 * 解析永不抛异常:畸形 JSON → 回落 B 通路;解析结果超 4 泡 → 截断保留前 4
 * (§6.3 「禁长段落」的硬约束);kind 不识别 → 当 TEXT。
 */
object MultiBubbleParser {

    /** 一泡正文的最大长度提示(超长的 TEXT 不截断内容,只影响上游 prompt 约束)。 */
    const val MAX_BUBBLES = 4

    // ── 行内占位约定正则(JSON 通路与分隔符兜底共用;整行匹配)─────────

    /** 动作行/文本行的 () 动作提取(全/半角括号)。 */
    internal val actionRegex = Regex("[（(]([^（）()]{1,40})[)）]")

    /** [sticker:描述] / [sticker：描述]。 */
    internal val stickerLineRegex = Regex("^\\[sticker[:：]\\s*(.+?)\\]$", RegexOption.DOT_MATCHES_ALL)

    /** [img:描述] / [img：描述]。 */
    internal val imgLineRegex = Regex("^\\[img[:：]\\s*(.+?)\\]$", RegexOption.DOT_MATCHES_ALL)

    /**
     * 语音条整行(格式归一化,F4 验收 medium 修复):提示词教的是 (语音:内容),
     * 但模型会自发换用方括号变体 [语音:内容]/[语音：内容](全/半角冒号同样都收)。
     * 四种写法统一识别为 VOICE 泡,渲染层只有一种「•)N″」语音条。
     */
    internal val voiceLineRegex = Regex(
        "^[(\\[（\\[]\\s*语音[:：]\\s*(.+?)[)\\]）\\]]$",
        RegexOption.DOT_MATCHES_ALL,
    )

    // lenient JSON 通路把「[x:内容]」读成单元素数组时,方括号被消费成定界符,
    // 裸字符串元素只剩「x:内容」——这组正则按同样约定还原占位泡(整行匹配)。
    internal val stickerBareRegex = Regex("^sticker[:：]\\s*(.+)$", RegexOption.DOT_MATCHES_ALL)
    internal val imgBareRegex = Regex("^img[:：]\\s*(.+)$", RegexOption.DOT_MATCHES_ALL)
    internal val voiceBareRegex = Regex("^语音[:：]\\s*(.+)$", RegexOption.DOT_MATCHES_ALL)

    fun parse(raw: String): MultiBubbleReply {
        val trimmed = raw.trim().trimPrefix("```json").trimPrefix("```").trimSuffix("```").trim()
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            tryParseJson(trimmed, raw)?.let { return it }
        }
        return parseFallback(raw)
    }

    // ── A. JSON 通路 ────────────────────────────────────────────────

    private fun tryParseJson(trimmed: String, raw: String): MultiBubbleReply? = try {
        val root = Json { ignoreUnknownKeys = true; isLenient = true }
            .parseToJsonElement(trimmed)
        val bubbles = mutableListOf<Bubble>()
        var proactivity: Bubble? = null
        when {
            root is kotlinx.serialization.json.JsonArray ->
                root.forEach { bubbles += bubbleFrom(it) }
            root is kotlinx.serialization.json.JsonObject -> {
                (root["bubbles"] as? kotlinx.serialization.json.JsonArray)
                    ?.forEach { bubbles += bubbleFrom(it) }
                proactivity = (root["proactivity"] as? kotlinx.serialization.json.JsonObject)
                    ?.let { bubbleFrom(it) }
                // 对象无 bubbles 键但内容像单泡 {kind,text}
                if (bubbles.isEmpty() && root.containsKey("text")) bubbles += bubbleFrom(root)
            }
        }
        val cleaned = bubbles.filter { it.text.isNotBlank() || it.actions.isNotEmpty() || it.kind != Bubble.Kind.TEXT }
            .take(MAX_BUBBLES)
        if (cleaned.isEmpty() && proactivity == null) null
        else MultiBubbleReply(cleaned, proactivity, raw)
    } catch (_: Exception) {
        null
    }

    private fun bubbleFrom(el: kotlinx.serialization.json.JsonElement): Bubble {
        // lenient(isLenient=true)解析会把「[语音：…]」这类单行行内约定读成
        // 只含一个裸字符串元素的 JsonArray(F4 实锤:方括号语音条因此进了 JSON
        // 通路、退化为 TEXT 泡)——且此时方括号已被消费成数组定界符,裸串只剩
        // 「语音:…」前缀;裸字符串元素按行内约定还原为占位泡。
        if (el !is kotlinx.serialization.json.JsonObject) {
            val text = (el as? kotlinx.serialization.json.JsonPrimitive)?.content ?: el.toString()
            // 括号仍在的形态((语音:…) / [语音:…] / [sticker:…] / [img:…])
            voiceLineRegex.find(text)?.let {
                return Bubble(Bubble.Kind.VOICE, it.groupValues[1].trim())
            }
            stickerLineRegex.find(text)?.let {
                return Bubble(Bubble.Kind.STICKER, it.groupValues[1].trim())
            }
            imgLineRegex.find(text)?.let {
                return Bubble(Bubble.Kind.IMAGE, it.groupValues[1].trim())
            }
            // 括号已被 JSON 数组定界符消费的形态:裸串直接以 语音/sticker/img 前缀开头
            voiceBareRegex.find(text)?.let {
                return Bubble(Bubble.Kind.VOICE, it.groupValues[1].trim())
            }
            stickerBareRegex.find(text)?.let {
                return Bubble(Bubble.Kind.STICKER, it.groupValues[1].trim())
            }
            imgBareRegex.find(text)?.let {
                return Bubble(Bubble.Kind.IMAGE, it.groupValues[1].trim())
            }
            return Bubble(Bubble.Kind.TEXT, text)
        }
        val obj = el
        val kind = when (obj["kind"]?.toString()?.trim('"')?.lowercase()) {
            "sticker" -> Bubble.Kind.STICKER
            "img", "image" -> Bubble.Kind.IMAGE
            "voice" -> Bubble.Kind.VOICE
            else -> Bubble.Kind.TEXT
        }
        val text = (obj["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        val actions = (obj["actions"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            .orEmpty()
        return Bubble(kind, text, actions)
    }

    // ── B. 分隔符兜底 ───────────────────────────────────────────────

    /**
     * 按换行或 `|||` 切段;行内解析 [sticker:…] / [img:…] / (语音:…) / (动作) 约定。
     * `(动作)` 支持全角(())与半角(())括号;动作行(整行只有动作)不产泡,
     * 归并进下一文本泡的 actions。
     */
    internal fun parseFallback(raw: String): MultiBubbleReply {
        val segments = raw.split("\n", "|||").map { it.trim() }.filter { it.isNotEmpty() }
        val bubbles = mutableListOf<Bubble>()
        var pendingActions = mutableListOf<String>()
        var proactivity: Bubble? = null

        for (seg in segments) {
            // 行内占位约定优先判定([sticker:]/[img:]/(语音:)在动作剥离前识别,
            // 否则 (语音:…) 会被 (动作) 规则误吞)
            val stickerMatch = stickerLineRegex.find(seg)
            val imgMatch = imgLineRegex.find(seg)
            val voiceMatch = voiceLineRegex.find(seg)
            if (stickerMatch != null || imgMatch != null || voiceMatch != null) {
                val kind: Bubble.Kind
                val text: String
                when {
                    stickerMatch != null -> { kind = Bubble.Kind.STICKER; text = stickerMatch.groupValues[1].trim() }
                    imgMatch != null -> { kind = Bubble.Kind.IMAGE; text = imgMatch.groupValues[1].trim() }
                    else -> { kind = Bubble.Kind.VOICE; text = voiceMatch!!.groupValues[1].trim() }
                }
                val carried = pendingActions.toList()
                pendingActions = mutableListOf()
                bubbles += Bubble(kind, text, carried)
                continue
            }
            var work = seg
            val actions = mutableListOf<String>()
            // 提取全部 (动作)
            for (m in actionRegex.findAll(work)) actions += m.groupValues[1].trim()
            work = actionRegex.replace(work, "").trim()
            // 动作行(剥完动作后空)→ 归并进下一泡
            if (work.isEmpty()) {
                pendingActions += actions
                continue
            }
            val allActions = (pendingActions + actions).distinct()
            pendingActions = mutableListOf()
            bubbles += Bubble(Bubble.Kind.TEXT, work, allActions)
        }
        // 收尾:残留动作行(文本结束后还有动作)挂到最后一泡
        if (pendingActions.isNotEmpty() && bubbles.isNotEmpty()) {
            val last = bubbles.removeAt(bubbles.size - 1)
            bubbles.add(last.copy(actions = (last.actions + pendingActions).distinct()))
        }
        val capped = bubbles.take(MAX_BUBBLES)
        // 兜底无内容时整体当一条 TEXT(避免上游拿到空回复)
        if (capped.isEmpty()) {
            val flat = raw.trim()
            if (flat.isEmpty()) return MultiBubbleReply(emptyList(), null, raw)
            return MultiBubbleReply(listOf(Bubble(Bubble.Kind.TEXT, flat)), null, raw)
        }
        return MultiBubbleReply(capped, proactivity, raw)
    }

    private fun String.trimPrefix(p: String) = if (startsWith(p)) removePrefix(p) else this
    private fun String.trimSuffix(p: String) = if (endsWith(p)) removeSuffix(p) else this
}
