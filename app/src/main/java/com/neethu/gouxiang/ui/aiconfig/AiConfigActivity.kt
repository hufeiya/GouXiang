package com.neethu.gouxiang.ui.aiconfig

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neethu.gouxiang.ai.config.AiConfig
import com.neethu.gouxiang.ai.config.AiConfigStore
import com.neethu.gouxiang.ai.config.AiProvider
import com.neethu.gouxiang.ai.config.AsrEngine
import com.neethu.gouxiang.ai.config.ModelCatalogCache
import com.neethu.gouxiang.ai.config.ModelOption
import com.neethu.gouxiang.ai.config.OpenAiCompatibleModelCatalog
import com.neethu.gouxiang.ai.config.asrExplicitAsrModels
import com.neethu.gouxiang.ai.config.asrProviderFor
import com.neethu.gouxiang.ai.config.chatModelOptions
import com.neethu.gouxiang.ai.config.resolveAsrModel
import com.neethu.gouxiang.ai.config.resolveVoice
import com.neethu.gouxiang.ui.theme.GouXiangTheme
import kotlinx.coroutines.launch

/**
 * AI 服务配置页(「我的 → AI 服务配置」,移植自 AIAvatar-SDK MainActivity 设置的
 * AI 配置子区块,独立成 Activity):大模型(服务商/Key/模型/自定义端点)、
 * 语音通话的合成与识别(豆包语音 Key/硅基流动兜底 Key/ASR 模型)。
 *
 * 与 AIAvatar 设置页的差异(GouXiang 消费面裁剪,详见 AiConfigStore KDoc):
 * - TTS/ASR 只服务语音通话 F6,不做 Edge-TTS 多引擎矩阵与「同服务商」勾选
 *   (通话内置「火山优先、硅基兜底」双链);系统识别与「语音直接发送」无消费面;
 * - 每项改动即存即生效(编排器每回合现读),无需重启。
 */
class AiConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GouXiangTheme {
                var config by remember { mutableStateOf(AiConfigStore.load(this)) }
                AiConfigScreen(
                    config = config,
                    onUpdate = { new ->
                        config = new
                        AiConfigStore.save(this, new)
                    },
                    onBack = { finish() },
                )
            }
        }
    }
}

/** 下拉框的一个选项:[value] 为存进 prefs 的值,[label] 为显示名。 */
private data class DropdownOption(val value: String, val label: String)

@Composable
fun AiConfigScreen(
    config: AiConfig,
    onUpdate: (AiConfig) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        // 顶栏(与聊天历史页同款手绘风格:返回键 + 标题)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBackIosNew,
                    contentDescription = "返回",
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = "AI 服务配置",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        HorizontalDivider()

        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp, vertical = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { LlmSection(config, onUpdate) }
            item { TtsSection(config, onUpdate) }
            item { AsrSection(config, onUpdate) }
            // 底部说明(键前缀与仓库 secrets.properties 同名,一键推送技能可直写)
            item {
                Text(
                    text = "改动即存即生效。密钥留空时使用仓库 secrets.properties " +
                        "内置的默认值;此页填写后以本页为准。",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .navigationBarsPadding(),
                )
            }
        }
    }
}

// ── 大模型(聊天/记忆/心声/朋友圈生文 共用一条链路)──────────────────────

