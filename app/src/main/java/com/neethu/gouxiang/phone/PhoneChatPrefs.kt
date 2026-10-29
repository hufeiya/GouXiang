package com.neethu.gouxiang.phone

import android.content.Context
import android.content.SharedPreferences

/**
 * 手机聊天 prefs:主动消息触发阈值(05 号文档「可配置」的本地落地)。
 * 键前缀 phone_,惯例同 AIAvatar-SDK 的 GreetingNotifyPrefs 六键风格。
 */
object PhoneChatPrefs {

    private const val FILE = "phone_chat_prefs"

    /** 距上次对话超过该毫秒数才触发「离线回归」主动消息(默认 2 小时)。 */
    private const val KEY_OFFLINE_THRESHOLD_MS = "phone_offline_threshold_ms"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun offlineThresholdMs(context: Context): Long =
        prefs(context).getLong(
            KEY_OFFLINE_THRESHOLD_MS,
            com.neethu.gouxiang.protocol.ProactiveMessages.DEFAULT_OFFLINE_THRESHOLD_MS,
        )

    fun setOfflineThresholdMs(context: Context, valueMs: Long) {
        prefs(context).edit()
            .putLong(KEY_OFFLINE_THRESHOLD_MS, valueMs.coerceAtLeast(0))
            .apply()
    }
}
