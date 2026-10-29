package com.neethu.gouxiang.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 手机聊天(模拟手机壳,04-phone-chat-shell.md)的扩展协议碎片:
 * 在 F2 多泡协议的四种段型(text/sticker/img/voice)之上,为「+」扩展面板
 * 补齐六种卡片消息(13b 六宫格:发送图片/音乐/帖子/位置/转账/代付)。
 *
 * 复用决策:卡片复用 [com.neethu.gouxiang.data.MessageEntity] 的既有列,
 * 不新建表、不迁移库 —— actionsJson 存 [CardPayload](类型标签 + 明细),
 * 渲染层按 kind=card 反序列化;上下文编码成行内标记 [卡片:转账 520|备注x]。
 *
 * 本地版无计费(验收范围 2):转账/代付是虚拟道具,卡片明细里不携带任何
 * 金额扣减/账户语义,只承载「给对方的仪式感」。
 */

/** 六种卡片(对齐 13b 面板六宫格的行序:发送图片/音乐/帖子/位置/转账/代付)。 */
@Serializable
enum class PhoneCardKind(val label: String) {
    /** 发送图片(占位描述,生图管线为后续功能)。 */
    IMAGE("发送图片"),

    /** 分享音乐(链接占位)。 */
    MUSIC("音乐"),

    /** 分享帖子(链接卡)。 */
    POST("帖子"),

    /** 位置共享(地名卡)。 */
    LOCATION("位置"),

    /** 转账(双向卡片;本地版纯道具)。 */
    TRANSFER("转账"),

    /** 代付(请求型卡片)。 */
    PAY_FOR("代付"),
}

/**
 * 卡片载荷(kind=card 消息的 actionsJson 内容)。
 * 字段全可空 + 默认值:模型参数缺失不炸渲染,缺什么显示占位文案。
 */
@Serializable
data class CardPayload(
    val kind: PhoneCardKind,
    /** 通用主字段:音乐/帖子=标题,位置=地名,转账/代付=金额文本,图片=描述。 */
    val title: String = "",
    /** 次要字段:转账/代付备注、帖子摘要。 */
    val note: String = "",
) {
    /** 会话行/列表预览的单行摘要(空字段降级为类型名)。 */
    fun summary(): String = when (kind) {
        PhoneCardKind.TRANSFER, PhoneCardKind.PAY_FOR ->
            if (title.isBlank()) "[${kind.label}]" else "[${kind.label}] $title"
        PhoneCardKind.IMAGE ->
            if (title.isBlank()) "[图片]" else "[图片] $title"
        else ->
            if (title.isBlank()) "[${kind.label}]" else "[${kind.label}] $title"
    }

    fun encode(): String = CardJson.encode(this)

    companion object {
        fun decode(raw: String): CardPayload? = CardJson.decodeOrNull(raw)
    }
}

/** CardPayload 编解码(失败安全,惯例同 ActionsJson)。 */
object CardJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    fun encode(payload: CardPayload): String = try {
        json.encodeToString(payload)
    } catch (_: Exception) {
        ""
    }

    fun decodeOrNull(raw: String): CardPayload? = try {
        json.decodeFromString(CardPayload.serializer(), raw)
    } catch (_: Exception) {
        null
    }
}

/**
 * 卡片载荷 → 行内协议标记(上下文回放用,模型把它读作「自己/用户发过的卡」):
 * `[卡片:转账 520|给你买奶茶]` / `[卡片:位置 中央公园]`;字段缺失省略该段。
 */
fun CardPayload.toProtocolLine(): String = buildString {
    append("[卡片:").append(kind.label)
    if (title.isNotBlank()) append(' ').append(title)
    if (note.isNotBlank()) append('|').append(note)
    append(']')
}

/**
 * 「[卡片:类型 参数|备注]」行内标记 → 载荷(用户从 + 面板提交的编码入口
 * 同用此格式;解析失败返回 null,调用方丢弃不崩)。
 * 例:`[卡片:转账 520|给你买奶茶]`、`[卡片:位置 中央公园]`、`[卡片:代付 88]`。
 */
fun parseCardLine(line: String): CardPayload? {
    val m = Regex("^\\s*\\[卡片[:：]\\s*(.+?)\\]\\s*$").find(line) ?: return null
    val body = m.groupValues[1].trim()
    if (body.isEmpty()) return null
    val head = body.substringBefore('|').trim()
    val note = body.substringAfter('|', "").trim()
    val kind = PhoneCardKind.entries.firstOrNull { head.startsWith(it.label) } ?: return null
    val title = head.removePrefix(kind.label).trim()
    return CardPayload(kind = kind, title = title, note = note)
}

// ── 语音条时长(04 §1.3 AudioVoiceContent.voiceDuration 的本地化)────────

/**
 * 语音条时长展示(猫箱形态:「2"」)——由转写文本按中文语速 ~4 字/秒估算,
 * 向上取整(不低估时长)、下限 1 秒、上限 60 秒。本地无真实音频,这是展示用
 * 确定性推导(标注:推测,猫箱侧服务端 TTS 时长不可见)。
 */
fun voiceDurationSec(text: String): Int {
    val cps = text.codePoints().count().coerceAtLeast(1)
    return ((cps + 3) / 4).toInt().coerceIn(1, 60)
}

/** 语音条标签:「4"」(对齐 14-phone-chat.xml 的 time 字段)。 */
fun voiceDurationLabel(sec: Int): String = "${sec}\""