@Composable
private fun LlmSection(config: AiConfig, onUpdate: (AiConfig) -> Unit) {
    CollapsibleSection(
        title = "大模型(LLM · 服务商 / API Key / 模型)",
        initiallyExpanded = true,
    ) {
        SettingsGroupLabel(
            when {
                config.isConfigured -> "已配置,保存后下一回合立即生效"
                config.provider == AiProvider.CUSTOM && config.customBaseUrl.isBlank() ->
                    "自定义端点:先填下方 Base URL"
                else -> "选择服务商、填 API Key 即可对话"
            }
        )
        SettingsDropdownRow(
            title = "大模型服务商",
            options = AiProvider.entries.map { DropdownOption(it.name, it.label) },
            selectedValue = config.provider.name,
        ) { value ->
            // 切服务商:模型名保留会跨服务商泄漏(校验落默认,显示也归位),
            // 这里直接清空让下拉显示新服务商默认
            onUpdate(config.copy(provider = AiProvider.valueOf(value), llmModel = ""))
        }
        SettingsTextFieldRow(
            title = config.provider.llmKeyLabel +
                if (config.apiKeyFor(config.provider).isBlank()) "(未填写,用内置默认)" else "",
            value = config.apiKeyFor(config.provider),
            placeholder = config.provider.llmKeyHint,
            password = true,
        ) { key ->
            onUpdate(withApiKey(config, config.provider, key))
        }
        if (config.provider == AiProvider.CUSTOM) {
            // 自定义端点(中转站/本地模型):baseUrl/模型名手填 + 连通性测试,
            // 模型不走目录校验
            SettingsTextFieldRow(
                title = "端点 Base URL(OpenAI 兼容)",
                value = config.customBaseUrl,
                placeholder = "https://your-relay.example.com/v1 或 http://192.168.x.x:11434/v1",
            ) { onUpdate(config.copy(customBaseUrl = it)) }
            SettingsTextFieldRow(
                title = "模型名(手填)",
                value = config.llmModel,
                placeholder = "端点 /models 目录里的 id,如 deepseek-v4 / qwen3:32b",
            ) { onUpdate(config.copy(llmModel = it)) }
            CustomEndpointTestRow(
                baseUrl = config.customBaseUrl,
                apiKey = config.effectiveApiKey(config.provider),
            )
        } else {
            // 模型下拉 = /models 实时目录(展开时拉取,内存缓存重启失效)+
            // 静态清单兜底;带「视觉」标签的模型可看图
            SettingsModelDropdownRow(
                provider = config.provider,
                selectedValue = config.resolvedLlmModel(ModelCatalogCache.idsFor(config.provider)),
                apiKey = config.effectiveApiKey(config.provider),
            ) { onUpdate(config.copy(llmModel = it)) }
        }
    }
}

/** 写指定服务商的 API Key(其余服务商的 key 原样保留)。 */
private fun withApiKey(config: AiConfig, provider: AiProvider, key: String): AiConfig =
    when (provider) {
        AiProvider.SILICONFLOW -> config.copy(apiKeySiliconflow = key)
        AiProvider.VOLCANO -> config.copy(apiKeyVolcano = key)
        AiProvider.OPENROUTER -> config.copy(apiKeyOpenrouter = key)
        AiProvider.DEEPSEEK -> config.copy(apiKeyDeepseek = key)
        AiProvider.BAILIAN -> config.copy(apiKeyBailian = key)
        AiProvider.GROK -> config.copy(apiKeyGrok = key)
        AiProvider.CUSTOM -> config.copy(apiKeyCustom = key)
    }

// ── 语音合成(语音通话 F6:火山 seed-tts-2.0 优先,硅基 CosyVoice 兜底)────

@Composable
private fun TtsSection(config: AiConfig, onUpdate: (AiConfig) -> Unit) {
    CollapsibleSection(title = "语音合成(TTS · 语音通话)") {
        SettingsGroupLabel(
            "通话语音:火山豆包语音优先,未配置/失败自动落硅基流动兜底。" +
                "火山的大模型与语音是两把互不通用的 Key,各填各的。"
        )
        SettingsTextFieldRow(
            title = AiProvider.VOLCANO.ttsKeyLabel +
                if (config.apiKeyVolcanoTts.isBlank()) "(未填写,用内置默认)" else "",
            value = config.apiKeyVolcanoTts,
            placeholder = AiProvider.VOLCANO.ttsKeyHint,
            password = true,
        ) { onUpdate(config.copy(apiKeyVolcanoTts = it)) }
        if (config.provider != AiProvider.SILICONFLOW) {
            // 大模型不走硅基流动时,兜底链路的 Key 在这里补(与 ASR 共用一份)
            SettingsTextFieldRow(
                title = AiProvider.SILICONFLOW.ttsKeyLabel +
                    if (config.apiKeySiliconflow.isBlank()) "(未填写,用内置默认)" else "",
                value = config.apiKeySiliconflow,
                placeholder = AiProvider.SILICONFLOW.ttsKeyHint,
                password = true,
            ) { onUpdate(withApiKey(config, AiProvider.SILICONFLOW, it)) }
        }
        SettingsDropdownRow(
            title = "通话音色(火山)",
            options = AiProvider.VOLCANO.voices.map { DropdownOption(it, it) },
            selectedValue = resolveVoice(AiProvider.VOLCANO, config.ttsVoice),
        ) { value -> onUpdate(config.copy(ttsVoice = value)) }
    }
}

