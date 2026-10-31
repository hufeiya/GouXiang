package com.neethu.gouxiang.ui.hall

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.neethu.gouxiang.data.CatalogCharacter

private val Yellow = Color(0xFFFFE600)
private val Ink = Color(0xFF171717)

/** 搜索和分类均在本地目录上执行；点击卡片后先查看简介，再决定是否开始聊天。 */
@Composable
fun CharacterHallScreen(
    characters: List<CatalogCharacter>,
    onBack: () -> Unit,
    onStartChat: (CatalogCharacter) -> Unit,
    initialCharacter: CatalogCharacter? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf("全部") }
    var selectedId by rememberSaveable(initialCharacter?.sourceId) { mutableStateOf(initialCharacter?.sourceId) }
    val selected = characters.firstOrNull { it.sourceId == selectedId }
    BackHandler {
        if (selectedId != null) selectedId = null else onBack()
    }
    if (selected != null) {
        CatalogDetail(selected, onBack = { selectedId = null }, onStartChat = { onStartChat(selected) })
        return
    }

    val categories = listOf("全部", "都市", "校园", "古风", "情感")
    val results = remember(characters, query, selectedCategory) {
        val keyword = query.trim()
        characters.filter { character ->
            val text = listOf(character.name, character.intro, character.profile, character.creatorName).joinToString(" ")
            (keyword.isEmpty() || text.contains(keyword, ignoreCase = true)) &&
                (selectedCategory == "全部" || categoryKeywords.getValue(selectedCategory).any { text.contains(it, ignoreCase = true) })
        }
    }
    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Ink)
            }
            Row(
                Modifier.weight(1f).height(48.dp).border(2.dp, Ink, RoundedCornerShape(28.dp)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = Ink, modifier = Modifier.size(23.dp))
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = TextStyle(color = Ink, fontSize = 16.sp),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner -> Box { if (query.isEmpty()) Text("搜索感兴趣的内容", color = Color.Gray, fontSize = 16.sp); inner() } },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            categories.forEach { category ->
                val active = category == selectedCategory
                Box(
                    Modifier.background(if (active) Yellow else Color.White, RoundedCornerShape(14.dp))
                        .border(1.dp, if (active) Yellow else Color(0xFFE2E2E2), RoundedCornerShape(14.dp))
                        .clickable { selectedCategory = category }
                        .padding(horizontal = 20.dp, vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(category, color = Ink, fontSize = 16.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium) }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (characters.isEmpty()) "人物目录为空" else "没有找到相关人物，试试其他关键词或分类",
                    color = Color.Gray, fontSize = 14.sp,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(results, key = { it.sourceId }) { character ->
                    CatalogCard(character) { selectedId = character.sourceId }
                }
            }
        }
    }
}

// 样本的数字标签没有可验证的含义，筛选仅依据公开文本中的词。
private val categoryKeywords = mapOf(
    "都市" to listOf("都市", "公司", "总裁", "职场", "老板", "邻居"),
    "校园" to listOf("校园", "学校", "大学", "同学", "老师", "学生"),
    "古风" to listOf("古代", "古风", "皇帝", "王爷", "公主", "仙", "宗门", "修仙"),
    "情感" to listOf("陪伴", "恋人", "喜欢", "恋爱", "温柔", "相遇"),
)

@Composable
private fun CatalogCard(character: CatalogCharacter, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(0.68f).background(Color(0xFF3A3B44)).clickable(onClick = onClick)) {
        AsyncImage(
            model = "file:///android_asset/${character.portraitAsset}", contentDescription = character.name,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.78f))))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp)) {
            Text(character.name, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(character.intro, color = Color.White, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (character.creatorName.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("@${character.creatorName}", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CatalogDetail(character: CatalogCharacter, onBack: () -> Unit, onStartChat: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xFFFAFAF8))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 88.dp)) {
            Box(Modifier.fillMaxWidth().height(420.dp).background(Color(0xFF3A3B44))) {
                AsyncImage(
                    model = "file:///android_asset/${character.portraitAsset}", contentDescription = character.name,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.72f))))
                Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                    Text(character.name, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    if (character.creatorName.isNotBlank()) Text("@${character.creatorName}", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                }
            }
            Column(Modifier.padding(20.dp)) {
                Text("人物介绍", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(character.intro.ifBlank { "暂无介绍" }, color = Color(0xFF444444), fontSize = 15.sp, lineHeight = 23.sp)
                if (character.profile.isBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text("此人物的完整设定未公开，聊天时将以公开简介作为角色背景。", color = Color(0xFF777777), fontSize = 13.sp, lineHeight = 19.sp)
                }
                if (character.openingLine.isNotBlank()) {
                    Spacer(Modifier.height(24.dp))
                    Text("开场白", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Text(character.openingLine, color = Color(0xFF444444), fontSize = 15.sp, lineHeight = 23.sp)
                }
            }
        }
        IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(8.dp).background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(50))) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
        }
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)
                .height(50.dp).background(Yellow, RoundedCornerShape(25.dp)).clickable(onClick = onStartChat),
            contentAlignment = Alignment.Center,
        ) { Text("开始聊天", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
    }
}
