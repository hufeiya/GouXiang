package com.neethu.gouxiang.ui.derivatives

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.neethu.gouxiang.data.ChatDerivativeEntity
import com.neethu.gouxiang.derivatives.ChatDerivativeOutput
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

private val Ink = Color(0xFF594637)
private val Rose = Color(0xFFD56E81)
private val Paper = Color(0xFFFFF9F2)

/** A page contains whole paragraphs; overflowing content remains readable by vertical scrolling. */
private data class JournalPage(val paragraphs: List<ChatDerivativeOutput.Paragraph>)

private fun journalPages(paragraphs: List<ChatDerivativeOutput.Paragraph>): List<JournalPage> {
    if (paragraphs.isEmpty()) return listOf(JournalPage(emptyList()))
    val pages = mutableListOf(JournalPage(listOf(paragraphs.first())))
    var index = 1
    while (index < paragraphs.size) {
        val count = if (index + 1 < paragraphs.size &&
            paragraphs[index].body.length + paragraphs[index + 1].body.length <= 220) 2 else 1
        pages += JournalPage(paragraphs.subList(index, index + count))
        index += count
    }
    return pages
}

@Composable
internal fun HandbookReader(
    record: ChatDerivativeEntity,
    characterName: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryImage: (() -> Unit)? = null,
) {
    BackHandler(onBack = onClose)
    key(record.id) {
        val handbook = remember(record.bodyJson) { handbookOf(record) }
        val pages = remember(handbook) { journalPages(handbook?.paragraphs.orEmpty()) }
        val pager = rememberPagerState(pageCount = { pages.size })
        val scope = rememberCoroutineScope()
        var contentsOpen by remember { mutableStateOf(false) }
        Box(
            modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(Color(0xFFE9949B), Color(0xFFF4B0A8), Color(0xFFD98591)))
            ),
        ) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(42.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.ArrowBackIosNew, contentDescription = "返回", tint = Color.White, modifier = Modifier.size(21.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "☰ 目录", color = Color(0xFFFFF9F2), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF7B4A3B))
                            .clickable { contentsOpen = true }.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(start = 17.dp, end = 17.dp, bottom = 20.dp)) {
                    // A thin stack of warm paper beneath the active sheet.
                    Box(Modifier.fillMaxSize().padding(start = 7.dp, top = 7.dp)
                        .background(Color(0xFFD7B899), RoundedCornerShape(23.dp)))
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                        val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(-1f, 1f)
                        Box(
                            Modifier.fillMaxSize().padding(end = 6.dp, bottom = 7.dp)
                                .graphicsLayer {
                                    rotationY = offset * -13f
                                    cameraDistance = 16 * density
                                    transformOrigin = TransformOrigin(if (offset > 0) 0f else 1f, 0.5f)
                                    alpha = 1f - offset.absoluteValue * 0.13f
                                }
                                .clip(RoundedCornerShape(22.dp)).background(Paper),
                        ) {
                            PaperGrid()
                            Column(Modifier.fillMaxSize().padding(start = 29.dp, end = 20.dp, top = 17.dp, bottom = 10.dp)) {
                                Column(
                                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                                ) {
                                    if (page == 0) {
                                        Spacer(Modifier.height(12.dp))
                                        Text(
                                            handbook?.title?.ifBlank { record.title } ?: record.title.ifBlank { "一页手账" },
                                            color = Rose, fontSize = 24.sp, lineHeight = 33.sp,
                                            fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
                                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                                        )
                                        val subtitle = handbook?.subtitle.orEmpty()
                                        if (subtitle.isNotBlank()) {
                                            Text(subtitle, color = Ink.copy(alpha = .75f), fontSize = 14.sp,
                                                lineHeight = 22.sp, fontFamily = FontFamily.Serif,
                                                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                                        }
                                        Text(
                                            listOfNotNull(characterName.takeIf { it.isNotBlank() }?.let { "$it 的手账" },
                                                handbook?.mood?.takeIf { it.isNotBlank() }?.let { "心情 · $it" }).joinToString("  /  "),
                                            color = Color(0xFFAF8C7D), fontSize = 11.sp, textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth().padding(top = 9.dp, bottom = 18.dp),
                                        )
                                        if (record.imageFile.isNotBlank()) JournalImage(record.imageFile)
                                        else if (record.imageStatus == ChatDerivativeEntity.IMAGE_PENDING || record.imageStatus == ChatDerivativeEntity.IMAGE_FAILED) {
                                            Row(Modifier.fillMaxWidth().padding(bottom = 15.dp), horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically) {
                                                Text(if (record.imageStatus == ChatDerivativeEntity.IMAGE_PENDING) "插图正在绘制…" else "插图生成失败",
                                                    fontSize = 12.sp, color = Ink.copy(alpha = .6f))
                                                if (record.imageStatus == ChatDerivativeEntity.IMAGE_FAILED && onRetryImage != null) {
                                                    Text("  重试配图", color = Rose, fontSize = 12.sp,
                                                        modifier = Modifier.clickable { onRetryImage() })
                                                }
                                            }
                                        }
                                    } else {
                                        Text("MEMORIES  /  ${"%02d".format(page + 1)}", color = Rose.copy(alpha = .75f),
                                            fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 7.dp, bottom = 20.dp))
                                    }
                                    if (pages[page].paragraphs.isEmpty()) {
                                        Text("这篇手账暂时没有内容", color = Ink.copy(alpha = .6f), fontSize = 14.sp)
                                    }
                                    pages[page].paragraphs.forEach { paragraph ->
                                        MarkedParagraph(paragraph)
                                        Spacer(Modifier.height(24.dp))
                                    }
                                    if (record.isStale) Text("对话已回溯，内容可能已过时", color = Rose, fontSize = 11.sp)
                                }
                                Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("由 AI 生成", color = Color(0xFFBEADA2), fontSize = 10.sp)
                                    Spacer(Modifier.weight(1f))
                                    if (page > 0) PageButton("← 上一页") { scope.launch { pager.animateScrollToPage(page - 1) } }
                                    Text(" ${page + 1} / ${pages.size} ", color = Color(0xFFAC9687), fontSize = 11.sp)
                                    if (page < pages.lastIndex) PageButton("下一页 →") { scope.launch { pager.animateScrollToPage(page + 1) } }
                                }
                            }
                            BindingRings(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(20.dp))
                        }
                    }
                }
            }
        }
        if (contentsOpen) {
            Dialog(onDismissRequest = { contentsOpen = false }) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Paper).padding(22.dp)) {
                    Text("目录", color = Ink, fontSize = 21.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
                    Text("翻开属于你们的回忆", color = Color(0xFFAE8F80), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                    Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                        pages.forEachIndexed { index, item ->
                            val label = if (index == 0) handbook?.title?.ifBlank { record.title }.orEmpty().ifBlank { "扉页" }
                            else item.paragraphs.firstOrNull()?.body?.trim()?.take(18).orEmpty().ifBlank { "回忆" }
                            Row(Modifier.fillMaxWidth().clickable {
                                contentsOpen = false
                                scope.launch { pager.animateScrollToPage(index) }
                            }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${"%02d".format(index + 1)}", color = Rose, fontSize = 13.sp)
                                Text(label, color = Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(horizontal = 14.dp))
                                Text("›", color = Color(0xFFBEA697), fontSize = 18.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageButton(label: String, onClick: () -> Unit) {
    Text(label, color = Ink.copy(alpha = .75f), fontSize = 12.sp,
        modifier = Modifier.border(1.dp, Color(0xFFDCCCBF), RoundedCornerShape(50)).clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp))
}

@Composable
private fun JournalImage(file: String) {
    Box(Modifier.fillMaxWidth().padding(bottom = 23.dp).graphicsLayer { rotationZ = -1f }
        .background(Color(0xFFEFD9B5), RoundedCornerShape(5.dp)).padding(9.dp)) {
        AsyncImage(model = java.io.File(file), contentDescription = "手账插图", contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(2.dp)))
        Text("✦", color = Color(0xFFD88083), fontSize = 24.sp, modifier = Modifier.align(Alignment.TopEnd).offset(x = 9.dp, y = (-15).dp))
    }
}

@Composable
private fun PaperGrid() {
    Canvas(Modifier.fillMaxSize()) {
        val spacing = 24.dp.toPx()
        var x = 0f
        while (x < size.width) { drawLine(Color(0xFFF2DDD8).copy(alpha = .35f), Offset(x, 0f), Offset(x, size.height), 1f); x += spacing }
        var y = 0f
        while (y < size.height) { drawLine(Color(0xFFF2DDD8).copy(alpha = .35f), Offset(0f, y), Offset(size.width, y), 1f); y += spacing }
    }
}

@Composable
private fun BindingRings(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        for (i in 1..6) {
            val y = size.height * i / 7f
            drawCircle(Color(0xFF987767), 5.dp.toPx(), Offset(size.width - 3.dp.toPx(), y))
            drawRoundRect(Color(0xFFC6AB93), Offset(0f, y - 2.dp.toPx()), Size(size.width - 2.dp.toPx(), 4.dp.toPx()), CornerRadius(3.dp.toPx()))
        }
    }
}

@Composable
private fun MarkedParagraph(paragraph: ChatDerivativeOutput.Paragraph) {
    var layout by remember(paragraph.body) { mutableStateOf<TextLayoutResult?>(null) }
    val highlight = remember(paragraph) { paragraph.body.indexOf(paragraph.highlight).takeIf { paragraph.highlight.isNotBlank() && it >= 0 } }
    val circle = remember(paragraph) { paragraph.body.indexOf(paragraph.circle).takeIf { paragraph.circle.isNotBlank() && it >= 0 } }
    Text(
        paragraph.body, color = Ink, fontSize = 16.sp, lineHeight = 29.sp, fontFamily = FontFamily.Serif,
        onTextLayout = { layout = it },
        modifier = Modifier.fillMaxWidth().drawWithContent {
            fun mark(start: Int?, length: Int, outlined: Boolean) {
                val result = layout ?: return
                if (start == null || length == 0) return
                val end = (start + length).coerceAtMost(paragraph.body.length)
                if (start >= end) return
                val firstLine = result.getLineForOffset(start)
                val lastLine = result.getLineForOffset(end - 1)
                for (line in firstLine..lastLine) {
                    val lineStart = maxOf(start, result.getLineStart(line))
                    val lineEnd = minOf(end, result.getLineEnd(line, visibleEnd = true))
                    if (lineStart >= lineEnd) continue
                    val first = result.getBoundingBox(lineStart)
                    val last = result.getBoundingBox(lineEnd - 1)
                    val left = minOf(first.left, last.left) - 2.dp.toPx()
                    val right = maxOf(first.right, last.right) + 2.dp.toPx()
                    if (outlined) {
                        drawRoundRect(Rose, Offset(left, first.top + 2.dp.toPx()), Size(right - left, first.height - 4.dp.toPx()),
                            CornerRadius(12.dp.toPx()), style = Stroke(width = 1.5.dp.toPx()))
                    } else {
                        drawRoundRect(Color(0xFFF3AAB8).copy(alpha = .6f), Offset(left, first.bottom - 9.dp.toPx()),
                            Size(right - left, 7.dp.toPx()), CornerRadius(3.dp.toPx()))
                    }
                }
            }
            mark(highlight, paragraph.highlight.length, false)
            drawContent()
            mark(circle, paragraph.circle.length, true)
        },
    )
}