// ── 语音识别(语音通话 F6:云端识别跟随大模型服务商)───────────────────

@Composable
private fun AsrSection(config: AiConfig, onUpdate: (AiConfig) -> Unit) {
    CollapsibleSection(title = "语音识别(ASR · 语音通话)") {
        val asrProvider = asrProviderFor(config.provider)
        SettingsGroupLabel(
            "按住说话的云端识别走${asrProvider.label}" +
                (if (asrProvider != config.provider) "(${config.provider.label} 回落)" else "") +
                ",Key 与大模型共用一份。"
        )
        if (asrProvider == AiProvider.SILICONFLOW && config.provider != AiProvider.SILICONFLOW) {
            // 火山/DeepSeek 无云端 ASR 端点回落硅基流动:硅基 Key 在 LLM 区没露过
            // 面时在这里单独填一份(与 TTS 兜底共用)
            SettingsTextFieldRow(
                title = "硅基流动 API Key(语音识别用)" +
                    if (config.apiKeySiliconflow.isBlank()) "(未填写,用内置默认)" else "",
                value = config.apiKeySiliconflow,
                placeholder = "sk-...",
                password = true,
            ) { onUpdate(withApiKey(config, AiProvider.SILICONFLOW, it)) }
        }
        SettingsDropdownRow(
            title = "ASR 模型",
            options = listOf(DropdownOption("", "自动(推荐)")) +
                asrExplicitAsrModels(asrProvider).map { DropdownOption(it, it) },
            selectedValue = config.asrModel,
        ) { value -> onUpdate(config.copy(asrModel = value)) }
        SettingsGroupLabel(
            "当前生效:" + resolveAsrModel(asrProvider.baseUrl, config.asrModel) +
                "(引擎 ${AsrEngine.CLOUD.name})"
        )
    }
}

// ── 通用行组件(移植自 AIAvatar-SDK SettingsScreen,去 i18n 化)──────────

/**
 * 折叠分类:标题行点击展开/收起(箭头旋转),内容随展开动画进出。
 * 页面级状态即可(独立 Activity,离开即销毁,无需跨面板保留)。
 */
@Composable
private fun CollapsibleSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            val rotation by animateFloatAsState(
                targetValue = if (expanded) 180f else 0f,
                label = "chevronRotation",
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(rotation),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier.padding(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = content,
            )
        }
    }
}

/** 分组小标签(一节内的说明行)。 */
@Composable
private fun SettingsGroupLabel(title: String) {
    Text(
        text = title,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 2.dp),
    )
}

/** 带标签的单行文本输入(密钥带掩码);改动即回调,调用方决定何时持久化。 */
@Composable
private fun SettingsTextFieldRow(
    title: String,
    value: String,
    placeholder: String,
    password: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(text = placeholder, fontSize = 13.sp) },
            singleLine = true,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
    }
}

/** 标签 + 下拉选择行:选项在锚定当前值的 DropdownMenu 里展开。 */
@Composable
private fun SettingsDropdownRow(
    title: String,
    options: List<DropdownOption>,
    selectedValue: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label ?: selectedValue
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
                    .padding(top = 6.dp, bottom = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedLabel,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "选择$title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option.label + if (option.value == selectedValue) "(当前)" else "",
                                fontWeight = if (option.value == selectedValue) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        onClick = {
                            expanded = false
                            if (option.value != selectedValue) onSelect(option.value)
                        },
                    )
                }
            }
        }
    }
}

