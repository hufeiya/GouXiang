package com.neethu.gouxiang.chat

import com.neethu.gouxiang.ai.adapter.ChatMessage
import com.neethu.gouxiang.ai.adapter.ChatRole
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.MessageEntity
import com.neethu.gouxiang.protocol.ActionsJson
import com.neethu.gouxiang.protocol.toProtocolLine

/**
 * 上下文组装(依据 01-character-card.md §1.2 四字段用途差异 + §6.3 行为规范):
 *
 * system =
 *   [人设] profile(角色设定,「用户」称呼对话者)+ 台词示例 few-shot
 * + [输出协议] 仿真人 IM:1~4 条短消息连发/禁长段落/[sticker:][img:]()动作
 * + [当前形态] 手机聊天模式 | 台词长度 ≤{maxBubbleChars}字/条 | 连发 {N} 或 {N~M} 条
 *     (参数化:对话风格弹层 F5 经 [BurstParams] 真实改写这两个口径)
 * + [对话模式] 行为段(F5:09 弹层五选一,附加对应行为段,本地版全免费)
 * + [记忆] 单点走 MemoryInjection.block:画像(称呼/身份)+ 关系行
 *     + 事件 TopN(容量外条目带「逐渐模糊」后缀,F3 渐忘语义)
 *
 * history = 最近 {historyLimit} 条消息,按原输出协议编码回放(泡间 "\n"):
 *   - assistant text 泡:(动作)还原在段前,与 seed 开场白/台词示例语感一致;
 *   - sticker/img/voice 按行内占位约定还原 —— 模型把历史当「自己说过的话」
 *     读,天然续写同样风格。
 */
object ChatContextBuilder {

    /** 事件记忆的容量上限(超出丢弃最旧的)。 */
    const val MAX_EVENT_MEMORIES = 6

