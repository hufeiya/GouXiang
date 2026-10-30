package com.neethu.gouxiang.derivatives

import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.MemoryTurn

/**
 * 手帐/小说生成 prompt(G1/G2;分层与 [com.neethu.gouxiang.chat.InnerVoiceGenerator]/
 * [com.neethu.gouxiang.sidestory.SidestoryPrompts] 同构:人设/记忆/素材/输出协议):
 *
 * - system = 演绎引擎身份 + 人设 + 记忆块 + 体裁规范 + 输出协议(边界随长度档注入);
 * - user = 选定来源区间的对话回合回放(手帐按选中区间,小说按快照窗口);
 * - 红线:prompt 严禁出现「猫粮/扣费/计费/会员」(单测扫描全量输出)。
 */
object ChatDerivativePrompts {

    /** 手帐每段正文上限(与 [ChatDerivativeOutput.HANDBOOK_BODY_MAX] 一致)。 */
    private const val HANDBOOK_BODY_MAX = ChatDerivativeOutput.HANDBOOK_BODY_MAX

    /** 小说每章正文上限(与 [ChatDerivativeOutput.NOVEL_CHAPTER_CONTENT_MAX] 一致)。 */
    private const val NOVEL_CONTENT_MAX = ChatDerivativeOutput.NOVEL_CHAPTER_CONTENT_MAX

    /** JSON 字符串成员片段(`"key":"value"`,转义安全)。 */
    private fun jstr(key: String, value: String): String =
        "\"$key\":\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** 组手帐 system prompt。 */
    fun handbookSystemPrompt(
        character: CharacterEntity,
        memoriesBlock: String?,
    ): String {
        val s = StringBuilder()
        s.appendLine("你是「${character.name}」,以 TA 的口吻写一篇聊天手帐——把你们最近的对话记成" +
            "一篇带心情的图文手帐,写给用户看,像悄悄整理的回忆。")
        s.appendLine("[人设] ${character.profile}")
        memoriesBlock?.takeIf { it.isNotBlank() }?.let { s.appendLine(it) }
        s.appendLine("[输出协议] 只输出一行 JSON,不要输出任何其他内容:")
        s.appendLine(
            "{\"title\":\"不超过${ChatDerivativeOutput.HANDBOOK_TITLE_MAX}字的篇名\"," +
                "\"mood\":\"不超过${ChatDerivativeOutput.HANDBOOK_MOOD_MAX}字的心情\"," +
                "\"paragraphs\":[{${jstr("body", "手帐一段正文")}}]," +
                "\"imagePrompt\":\"配图画面描述,简体中文,${ChatDerivativeOutput.IMAGE_PROMPT_MAX}字内\"}",
        )
        s.appendLine("规则:")
        s.appendLine("1. paragraphs 输出 ${ChatDerivativeOutput.HANDBOOK_PARAGRAPH_MIN}~${ChatDerivativeOutput.HANDBOOK_PARAGRAPH_MAX} 段," +
            "每段不超过 ${HANDBOOK_BODY_MAX}字;先叙事再收在一点心情上。")
        s.appendLine("2. 只依据上面给到的对话与记忆取材,引用真实说过的内容,不编造没发生过的事。")
        s.appendLine("3. 第一人称「我」,称呼用户沿用你们聊天里的习惯;()内可描述动作与神态。")
        s.appendLine("4. 不出现「JSON」「字段」「输出」这类字眼;不出现任何额度、次数、道具相关的表述。")
        return s.toString().trim()
    }

    /** 组手帐 user prompt:选定来源区间的对话回合。 */
    fun sourceTurnsPrompt(character: CharacterEntity, turns: List<MemoryTurn>): String {
        if (turns.isEmpty()) return "(这段范围还没有内容,按人设与记忆写)"
        return "[选中的聊天记录]\n" + turns.joinToString("\n\n") { turn ->
            buildString {
                if (turn.user.isNotBlank()) append("用户: ${turn.user}\n")
                if (turn.assistant.isNotBlank()) append("${character.name}: ${turn.assistant}")
            }.trim()
        }
    }

    /** 组小说 system prompt(章数边界随长度档注入)。 */
    fun novelSystemPrompt(
        character: CharacterEntity,
        memoriesBlock: String?,
        length: NovelLength,
    ): String {
        val s = StringBuilder()
        s.appendLine("你是小说化引擎,把「${character.name}」与用户的最近对话改写成一部多章短篇小说," +
            "第三人称,用户以「他/她」或他们聊天里的称呼出现。")
        s.appendLine("[人设] ${character.profile}")
        memoriesBlock?.takeIf { it.isNotBlank() }?.let { s.appendLine(it) }
        s.appendLine("[输出协议] 只输出一行 JSON,不要输出任何其他内容:")
        s.appendLine(
            "{\"title\":\"不超过${ChatDerivativeOutput.NOVEL_TITLE_MAX}字的书名\"," +
                "\"introduction\":\"不超过${ChatDerivativeOutput.NOVEL_INTRODUCTION_MAX}字的内容简介\"," +
                "\"chapters\":[{${jstr("title", "章节名")},${jstr("overview", "本章概要")},${jstr("content", "本章正文")}}]," +
                "\"imagePrompt\":\"插图画面描述,简体中文,${ChatDerivativeOutput.IMAGE_PROMPT_MAX}字内\"}",
        )
        s.appendLine("规则:")
        s.appendLine("1. chapters 恰好 ${length.chapters.first}~${length.chapters.last} 章," +
            "按故事推进排序;每章 content 不超过 ${NOVEL_CONTENT_MAX}字,title 不超过" +
            " ${ChatDerivativeOutput.NOVEL_CHAPTER_TITLE_MAX}字,overview 不超过" +
            " ${ChatDerivativeOutput.NOVEL_CHAPTER_OVERVIEW_MAX}字。")
        s.appendLine("2. 章与章之间承接推进,不重复叙述;结尾一章给一个有余韵的收束,不烂尾。")
        s.appendLine("3. 只依据上面给到的对话与记忆取材,不编造与素材矛盾的事。")
        s.appendLine("4. 不出现「JSON」「字段」「输出」这类字眼;不出现任何额度、次数、道具相关的表述。")
        return s.toString().trim()
    }

    /** 组小说 user prompt:快照窗口内的对话回合。 */
    fun novelUserPrompt(character: CharacterEntity, turns: List<MemoryTurn>): String {
        if (turns.isEmpty()) return "(还没有聊天记录,按人设与记忆把故事写完整)"
        return "[对话素材]\n" + turns.joinToString("\n\n") { turn ->
            buildString {
                if (turn.user.isNotBlank()) append("用户: ${turn.user}\n")
                if (turn.assistant.isNotBlank()) append("${character.name}: ${turn.assistant}")
            }.trim()
        }
    }

    /**
     * 合照生图 prompt(G3):角色形象 + 用户描述 + 合影风格词。
     * 现有生图是文生图(无参考图、无身份保持),prompt 只描述构图与氛围,
     * 不承诺长相一致——UI 侧同步明示该边界。
     */
    fun photoPrompt(character: CharacterEntity, description: String): String =
        "${character.appearance}。与用户的合影画面:${description}。" +
            "${character.appearanceStyle},柔和电影感光影,竖版构图,两人同框合影"
}
