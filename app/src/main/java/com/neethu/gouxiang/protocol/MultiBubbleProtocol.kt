package com.neethu.gouxiang.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 多泡输出协议 · 消息段(对齐 maoxiang-feature-analysis.md §6.3)。
 *
 * 一次回复 = [MultiBubbleReply](1~4 条 [Bubble]),段类型:
 * - [Bubble.Kind.TEXT]  普通文本气泡(白名单命中的动作文字剥离为 [Bubble.actions],
 *                       非白名单括号文本保留正文;动作/心理等非语言内容=括号文本
 *                       直接夹在消息里,猫箱口径 2026-10-31)
 * - [Bubble.Kind.IMAGE] 图片占位(渲染为图片占位卡,后续接生图)
 * - [Bubble.Kind.VOICE] 语音条占位((语音:…) / [语音:…] 均收,→ 语音条 UI)
 * - [Bubble.Kind.CARD]  卡片整行([卡片:类型 参数|备注] 六型,content 保留
 *                       原行,落库侧解析为 [CardPayload] 存 actionsJson)
 *
 * 表情包不再独立成泡(2026-10-31 用户口径:输出协议去掉 sticker 标签,非语言
 * 内容直接括号;存量 [sticker:…] 标记由 [MultiBubbleParser.normalizeStickerMarkers]
 * 降级为 `(描述)` 括号文本,与正文同泡上屏)。
 */
