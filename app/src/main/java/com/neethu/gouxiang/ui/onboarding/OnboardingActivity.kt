package com.neethu.gouxiang.ui.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neethu.gouxiang.R
import com.neethu.gouxiang.ai.config.AiConfigStore
import com.neethu.gouxiang.ai.config.AiProvider
import com.neethu.gouxiang.ai.config.AsrProvider
import com.neethu.gouxiang.ai.config.TtsEngine
import com.neethu.gouxiang.ui.theme.GouXiangTheme

/** 引导完成状态独立于 AI 配置，跳过后也不会在每次启动时重新出现。 */
object OnboardingStore {
    private const val FILE = "onboarding_prefs"
    private const val COMPLETED = "completed"

    fun isCompleted(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(COMPLETED, false)

    fun markCompleted(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(COMPLETED, true).apply()
    }
}

class OnboardingActivity : ComponentActivity() {
    companion object {
        fun intent(context: Context): Intent = Intent(context, OnboardingActivity::class.java)
        private const val ARK_URL = "https://ark.volcengine.com/region:cn-beijing/apiKey"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initial = AiConfigStore.load(this)
        setContent {
            GouXiangTheme {
                OnboardingScreen(
                    initialLlmKey = initial.apiKeyVolcano,
                    initialTtsKey = initial.apiKeyVolcanoTts,
                    onOpenBrowser = {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ARK_URL))) }
                            .onFailure { Toast.makeText(this, "无法打开浏览器，请先安装浏览器", Toast.LENGTH_LONG).show() }
                    },
                    onSkip = {
                        if (!OnboardingStore.isCompleted(this)) {
                            val config = AiConfigStore.load(this)
                            AiConfigStore.save(this, config.copy(
                                apiKeyVolcano = "",
                                provider = AiProvider.VOLCANO,
                                asrProvider = AsrProvider.SYSTEM,
                                disableBuiltInKeys = true,
                                ttsEngine = TtsEngine.EDGE,
                            ))
                        }
                        OnboardingStore.markCompleted(this)
                        finish()
                    },
                    onSave = { llmKey, ttsKey ->
                        val config = AiConfigStore.load(this)
                        AiConfigStore.save(this, config.copy(
                            provider = AiProvider.VOLCANO,
                            llmModel = "",
                            apiKeyVolcano = llmKey.trim(),
                            disableBuiltInKeys = false,
                            ttsProvider = AiProvider.VOLCANO,
                            apiKeyVolcanoTts = ttsKey.trim(),
                            ttsEngine = TtsEngine.OPENAI_COMPATIBLE,
                        ))
                        OnboardingStore.markCompleted(this)
                        finish()
                    },
                )
            }
        }
    }
}

private data class GuideStep(val title: String, val description: String, val image: Int)

private val steps = listOf(
    GuideStep("登录火山引擎", "在浏览器中登录或注册火山引擎账号。完成后返回这里，继续看下一步。", R.drawable.onboarding_1),
    GuideStep("创建大模型 Key", "在方舟的 API Key 管理页面，点击「创建 API Key」。", R.drawable.onboarding_2),
    GuideStep("复制大模型 Key", "点击 Key 旁的复制按钮。回到狗箱后，立即粘贴到下方输入框。", R.drawable.onboarding_3),
    GuideStep("进入开通管理", "打开方舟侧边菜单，选择「开通管理」。", R.drawable.onboarding_4),
    GuideStep("开通大模型", "找到豆包 Seed 2.0 Mini 并按页面提示开通；图中模型仅作示例。开通可能需要实名认证，请留意页面上的价格和免费额度。", R.drawable.onboarding_5),
    GuideStep("开通语音合成", "向下找到「豆包语音合成 2.0」，按页面提示开通并进入语音控制台。", R.drawable.onboarding_6),
    GuideStep("进入新版语音控制台", "如果看到「新版体验」，点击进入。", R.drawable.onboarding_7),
    GuideStep("复制语音 Key", "在语音控制台侧边菜单选择「API Key」，创建或复制语音 Key，回到狗箱后粘贴到下方。它与方舟的大模型 Key 不同。", R.drawable.onboarding_8),
)

@Composable
private fun OnboardingScreen(
    initialLlmKey: String,
    initialTtsKey: String,
    onOpenBrowser: () -> Unit,
    onSkip: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    BackHandler(onBack = onSkip)
    var step by rememberSaveable { mutableIntStateOf(0) }
    var llmKey by rememberSaveable { mutableStateOf(initialLlmKey) }
    var ttsKey by rememberSaveable { mutableStateOf(initialTtsKey) }
    val scrollState = rememberScrollState()
    LaunchedEffect(step) { scrollState.scrollTo(0) }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("配置 AI 服务", modifier = Modifier.padding(top = 12.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onSkip) { Text("跳过") }
        }
        Text("${step + 1} / ${steps.size + 1}", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (step < steps.size) {
                val guide = steps[step]
                Text(guide.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                Text(guide.description, lineHeight = 23.sp)
                OutlinedButton(onClick = onOpenBrowser, modifier = Modifier.fillMaxWidth()) { Text("在浏览器打开火山方舟") }
                Text("浏览器操作后切回狗箱，进度会保留。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (step == 2 || step == 7) {
                    val isLlm = step == 2
                    OutlinedTextField(
                        value = if (isLlm) llmKey else ttsKey,
                        onValueChange = { if (isLlm) llmKey = it else ttsKey = it },
                        label = { Text(if (isLlm) "大模型 Key（火山方舟）" else "语音 Key（豆包语音）") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
                Image(painterResource(guide.image), contentDescription = guide.title + "操作示意图", modifier = Modifier.fillMaxWidth())
            } else {
                Text("粘贴两把 Key", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                Text("分别粘贴刚才复制的 Key。两把 Key 用途不同，请不要填反。", lineHeight = 23.sp)
                OutlinedTextField(
                    value = llmKey, onValueChange = { llmKey = it }, label = { Text("大模型 Key（火山方舟）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = ttsKey, onValueChange = { ttsKey = it }, label = { Text("语音 Key（豆包语音）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(),
                )
                Text("Key 用于连接对应服务，日后可在 AI 服务配置中修改。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step > 0) OutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f)) { Text("上一步") }
            if (step < steps.size) {
                Button(onClick = { step++ }, modifier = Modifier.weight(1f)) { Text("下一步") }
            } else {
                Button(onClick = { onSave(llmKey, ttsKey) }, enabled = llmKey.isNotBlank() && ttsKey.isNotBlank(), modifier = Modifier.weight(1f)) { Text("保存并开始") }
            }
        }
    }
}
