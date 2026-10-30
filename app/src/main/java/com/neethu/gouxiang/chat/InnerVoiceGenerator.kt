package com.neethu.gouxiang.chat

import com.neethu.gouxiang.ai.adapter.ChatMessage
import com.neethu.gouxiang.ai.adapter.ChatRole
import com.neethu.gouxiang.ai.adapter.LlmStreamEvent
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.InnerVoiceEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 心声生成(F8「Ta 的心声」,对齐 07-chat-derivatives.md §3.3 心声 prompt 口径:
 * 「角色人设 + 近期事件记忆 top3 + 心情 → 心声卡」;03-memory-upgrade.md §1.10:
 * 心声 = 记忆系统的衍生图文卡,card_echo 为第一人称内心短句):
 *
 * - 素材 = 角色人设 + 画像/关系记忆 + 事件记忆 TopN + 最近对话回合(共用
 *   [ChatOrchestrator.HISTORY_LIMIT] 编码回放,与聊天上下文同源);
 * - 输出契约 = 单行 JSON {"voices":[{title, echo}×2~4]},宽松解析
 *   (移植 MemoryOpsParser 的截首个平衡 JSON + lenient 链,单独收敛为
 *   心声 shape,不复用 ops 语义);
 * - 客户端盖章:title ≤12 字、echo ≤40 字(基准卡为 6 字/14 字量级)、
 *   条数 2~4(超取前 4,不足 2 判失败重试),at 由调用方传入错开;
 * - 全链路失败安全:LLM 未配置/流失败/解析失败 → 返回空列表,UI 提示
 *   「生成失败」不崩溃(与 F2/F3 兜底约定一致)。
 */
object InnerVoiceGenerator {

    /** 单条心声的 LLM 输出(解析中间态)。 */
    data class Draft(val title: String, val echo: String)

    const val MIN_VOICES = 2
    const val MAX_VOICES = 4
    const val TITLE_MAX_CHARS = 12
    const val ECHO_MAX_CHARS = 40

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 组 system prompt(与 ChatContextBuilder 分层同构:人设/记忆/输出协议三段,
     * 无连发与 IM 协议——心声不是聊天消息)。
     */
    fun systemPrompt(character: CharacterEntity, memories: List<com.neethu.gouxiang.data.MemoryEntity>): String {
        val s = StringBuilder()
        s.appendLine("你是「${character.name}」,正在给「用户」写一条只有自己知道的心声——不发给对方," +
            "是悄悄记下自己对 TA 的心思。")
        s.appendLine("[人设] ${character.profile}")
        MemoryInjection.block(character.name, memories, "")?.let { s.appendLine(it) }
        s.appendLine("[输出协议] 只输出一行 JSON,不要输出任何其他内容:")
        s.appendLine("{\"voices\":[{\"title\":\"6字以内的小标题\",\"echo\":\"20字以内的第一人称内心短句\"}]}")
        s.appendLine("规则:")
        s.appendLine("1. 输出 $MIN_VOICES~$MAX_VOICES 条心声;每条 title 不超过 ${TITLE_MAX_CHARS}个字,echo 不超过 ${ECHO_MAX_CHARS}个字。")
        s.appendLine("2. 全部第一人称「我」,写给用户,语气贴合人设与两人的关系;" +
            "可以引用你们之间具体的事(借用上面记忆里的细节),也可以是此刻的小情绪。")
        s.appendLine("3. 只写心声本身:不写分析、计划、旁白,不出现「JSON」「字段」「输出」这类字眼。")
        s.appendLine("4. 几条之间话题错开(一条贴最近发生的事,一条写日常小情绪),不要重复同一个意思。")
        return s.toString().trim()
    }