@Serializable
data class Bubble(
    val kind: Kind,
    /** 文本内容:TEXT=正文;IMAGE=占位描述;VOICE=语音条转写;CARD=整行卡片标记。 */
    val text: String,
    /** TEXT 段内嵌的 () 场景动作(顺序保留,渲染时以淡色斜体/小字展示)。 */
    val actions: List<String> = emptyList(),
) {
    @Serializable
    enum class Kind { TEXT, IMAGE, VOICE, CARD }
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
 *   {"kind":"text","text":"(歪头撒娇)那房子真的好喜欢,什么时候能搬进去呀?"}
 * ]
 * ```
 * 顶层也可为对象 `{"bubbles":[…],"proactivity":{…}}`。
 *
 * B. 分隔符兜底(模型没按 JSON 时):换行或 `|||` 分段 + 行内约定
 * `[img:描述]` `[卡片:类型 参数|备注]` `(动作)`。
 *
 * 解析永不抛异常:畸形 JSON → 回落 B 通路;解析结果超 4 泡 → 截断保留前 4
 * (§6.3 「禁长段落」的硬约束);kind 不识别 → 当 TEXT。
 */
object MultiBubbleParser {

    /** 一泡正文的最大长度提示(超长的 TEXT 不截断内容,只影响上游 prompt 约束)。 */
    const val MAX_BUBBLES = 4

    // ── 行内占位约定正则(JSON 通路与分隔符兜底共用;整行匹配)─────────

    /**
     * 动作行/文本行的 () 内容匹配(全/半角括号)。是否剥离为 Action 由
     * [actionWhitelist] 白名单判定(02 文档 §3.3 规则4),正则本身只负责圈出括号。
     */
    internal val actionRegex = Regex("[（(]([^（）()]{1,40})[)）]")

    /**
     * () 动作白名单(02 文档 §3.3 规则4:白名单动作转 Action 段,非白名单保留
     * 为文本——照 SDK StageDirectionFilter.kt:27 起的有限精确清单语义,避免
     * 误伤普通括号内容)。词源两批:
     * 1. 文档通用短动作(SDK 清单同款 + 叹气/抬头/歪头);
     * 2. 本工程 prompt few-shot 与兜底文案动作(保既有单测与教学样例不回归)。
     */
    internal val actionWhitelist: Set<String> = setOf(
        // 文档通用短动作(歪头:实机高频动作词,few-shot 贴纸「歪头撒娇」同源词)
        "点头", "摇头", "微笑", "眨眼", "挥手", "叹气", "低头", "抬头", "鞠躬",
        "害羞地低头", "轻轻点头", "歪头",
        // 本工程兜底文案动作(ChatOrchestrator.FALLBACK_TEXT / PROACTIVE_FALLBACK_TEXT)
        "戳了戳你", "抱着手机等了好一会儿",
        // 本工程输出协议 few-shot(ChatContextBuilder)
        "凑过来瞄你的屏幕",
        // 解析器示例 / SeedData 开场白 / 既有单测锁定的动作词
        "靠在你肩头", "凑近你耳边", "低头轻笑", "轻轻拍你的背", "瘫在你身上",
    )

    /**
     * 02 文档 §3.3 规则5「空段/纯标点段丢弃」:段内含字母/数字/汉字才算有效
     * 内容,纯标点/纯符号/空白段不成泡。
     */
    internal fun hasValidContent(seg: String): Boolean =
        seg.any { it.isLetterOrDigit() || it.code in 0x4E00..0x9FFF }

    /**
     * 残留表情包标记的行内降级(2026-10-31 口径:协议已无表情泡,但历史 few-shot
     * /旧习惯仍可能让模型吐出 [sticker:描述]):整串替换为 `(描述)` 括号文本,
     * 与正文同段同泡——流式草稿里的原文和上屏后的括号文本一致,不再裂出独立
     * 表情泡。全/半角冒号都收;标记内不允许再嵌方括号(防跨标记误吞)。
     */
    internal val stickerInlineRegex = Regex("\\[\\s*sticker[:：]\\s*([^\\[\\]]+?)\\s*\\]")

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
    // sticker 已无独立泡:裸串形态同样降级为括号文本(bubbleFrom 内处理)。
    internal val stickerBareRegex = Regex("^sticker[:：]\\s*(.+)$", RegexOption.DOT_MATCHES_ALL)
    internal val imgBareRegex = Regex("^img[:：]\\s*(.+)$", RegexOption.DOT_MATCHES_ALL)
    internal val voiceBareRegex = Regex("^语音[:：]\\s*(.+)$", RegexOption.DOT_MATCHES_ALL)

    /**
     * 卡片整行 `[卡片:类型 参数|备注]`(与 [parseCardLine] 同构,全/半角冒号都收)。
     * 与 sticker/img/voice 不同:CARD 泡的 text 保留整行原样(落库侧再由
     * [parseCardLine] 解出载荷存 actionsJson),不做剥离。
     */
    internal val cardLineRegex = Regex("^\\s*\\[卡片[:：]\\s*(.+?)\\]\\s*$")

    /** 方括号被 lenient JSON 数组定界符消费后的裸串形态(「卡片:…」开头)。 */
    internal val cardBareRegex = Regex("^卡片[:：]\\s*(.+)$", RegexOption.DOT_MATCHES_ALL)

    /**
     * 行尾缀占位(模型不换行把 [img:…]/[卡片:…] 直接缀在正文后,真机实锤
     * 2026-10-31):组1=占位前的正文(非空,至少一个非空白字符),组2=行尾
     * 占位标记本身。整行占位不匹配(组1 要求前面有正文),仍走整行判定。
     * sticker 不再参与拆分——它的行尾缀形态在 [parse] 入口就被
     * [stickerInlineRegex] 原位降级成括号文本,不裂泡。
     */
    internal val trailingPlaceholderRegex =
        Regex("^(.*\\S)\\s*(\\[(?:img|卡片)[:：]\\s*[^\\[\\]]+?\\])\\s*$")

    /**
     * 行展开预处理:行尾缀占位的行拆成「正文行 + 独立占位行」两段,交给主
     * 循环按既有整行判定各自成泡;无行尾占位的行原样返回单段。
     */
    internal fun expandTrailingPlaceholder(line: String): List<String> {
        val m = trailingPlaceholderRegex.find(line) ?: return listOf(line)
        return listOf(m.groupValues[1].trim(), m.groupValues[2].trim())
    }

    fun parse(raw: String): MultiBubbleReply {
        val trimmed = raw.trim().trimPrefix("```json").trimPrefix("```").trimSuffix("```").trim()
        // 表情包标记先行降级(JSON 通路与兜底通路共用同一入口,标记不会再
        // 以 [sticker:…] 形态进入任何解析分支)
        val normalized = normalizeStickerMarkers(trimmed)
        if (normalized.startsWith("[") || normalized.startsWith("{")) {
            tryParseJson(normalized, raw)?.let { return it }
        }
        return parseFallback(normalized)
    }

    /** [sticker:…] / [sticker：…] → `(描述)`,全文原位降级(见 [stickerInlineRegex])。 */
    internal fun normalizeStickerMarkers(text: String): String =
        stickerInlineRegex.replace(text) { "(${it.groupValues[1].trim()})" }

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
            // 括号仍在的形态((语音:…) / [语音:…] / [img:…]);sticker 方括号形态
            // 已在 parse 入口降级为括号文本,不会以 [sticker:…] 到达这里
            voiceLineRegex.find(text)?.let {
                return Bubble(Bubble.Kind.VOICE, it.groupValues[1].trim())
            }
            imgLineRegex.find(text)?.let {
                return Bubble(Bubble.Kind.IMAGE, it.groupValues[1].trim())
            }
            // 括号已被 JSON 数组定界符消费的形态:裸串直接以 语音/sticker/img 前缀开头
            // (sticker 裸串同样降级为括号文本,不再出独立表情泡)
            voiceBareRegex.find(text)?.let {
                return Bubble(Bubble.Kind.VOICE, it.groupValues[1].trim())
            }
            stickerBareRegex.find(text)?.let {
                return Bubble(Bubble.Kind.TEXT, "(${it.groupValues[1].trim()})")
            }
            imgBareRegex.find(text)?.let {
                return Bubble(Bubble.Kind.IMAGE, it.groupValues[1].trim())
            }
            // 卡片(端到端打通):方括号仍在的形态保留整行原样;裸串形态(方括号
            // 已被 JSON 数组定界符消费)补回方括号,与整行形态统一,保证落库侧
            // parseCardLine 一定能从 text 解出载荷。
            cardLineRegex.find(text)?.let {
                return Bubble(Bubble.Kind.CARD, text.trim())
            }
            cardBareRegex.find(text)?.let {
                return Bubble(Bubble.Kind.CARD, "[卡片:${it.groupValues[1].trim()}]")
            }
            return Bubble(Bubble.Kind.TEXT, text)
        }
        val obj = el
        val stickerKind = obj["kind"]?.toString()?.trim('"')?.lowercase() == "sticker"
        val kind = when (obj["kind"]?.toString()?.trim('"')?.lowercase()) {
            "img", "image" -> Bubble.Kind.IMAGE
            "voice" -> Bubble.Kind.VOICE
            "card" -> Bubble.Kind.CARD
            else -> Bubble.Kind.TEXT
        }
        var text = (obj["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        // 存量 sticker kind 降级:JSON 里写 {"kind":"sticker","text":"歪头撒娇"}
        // 的回复按括号文本收进正文,与流式草稿观感一致(不再裂出表情占位卡)
        if (stickerKind) text = "(${text.trim()})"
        val actions = (obj["actions"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            .orEmpty()
        return Bubble(kind, text, actions)
    }

    // ── B. 分隔符兜底 ───────────────────────────────────────────────

    /**
     * 按换行或 `|||` 切段;行内解析 [img:…] / (语音:…) / (动作) 约定(sticker
     * 标记已在 [parse] 入口降级为括号文本,不进本通路)。
     * `(动作)` 支持全角(())与半角(())括号,且按 02 文档 §3.3 规则4 只剥白名单
     * ([actionWhitelist])命中词为 Action,非白名单括号文本保留正文;动作行
     * (整行只有动作)不产泡,归并进下一文本泡的 actions。
     */
    internal fun parseFallback(raw: String): MultiBubbleReply {
        val segments = raw.split("\n", "|||").map { it.trim() }.filter { it.isNotEmpty() }
            .flatMap { expandTrailingPlaceholder(it) }
        val bubbles = mutableListOf<Bubble>()
        var pendingActions = mutableListOf<String>()
        var proactivity: Bubble? = null

        for (seg in segments) {
            // 行内占位约定优先判定([img:]/[卡片:]/(语音:)在动作剥离前识别,
            // 否则 (语音:…) 会被 (动作) 规则误吞、[卡片:位置 原地(东门)]
            // 这类带括号载荷会被剥掉)
            val imgMatch = imgLineRegex.find(seg)
            val voiceMatch = voiceLineRegex.find(seg)
            val cardMatch = cardLineRegex.find(seg)
            if (imgMatch != null || voiceMatch != null) {
                val kind: Bubble.Kind
                val text: String
                when {
                    imgMatch != null -> { kind = Bubble.Kind.IMAGE; text = imgMatch.groupValues[1].trim() }
                    else -> { kind = Bubble.Kind.VOICE; text = voiceMatch!!.groupValues[1].trim() }
                }
                val carried = pendingActions.toList()
                pendingActions = mutableListOf()
                bubbles += Bubble(kind, text, carried)
                continue
            }
            // 卡片整行:text 保留原行(与上方占位泡同层,先于动作剥离),载荷在
            // 落库侧由 parseCardLine 解出
            if (cardMatch != null) {
                val carried = pendingActions.toList()
                pendingActions = mutableListOf()
                bubbles += Bubble(Bubble.Kind.CARD, seg, carried)
                continue
            }
            var work = seg
            val actions = mutableListOf<String>()
            // 提取白名单命中的 (动作);非白名单括号文本保留正文不再剥离
            // (02 文档 §3.3 规则4,照 StageDirectionFilter 有限清单语义防误伤)
            for (m in actionRegex.findAll(work)) {
                val name = m.groupValues[1].trim()
                if (name in actionWhitelist) actions += name
            }
            work = actionRegex.replace(work) {
                if (it.groupValues[1].trim() in actionWhitelist) "" else it.value
            }.trim()
            // 动作行(剥完动作后空)→ 归并进下一泡
            if (work.isEmpty()) {
                pendingActions += actions
                continue
            }
            // 纯标点段丢弃(02 文档 §3.3 规则5):剥完动作后无字母/数字/汉字的
            // 残段不成泡;占位行/动作行已先于本判定识别不受影响,段内已剥出的
            // 动作照动作行语义归并进下一泡(不随段丢弃)。
            if (!hasValidContent(work)) {
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
