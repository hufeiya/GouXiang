package com.neethu.gouxiang.ui.character

import android.content.Context
import kotlinx.serialization.json.Json

/**
 * 新建角色草稿的本地持久化(C 工作包 C1「支持本地草稿」):
 *
 * - 只服务新建路径(id=0);编辑既有角色直接改行,不落草稿;
 * - 单草稿槽(SharedPreferences JSON),进编辑页自动恢复,保存成功即清除;
 * - 失败安全:损坏 JSON 返回 null 重新开表,绝不抛异常阻断创作。
 */
class CharacterDraftPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 读草稿(无草稿/损坏返回 null;空字段全空白的草稿视为无草稿)。 */
    fun load(): CharacterDraft? = try {
        val raw = prefs.getString(KEY_DRAFT, null) ?: return null
        val draft = json.decodeFromString(CharacterDraft.serializer(), raw)
        if (draft.isBlankDraft()) null else draft
    } catch (_: Exception) {
        null
    }

    /** 写草稿(全空白视为丢弃,不落盘)。 */
    fun save(draft: CharacterDraft) {
        if (draft.isBlankDraft()) {
            clear()
            return
        }
        prefs.edit().putString(KEY_DRAFT, json.encodeToString(CharacterDraft.serializer(), draft)).apply()
    }

    /** 保存成功/用户放弃后清槽。 */
    fun clear() {
        prefs.edit().remove(KEY_DRAFT).apply()
    }

    private fun CharacterDraft.isBlankDraft(): Boolean =
        name.isBlank() && profile.isBlank() && intro.isBlank() && exampleLines.isBlank() &&
            openingLine.isBlank() && appearance.isBlank() && appearanceStyle.isBlank()

    companion object {
        const val PREFS_NAME = "character_draft"
        const val KEY_DRAFT = "draft"
    }
}
