package com.neethu.gouxiang.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * 通知通道基建(docs/background-generation-plan-20261030.md §2.3):
 * - "derivative"(IMPORTANCE_DEFAULT):衍生内容完成(心声/朋友圈/手帐/小说/合照);
 * - "proactive"(IMPORTANCE_HIGH):离线主动消息(用户该尽快看到的会话级事件)。
 * 幂等:通道已存在时重复调用静默无副作用(GouXiangApp.onCreate 每次启动都调)。
 * 红线:通道名/描述纳入文案扫描,不含敏感商业字样(红线清单见 DerivativeTaskTest,[DerivativeCopy])。
 */
object NotificationChannels {

    fun ensureAll(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                DerivativeCopy.CHANNEL_DERIVATIVE,
                DerivativeCopy.CHANNEL_DERIVATIVE_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = DerivativeCopy.CHANNEL_DERIVATIVE_DESC },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                DerivativeCopy.CHANNEL_PROACTIVE,
                DerivativeCopy.CHANNEL_PROACTIVE_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = DerivativeCopy.CHANNEL_PROACTIVE_DESC },
        )
    }
}
