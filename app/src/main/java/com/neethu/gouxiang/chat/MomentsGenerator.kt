package com.neethu.gouxiang.chat

import com.neethu.gouxiang.ai.adapter.ChatMessage
import com.neethu.gouxiang.ai.adapter.ChatRole
import com.neethu.gouxiang.ai.adapter.LlmConfig
import com.neethu.gouxiang.ai.adapter.LlmStreamEvent
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.MomentCommentEntity
import com.neethu.gouxiang.data.MomentEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 朋友圈生成(F9「Ta 的朋友圈」,对齐 10-moments.md §1.3/§3.3 口径:
 * 「每天发布图文朋友圈,分享自己的日常和与你相处的点点滴滴」的本地化
 * 按需版——首次进入无动态或点「生成动态」时生成一条):
 *
 * - 素材 = 角色人设 + 画像/关系/事件记忆(「与你相处的点点滴滴」)+
 *   最近对话回合(共用 [ChatOrchestrator.HISTORY_LIMIT] 编码回放);
 * - 输出契约 = 单行 JSON {"text","imagePrompt"},宽松解析(移植
 *   InnerVoiceGenerator 的截首个平衡 JSON + lenient 链);
 * - imagePrompt 供生图(火山 seedream 优先,失败保持纯文字卡);
 * - 评论回复契约 = 同形 JSON,素材带上动态原文 + 已有评论线程 + 被回复的
 *   用户评论(10 §1.4 reply_to_name 楼中楼语义,本地版一层);
 * - 全链路失败安全:LLM 未配置/流失败/解析失败 → 返回 null,UI 提示
 *   「生成失败」不崩溃(与 F2/F8 兜底约定一致)。
 */
object MomentsGenerator {

    /** 动态草稿(解析中间态)。 */
    data class PostDraft(val text: String, val imagePrompt: String)

    const val TEXT_MAX_CHARS = 120

    /** 生图喂料上限(配图描述过长时截断,防 prompt 膨胀;与 F8 同惯例)。 */
    const val IMAGE_PROMPT_MAX_CHARS = 60

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 组发帖 system prompt(分层与 [ChatContextBuilder]/[InnerVoiceGenerator]
     * 同构:人设/记忆/输出协议三段;10-moments.md §3.3:第一人称短句、禁长
     * 段落、场景动作 () 描述、禁提 AI/模型/服务——朋友圈是异步图文,
     * IM 分泡条款裁剪,一条动态 = 一段文本)。
     */
    fun postSystemPrompt(
        character: CharacterEntity,
        memories: List<com.neethu.gouxiang.data.MemoryEntity>,
    ): String {
        val s = StringBuilder()
        s.appendLine("你是「${character.name}」,正在发一条自己的朋友圈(只有好友能看到的动态)。")
        s.appendLine("[人设] ${character.profile}")
        MemoryInjection.block(character.name, memories, "")?.let { s.appendLine(it) }
        s.appendLine("[输出协议] 只输出一行 JSON,不要输出任何其他内容:")
        s.appendLine("{\"text\":\"朋友圈正文\",\"imagePrompt\":\"配图画面描述\"}")
        s.appendLine("规则:")
        s.appendLine("1. text 用第一人称「我」,是一段 $TEXT_MAX_CHARS 字以内的日常随想;" +
            "禁止长段落、禁止总结腔,像随手发的说说;场景动作用()写在句子里。")
        s.appendLine("2. 内容来自你的日常和与用户相处的点滴(可自然借用上面记忆里的细节)," +
            "可以让用户看了想回你一句;语气贴合人设。")
        s.appendLine("3. imagePrompt 用第三人称描述一条配图画面(30字以内)," +
            "是你此刻身边能拍到或画出来的场景;为空字符串表示不发图。")
        s.appendLine("4. 只输出 JSON 本身:不写分析、旁白,不出现「JSON」「字段」「输出」这类字眼," +
            "绝不提自己是 AI、模型或任何程序。")
        return s.toString().trim()
    }

