package com.neethu.gouxiang.chat

import android.content.Context
import android.content.SharedPreferences

/**
 * 对话风格持久化(角色主页 04 弹层选择,进程重启后保留):
 * 全局一套键(本地版当前主线角色单条会话,粒度不按角色分,后续多角色再分片)。
 * 键前缀 chat_style_,惯例同 [com.neethu.gouxiang.phone.PhoneChatPrefs]。
 */
object ChatPrefs {

    private const val FILE = "chat_style_prefs"
    private const val KEY_MODE = "chat_style_mode"
    private const val KEY_LENGTH = "chat_style_length"
    private const val KEY_SOUND_ENABLED = "chat_sound_enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun soundEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_SOUND_ENABLED, true)

    fun setSoundEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SOUND_ENABLED, enabled).apply()
    }

    /** 对话模式(缺省 = 心动模式,09 弹层默认勾选)。 */
    fun dialogMode(context: Context): DialogMode =
        runCatching {
            prefs(context).getString(KEY_MODE, null)?.let { DialogMode.valueOf(it) }
        }.getOrNull() ?: DialogMode.DEFAULT

    fun setDialogMode(context: Context, mode: DialogMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    /** 回复长度(缺省 = 适中,10 弹层默认选中)。 */
    fun replyLength(context: Context): ReplyLength =
        runCatching {
            prefs(context).getString(KEY_LENGTH, null)?.let { ReplyLength.valueOf(it) }
        }.getOrNull() ?: ReplyLength.DEFAULT

    fun setReplyLength(context: Context, length: ReplyLength) {
        prefs(context).edit().putString(KEY_LENGTH, length.name).apply()
    }
}