    /** 组 user prompt:最近对话回合(与记忆提炼同款「用户:/角色名:」回放)。 */
    fun userPrompt(character: CharacterEntity, recentTurns: List<com.neethu.gouxiang.data.MemoryTurn>): String {
        if (recentTurns.isEmpty()) return "(还没有聊天记录,按人设与记忆写日常心声)"
        return "[最近对话]\n" + recentTurns.joinToString("\n\n") { turn ->
            buildString {
                if (turn.user.isNotBlank()) append("用户: ${turn.user}\n")
                if (turn.assistant.isNotBlank()) append("${character.name}: ${turn.assistant}")
            }.trim()
        }
    }

    /**
     * 宽松解析:截首个平衡 `{...}` → voices 数组 → 逐条清洗(空白行丢弃、
     * 截断)。@return 合法草稿(0 < size;空列表 = 无法解析或无可用心声)。
     */
    fun parse(raw: String): List<Draft> {
        val fragment = firstJsonObject(raw) ?: return emptyList()
        val root = try {
            json.parseToJsonElement(fragment).jsonObject
        } catch (_: Exception) {
            return emptyList()
        }
        val voices = root["voices"] as? JsonArray ?: return emptyList()
        return voices.asSequence()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { obj ->
                val title = clamp(obj.strMember("title"), TITLE_MAX_CHARS)
                val echo = clamp(obj.strMember("echo"), ECHO_MAX_CHARS)
                if (title.isEmpty() && echo.isEmpty()) return@mapNotNull null
                Draft(
                    title = title.ifEmpty { echo.take(TITLE_MAX_CHARS) },
                    echo = echo,
                )
            }
            .take(MAX_VOICES)
            .toList()
    }

    /** 首个平衡 `{...}` 片段(模型偶尔在 JSON 前后带客套话或 markdown 围栏)。 */
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

    private fun clamp(raw: String?, max: Int): String =
        raw?.trim()?.take(max).orEmpty()

    private fun JsonObject.strMember(key: String): String? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { it !is JsonNull }
            ?.let { runCatching { it.content }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

    /**
     * 完整生成链:解析 → 条数校验 → 盖章 [InnerVoiceEntity](at 由调用方
     * 错开传入)。失败返回空列表(调用方提示,不抛)。
     */
    fun toEntities(
        raw: String,
        characterId: Long,
        baseAt: Long,
    ): List<InnerVoiceEntity> {
        val drafts = parse(raw)
        if (drafts.size < MIN_VOICES) return emptyList()
        return drafts.mapIndexed { index, d ->
            InnerVoiceEntity(
                characterId = characterId,
                title = d.title,
                echo = d.echo,
                at = baseAt + index,
            )
        }
    }
}

/**
 * 心声生成的请求通道(MemoryExtractor.request 同款执行语义:单发非流式文本
 * 输出、流事件合并、失败返回 null 永不抛;独立小类便于单测注入假 LLM)。
 */
class InnerVoiceChannel(
    private val llm: ChatOrchestrator.Llm,
    private val llmConfig: () -> com.neethu.gouxiang.ai.adapter.LlmConfig,
) {

    /** 心声要稳:temperature 覆盖主配置(MemoryExtractor.extractTemp 同款修正)。 */
    private val extractTemp = 0.8f

    /** @return 模型原始输出;请求失败/流错误/空输出返回 null(调用方提示失败)。 */
    suspend fun request(system: String, user: String, strict: Boolean = false): String? {
        val sb = StringBuilder()
        var failure: Throwable? = null
        try {
            llm.streamChat(
                listOf(
                    ChatMessage(ChatRole.SYSTEM, system),
                    ChatMessage(ChatRole.USER, user),
                ),
                llmConfig().copy(temperature = extractTemp),
            ).collect { event ->
                when (event) {
                    is LlmStreamEvent.TextDelta -> sb.append(event.text)
                    is LlmStreamEvent.Error -> failure = event.throwable
                    else -> Unit
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (strict) throw DerivativeFailure.from(t)
            return null
        }
        if (failure != null) {
            if (strict) throw DerivativeFailure.from(failure!!)
            return null
        }
        return sb.toString().takeIf { it.isNotBlank() } ?: if (strict) throw DerivativeFailure.empty() else null
    }
}
