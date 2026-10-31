package com.neethu.gouxiang.ui.character

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.neethu.gouxiang.ai.speech.CharacterVoiceStore
import com.neethu.gouxiang.ai.speech.VoiceAge
import com.neethu.gouxiang.ai.speech.VoiceCandidates
import com.neethu.gouxiang.ai.speech.VoiceGender
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
        AlertDialog(
            onDismissRequest = { showOptions = false },
            title = { Text("选择火山音色") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("选择后立即保存。年龄感仅供参考，可在火山控制台试听；可用性取决于账号权限。", fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    options.forEach { voice ->
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
                    TextButton(onClick = {
                        store.reset(character)
                        assignment = store.getOrAssign(character)
                        showOptions = false
                    }) { Text("恢复自动匹配") }
                }
            },
            confirmButton = { TextButton(onClick = { showOptions = false }) { Text("关闭") } },
        )
    }
}

/** 展示易辨认的名称并保留 ID，方便核对火山控制台。 */
private fun voiceLabel(id: String): String {
    val label = when (id) {
        "zh_female_vv_uranus_bigtts" -> "女声 · Vivi（年龄感待试听）"
        "zh_female_xiaohe_uranus_bigtts" -> "女声 · 小何（年龄感待试听）"
        "zh_male_m191_uranus_bigtts" -> "男声 · 云舟（年龄感待试听）"
        "zh_male_taocheng_uranus_bigtts" -> "男声 · 小天（年龄感待试听）"
        "zh_male_shaonianzixin_uranus_bigtts" -> "男声 · 少年梓辛（少年感）"
        "zh_female_meilinvyou_uranus_bigtts" -> "女声 · 魅力女友（年龄感待试听）"
        "zh_female_sophie_uranus_bigtts" -> "女声 · 魅力苏菲（年龄感待试听）"
        "zh_female_qingxinnvsheng_uranus_bigtts" -> "女声 · 清新女声（年龄感待试听）"
        "zh_female_cancan_uranus_bigtts" -> "女声 · 知性灿灿（年龄感待试听）"
        "ICL_uranus_zh_male_qingshuangshaonian_tob" -> "男声 · 清爽少年（少年感）"
        "ICL_uranus_zh_male_wennuanshaonian_tob" -> "男声 · 温暖少年（少年感）"
        "ICL_uranus_zh_female_chunzhenshaonv_tob" -> "女声 · 纯真少女（少女感）"
        "ICL_uranus_zh_female_qingxinshaonv_tob" -> "女声 · 倾心少女（少女感）"
        "ICL_uranus_zh_female_bingruoshaonv_tob" -> "女声 · 病弱少女（少女感）"
        "zh_male_ruyaqingnian_uranus_bigtts" -> "男声 · 儒雅青年（青年感）"
        "zh_male_fanjuanqingnian_uranus_bigtts" -> "男声 · 反卷青年（青年感）"
        "zh_female_wenroushunv_uranus_bigtts" -> "女声 · 温柔淑女（年龄感待试听）"
        "zh_female_roumeinvyou_uranus_bigtts" -> "女声 · 柔美女友（年龄感待试听）"
        "zh_male_baqiqingshu_uranus_bigtts" -> "男声 · 霸气青叔（成熟感）"
        "zh_male_yuanboxiaoshu_uranus_bigtts" -> "男声 · 渊博小叔（成熟感）"
        "zh_female_wenroumama_uranus_bigtts" -> "女声 · 温柔妈妈（成熟感）"
        "ICL_uranus_zh_female_linjuayi_tob" -> "女声 · 邻居阿姨（成熟感）"
        "ICL_uranus_zh_male_youmodaye_tob" -> "男声 · 幽默大爷（年长感）"
        "zh_female_popo_uranus_bigtts" -> "女声 · 婆婆（年长感）"
        "ICL_uranus_zh_female_heainainai_tob" -> "女声 · 和蔼奶奶（年长感）"
        else -> "$id（年龄感待试听）"
    }
    return "$label\n$id"
}
