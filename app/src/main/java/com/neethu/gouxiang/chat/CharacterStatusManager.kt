package com.neethu.gouxiang.chat

import androidx.room.withTransaction
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.CharacterStatusDao
import com.neethu.gouxiang.data.CharacterStatusEntity
import com.neethu.gouxiang.data.CharacterStatusDetailEntity
import com.neethu.gouxiang.data.GouXiangDatabase
import com.neethu.gouxiang.data.MessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 状态输出的宽松解析(对齐 [MemoryOpsParser] 的宽松链口径,E2 的 schema 校验):
 * 截首个平衡 `{...}` → lenient JSON → 逐字段清洗(mood/doing/thinking 各自
 * 长度上限、emoji 合法性、doing 必填)。
 *
 * @return 合法快照;null = 输出无法解析 / doing 缺失 —— 调用方**不落库**,
 *   最近一次有效状态自然保留(E2「失败保持最近有效状态」),永不抛。
 */
object StatusSnapshotParser {

    const val FIELD_MAX_CHARS = 40

    /** 解析产出的状态快照(已按 schema 清洗;emoji 客户端兜底盖章)。 */
    data class Snapshot(
        val emoji: String,
        val mood: String,
        val doing: String,
        val thought: String,
    )

    /** emoji 解析失败/缺省时的兜底(单字素,不含任何红线字样)。 */
    const val DEFAULT_EMOJI = "✨"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): Snapshot? {
        val fragment = MemoryOpsParser.firstJsonObject(raw) ?: return null
        val root = try {
            json.parseToJsonElement(fragment).jsonObject
        } catch (_: Exception) {
            return null
        }
        // doing 必填(状态条正文);mood/thought 可空串(面板行按需隐藏)。
        val doing = clamp(root.strMember("doing")) ?: return null
        val mood = clamp(root.strMember("mood")).orEmpty()
        val thought = clamp(root.strMember("thinking")).orEmpty()
        return Snapshot(emoji = validEmoji(root.strMember("emoji")), mood = mood, doing = doing, thought = thought)
    }

    /** 超长截断到 [FIELD_MAX_CHARS];空白串归一为 null(必填字段判缺失用)。 */
    internal fun clamp(raw: String?): String? =
        raw?.trim()?.take(FIELD_MAX_CHARS)?.takeIf { it.isNotEmpty() }

    /**
     * emoji 合法性:必须是单个 Unicode 字素近似 —— 一个码点,或 BMP 外的
     * 代理对。多字符/切半代理一律回退 [DEFAULT_EMOJI](与 StatusLine 的
     * 「绝不按 UTF-16 位切 emoji」同坑位:F2 实锤未配对代理会炸 uiautomator)。
     */
    internal fun validEmoji(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return DEFAULT_EMOJI
        val first = value.codePointAt(0)
        val charCount = Character.charCount(first)
        return if (value.codePointCount(0, value.length) == 1 && value.length == charCount) value else DEFAULT_EMOJI
    }
}

private fun JsonObject.strMember(key: String): String? {
    val v = this[key] as? JsonPrimitive ?: return null
    if (v is JsonNull) return null
    val s = runCatching { v.content }.getOrNull() ?: return null
    return s.trim().takeIf { it.isNotEmpty() && it != "null" }
}

/**
 * 角色状态生成器(E 工作包 E2 的 LLM 通道;对齐 [MemoryExtractor] 的
 * 「独立非流式请求 → 一行 JSON → schema 校验 → 短事务落库」管线):
 *
 * - **触发**:回合收尾 [onTurnCompleted](每角色冷却 [minIntervalMs] + 单飞),
 *   fire-and-forget,失败只 Log、永不影响聊天回合;
 * - **版本拦截**:领取时快照会话版本,落库事务内复验 —— 回溯把版本 +1
 *   ([com.neethu.gouxiang.chat.DurableMemoryProcessor.rewind] 语义),在途旧
 *   候选版本不等直接丢弃,防止过期任务回写(E3);
 * - **失败保持**:请求失败/解析失败/版本过期一律不写行,最近一次有效状态
 *   保留;无任何记录时 UI 走 [StatusRule] 规则兜底(E2 口径);
 * - **来源范围**:记录锚定会话/回合/消息区间(落库时现查回合尾消息 id),
 *   回溯事务按 `sourceEndMessageId >= messageId` 标记失效。
 *
 * 网络请求在事务外完成,候选生成后才进短事务(公共约束:网络不入事务)。
 */
