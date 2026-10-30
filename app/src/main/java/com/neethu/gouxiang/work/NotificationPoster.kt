package com.neethu.gouxiang.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.neethu.gouxiang.MainActivity
import com.neethu.gouxiang.ai.GenerationPrefs

/**
 * 系统通知发送(§2.3):统一走 [notifyEnabled] 开关 + ≥33 运行时权限双闸;
 * 拒绝/关闭任一即静默不发(任务照跑,App 内 Room 流照常上屏——
 * 「拒绝则降级为仅 App 内红点」语义)。
 *
 * 直跳 extras:独立命名空间(open_inner_voice_conversation_id / open_moments_
 * conversation_id),与 ai_cmd 调试 extras 完全分开;MainActivity singleTask 的
 * onNewIntent 消费(运行中)或 onCreate(冷启动)。
 */
internal object NotificationPoster {

    /** 直跳心声页 extra。 */
    const val EXTRA_OPEN_INNER_VOICE = "open_inner_voice_conversation_id"

    /** 直跳朋友圈页 extra。 */
    const val EXTRA_OPEN_MOMENTS = "open_moments_conversation_id"

    /** 直跳聊天页(手机形态)extra。 */
    const val EXTRA_OPEN_CHAT = "open_chat_conversation_id"

    /** 直跳消息列表(不落具体会话)。 */
    const val EXTRA_OPEN_MESSAGES = "open_messages"

    private fun canPost(context: Context): Boolean {
        if (!GenerationPrefs.notifyEnabled(context)) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return true
    }

    private fun contentIntent(context: Context, conversationId: Long, direct: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            // singleTask 复用既有实例走 onNewIntent;冷启动走 onCreate 同路。
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(direct, conversationId)
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode() + (direct?.hashCode() ?: 0),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notify(
        context: Context,
        channel: String,
        id: Int,
        title: String,
        body: String,
        contentIntent: PendingIntent?,
    ) {
        if (!canPost(context)) return
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .apply { if (contentIntent != null) setContentIntent(contentIntent) }
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }

    /** 衍生内容完成通知(通道一;直跳心声/朋友圈)。 */
    fun derivative(
        context: Context,
        kind: com.neethu.gouxiang.chat.DerivativeKind,
        characterName: String,
        conversationId: Long,
    ) {
        val (title, direct) = when (kind) {
            com.neethu.gouxiang.chat.DerivativeKind.INNER_VOICE ->
                DerivativeCopy.innerVoiceTitle(characterName) to EXTRA_OPEN_INNER_VOICE
            com.neethu.gouxiang.chat.DerivativeKind.MOMENT ->
                DerivativeCopy.momentTitle(characterName) to EXTRA_OPEN_MOMENTS
            else -> DerivativeCopy.genericTitle(characterName) to EXTRA_OPEN_MESSAGES
        }
        notify(
            context,
            DerivativeCopy.CHANNEL_DERIVATIVE,
            id = ("deriv$characterName$conversationId${kind.name}").hashCode(),
            title = title,
            body = DerivativeCopy.BODY_TAP_TO_VIEW,
            contentIntent = contentIntent(context, conversationId, direct),
        )
    }

    /** 主动消息通知(通道二 HIGH;直跳聊天页)。 */
    fun proactive(context: Context, characterName: String, conversationId: Long, count: Int) {
        notify(
            context,
            DerivativeCopy.CHANNEL_PROACTIVE,
            id = ("proactive$conversationId").hashCode(),
            title = DerivativeCopy.proactiveTitle(characterName),
            body = DerivativeCopy.proactiveBody(count),
            contentIntent = contentIntent(context, conversationId, EXTRA_OPEN_CHAT),
        )
    }
}