    fun systemPrompt(
        character: CharacterEntity,
        memories: List<com.neethu.gouxiang.data.MemoryEntity>,
        maxBubbles: Int = 3,
        maxBubbleChars: Int = 30,
        minBubbles: Int = 0,
        userText: String = "",
        mode: DialogMode = DialogMode.DEFAULT,
        phoneStyle: Boolean = false,
    ): String {
        val s = StringBuilder()
        s.appendLine("你是「${character.name}」,与用户在手机聊天。")
        s.appendLine("[人设] ${character.profile}")
        character.exampleLines.trim().takeIf { it.isNotEmpty() }?.let {
            s.appendLine("[台词示例](() 描述动作与场景)")
            it.lines().mapNotNull(String::trim).filter(String::isNotEmpty).take(3).forEach { line ->
                s.appendLine(line)
            }
        }
        // 输出协议给足例子并收口成硬规则(实机 F2 验收:只讲一遍"拆成 1~4 条"
        // 时 mini 档模型仍输出单泡,必须few-shot + 每条消息的格式约束双管齐下)。
        // 手机聊天模式(F4)把连发区间放宽为 2~4 条、每条更短(≤[maxBubbleChars]),
        // 对齐 04-phone-chat-shell.md §3.2.C「一次回复 1~5 条短消息,每条 ≤30 字」
        // 的更碎形态;陪伴聊天保持 F2 既定的固定条数语义不变。
        // F5 对话风格弹层经 [minBubbles]/[maxBubbles]/[mode] 真实改写这三处:
        // [minBubbles] 未显式传(=0)时走 F2/F4 既定口径(IM 固定 [maxBubbles] 条、
        // 手机固定 2~4 条)——既有 prompt 逐字节兼容,实机验收不回归。
        val (lo, hi) = when {
            minBubbles > 0 -> minBubbles.coerceAtMost(maxBubbles) to maxBubbles
            phoneStyle -> 2 to 4
            else -> maxBubbles to maxBubbles
        }
        val burstRule = if (phoneStyle) {
            "1. 每次回复必须输出 $lo~$hi 条独立的短消息(至少 $lo 条,更碎、更像真人手速)," +
                "每条消息各占一行,行与行之间不写任何连接词;" +
                "每条只说一件事,一件事说完立刻换行开启下一条。"
        } else if (lo < hi) {
            "1. 每次回复必须输出 $lo~$hi 条独立的短消息,每条消息各占一行,行与行之间不写任何连接词;" +
                "每条只说一件事,一件事说完立刻换行开启下一条。"
        } else {
            "1. 每次回复必须输出 $hi 条独立的短消息,每条消息各占一行,行与行之间不写任何连接词;" +
                "每条只说一件事,一件事说完立刻换行开启下一条。"
        }
        s.appendLine("[输出协议] 仿真人 IM(像真人一样连发短消息,绝不长段落):")
        s.appendLine(burstRule)
        s.appendLine("2. 每条消息不超过 ${maxBubbleChars}个字;允许把一句话拆成两条连发(真人的打字习惯)。")
        s.appendLine("3. 可插入:表情包占位 [sticker:描述] / 图片占位 [img:描述] / 语音条 (语音:内容);" +
            "卡片消息 [卡片:转账 520|备注] / [卡片:位置 地名] / [卡片:音乐 歌名] / [卡片:帖子 标题] / [卡片:代付 金额]。" +
            // 白名单口径(02 文档 §3.3 规则4):解析器只剥白名单命中词,这里枚举
            // 白名单内示例词并要求每次回复至少带一个,否则 mini 档模型实测一次
            // () 都不产出,灰字动作(渲染层斜体小字)整条链路空转。
            "常用小动作用()放在消息开头描述,如(点头)(微笑)(眨眼)(靠在你肩头),每次回复至少带一个。")
        s.appendLine("4. 每次回复可附带 0~1 条与当前话题无关的日常分享,作为单独一行。")
        // F5 实机验收泄漏项修复:mini 档模型会把内心规划当泡发出来
        // (「用户一直在发hello4,可能是想玩重复游戏?…还要分三条短消息。」)。
        // 硬规则正面禁止 + 负例说明(负例比正则更管住该档模型)。
        s.appendLine("5. 只输出要发给用户的消息本身:不写分析/计划/旁白/自我提醒," +
            "不要提「分几条」「短消息」「用户可能怎么想」这类元话;例:输出「在忙吗?」,不输出「用户在问在吗,我要分三条回复他。」。")
        s.appendLine("示例(两轮对话的回复形态,不要复述内容):")
        s.appendLine("在忙吗")
        s.appendLine("(凑过来瞄你的屏幕)让我看看嘛")
        s.appendLine("[sticker:歪头撒娇]")
        s.appendLine(
            if (lo < hi) "[当前形态] 手机聊天模式 | 台词长度 ≤${maxBubbleChars}字/条 | 连发 $lo~$hi 条"
            else "[当前形态] 手机聊天模式 | 台词长度 ≤${maxBubbleChars}字/条 | 连发 $hi 条",
        )
        // 对话模式行为段(F5 范围 2:09 弹层选择真实生效;本地版全部免费,
        // 模式段只改行为不改门槛,且不输出任何敏感商业字样——PhoneChatTest 红线)。
        s.appendLine(mode.promptBlock)
        // 记忆注入(F3):画像/关系行 + 事件 TopN + 容量外「逐渐模糊」降权,
        // 单点走 [MemoryInjection.block](分带在 MemoryCapacity.band 现场算,不落库)。
        MemoryInjection.block(character.name, memories, userText)?.let { s.appendLine(it) }
        return s.toString().trim()
    }

    /** 数据库消息行 → 协议上下文行(泡间 "\n" 分隔,统一入口)。 */
    fun encodeBubbleLine(m: MessageEntity): String {
        val body = when (m.kind) {
            "sticker" -> "[sticker:${m.content}]"
            "img" -> "[img:${m.content}]"
            "voice" -> "(语音:${m.content})"
            // 手机聊天卡片(F4):actionsJson 存 CardPayload,回放为行内标记
            "card" -> com.neethu.gouxiang.protocol.CardPayload.decode(m.actionsJson)
                ?.toProtocolLine() ?: m.content
            else -> m.content
        }
        if (m.role == "assistant") {
            val actions = ActionsJson.decode(m.actionsJson)
            val prefix = actions.joinToString("") { "($it)" }
            return (prefix + body).trim()
        }
        return body
    }

    /** 最近对话窗口 → ChatMessage 列表(无历史返回空表)。 */
    fun history(messages: List<MessageEntity>): List<ChatMessage> {
        if (messages.isEmpty()) return emptyList()
        return messages.map { m ->
            ChatMessage(
                role = if (m.role == "user") ChatRole.USER else ChatRole.ASSISTANT,
                content = encodeBubbleLine(m),
            )
        }
    }
}
