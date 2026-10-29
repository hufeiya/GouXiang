package com.neethu.gouxiang.chat

import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.MemoryEntity
import com.neethu.gouxiang.data.MemoryTurn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * LLM 输出的宽松解析(移植自 AIAvatar-SDK memory/MemoryParser.kt:36 的宽松链,
 * 收紧为本项目四分类):截首个平衡 `{...}` → lenient JSON → 逐条清洗
 * (未知 category 归 event、importance clamp、content ≤120 字、ops ≤5 条)。
 *
 * @return 合法 ops(空列表 = 模型判定无可记,是**成功**结论);null = 输出
 *   无法解析为 ops JSON —— 调用方保留窗口下轮重试(永不抛)。
 */
object MemoryOpsParser {

    const val MAX_OPS = 5
    const val CONTENT_MAX_CHARS = 120

    /** 单条变更操作。 */
    data class Op(
        val action: String,
        val id: String? = null,
        val content: String = "",
        val category: String = MemoryCategories.EVENT,
        val importance: Int = 5,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): List<Op>? {
        val fragment = firstJsonObject(raw) ?: return null
        val root = try {
            json.parseToJsonElement(fragment).jsonObject
        } catch (_: Exception) {
            return null
        }
        val ops = root["ops"] as? JsonArray ?: return null
        return ops.asSequence()
            .mapNotNull { it as? JsonObject }
            .mapNotNull(::parseOp)
            .take(MAX_OPS)
            .toList()
    }

    private fun parseOp(obj: JsonObject): Op? {
        val action = obj.str("action")?.lowercase() ?: return null
        return when (action) {
            "add" -> {
                val content = clampContent(obj.str("content"))
                if (content.isEmpty()) return null
                Op(
                    action = "add",
                    content = content,
                    category = MemoryCategories.normalize(obj.str("category")),
                    importance = clampImportance(obj.str("importance")),
                )
            }
            "delete" -> {
                val id = obj.str("id")?.trim().orEmpty()
                if (id.isEmpty()) return null
                Op(action = "delete", id = id)
            }
            else -> null // 未知 action(update 不开放)整条丢弃
        }
    }

    internal fun clampContent(raw: String?): String =
        raw?.trim()?.take(CONTENT_MAX_CHARS).orEmpty()

    private fun clampImportance(raw: String?): Int =
        (raw?.toIntOrNull() ?: 5).coerceIn(1, 10)

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { it !is JsonNull }
            ?.let { runCatching { it.content }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

    /** 截首个平衡 `{...}`(模型偶尔在 JSON 前后带客套话或 markdown 围栏)。 */
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
}

/** 记忆的封闭分类(本项目收敛为三类;未知值一律归 event)。 */
object MemoryCategories {
    const val PROFILE = "profile"
    const val RELATIONSHIP = "relationship"
    const val EVENT = "event"

    /** 画像细类:我的称呼 / 我的身份(03 §3.2b,对齐 BotMemoryType.UserName/UserProfile)。 */
    const val SUBTYPE_NAME = "name"
    const val SUBTYPE_IDENTITY = "identity"

    val ALL = listOf(PROFILE, RELATIONSHIP, EVENT)

    fun normalize(raw: String?): String =
        raw?.trim()?.lowercase()?.let { s -> ALL.firstOrNull { it == s } } ?: EVENT
}

/**
 * 记忆行存取缝(对齐 ChatOrchestrator.DaoAccess 的抽象惯例:Repo 实现,
 * 单测注入内存 fake)。MemoryBook 的全部读写经它走。
 */
interface MemorySource {
    /** 某角色的全量记忆(分带在 [MemoryCapacity] 现场算,不落库)。 */
    suspend fun all(characterId: Long): List<MemoryEntity>

    suspend fun update(memory: MemoryEntity)

    suspend fun insert(memory: MemoryEntity): Long

    suspend fun deleteById(id: Long)
}

/**
 * 记忆库的进程内门面:按 characterId 分片的读写 + 去重 + 容量状态。
 * 数据仍是 memories 表行,UI 与抽取通道都经它走。
 */
class MemoryBook(private val source: MemorySource) {

