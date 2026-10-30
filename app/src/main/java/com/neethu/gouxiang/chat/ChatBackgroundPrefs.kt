package com.neethu.gouxiang.chat

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 聊天页背景持久化(G 工作包「可用的背景设置」,对齐 07-chat-derivatives.md
 * §1.3「聊天转合照 → 设为聊天背景」的本地化闭环):
 *
 * - 每会话一键:背景 = 合照记录落盘的图片绝对路径;「还原默认背景」= 清键
 *  (回落角色立绘/渐变占位);写读均走 SharedPreferences,进程内以
 *  [state] 同步(照片页设置后聊天页即时生效,重启后从盘读回);
 * - 只存本应用 filesDir 下的路径(合照产物),路径随记录删除而失效——
 *   读取时校验文件存在,不存在的背景回落默认(不留死引用)。
 */
object ChatBackgroundPrefs {

    private const val FILE = "chat_background_prefs"
    private const val KEY_PREFIX = "chat_bg_"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 进程内同步态(conversationId → 背景文件绝对路径;无 = 默认背景)。 */
    private val stateInternal = MutableStateFlow<Map<Long, String>>(emptyMap())

    /** 聊天页/照片页订阅用(设置后即时生效)。 */
    val state: StateFlow<Map<Long, String>> = stateInternal.asStateFlow()

    /** 读一次(启动首帧/调试命令用;同步触发磁盘读取并入缓存)。 */
    fun background(context: Context, conversationId: Long): String? {
        val cached = stateInternal.value[conversationId]
        if (cached != null) return cached.takeIf { java.io.File(it).isFile }
        val stored = runCatching {
            prefs(context).getString(key(conversationId), null)
        }.getOrNull() ?: return null
        val valid = stored.takeIf { java.io.File(it).isFile }
        if (valid == null) {
            // 死引用(记录删除后文件已清理):就地清掉,不留「设置了但看不到」的假象。
            runCatching { prefs(context).edit().remove(key(conversationId)).apply() }
            stateInternal.value = stateInternal.value - conversationId
            return null
        }
        stateInternal.value = stateInternal.value + (conversationId to valid)
        return valid
    }

    /** 设为聊天背景(合照页动作;文件不存在不写,防死引用)。 */
    fun setBackground(context: Context, conversationId: Long, file: String) {
        if (!java.io.File(file).isFile) return
        runCatching { prefs(context).edit().putString(key(conversationId), file).apply() }
        stateInternal.value = stateInternal.value + (conversationId to file)
    }

    /** 还原默认背景(合照页动作;幂等)。 */
    fun clearBackground(context: Context, conversationId: Long) {
        runCatching { prefs(context).edit().remove(key(conversationId)).apply() }
        stateInternal.value = stateInternal.value - conversationId
    }

    private fun key(conversationId: Long): String = "$KEY_PREFIX$conversationId"
}
