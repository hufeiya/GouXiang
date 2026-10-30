package com.neethu.gouxiang.ui.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「小手机使用说明」页(基准 13c-phone-chat-guide.png/13c-phone-chat-guide.xml):
 *
 * dump 结构:title_bar_container(iv_back + tv_title「小手机使用说明」)+
 * bullet_container(WebView 内容区)。参考图内容分两块:「小手机扣费规则说明」
 * (扣费模式/免费聊天次数/非扣费消息)与「小手机功能更新」(信息支持回溯)。
 *
 * 本地化改写(范围 2 明确要求:本地版无付费,不得出现敏感商业字样):
 * - 标题与顶栏结构照搬 dump;正文改为「免费说明」三则 + 「小手机功能更新」
 *   保留长按回溯/重新生成说明(13c 参考图同样有「长按选择回溯/重新生成」条目);
 * - 原图猫形象/黄底 banner 为美术资源,本地版以黄底 + emoji 简化占位。
 */
@Composable
fun PhoneChatGuideScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    // 系统返回键退出(F4 验收 low 修复:此前只能点左上「‹」)
    BackHandler { onBack() }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding(),
    ) {
        // 顶栏:返回 + 居中标题(对齐 dump iv_back + tv_title 居中)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.ArrowBackIosNew,
                contentDescription = "返回",
                tint = Color(0xFF1A1A1A),
                modifier = Modifier
                    .size(22.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onBack() },
            )
            Spacer(Modifier.width(0.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "小手机使用说明",
                    color = Color(0xFF1A1A1A),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(22.dp)) // 平衡左侧返回键宽,标题视觉居中
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(20.dp))
            // 黄底 banner(参考图美术资源的本地简化占位)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(Color(0xFFF7E733), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📱✨", fontSize = 40.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "小手机\n使用说明",
                        color = Color(0xFF1A1A1A),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        lineHeight = 28.sp,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            SectionTitle("小手机免费说明:")
            GuideItem(1, "完全免费", "小手机里发送消息、收发语音条与卡片,全部免费,不消耗任何资源。")
            GuideItem(2, "回复不限次", "想聊多少条都行,没有每日次数限制,也没有任何付费项目。")
            GuideItem(3, "主动消息免费", "Ta 主动发来的小手机消息,同样不消耗任何东西。")
            Spacer(Modifier.height(24.dp))
            SectionTitle("小手机功能更新:")
            GuideStarItem(
                "小手机信息支持回溯",
                "长按任意一条小手机消息,可以选择回溯(删掉这段对话)或重新生成(让 Ta 重新说一次)。",
            )
            GuideStarItem(
                "更真实的连发",
                "Ta 会把一句话拆成几条连发,也会发语音条,像真人一样聊天。",
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = Color(0xFF1A1A1A),
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(16.dp))
}

/** 黄圈数字条目(对齐 13c 参考图 1/2/3 黄圈编号样式)。 */
@Composable
private fun GuideItem(number: Int, title: String, body: String) {
    Row(Modifier.padding(bottom = 20.dp)) {
        Box(
            Modifier
                .size(24.dp)
                .background(Color(0xFFF7E733), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", color = Color(0xFF1A1A1A), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = Color(0xFF1A1A1A), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(body, color = Color(0xFF6B6B6B), fontSize = 14.sp, lineHeight = 21.sp)
        }
    }
}

/** ✨ 星形条目(对齐 13c 参考图功能更新区样式)。 */
@Composable
private fun GuideStarItem(title: String, body: String) {
    Row(Modifier.padding(bottom = 20.dp)) {
        Text("✨", fontSize = 15.sp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, color = Color(0xFF1A1A1A), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(body, color = Color(0xFF6B6B6B), fontSize = 14.sp, lineHeight = 21.sp)
        }
    }
}
