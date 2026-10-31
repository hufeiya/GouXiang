package com.neethu.gouxiang.ui.profile

import android.content.Context
import android.content.SharedPreferences

/**
 * 用户标签持久化(2026-10-31):性别(单选)+ 喜好标签(多选)+ 是否已完成
 * 首次选择。惯例同 GenerationPrefs/ChatPrefs:object + FILE + KEY_ 前缀 +
 * runCatching 回落默认。
 *
 * 「未完成」口径:completed=false 时主框架每次进入都弹引导弹层(用户可点
 * 「关闭」跳过,下次进入再弹);点「确认」才落 completed=true,此后只从
 * 我的页「个人标签」进入修改。
 */
object UserTagPrefs {

    private const val FILE = "user_tag_prefs"

    /** 是否已完成首次选择(确认过一次即 true,此后不再自动弹层)。 */
    const val KEY_COMPLETED = "user_tag_completed"

    /** 性别(UserGender.id;未选 = 空串)。 */
    const val KEY_GENDER = "user_tag_gender"

    /** 喜好标签 id 集合(UserTagCatalog.TAGS 的 id 子集)。 */
    const val KEY_TAGS = "user_tag_tags"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isCompleted(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(KEY_COMPLETED, false) }.getOrDefault(false)

    fun gender(context: Context): UserGender? =
        UserGender.fromId(
            runCatching { prefs(context).getString(KEY_GENDER, null) }.getOrNull(),
        )

    /** 已选喜好标签 id 集合(getStringSet 返回内部实例,这里拷出防串改)。 */
    fun tagIds(context: Context): Set<String> =
        runCatching { prefs(context).getStringSet(KEY_TAGS, emptySet()) ?: emptySet() }
            .getOrDefault(emptySet())
            .toSet()

    /** 确认选择:性别 + 标签 + completed 一次落盘(调用方在确认按钮里走)。 */
    fun save(context: Context, gender: UserGender, tags: Set<String>) {
        runCatching {
            prefs(context).edit()
                .putString(KEY_GENDER, gender.id)
                .putStringSet(KEY_TAGS, tags.toSet())
                .putBoolean(KEY_COMPLETED, true)
                .apply()
        }
    }
}
