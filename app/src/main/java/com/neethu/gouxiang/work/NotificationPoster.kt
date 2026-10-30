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

    /** 直跳番外/心动页 extra(F 工作包)。 */
    const val EXTRA_OPEN_SIDESTORY = "open_sidestory_conversation_id"

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

    /**
     * 衍生内容完成通知(通道一;直跳心声/朋友圈)。通知 ID 走 [stableDerivativeId]:
     * 同角色/同会话/同类型的后续完成替换同一条通知(重试/中断恢复不重复提醒);
     * 不同会话或类型各占一条。是否重复提醒另受 `derivative_results.notificationDone`
     * 门禁(Worker 在同事务标记,崩溃恢复重跑不重复投递)。
     */
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
            id = stableDerivativeId(kind, characterName, conversationId),
            title = title,
            body = DerivativeCopy.BODY_TAP_TO_VIEW,
            contentIntent = contentIntent(context, conversationId, direct),
        )
    }

    /**
     * 番外/心动完成通知(F 工作包;通道一,直跳番外页)。ID 走 [stableSidestoryId]:
     * 同角色/同会话的后续完成替换同一条(与心声/朋友圈同语义)。
     */
    fun sidestory(context: Context, characterName: String, conversationId: Long) {
        notify(
            context,
            DerivativeCopy.CHANNEL_DERIVATIVE,
            id = stableSidestoryId(characterName, conversationId),
            title = DerivativeCopy.sidestoryTitle(characterName),
            body = DerivativeCopy.BODY_TAP_TO_VIEW,
            contentIntent = contentIntent(context, conversationId, EXTRA_OPEN_SIDESTORY),
        )
    }

    /** 主动消息通知(通道二 HIGH;直跳聊天页)。ID 走 [stableProactiveId]:每会话一条,累计条数原地更新。 */
    fun proactive(context: Context, characterName: String, conversationId: Long, count: Int) {
        notify(
            context,
            DerivativeCopy.CHANNEL_PROACTIVE,
            id = stableProactiveId(conversationId),
            title = DerivativeCopy.proactiveTitle(characterName),
            body = DerivativeCopy.proactiveBody(count),
            contentIntent = contentIntent(context, conversationId, EXTRA_OPEN_CHAT),
        )
    }

    /** 衍生内容通知稳定 ID(纯函数,单测锁死:同参同值,异参异值)。 */
    internal fun stableDerivativeId(
        kind: com.neethu.gouxiang.chat.DerivativeKind,
        characterName: String,
        conversationId: Long,
    ): Int = ("deriv$characterName$conversationId${kind.name}").hashCode()

    /** 主动消息通知稳定 ID(纯函数:每会话一条)。 */
    internal fun stableProactiveId(conversationId: Long): Int =
        ("proactive$conversationId").hashCode()

    /** 番外/心动通知稳定 ID(纯函数,单测锁死:同参同值,异参异值)。 */
    internal fun stableSidestoryId(characterName: String, conversationId: Long): Int =
        ("deriv-sidestory$characterName$conversationId").hashCode()
}
