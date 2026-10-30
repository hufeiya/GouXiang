package com.neethu.gouxiang.sidestory

import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.CharacterStatusEntity
import com.neethu.gouxiang.data.MemoryTurn

/**
 * 番外/心动生成 prompt(F 工作包 F2/F4;分层与 [com.neethu.gouxiang.chat.InnerVoiceGenerator]
 * 同构:人设/记忆/输出协议三段,心声不是聊天消息,这里同理无连发协议):
 *
 * - system = 人设 + 记忆块 + 模板选题基调 + 输出协议(schema 边界随模板注入);
 * - user = 最近对话回合回放(+ 查Ta状态时注入 E 工作包的角色状态快照,
 *   09 号专项「与 08 号文档边界」:深度查看带独白,常驻状态是一行快照,
 *   本地版两者共享 E 的状态数据但不互相改写);
 * - 红线:prompt 严禁出现「猫粮/扣费/计费/会员」(单测扫描全量输出)。
 */
object SidestoryPrompts {

    /** 每格正文上限(与 [SidestoryOutput.BODY_MAX_CHARS] 一致,prompt 里明示)。 */
    private const val BODY_MAX = SidestoryOutput.BODY_MAX_CHARS
    private const val TITLE_MAX = SidestoryOutput.TITLE_MAX_CHARS

    /** 组 system prompt。 */
    fun systemPrompt(
        character: CharacterEntity,
        entry: SidestoryCatalog.Entry,
        memoriesBlock: String?,
    ): String {
        val s = StringBuilder()
        s.appendLine("你是「${character.name}」的演绎引擎,为「用户」创作一篇图文小剧场。")
        s.appendLine("[人设] ${character.profile}")
        memoriesBlock?.takeIf { it.isNotBlank() }?.let { s.appendLine(it) }
        s.appendLine("[选题与基调] ${entry.name} —— ${entry.subtitle}。${entry.brief}")
        s.appendLine("[输出协议] 只输出一行 JSON,不要输出任何其他内容:")
        when (entry.pageStyle) {
            SidestoryCatalog.PageStyle.SECTIONS -> {
                val sample = entry.sections.joinToString(",") { sec ->
                    "{${jstr("title", sec)},${jstr("body", "该区内容")},${jstr("imagePrompt", "")}}"
                }
                s.appendLine(
                    "{\"title\":\"不超过${TITLE_MAX}字的总标题\",\"pages\":[$sample]}",
                )
            }
            SidestoryCatalog.PageStyle.THEATER -> s.appendLine(
                "{\"title\":\"不超过${TITLE_MAX}字的总标题\",\"pages\":[" +
                    "{\"title\":\"该格小标题(可为空)\",\"body\":\"该格图文案\",\"imagePrompt\":\"该格画面描述,简体中文,60字内\"}]}",
            )
        }
        s.appendLine("规则:")
        if (entry.pageStyle == SidestoryCatalog.PageStyle.SECTIONS) {
            s.appendLine("1. pages 必须恰好 ${entry.sections.size} 格,顺序与分区标题一致:${entry.sections.joinToString("、")}(title 原样使用给定标题)。")
        } else {
            s.appendLine("1. pages 输出 ${entry.pageMin}~${entry.pageMax} 格,叙事有起有收。")
        }
        s.appendLine("2. 每格 body 不超过 ${BODY_MAX}字;文案延续人设语气与既定关系,不跳出「${character.name}」与用户的关系视角。")
        s.appendLine("3. (场景动作用()描述,继承开场白规范);只依据人设、记忆和最近对话里真实出现的内容,不编造无依据的事。")
        s.appendLine("4. 不出现「JSON」「字段」「输出」这类字眼;不出现任何额度、次数、道具相关的表述。")
        return s.toString().trim()
    }

    /** JSON 字符串成员片段(`"key":"value"`,转义安全)。 */
    private fun jstr(key: String, value: String): String =
        "\"$key\":\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"") + "\""

    /** 组 user prompt:最近对话回合 + 可选的 E 工作包角色状态快照。 */
    fun userPrompt(
        character: CharacterEntity,
        entry: SidestoryCatalog.Entry,
        recentTurns: List<MemoryTurn>,
        status: CharacterStatusEntity?,
    ): String {
        val s = StringBuilder()
        if (entry.usesCharacterStatus) {
            s.appendLine("[TA当前状态]")
            s.appendLine(
                if (status == null) "(暂无状态记录,按最近对话与记忆推断TA此刻的状态)"
                else "心情=${status.mood};正在做=${status.doing};内心想法=${status.thought}(记录时间:${status.generatedAt})",
            )
        }
        if (recentTurns.isEmpty()) {
            s.appendLine("(还没有聊天记录,按人设与记忆创作)")
        } else {
            s.appendLine("[最近对话]")
            s.appendLine(
                recentTurns.joinToString("\n\n") { turn ->
                    buildString {
                        if (turn.user.isNotBlank()) append("用户: ${turn.user}\n")
                        if (turn.assistant.isNotBlank()) append("${character.name}: ${turn.assistant}")
                    }.trim()
                },
            )
        }
        return s.toString().trim()
    }
}