/**
 * 大模型下拉(实时目录版):展开时若无内存缓存则拉该服务商 GET /models(缓存
 * 刻意不落盘,重启失效)。选项 = 实时目录过滤后的对话模型 + 静态清单兜底并集,
 * 带「视觉」标签的模型支持图片输入;拉取中显示 loading 行,失败回退内置清单并
 * 提示。未填 Key 只显示静态清单附引导文案。选中值经目录归位后传入。
 */
@Composable
private fun SettingsModelDropdownRow(
    provider: AiProvider,
    selectedValue: String,
    apiKey: String,
    onSelect: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // 选项与拉取状态随服务商记忆(切服务商各自独立;缓存归全局缓存对象)
    var options by remember(provider) {
        mutableStateOf(chatModelOptions(provider, ModelCatalogCache.entriesFor(provider)))
    }
    var loading by remember(provider) { mutableStateOf(false) }
    var fetchFailed by remember(provider) { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    val startFetch: () -> Unit = {
        if (apiKey.isNotBlank() && !ModelCatalogCache.has(provider) && !loading) {
            loading = true
            scope.launch {
                val entries = ModelCatalogCache.fetch(provider, apiKey)
                fetchFailed = entries.isEmpty()
                options = chatModelOptions(provider, ModelCatalogCache.entriesFor(provider))
                loading = false
            }
        }
    }

    // 选中值不在选项里(如缓存过期)时追加保底,避免下拉框显示成裸值
    val displayOptions = remember(options, selectedValue) {
        if (selectedValue.isNotBlank() && options.none { it.id == selectedValue }) {
            options + ModelOption(selectedValue, false)
        } else options
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = "大模型",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        expanded = true
                        startFetch()
                    }
                    .padding(top = 6.dp, bottom = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedValue,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "选择大模型",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (options.isEmpty() && loading) {
                    DropdownMenuItem(
                        text = { Text(text = "正在获取模型清单…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = {},
                        enabled = false,
                    )
                }
                displayOptions.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = option.id + if (option.id == selectedValue) "(当前)" else "",
                                    fontWeight = if (option.id == selectedValue) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (option.vision) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.padding(start = 8.dp),
                                    ) {
                                        Text(
                                            text = "视觉",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                            }
                        },
                        onClick = {
                            expanded = false
                            if (option.id != selectedValue) onSelect(option.id)
                        },
                    )
                }
                if (fetchFailed && options.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text(text = "清单拉取失败,显示内置清单;重开下拉可重试", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = {},
                        enabled = false,
                    )
                }
                if (apiKey.isBlank()) {
                    DropdownMenuItem(
                        text = { Text(text = "填写 API Key 后可拉取实时清单", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = {},
                        enabled = false,
                    )
                }
            }
        }
    }
}

/**
 * 自定义端点连通性测试:GET {baseUrl}/models 验证端点与 Key,结果内联展示在行下;
 * 成功时目录顺带入 ModelCatalogCache(进程内存活,模型下拉直接可用)。
 */
@Composable
private fun CustomEndpointTestRow(baseUrl: String, apiKey: String) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
                .clickable {
                    val base = baseUrl.trim().trimEnd('/')
                    if (base.isBlank()) {
                        result = "连接失败:先填 Base URL"
                    } else {
                        scope.launch {
                            result = try {
                                val entries = OpenAiCompatibleModelCatalog().fetch(base, apiKey.trim())
                                if (entries.isEmpty()) "连接失败:/models 目录为空"
                                else "连接成功,目录共 ${entries.size} 个模型"
                            } catch (t: Throwable) {
                                "连接失败:${t.message ?: t.javaClass.simpleName}"
                            }
                        }
                    }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "测试连接",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = "GET Base URL/models 验证端点与 Key 连通",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        result?.let { SettingsGroupLabel(it) }
    }
}