class CharacterStatusManager(
    private val db: GouXiangDatabase,
    private val llm: ChatOrchestrator.Llm,
    private val llmConfig: () -> com.neethu.gouxiang.ai.adapter.LlmConfig,
    private val scope: CoroutineScope,
    private val autoDetail: () -> Boolean = { false },
) {

    private val dao: CharacterStatusDao get() = db.characterStatusDao()

    /** 每角色冷却间隔(回合收尾触发;两次状态生成之间的最小间隔)。 */
    @Volatile var minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS

    private val lastAttemptAt = HashMap<Long, Long>()
    private val inFlight = HashSet<Long>()

    /**
     * 回合收尾触发(ChatOrchestrator.statusHook 调用):冷却 + 单飞守卫后
     * fire-and-forget 后台生成。真实生成成功的回合才调用(兜底/降级回合
     * 不应污染状态记录)。
     */
    fun onTurnCompleted(
        character: CharacterEntity,
        conversationId: Long,
        turnId: String,
        userText: String,
        assistantText: String,
        userMessageId: Long?,
    ) {
        if (userText.isBlank() && assistantText.isBlank()) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - (lastAttemptAt[character.id] ?: 0L) < minIntervalMs) return
            if (character.id in inFlight) return
            inFlight += character.id
            lastAttemptAt[character.id] = now
        }
        scope.launch {
            try {
                val record = generate(
                    character = character,
                    conversationId = conversationId,
                    turnId = turnId,
                    userText = userText,
                    assistantText = assistantText,
                    userMessageId = userMessageId,
                    sourceKind = CharacterStatusEntity.SOURCE_TURN,
                )
                if (record != null && autoDetail()) generateDetail(character, record)
            } finally {
                synchronized(this@CharacterStatusManager) { inFlight -= character.id }
            }
        }
    }

    /**
     * 生成并落库一条状态(回合收尾与调试 `status_refresh` 共用;调试传
     * [bypassCooldown] 无意义——本方法不做冷却判定,守卫在 [onTurnCompleted])。
     *
     * 返回落库的记录;null = LLM 失败 / 输出不合 schema / 版本过期(回溯拦截),
     * 三种失败都不写行。
     */
    suspend fun generate(
        character: CharacterEntity,
        conversationId: Long,
        turnId: String,
        userText: String,
        assistantText: String,
        userMessageId: Long?,
        sourceKind: String,
    ): CharacterStatusEntity? {
        val version = db.memoryWindowDao().version(conversationId) ?: 0L
        // 来源范围落库前现查(回合尾消息 id);查不到锚定为 null(仍记录状态)
        val endMessageId = if (turnId.isBlank()) null else db.messageDao().lastIdOfTurn(conversationId, turnId)
        val startMessageId = userMessageId ?: db.messageDao().firstIdOfTurn(conversationId, turnId)
        val raw = request(StatusPrompts.system(character.name), StatusPrompts.user(userText, assistantText)) ?: return null
        val snapshot = StatusSnapshotParser.parse(raw) ?: return null
        // 候选就绪后才进短事务(网络在事务外);复验版本防过期回写。
        val record = CharacterStatusEntity(
            characterId = character.id,
            conversationId = conversationId,
            emoji = snapshot.emoji,
            mood = "",
            doing = snapshot.doing,
            thought = "",
            sourceKind = sourceKind,
            sourceConversationId = conversationId,
            sourceTurnId = turnId,
            sourceStartMessageId = startMessageId,
            sourceEndMessageId = endMessageId,
            sourceVersion = version,
            state = CharacterStatusEntity.STATE_ACTIVE,
            generatedAt = System.currentTimeMillis(),
        )
        return db.withTransaction {
            if ((db.memoryWindowDao().version(conversationId) ?: 0L) != version) return@withTransaction null
            val id = dao.insert(record)
            record.copy(id = id)
        }
    }

    private val detailLocks = HashMap<Long, Mutex>()

    /** 根据该记录的来源对话生成详情；失败不保存，之后可重试。 */
    suspend fun generateDetail(character: CharacterEntity, record: CharacterStatusEntity): CharacterStatusDetailEntity? {
        val lock = synchronized(detailLocks) { detailLocks.getOrPut(record.id) { Mutex() } }
        return lock.withLock {
            val current = dao.byId(record.id) ?: return@withLock null
            if (current.characterId != character.id) return@withLock null
            dao.detail(current.id)?.let { return@withLock it }
            if (!current.isActive) return@withLock null
            val version = db.memoryWindowDao().version(current.conversationId) ?: 0L
            val start = current.sourceStartMessageId
            val end = current.sourceEndMessageId
            val messages = if (start != null && end != null && start <= end) {
                db.messageDao().rangeForConversation(current.conversationId, start, end)
            } else emptyList()
            val source = messages.joinToString("\n") { "${it.role}: ${it.content}" }.take(6000)
            val raw = request(StatusPrompts.detailSystem(character.name),
                "状态标题: ${current.emoji} ${current.doing}\n已有心情: ${current.mood}\n已有想法: ${current.thought}\n来源对话:\n${source.ifBlank { "（无可用来源对话）" }}") ?: return@withLock null
            val detail = parseDetail(current.id, raw) ?: return@withLock null
            db.withTransaction {
                val latest = dao.byId(current.id)
                if (latest == null || !latest.isActive || (db.memoryWindowDao().version(current.conversationId) ?: 0L) != version) {
                    return@withTransaction null
                }
                dao.insertDetail(detail)
                dao.detail(current.id)
            }
        }
    }

    internal fun parseDetail(statusId: Long, raw: String): CharacterStatusDetailEntity? {
        val fragment = MemoryOpsParser.firstJsonObject(raw) ?: return null
        val root = runCatching { Json { isLenient = true }.parseToJsonElement(fragment).jsonObject }.getOrNull() ?: return null
        val action = root.strMember("action")?.take(300) ?: return null
        val monologue = root.strMember("monologue")?.take(1500) ?: return null
        val metrics = (root["metrics"] as? JsonArray).orEmpty().mapNotNull { element ->
            val metric = element as? JsonObject ?: return@mapNotNull null
            val label = metric.strMember("label")?.take(20) ?: return@mapNotNull null
            val value = (metric["value"] as? JsonPrimitive)?.intOrNull?.coerceIn(0, 100) ?: return@mapNotNull null
            JsonObject(mapOf(
                "emoji" to JsonPrimitive(metric.strMember("emoji")?.take(8) ?: "✨"),
                "label" to JsonPrimitive(label),
                "value" to JsonPrimitive(value),
                "description" to JsonPrimitive(metric.strMember("description")?.take(600).orEmpty()),
            ))
        }.take(5)
        return CharacterStatusDetailEntity(
            statusId = statusId,
            location = root.strMember("location")?.take(300).orEmpty(),
            mood = root.strMember("mood")?.take(300).orEmpty(),
            action = action,
            monologue = monologue,
            metricsJson = JsonArray(metrics).toString(),
        )
    }

    /**
     * 手动注入一条状态(调试 `status_inject`;离线验收不依赖 LLM):
     * sourceKind=manual、不锚定回合;[mood]/[doing]/[thought] 与 [emoji]
     * 走与 LLM 输出同源的清洗(超长截断/emoji 兜底),空 doing 拒绝。
     */
    suspend fun injectManual(character: CharacterEntity, conversationId: Long, mood: String, doing: String, thought: String, emoji: String?): CharacterStatusEntity {
        val body = StatusSnapshotParser.clamp(doing) ?: throw IllegalArgumentException("doing 为空,无法注入状态")
        val record = CharacterStatusEntity(
            characterId = character.id,
            conversationId = conversationId,
            emoji = StatusSnapshotParser.validEmoji(emoji ?: StatusSnapshotParser.DEFAULT_EMOJI),
            mood = StatusSnapshotParser.clamp(mood).orEmpty(),
            doing = body,
            thought = StatusSnapshotParser.clamp(thought).orEmpty(),
            sourceKind = CharacterStatusEntity.SOURCE_MANUAL,
            sourceConversationId = conversationId,
            sourceTurnId = "",
            sourceVersion = db.memoryWindowDao().version(conversationId) ?: 0L,
            state = CharacterStatusEntity.STATE_ACTIVE,
            generatedAt = System.currentTimeMillis(),
        )
        val id = dao.insert(record)
        return record.copy(id = id)
    }

    /**
     * 当前状态条取值(E3/E2 口径):有 active 记录 → 记录行(emoji+doing);
     * 无记录 → [StatusRule] 规则兜底。旧记录即使存在也不改写规则行为 ——
     * 失效(stale)记录不作为当前状态。
     */
    suspend fun currentLine(character: CharacterEntity, conversationId: Long, recent: List<MessageEntity>): StatusLine {
        val record = dao.latestActive(character.id)
        return if (record != null) {
            StatusLine(record.emoji, record.doing.ifBlank { record.mood })
        } else {
            StatusRule.generate(character, recent)
        }
    }

    /** 状态生成的请求配置:非流式单发(temperature 与记忆抽取同稳态口径)。 */
    private suspend fun request(system: String, user: String): String? {
        val sb = StringBuilder()
        var failed = false
        try {
            llm.streamChat(
                listOf(
                    com.neethu.gouxiang.ai.adapter.ChatMessage(com.neethu.gouxiang.ai.adapter.ChatRole.SYSTEM, system),
                    com.neethu.gouxiang.ai.adapter.ChatMessage(com.neethu.gouxiang.ai.adapter.ChatRole.USER, user),
                ),
                llmConfig().copy(temperature = 0.2f),
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

    companion object {
        /** 回合收尾触发的默认冷却(避免每轮都烧一次请求;可测注入覆盖)。 */
        const val DEFAULT_MIN_INTERVAL_MS = 3 * 60_000L
    }
}

/**
 * 状态生成的文案(zh 单语,对齐 [EventPrompts]/[ProfilePrompts] 的风格):
 * 一行 JSON 三要素,绝不安造(没有明确依据时沿上一状态语境,不做空想)。
 */
object StatusPrompts {

    fun system(charName: String): String =
        "你在为虚拟人角色「$charName」写一条状态标题。根据最近对话，只输出一行 JSON：" +
            "{\"emoji\":\"一个表情\",\"doing\":\"正在做的事或状态标题\"}。" +
            "只依据对话，不编造；doing 使用第三人称短句，不超过 24 字。"

    fun detailSystem(charName: String): String =
        "你在为虚拟角色「$charName」写一条历史状态详情。只依据给定的状态标题和来源对话，" +
            "不把现在的情况当作过去的事实；信息不足时保守描述。只输出 JSON：" +
            "{\"location\":\"位置\",\"mood\":\"心情\",\"action\":\"状态和动作\",\"monologue\":\"第一人称内心独白\", " +
            "\"metrics\":[{\"emoji\":\"❤️\",\"label\":\"好感度\",\"value\":50,\"description\":\"变化和心情描述\"}]}。" +
            "metrics 按对话写 1 至 3 个有意义的情绪指标，value 为 0 到 100 的整数；不要输出图片。"

    fun user(userText: String, assistantText: String): String =
        "[最近对话片段]\n用户: ${userText.ifBlank { "(没有说话,是 TA 主动开口的回合)" }}\nTA: $assistantText"
}
