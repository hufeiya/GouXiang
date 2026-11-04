package com.neethu.gouxiang.ui.aiconfig

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.neethu.gouxiang.ai.config.AiProvider
import com.neethu.gouxiang.ai.config.volcanoVoiceName
import com.neethu.gouxiang.ai.speech.VolcanoEngineTtsAdapter

/** 搜索火山音色；选中或恢复自动匹配后关闭弹窗。 */
@Composable
fun VolcanoVoicePickerDialog(
    selectedValue: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    showUnselectedOption: Boolean = true,
    onResetAutomatic: (() -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    val voices = remember { AiProvider.VOLCANO.voices }
    val filtered = remember(query, voices) {
        val search = query.trim()
        voices.filter { volcanoVoiceName(it).contains(search, ignoreCase = true) || it.contains(search, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择火山音色") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("搜索音色名称或 ID") },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    if (showUnselectedOption && query.isBlank()) item {
                        Text("未选择（不播放）", modifier = Modifier.fillMaxWidth().clickable {
                            onSelect("")
                            onDismiss()
                        }.padding(vertical = 12.dp))
                    }
                    items(filtered, key = { it }) { id ->
                        val marker = if (VolcanoEngineTtsAdapter.supportsInstruction(id)) " · 支持指令" else ""
                        Text((if (id == selectedValue) "✓ " else "") + volcanoVoiceName(id) + marker,
                            modifier = Modifier.fillMaxWidth().clickable {
                                onSelect(id)
                                onDismiss()
                            }.padding(vertical = 12.dp))
                    }
                    if (filtered.isEmpty()) item { Text("没有找到音色", modifier = Modifier.padding(vertical = 12.dp)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = onResetAutomatic?.let { reset ->
            { TextButton(onClick = { reset(); onDismiss() }) { Text("恢复自动匹配") } }
        },
    )
}
