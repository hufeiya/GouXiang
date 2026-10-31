package com.neethu.gouxiang.ui.innervoice

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import com.neethu.gouxiang.data.InnerVoiceEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 心声详情页(F8 升级,对齐猫箱参考截图):
 *
 * - 全屏覆盖层:近黑背景 + 顶部返回 + 中间翻转卡 + 底部动作行(下载 / 设为聊天背景);
 * - 入场:配图拆成上中下三条切片,依次左/右/左飞入拼合(仅首次,翻回不重播;
 *   无图直接进入拼合态画占位);
 * - 图片面 = 拍立得白卡:图区 3:4 + 「AI 生成」角标 + 左下朗读喇叭 +
 *   右下「查看心声」;图下 echo 打字机逐字显示 + 右下作者署名;
 * - 信纸面 = 米白纸卡:title + 正文短文(content 空回落 echo)+ 署名 +
 *   「由 AI 生成」水印;翻到信纸自动 TTS 朗读全文(外部 BGM 由调用方联动压低),
 *   点击信纸翻回图片面;
 * - 翻转 = Animatable 0→1 驱动 rotationY,90° 处 alpha 换面,背面预置 180°
 *   校正父层镜像。
 */
@Composable
fun InnerVoiceDetailOverlay(
    voice: InnerVoiceEntity,
    characterName: String,
    speaking: Boolean,
    onBack: () -> Unit,
    onSpeak: (String) -> Unit,
    onStopSpeak: () -> Unit,
    onSetBackground: (InnerVoiceEntity) -> Unit,
    onDownload: (InnerVoiceEntity, onResult: (Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hasImage = voice.imageFile.isNotBlank()

    // 入场三段飞入:仅首次(拼合后置 true;无图初始即拼合态)
    var assembled by remember(voice.id) { mutableStateOf(!hasImage) }
    // 翻转:0 = 图片面,1 = 信纸面(90° 处换内容)
    val flip = remember(voice.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val letterSide = flip.value >= 0.5f
    val caption = voice.echo.ifBlank { voice.title }
    var shownCaption by remember(voice.id) { mutableStateOf("") }
    var autoFlipPending by remember(voice.id) { mutableStateOf(true) }

    // 打字完成后只自动翻转一次；用户主动翻面后不再打断其选择。
    LaunchedEffect(voice.id, caption, assembled) {
        if (!assembled) return@LaunchedEffect
        shownCaption = ""
        for (ch in caption) {
            shownCaption += ch
            delay(110)
        }
        if (autoFlipPending) {
            autoFlipPending = false
            flip.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
        }
    }

    // BGM:进入详情立即随机播放(循环),离开释放;朗读时压低(duck 联动)
    val bgm = remember(voice.id) { InnerVoiceBgmPlayer(context) }
    DisposableEffect(voice.id) {
        onDispose {
            bgm.release()
            onStopSpeak()
        }
    }
    LaunchedEffect(bgm, speaking) { bgm.setDucked(speaking) }

    // 翻到信纸自动朗读全文;翻回图片面停读
    val letterBody = voice.content.ifBlank { voice.echo }
    val speechText = "${voice.title}。$letterBody"
    LaunchedEffect(letterSide) {
        if (letterSide) onSpeak(speechText) else onStopSpeak()
    }

    fun flipTo(target: Float) {
        autoFlipPending = false
        scope.launch {
            flip.animateTo(target, tween(600, easing = FastOutSlowInEasing))
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1A1B20), Color(0xFF0B0C0F)))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // ── 顶部返回(dump:左上 ‹)────────────────────────────
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 8.dp),
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .size(44.dp)
                        .noRippleClickable { onBack() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.ArrowBackIosNew,
                        contentDescription = "返回",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            // ── 翻转卡容器 ────────────────────────────────────────
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    // 卡片宽高比 ≈0.60:3:4 图区 + 底部 caption/署名条(0.72 会把
                    // caption 挤到无空间,打字机不可见——真机实测修正)
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.62f)
                        .graphicsLayer {
                            rotationY = flip.value * 180f
                            cameraDistance = 6000f
                        },
                ) {
                    val frontSide = flip.value < 0.5f
                    // 只放入当前可见面：透明的信纸面仍会参与命中测试，遮住图片上的按钮。
                    if (frontSide) {
                        PolaroidFace(
                            voice = voice,
                            characterName = characterName,
                            assembled = assembled,
                            onAssembled = { assembled = true },
                            onFlipToLetter = { flipTo(1f) },
                            speaking = speaking,
                            onSpeakToggle = { if (speaking) onStopSpeak() else onSpeak(speechText) },
                            interactive = assembled,
                            shownCaption = shownCaption,
                        )
                    } else {
                        // 信纸面预旋转 180°，校正父层翻转的镜像。
                        Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) {
                            LetterFace(voice, characterName, letterBody, interactive = true) { flipTo(0f) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            // ── 底部动作行:下载 / 设为聊天背景 ──────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(52.dp)
                        .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                        .noRippleClickable(enabled = hasImage) {
                            onDownload(voice) { ok ->
                                Toast.makeText(context, if (ok) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = "下载配图",
                        tint = if (hasImage) Color.White else Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(22.dp),
                    )
                }
                Box(
                    Modifier
                        .weight(1.25f)
                        .background(Color(0xFFFFE14D), RoundedCornerShape(100.dp))
                        .noRippleClickable(enabled = hasImage) { onSetBackground(voice) }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "设为聊天背景",
                        color = Color(0xFF2A2500),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** 图片面:拍立得白卡(图区 3:4 + 角标/喇叭/查看心声 + echo 打字机 + 署名)。 */
@Composable
private fun PolaroidFace(
    voice: InnerVoiceEntity,
    characterName: String,
    assembled: Boolean,
    onAssembled: () -> Unit,
    onFlipToLetter: () -> Unit,
    speaking: Boolean,
    onSpeakToggle: () -> Unit,
    interactive: Boolean,
    shownCaption: String,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.White, RoundedCornerShape(18.dp))
            .padding(10.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
            if (voice.imageFile.isNotBlank()) {
                // 切片拼合态与整图像素完全一致(同 Crop 同几何),直接切换无缝
                if (assembled) {
                    AsyncImage(
                        model = java.io.File(voice.imageFile),
                        contentDescription = voice.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    SliceEntranceImage(
                        imageFile = voice.imageFile,
                        onDone = onAssembled,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                // 无图兜底(生图失败/生成中):占位底 + 心形,其余交互照常
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFFEDE7F0), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("💗", fontSize = 42.sp)
                }
            }
            Text(
                "AI 生成",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.28f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .size(40.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                    .noRippleClickable(enabled = interactive) { onSpeakToggle() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (speaking) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    contentDescription = if (speaking) "停止朗读" else "朗读心声",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(100.dp))
                    .noRippleClickable(enabled = interactive) { onFlipToLetter() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("查看心声", color = Color.White, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().weight(1f)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                TypewriterText(shownCaption)
            }
            Text(
                characterName,
                color = Color(0xFF4A4A4A),
                fontSize = 15.sp,
                modifier = Modifier.align(Alignment.End).padding(bottom = 2.dp),
            )
        }
    }
}

/** 信纸面:米白纸卡,title + 正文短文 + 署名 + 水印;点击翻回图片面。 */
@Composable
private fun LetterFace(
    voice: InnerVoiceEntity,
    characterName: String,
    body: String,
    interactive: Boolean,
    onFlipBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(Color(0xFFF8F5EE), Color(0xFFEFEBDF)),
                ),
                RoundedCornerShape(18.dp),
            )
            .noRippleClickable(enabled = interactive) { onFlipBack() }
            .padding(horizontal = 22.dp, vertical = 26.dp),
    ) {
        Text(voice.title, color = Color(0xFF232323), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(18.dp))
        Text(
            body,
            color = Color(0xFF2E2E2E),
            fontSize = 16.sp,
            lineHeight = 28.sp,
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            characterName,
            color = Color(0xFF232323),
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.End),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "由 AI 生成",
            color = Color(0xFFB9B4A6),
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

/** 打字机文字；进度由详情页持有，翻面后不会重新开始。 */
@Composable
private fun TypewriterText(shown: String) {
    Text(
        shown,
        color = Color(0xFF2E2E2E),
        fontSize = 16.sp,
        lineHeight = 24.sp,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 入场三段飞入:三块多边形共用同一张完整图片,边界向相反方向倾斜。
 * 等图片加载完成后依次从左、右、左飞入,全部落位后才回调 [onDone]。
 */
@Composable
private fun SliceEntranceImage(imageFile: String, onDone: () -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val w = maxWidth
        val progress = remember(imageFile) { List(3) { Animatable(0f) } }
        val painter = rememberAsyncImagePainter(model = remember(imageFile) { java.io.File(imageFile) })
        val painterState by painter.state.collectAsState()
        val imageReady = painterState is AsyncImagePainter.State.Success
        val imageFailed = painterState is AsyncImagePainter.State.Error
        LaunchedEffect(imageFile, imageReady, imageFailed) {
            if (imageFailed) {
                onDone()
                return@LaunchedEffect
            }
            if (!imageReady) return@LaunchedEffect
            for (slice in progress) {
                slice.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
            }
            delay(80)
            onDone()
        }
        Box(Modifier.fillMaxSize().clipToBounds().testTag(if (imageReady) "innerVoiceImageReady" else "innerVoiceImageLoading")) {
            repeat(3) { i ->
                val sliceShape = remember(i) {
                    GenericShape { size, _ ->
                        // Two shared boundaries ensure that the three pieces meet exactly.
                        val topLeft = when (i) { 0 -> 0f; 1 -> 0.22f; else -> 0.61f } * size.height
                        val topRight = when (i) { 0 -> 0f; 1 -> 0.28f; else -> 0.57f } * size.height
                        val bottomLeft = when (i) { 0 -> 0.22f; 1 -> 0.61f; else -> 1f } * size.height
                        val bottomRight = when (i) { 0 -> 0.28f; 1 -> 0.57f; else -> 1f } * size.height
                        moveTo(0f, topLeft)
                        lineTo(size.width, topRight)
                        lineTo(size.width, bottomRight)
                        lineTo(0f, bottomLeft)
                        close()
                    }
                }
                Image(
                    painter = painter,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("innerVoiceSlice$i")
                        .graphicsLayer {
                            val p = progress[i].value
                            translationX = (if (i == 1) 1f else -1f) * w.toPx() * (1f - p)
                            alpha = p.coerceIn(0f, 1f)
                            shape = sliceShape
                            clip = true
                        },
                )
            }
        }
    }
}

/** 无涟漪点击(项目惯例:interactionSource + indication=null)。 */
private fun Modifier.noRippleClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = MutableInteractionSource(),
        indication = null,
        enabled = enabled,
        onClick = onClick,
    )
