package com.neethu.gouxiang.ui.character

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neethu.gouxiang.ai.config.AiProvider
import com.neethu.gouxiang.ai.config.volcanoVoiceName
import com.neethu.gouxiang.ai.speech.CharacterVoiceStore
import com.neethu.gouxiang.ai.speech.VoiceAge
import com.neethu.gouxiang.ai.speech.VoiceCandidates
import com.neethu.gouxiang.ai.speech.VoiceGender
import com.neethu.gouxiang.ai.speech.VolcanoEngineTtsAdapter
import com.neethu.gouxiang.data.CharacterEntity

/** 音色单独即时保存，避免角色表单校验阻止用户修正错误匹配。 */
@Composable
internal fun CharacterVoiceSection(character: CharacterEntity) {
    val context = LocalContext.current
    val store = remember(context) { CharacterVoiceStore(context.applicationContext) }
    val options = remember {
        (VoiceGender.entries.flatMap { gender ->
            VoiceAge.entries.flatMap { age -> VoiceCandidates.forProfile(gender, age) }
        } + AiProvider.VOLCANO.voices).distinct()
    }
    var assignment by remember(character.id) { mutableStateOf(store.getOrAssign(character)) }
    var showOptions by remember(character.id) { mutableStateOf(false) }

    Spacer(Modifier.height(12.dp))
    Text("角色音色", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(6.dp))
    Column(
        Modifier.fillMaxWidth()
            .background(Color(0xFF1E1E26), RoundedCornerShape(10.dp))
            .clickable { showOptions = true }
            .padding(12.dp),
    ) {
        Text(
            assignment.voiceId?.let(::voiceLabel) ?: "信息不足，请选择音色",
            color = Color.White,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (assignment.isManual) "手动选择 · 点击修改，即时保存" else "自动匹配 · 点击修改，即时保存",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 12.sp,
        )
    }
    if (showOptions) {
        var query by remember { mutableStateOf("") }
        val filteredOptions = remember(options, query) {
            val search = query.trim()
            options.filter { voice ->
                search.isEmpty() || volcanoVoiceName(voice).contains(search, ignoreCase = true) ||
                    voice.contains(search, ignoreCase = true)
            }
        }
        AlertDialog(
            onDismissRequest = { showOptions = false },
            title = { Text("选择火山音色") },
            text = {
                Column {
                    Text("选择后立即保存。标有「支持指令」的音色会应用 AI 服务配置中的语音指令；可在火山控制台试听，可用性取决于账号权限。", fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("搜索音色名称或 ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LazyColumn(Modifier.fillMaxWidth().height(300.dp)) {
                        if (filteredOptions.isEmpty()) {
                            item { Text("没有匹配的音色", modifier = Modifier.padding(vertical = 12.dp)) }
                        }
                        items(filteredOptions, key = { it }) { voice ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    store.setManual(character, voice)
                                    assignment = store.getOrAssign(character)
                                    showOptions = false
                                }.padding(vertical = 9.dp),
                            ) {
                                Text(
                                    (if (assignment.voiceId == voice) "✓  " else "     ") + voiceLabel(voice),
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    store.reset(character)
                    assignment = store.getOrAssign(character)
                    showOptions = false
                }) { Text("恢复自动匹配") }
            },
            dismissButton = { TextButton(onClick = { showOptions = false }) { Text("关闭") } },
        )
    }
}

/** 展示易辨认的名称并保留 ID，方便核对火山控制台。 */
private fun voiceLabel(id: String): String {
    val label = volcanoVoiceName(id)
    val instructionLabel = if (VolcanoEngineTtsAdapter.supportsInstruction(id)) " · 支持指令" else ""
    return "$label$instructionLabel\n$id"
}