    /** 角色名解析(注入块与总结 prompt 的兜底;查不到返回空串)。 */
    var characterNameResolver: suspend (Long) -> String = { "" }

    suspend fun characterName(characterId: Long): String = characterNameResolver(characterId)

    // ── 读 ─────────────────────────────────────────────────────────

    suspend fun all(characterId: Long): List<MemoryEntity> = source.all(characterId)

    suspend fun profile(characterId: Long): Profile {
        val rows = source.all(characterId)
            .filter { it.category == MemoryCategories.PROFILE }
        return Profile(
            name = rows.firstOrNull { it.subtype == MemoryCategories.SUBTYPE_NAME }?.content,
            identity = rows.firstOrNull { it.subtype == MemoryCategories.SUBTYPE_IDENTITY }?.content,
        )
    }

    suspend fun events(characterId: Long): List<MemoryEntity> =
        source.all(characterId)
            .filter { it.category == MemoryCategories.EVENT }
            .sortedByDescending { it.at }

    /** 容量状态(03 §3.2d 口径:used=条数,faded=max(0, used-capacity))。 */
    suspend fun capacity(characterId: Long): MemoryCapacity.CapacityState =
        MemoryCapacity.state(source.all(characterId))

    // ── 写:手动与自动共用(手写行也带 subtype 以便画像原地覆盖)─────

    /** 手动加一条记忆(管理入口);去重与自动通道共用 [upsertDeduped]。 */
    suspend fun addManual(characterId: Long, category: String, content: String, importance: Int = 8): MemoryEntity {
        val now = System.currentTimeMillis()
        return upsertDeduped(
            MemoryEntity(
                characterId = characterId,
                category = category,
                subtype = if (category == MemoryCategories.PROFILE) guessSubtype(content) else "",
                content = content.take(MemoryOpsParser.CONTENT_MAX_CHARS),
                importance = importance.coerceIn(1, 10),
                at = now,
            ),
        )
    }

