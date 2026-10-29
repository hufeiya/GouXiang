package com.neethu.gouxiang.chat

import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.MessageEntity
import com.neethu.gouxiang.protocol.ActionsJson

/**
 * 状态条数据(「emoji + 在做什么」,对齐 03 截图「🍔 正和老公分享美食」)。
 *
 * emoji 与文案分字段返回:任何一侧都不做字符串切片 —— 按 UTF-16 code unit
 * take/drop 会把 BMP 外 emoji(如 🍔 U+1F354)切成未配对代理对
 * (U+D83C 残留),既渲染乱码又令 uiautomator dump 的 KXmlSerializer
 * 写 attribute 时抛 IllegalArgumentException(实机 F2 验收实锤)。
 */
data class StatusLine(
    val emoji: String,
    val text: String,
) {
    fun render(): String = emoji + text
}

/**
 * 状态条规则生成(起步版:确定性关键词规则,稳定不崩、零网络依赖;
 * 后续功能可替换为 LLM 定时总结,接口不变)。
 * 上下文特征:最近用户消息关键词 → 最近助手动作 → 默认态。
 */
object StatusRule {

    fun generate(
        character: CharacterEntity,
        recent: List<MessageEntity>,
        now: Long = System.currentTimeMillis(),
    ): StatusLine {
        val lastUser = recent.lastOrNull { it.role == "user" }?.content.orEmpty()
        val lastAssistant = recent.lastOrNull { it.role == "assistant" }

        val line = when {
            lastUser.containsAny("吃", "饭", "饿", "奶茶", "外卖") -> StatusLine("🍔", "肚子饿了,想去吃饭")
            lastUser.containsAny("睡", "困", "晚安", "早") -> StatusLine("😴", "有点困了,揉着眼睛")
            lastUser.containsAny("画", "图", "写生", "作品") -> StatusLine("🎨", "在认真画画")
            lastUser.containsAny("班", "工作", "加班", "开会") -> StatusLine("💼", "在等你下班")
            lastUser.containsAny("电影", "剧", "追") -> StatusLine("📺", "在追剧,想和你一起看")
            lastUser.containsAny("想", "爱", "抱", "亲", "喜欢") -> StatusLine("🥰", "脸红着想你")
            lastUser.containsAny("出门", "玩", "逛街", "约会") -> StatusLine("🎒", "在收拾东西准备出门")
            lastAssistant?.let { it.kind == "text" } == true -> {
                val action = ActionsJson.decode(lastAssistant.actionsJson).firstOrNull()
                if (action.isNullOrBlank()) defaultFor(character)
                else StatusLine("✨", "正在${action.takeCodePoints(8)}")
            }
            else -> defaultFor(character)
        }
        return line
    }

    /** 默认态:无用户信号/无动作来源时的兜底。 */
    private fun defaultFor(character: CharacterEntity): StatusLine = StatusLine("💤", "在等你来聊天")

    /**
     * 按 Unicode 码点截断(非 UTF-16 code unit):不会把代理对劈成
     * 未配对高位代理 —— 否则渲染乱码 + uiautomator dump 必崩(F2 实锤)。
     */
    private fun String.takeCodePoints(n: Int): String {
        val sb = StringBuilder()
        codePoints().limit(n.toLong()).forEach { sb.appendCodePoint(it) }
        return sb.toString()
    }
}

private fun String.containsAny(vararg keywords: String): Boolean = keywords.any { contains(it) }