    /** 组发帖 user prompt:最近对话回合(与记忆提炼同款「用户:/角色名:」回放)。 */
    fun postUserPrompt(
        character: CharacterEntity,
        recentTurns: List<com.neethu.gouxiang.data.MemoryTurn>,
    ): String {
        if (recentTurns.isEmpty()) return "(还没有聊天记录,按人设与记忆发一条日常动态)"
        return "[最近对话]\n" + recentTurns.joinToString("\n\n") { turn ->
            buildString {
                if (turn.user.isNotBlank()) append("用户: ${turn.user}\n")
                if (turn.assistant.isNotBlank()) append("${character.name}: ${turn.assistant}")
            }.trim()
        }
    }

    /**
     * 组回复评论 system prompt(10 §1.4:角色回复自己朋友圈下的用户评论;
     * 三开关里 allow_ai_reply_user 的语义,本地版恒开)。
     */
    fun replySystemPrompt(
        character: CharacterEntity,
        memories: List<com.neethu.gouxiang.data.MemoryEntity>,
    ): String {
        val s = StringBuilder()
        s.appendLine("你是「${character.name}」,用户在你的朋友圈动态下留了言,你回复这条评论。")
        s.appendLine("[人设] ${character.profile}")
        MemoryInjection.block(character.name, memories, "")?.let { s.appendLine(it) }
        s.appendLine("[输出协议] 只输出一行 JSON,不要输出任何其他内容:")
        s.appendLine("{\"text\":\"回复内容\"}")
        s.appendLine("规则:")
        s.appendLine("1. text 是你说给用户的一句话,$TEXT_MAX_CHARS 字以内;第一人称,语气贴合人设," +
            "像在评论区随手回的话,不用客套开头。")
        s.appendLine("2. 只输出 JSON 本身:不写分析、旁白,不出现「JSON」「字段」「输出」这类字眼," +
            "绝不提自己是 AI、模型或任何程序。")
        return s.toString().trim()
    }

    /** 组回复评论 user prompt:动态原文 + 已有评论线程 + 目标评论。 */
    fun replyUserPrompt(
        character: CharacterEntity,
        post: MomentEntity,
        thread: List<MomentCommentEntity>,
        target: MomentCommentEntity,
    ): String {
        val s = StringBuilder()
        s.appendLine("[你的动态] ${post.text}")
        if (thread.isNotEmpty()) {
            s.appendLine("[评论区]")
            thread.forEach { c ->
                val who = if (c.authorKind == "user") "用户" else character.name
                s.appendLine("$who: ${c.text}")
            }
        }
        s.appendLine("[要回复的评论] 用户: ${target.text}")
        return s.toString()
    }

    /**
     * 组角色互评 system prompt(H3,10-moments.md §1.4「Ta们会互相评论」的
     * 本地化):评论者按可见角色人设 + 相关动态生成一条评论。
     * 复读抑制是硬约束(防互评退化成互捧/复读):评论区已有内容时必须换角度。
     */
    fun interactSystemPrompt(
        commenter: CharacterEntity,
        memories: List<com.neethu.gouxiang.data.MemoryEntity>,
    ): String {
        val s = StringBuilder()
        s.appendLine("你是「${commenter.name}」,在朋友圈看到一条动态,写一条自己的评论。")
        s.appendLine("[人设] ${commenter.profile}")
        MemoryInjection.block(commenter.name, memories, "")?.let { s.appendLine(it) }
        s.appendLine("[输出协议] 只输出一行 JSON,不要输出任何其他内容:")
        s.appendLine("{\"text\":\"评论内容\"}")
        s.appendLine("规则:")
        s.appendLine("1. text 是你说给动态作者看的一句话,$TEXT_MAX_CHARS 字以内;第一人称," +
            "语气贴合你的人设,像在评论区随手留的一句话,不用客套开头。")
        s.appendLine("2. 评论区已有内容时,换一个角度说,不要附和或重复别人说过的话," +
            "也不要逐条点评别人的评论。")
        s.appendLine("3. 只输出 JSON 本身:不写分析、旁白,不出现「JSON」「字段」「输出」这类字眼," +
            "绝不提自己是 AI、模型或任何程序。")
        return s.toString().trim()
    }

