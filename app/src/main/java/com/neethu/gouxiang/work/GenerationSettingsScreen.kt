package com.neethu.gouxiang.work

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 内容生成设置页(docs/background-generation-plan-20261030.md §3.3):
 * 六枚开关一行一枚(Switch + 标题 + 副标题),进页即读 prefs、切换即写 +
 * 即时 enqueue/cancel,无中间保存按钮。「完成通知」打开时(≥33)由
 * MainActivity 现场请求 POST_NOTIFICATIONS 运行时权限。
 *
 * 红线:副标题文案纳入敏感商业字样单测扫描(§6)。
 */
@Composable
fun GenerationSettingsScreen(
    snapshot: GenerationSettingsSnapshot,
    onToggle: (key: String, enabled: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        // 顶栏:返回 + 标题(与记忆页/历史页导航惯例一致)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "内容生成",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }

        // 页首说明(§3.1 语义:开关只门控自动路径,点击生成不受限)
        Text(
            text = "开启后会在后台自动生成对应内容;关闭后仅当你点击对应按钮时才生成。自动生成消耗大模型调用,请按需开启。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        SwitchRow(
            title = "心声自动生成",
            subtitle = "开启后定期自动写心声,消耗大模型调用;关闭后仅点「生成心声」时生成",
            checked = snapshot.innerVoiceAuto,
            onCheckedChange = { onToggle(KEY_INNER_VOICE_AUTO, it) },
        )
        SwitchRow(
            title = "朋友圈每日自动发帖",
            subtitle = "开启后每天自动发一条动态,消耗大模型调用;关闭后仅点「生成动态」时生成",
            checked = snapshot.momentsDaily,
            onCheckedChange = { onToggle(KEY_MOMENTS_DAILY, it) },
        )
        SwitchRow(
            title = "心声配图",
            subtitle = "关闭后新心声仅生成文字；手动补图仍可使用",
            checked = snapshot.innerVoiceImages,
            onCheckedChange = { onToggle(com.neethu.gouxiang.ai.GenerationPrefs.KEY_INNER_VOICE_IMAGES, it) },
        )
        SwitchRow(
            title = "朋友圈配图",
            subtitle = "关闭后新动态仅生成文字；手动补图仍可使用",
            checked = snapshot.momentsImages,
            onCheckedChange = { onToggle(com.neethu.gouxiang.ai.GenerationPrefs.KEY_MOMENTS_IMAGES, it) },
        )
        SwitchRow(
            title = "番外配图",
            subtitle = "关闭后新番外仅生成文字；失败保留文字，可在记录里查看",
            checked = snapshot.sidestoryImages,
            onCheckedChange = { onToggle(com.neethu.gouxiang.ai.GenerationPrefs.KEY_SIDESTORY_IMAGES, it) },
        )
        SwitchRow(
            title = "隔夜记忆总结",
            subtitle = "开启后闲置 8 小时以上自动提炼记忆窗口;关闭后记忆窗口等下次聊天时提炼",
            checked = snapshot.memoryOvernight,
            onCheckedChange = { onToggle(KEY_MEMORY_OVERNIGHT, it) },
        )
        SwitchRow(
            title = "离线主动消息",
            subtitle = "开启后 TA 会在你离线时先发消息;关闭后 TA 不会在离线时先开口",
            checked = snapshot.proactiveOutbox,
            onCheckedChange = { onToggle(KEY_PROACTIVE_OUTBOX, it) },
        )
        SwitchRow(
            title = "手帐/小说/合照自动",
            subtitle = "功能开发中，暂不可开启",
            checked = false,
            enabled = false,
            onCheckedChange = { },
        )
        SwitchRow(
            title = "完成通知",
            subtitle = "关闭后生成完成仅 App 内可见,不弹系统通知;不消耗任何调用",
            checked = snapshot.notifyEnabled,
            onCheckedChange = { onToggle(KEY_NOTIFY_ENABLED, it) },
        )

        Spacer(Modifier.height(24.dp))
    }
}

/** 一行开关:标题 + 副标题(写清「开启后自动…,消耗大模型调用」)+ 右侧 Switch。 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

private val KEY_INNER_VOICE_AUTO = com.neethu.gouxiang.ai.GenerationPrefs.KEY_INNER_VOICE_AUTO
private val KEY_MOMENTS_DAILY = com.neethu.gouxiang.ai.GenerationPrefs.KEY_MOMENTS_DAILY
private val KEY_MEMORY_OVERNIGHT = com.neethu.gouxiang.ai.GenerationPrefs.KEY_MEMORY_OVERNIGHT
private val KEY_PROACTIVE_OUTBOX = com.neethu.gouxiang.ai.GenerationPrefs.KEY_PROACTIVE_OUTBOX
private val KEY_DERIVATIVE_AUTO = com.neethu.gouxiang.ai.GenerationPrefs.KEY_DERIVATIVE_AUTO
private val KEY_NOTIFY_ENABLED = com.neethu.gouxiang.ai.GenerationPrefs.KEY_NOTIFY_ENABLED