    /** 编辑画像(空串 = 遗忘该条,03 §1.9 遗忘语义)。 */
    suspend fun writeProfile(characterId: Long, subtype: String, value: String) {
        val existing = source.all(characterId).firstOrNull {
            it.category == MemoryCategories.PROFILE && it.subtype == subtype
        }
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            existing?.let { source.deleteById(it.id) }
            return
        }
        if (existing != null) {
            source.update(
                existing.copy(
                    content = trimmed.take(MemoryOpsParser.CONTENT_MAX_CHARS),
                    at = System.currentTimeMillis(),
                ),
            )
        } else {
            source.insert(
                MemoryEntity(
                    characterId = characterId,
                    category = MemoryCategories.PROFILE,
                    subtype = subtype,
                    content = trimmed.take(MemoryOpsParser.CONTENT_MAX_CHARS),
                    importance = 8,
                    at = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** 遗忘一条(确认弹窗三连后调用)。 */
    suspend fun forget(id: Long) = source.deleteById(id)

    /** 全清(角色主页「清空对话与记忆」语义预留)。 */
    suspend fun clearAll(characterId: Long) {
        source.all(characterId).forEach { source.deleteById(it.id) }
    }

    /**
     * 去重落库:category+content 逐字相同 → 原地覆盖(保 id/at);画像行
     * subtype 命中 → 原地覆盖。其余新增。返回落库条。
     */
    internal suspend fun upsertDeduped(mem: MemoryEntity): MemoryEntity {
        val rows = source.all(mem.characterId)
        val dup = when {
            mem.category == MemoryCategories.PROFILE && mem.subtype.isNotEmpty() ->
                rows.firstOrNull { it.category == MemoryCategories.PROFILE && it.subtype == mem.subtype }
            else -> rows.firstOrNull {
                it.category == mem.category && it.content == mem.content
            }
        }
        return if (dup != null) {
            val merged = dup.copy(
                content = mem.content,
                importance = mem.importance,
                subtype = mem.subtype.ifEmpty { dup.subtype },
            )
            source.update(merged)
            merged
        } else {
            val id = source.insert(mem)
            mem.copy(id = id)
        }
    }

    /** 手写画像行的 subtype 猜测(「我的称呼:」前缀惯例,兼容种子文案)。 */
    private fun guessSubtype(content: String): String = when {
        content.startsWith("我的称呼") -> MemoryCategories.SUBTYPE_NAME
        content.startsWith("我的身份") -> MemoryCategories.SUBTYPE_IDENTITY
        else -> ""
    }

    /** 画像两件套快照。 */
    data class Profile(val name: String?, val identity: String?) {
        val isEmpty: Boolean get() = name.isNullOrBlank() && identity.isNullOrBlank()
    }
}

/**
 * LLM 抽取通道:两种任务(事件总结 / 画像判定)共用一套「独立非流式请求 →
 * 单行 JSON → 宽松解析 → 落库」管线(移植自 AIAvatar-SDK memory/MemoryExtractor.kt:51
 * 的执行语义,合二为一,任务由 [summarize]/[extractProfile] 区分)。
 */
class MemoryExtractor(
    private val book: MemoryBook,
    private val llm: ChatOrchestrator.Llm,
    private val llmConfig: () -> com.neethu.gouxiang.ai.adapter.LlmConfig,
) {

    /** 抽取要稳:temperature 覆盖主配置(AIAvatar-SDK MemoryExtractor.kt:40 同款修正)。 */
    private val extractTemp = 0.2f

    /**
     * 事件总结(「经历过的事」):最近对话窗口 → 1~3 条 category=event。
     * 解析失败/请求失败返回 false(窗口保留下次重试)。
     */
    suspend fun summarize(turns: List<MemoryTurn>, character: CharacterEntity): Boolean {
        if (turns.isEmpty()) return true
        val dialog = turns.joinToString("\n\n") { "用户: ${it.user}\n${character.name}: ${it.assistant}" }
        val manifest = existingEvents(character.id)
        val raw = request(system = EventPrompts.system(character.name), user = EventPrompts.user(manifest, dialog))
            ?: return false
        val ops = MemoryOpsParser.parse(raw) ?: return false
        apply(character.id, ops)
        return true
    }

    /**
     * 画像判定(我的称呼/我的身份):绝不安造(03 §5.5),null 表示维持现状
     * (即成功但无事可写)。解析失败返回 false。
     */
    suspend fun extractProfile(turns: List<MemoryTurn>, character: CharacterEntity): Boolean {
        if (turns.isEmpty()) return true
        val current = book.profile(character.id)
        val dialog = turns.joinToString("\n\n") { "用户: ${it.user}\n${character.name}: ${it.assistant}" }
        val raw = request(system = ProfilePrompts.system(current), user = ProfilePrompts.user(dialog)) ?: return false
        val root = MemoryOpsParser.firstJsonObject(raw)?.let { fragment ->
            try {
                Json.parseToJsonElement(fragment).jsonObject
            } catch (_: Exception) {
                null
            }
        } ?: return false
        val name = root.strMember("name")
        val identity = root.strMember("identity")
        if (name == null && identity == null) return true
        name?.let { book.writeProfile(character.id, MemoryCategories.SUBTYPE_NAME, it) }
        identity?.let { book.writeProfile(character.id, MemoryCategories.SUBTYPE_IDENTITY, it) }
        return true
    }

    /** 共用请求体:单发非流式文本输出(刻意不走工具通道,与提炼通道同坑位规避)。 */
    private suspend fun request(system: String, user: String): String? {
        val sb = StringBuilder()
        var failed = false
        try {
            llm.streamChat(
                listOf(
                    com.neethu.gouxiang.ai.adapter.ChatMessage(com.neethu.gouxiang.ai.adapter.ChatRole.SYSTEM, system),
                    com.neethu.gouxiang.ai.adapter.ChatMessage(com.neethu.gouxiang.ai.adapter.ChatRole.USER, user),
                ),
                llmConfig().copy(temperature = extractTemp),
            ).collect { event ->
                when (event) {
                    is com.neethu.gouxiang.ai.adapter.LlmStreamEvent.TextDelta -> sb.append(event.text)
                    is com.neethu.gouxiang.ai.adapter.LlmStreamEvent.Error -> failed = true
                    else -> Unit
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            return null
        }
        if (failed) return null
        return sb.toString().takeIf { it.isNotBlank() }
    }

    private suspend fun existingEvents(characterId: Long): String {
        val rows = book.events(characterId).take(10)
        if (rows.isEmpty()) return "(还没有事件记忆)"
        return rows.joinToString("\n") { "- ${it.content}" }
    }

    /** add 一律客户端盖章 category=event / importance=5(模型值不信任)。 */
    private suspend fun apply(characterId: Long, ops: List<MemoryOpsParser.Op>) {
        val now = System.currentTimeMillis()
        ops.filter { it.action == "delete" }.forEach { op ->
            op.id?.toLongOrNull()?.let { book.forget(it) }
        }
        ops.filter { it.action == "add" }.forEach { op ->
            book.upsertDeduped(
                MemoryEntity(
                    characterId = characterId,
                    category = MemoryCategories.EVENT,
                    content = op.content,
                    importance = 5,
                    at = now,
                ),
            )
        }
    }
}

/** 事件总结的文案(zh 单语;本地版 UI 全中文,03 §2.1 直接复用组)。 */
object EventPrompts {
    fun system(charName: String): String =
        "你在维护「$charName」(一个虚拟人角色)关于用户的长期记忆库,任务是总结『经历过的事』。" +
            "阅读下面的对话片段(你与用户最近的一段经历),把其中值得长期记住的『共同经历』总结为 1~3 条事件," +
            "只输出一行 JSON,不要输出任何其他内容:\n" +
            "{\"ops\":[{\"action\":\"add\",\"content\":\"第三人称事件:时间+地点+谁+发生了什么+结果/感受\"}]}\n" +
            "规则:\n" +
            "- 只写已经发生或明确约定的事,不要编造;大多数片段只有 1 条值得写,输出空 ops {\"ops\":[]} 是正常结论。\n" +
            "- 每条是一个完整事件而非原子事实:允许带时间/地点/因果与感受,但一条不超过 120 字。\n" +
            "- category 一律不填(客户端统一盖章);不要输出 update/delete(总结只增不改)。\n" +
            "- 对话里琐碎重复的寒暄不算事件;一段对话重复写同一件事只写一条;" +
            "与现有事件记忆重复的经历不要重复输出。"

    fun user(existingEvents: String, dialog: String): String =
        "[现有事件记忆]\n$existingEvents\n\n[待总结对话]\n$dialog"
}

/** 画像判定的文案(绝不安造红线,对齐 RelationshipTracker.judgeInstruction 风格)。 */
object ProfilePrompts {
    fun system(current: MemoryBook.Profile): String {
        val curName = current.name ?: "(空)"
        val curIdentity = current.identity ?: "(空)"
        return "你在维护一个虚拟人角色对用户的印象(用户画像)。现有画像:我的称呼=「$curName」," +
            "我的身份=「$curIdentity」。阅读下面的对话片段,判断是否要更新画像,只输出一行 JSON:\n" +
            "{\"name\": <字符串或 null>, \"identity\": <字符串或 null>}\n" +
            "规则:\n" +
            "- name = 用户希望被称呼什么(用户明确说『叫我X/我是你Y』时更新);" +
            "identity = 用户是什么样的人(职业/身份/与你角色的关系),用一句话概括。\n" +
            "- 画像未在对话中明确确立时对应字段输出 null,绝不安造;两个都 null 时输出 {\"name\":null,\"identity\":null}。" +
            "- 与现有画像一致时也不要重复输出(输出 null)。"
    }

    fun user(dialog: String): String = "[对话片段]\n$dialog"
}

private fun JsonObject.strMember(key: String): String? {
    val v = this[key] as? JsonPrimitive ?: return null
    if (v is JsonNull) return null
    val s = runCatching { v.content }.getOrNull() ?: return null
    return s.trim().takeIf { it.isNotEmpty() && it != "null" }
}
