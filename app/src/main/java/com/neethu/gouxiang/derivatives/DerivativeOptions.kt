package com.neethu.gouxiang.derivatives

/**
 * 三类聊天衍生的用户输入与长度控制(G1/G2/G3;纯 Kotlin,单测锁行为):
 *
 * - 手帐 [HandbookOption]:聊天范围 preset(参见 [DerivativeRange.Preset]);
 * - 小说 [NovelOption]:长度档(短/中/长 → 章数边界与 maxTokens);
 * - 合照 [PhotoOption]:用户描述(非空、≤60 字;「模型输出 ≠ 可信数据」的
 *   用户输入侧同规则:入库前清洗)。
 *
 * optionJson 是创建记录时的快照:生成与重试都按同输入执行(幂等),目录/
 * 档位调整不回改既有记录。
 */
sealed interface DerivativeOptions {

    /** 序列化为 `chat_derivative_records.optionJson`(紧凑单行)。 */
    fun toJson(): String

    /** 手帐:选中范围 preset 的 key。 */
    data class HandbookOption(val presetKey: String) : DerivativeOptions {
        val preset: DerivativeRange.Preset get() = DerivativeRange.Preset.fromKey(presetKey)

        override fun toJson(): String = "{\"range\":\"${preset.key}\"}"
    }

    /** 小说:长度档。 */
    data class NovelOption(val lengthKey: String) : DerivativeOptions {
        val length: NovelLength get() = NovelLength.fromKey(lengthKey)

        override fun toJson(): String = "{\"length\":\"${length.key}\"}"
    }

    /** 合照:用户描述(已清洗)。 */
    data class PhotoOption(val description: String) : DerivativeOptions {
        override fun toJson(): String = "{\"description\":\"" +
            description.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"}"
    }

    companion object {

        /** 合照描述上限(输入框 maxLength 同步)。 */
        const val PHOTO_DESCRIPTION_MAX_CHARS = 60

        /**
         * optionJson → 强类型(坏 JSON/未知结构不抛,按 kind 回落合理默认;
         * 旧记录只可能来自本版本写入,这里主要防手工库损坏)。
         */
        fun fromJson(kind: String, optionJson: String): DerivativeOptions = when (kind) {
            ChatDerivativeKinds.HANDBOOK -> {
                val raw = strMember(optionJson, "range")
                HandbookOption(DerivativeRange.Preset.fromKey(raw).key)
            }
            ChatDerivativeKinds.NOVEL -> {
                val raw = strMember(optionJson, "length")
                NovelOption(NovelLength.fromKey(raw).key)
            }
            else -> PhotoOption(
                (strMember(optionJson, "description") ?: "").take(PHOTO_DESCRIPTION_MAX_CHARS),
            )
        }

        /** 首个平衡 JSON 的字符串成员(宽松;复用 sidestory 的扫描口径)。 */
        private fun strMember(optionJson: String, key: String): String? = try {
            val fragment = com.neethu.gouxiang.sidestory.SidestoryOutput.firstJsonObject(optionJson)
                ?: return null
            val root = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
                .parseToJsonElement(fragment) as? kotlinx.serialization.json.JsonObject ?: return null
            (root[key] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                ?.let { runCatching { it.content }.getOrNull() }
        } catch (_: Exception) {
            null
        }
    }
}

/** kind 字符串(与 [com.neethu.gouxiang.data.ChatDerivativeEntity] 常量对齐)。 */
object ChatDerivativeKinds {
    const val HANDBOOK = "handbook"
    const val NOVEL = "novel"
    const val PHOTO = "photo"
}

/**
 * 小说长度档(G2 长度控制):章数边界同时是输出 schema 的校验边界;
 * [maxTokens] 是本次 LLM 请求的输出预算(章多档给高预算,防 JSON 截断)。
 */
enum class NovelLength(val key: String, val label: String, val chapters: IntRange, val maxTokens: Int) {
    SHORT("short", "短篇", 2..3, 1600),
    MEDIUM("medium", "中篇", 4..5, 2400),
    LONG("long", "长篇", 6..8, 3200),
    ;

    companion object {
        /** key → 档位(未知/空回落中篇,不抛)。 */
        fun fromKey(key: String?): NovelLength =
            entries.firstOrNull { it.key == key } ?: MEDIUM
    }
}