    /** 组互评 user prompt:动态原文(含作者) + 已有评论线程(评论者名)。 */
    fun interactUserPrompt(
        commenter: CharacterEntity,
        postAuthorName: String,
        post: MomentEntity,
        thread: List<MomentCommentEntity>,
        namesById: Map<Long, String>,
    ): String {
        val s = StringBuilder()
        s.appendLine("[${postAuthorName}的动态] ${post.text}")
        if (thread.isNotEmpty()) {
            s.appendLine("[评论区]")
            thread.forEach { c ->
                val who = commentAuthorName(c, commenter.name, namesById)
                s.appendLine("$who: ${c.text}")
            }
        }
        s.appendLine("[要发的评论](以「${commenter.name}」的身份):")
        return s.toString()
    }

    /** 评论作者展示名(user=用户;avatar=按 id 查名,查不到回退评论者名)。 */
    fun commentAuthorName(
        comment: com.neethu.gouxiang.data.MomentCommentEntity,
        fallbackAvatarName: String,
        namesById: Map<Long, String>,
    ): String = when (comment.authorKind) {
        "user" -> "用户"
        else -> namesById[comment.authorId] ?: fallbackAvatarName
    }

    /**
     * 宽松解析(动态/回复共用):截首个平衡 `{...}` → 取 text/imagePrompt
     * → 清洗(空白丢弃、截断)。@return null = 无法解析或正文为空。
     */
    fun parsePost(raw: String): PostDraft? {
        val obj = jsonObject(raw) ?: return null
        val text = clamp(obj.strMember("text"), TEXT_MAX_CHARS)
        if (text.isEmpty()) return null
        val imagePrompt = clamp(obj.strMember("imagePrompt"), IMAGE_PROMPT_MAX_CHARS)
        return PostDraft(text, imagePrompt)
    }

    /** 回复解析:text 为空返回 null(调用方提示失败)。 */
    fun parseReply(raw: String): String? {
        val obj = jsonObject(raw) ?: return null
        return clamp(obj.strMember("text"), TEXT_MAX_CHARS).takeIf { it.isNotEmpty() }
    }

    /** 解析草稿 → 实体盖章(at 由调用方传入;配图后置异步回填)。 */
    fun toEntity(draft: PostDraft, characterId: Long, at: Long): MomentEntity =
        MomentEntity(
            characterId = characterId,
            text = draft.text,
            imagePrompt = draft.imagePrompt,
            at = at,
        )

    /** 首个平衡 `{...}` 片段 → JsonObject(复用心声先例;null = 无 JSON)。 */
    internal fun jsonObject(raw: String): JsonObject? {
        val fragment = InnerVoiceGenerator.firstJsonObject(InnerVoiceGenerator.escapeRawNewlines(raw)) ?: return null
        return try {
            json.parseToJsonElement(fragment).jsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun clamp(raw: String?, max: Int): String =
        raw?.trim()?.take(max).orEmpty()

    private fun JsonObject.strMember(key: String): String? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { it !is JsonNull }
            ?.let { runCatching { it.content }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
}

/**
 * 朋友圈生成的请求通道(InnerVoiceChannel 同款执行语义:单发非流式文本
 * 输出、流事件合并、失败返回 null 永不抛;独立小类便于单测注入假 LLM)。
 */
class MomentsChannel(
    private val llm: ChatOrchestrator.Llm,
    private val llmConfig: () -> LlmConfig,
) {

    /** 朋友圈要活:temperature 覆盖主配置(MemoryExtractor.extractTemp 同款修正)。 */
    private val postTemp = 0.9f

    /** 评论回复要稳一点:temperature 折中。 */
    private val replyTemp = 0.8f

    // Some reasoning models count their thinking against max_tokens. Leave room
    // for a complete JSON response instead of truncating at the memory default.
    private val maxTokensOverride = 2048

    /** @return 模型原始输出;请求失败/流错误/空输出返回 null(调用方提示失败)。 */
    suspend fun requestPost(system: String, user: String, strict: Boolean = false): String? =
        request(system, user, postTemp, strict)

    suspend fun requestReply(system: String, user: String): String? =
        request(system, user, replyTemp)

    private suspend fun request(system: String, user: String, temp: Float, strict: Boolean = false): String? {
        val sb = StringBuilder()
        var failure: Throwable? = null
        try {
            llm.streamChat(
                listOf(
                    ChatMessage(ChatRole.SYSTEM, system),
                    ChatMessage(ChatRole.USER, user),
                ),
                llmConfig().copy(temperature = temp, maxTokens = maxTokensOverride),
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
