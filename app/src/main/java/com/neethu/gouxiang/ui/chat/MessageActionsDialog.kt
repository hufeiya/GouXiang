package com.neethu.gouxiang.ui.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Row

/**
 * 聊天气泡长按操作弹窗 —— im 聊天页(03)与手机聊天页(14)共用(F1:抽公共组件,
 * 原 [com.neethu.gouxiang.ui.phone.PhoneChatScreen] 内私有 RewindDialog 上提):
 *
 * - 复制 → 剪贴板(调用方写);回溯 → 删除该条及之后消息(调用方 VM.rewindTo);
 * - 重新生成仅角色侧气泡提供(用户泡没有「让 Ta 重新说一次」的语义);
 * - 文案/配色两页同源,行为差异只由调用方回调决定。
 */
@Composable
fun MessageActionsDialog(
    fromUser: Boolean,
    targetText: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onRewind: () -> Unit,
    onRegenerate: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("消息操作", fontSize = 17.sp) },
        text = {
            Text(
                if (fromUser) "回溯会删掉「$targetText」及其之后的消息。"
                else "可选择回溯「$targetText」这段对话,或让 Ta 重新说一次。",
                fontSize = 14.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onRewind) { Text("回溯", color = Color(0xFFE8A93D)) }
        },
        dismissButton = {
            // 复制与取消/重新生成同置左侧(复制为中性操作,不着强调色;
            // 回溯/重新生成才是会改记录的动作)
            Row {
                TextButton(onClick = onCopy) { Text("复制") }
                if (!fromUser) {
                    TextButton(onClick = onRegenerate) { Text("重新生成", color = Color(0xFFE8A93D)) }
                } else {
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            }
        },
    )
}

/**
 * 回溯确认弹窗:二次确认防误触(长按菜单「回溯」不直接删,先点名目标内容)。
 * 手机聊天页原私有 AlertDialog 上提共用;成功后 Toast 由调用方提示。
 */
@Composable
fun RewindConfirmDialog(
    targetText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("回溯这段对话?", fontSize = 17.sp) },
        text = {
            Text(
                "回溯后「$targetText」及其之后的消息会从记录里删掉,无法恢复。",
                fontSize = 14.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("回溯", color = Color(0xFFE8A93D)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 回溯弹窗点名目标消息:压平空白后截前 12 字(长按误触相邻泡时肉眼可辨)。
 * 原手机聊天页 internal 上提为聊天域公共工具,两页共用同一截取口径。
 */
internal fun rewindSnippetShared(text: String): String {
    val flat = text.trim().replace(Regex("\\s+"), " ")
    return if (flat.length > 12) flat.take(12) + "…" else flat
}
