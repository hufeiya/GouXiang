package com.neethu.gouxiang.ui.status

import androidx.compose.runtime.Immutable
import com.neethu.gouxiang.data.CharacterStatusEntity
import com.neethu.gouxiang.data.CharacterStatusDetailEntity
import java.util.Calendar

/**
 * 状态面板(E 工作包 E3)的 UI 状态:当前状态卡 + 近期记录时间轴。
 * 记录按生成时间倒序,首条 active 记录即当前状态(来源与时间随行展示)。
 */
@Immutable
data class StatusPanelUiState(
    val characterName: String = "",
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    /** 近期记录(生成时间倒序;含失效行,面板分「状态记录/失效记录」两区)。 */
    val rows: List<StatusRow> = emptyList(),
    val selectedId: Long? = null,
    val detail: CharacterStatusDetailEntity? = null,
    val detailLoading: Boolean = false,
    val detailFailed: Boolean = false,
) {
    /** 当前置顶卡(最新 active;null = 无记录,提示规则兜底口径)。 */
    val current: StatusRow? get() = rows.firstOrNull { !it.stale }

    /** 已随回溯失效的记录(冲突区)。 */
    val staleRows: List<StatusRow> get() = rows.filter { it.stale }

    val empty: Boolean get() = rows.isEmpty()
}

/** 面板单行(渲染字段 + 来源/时间文案;构造在 VM 侧,纯函数单测锁文案)。 */
@Immutable
data class StatusRow(
    val id: Long,
    val emoji: String,
    val mood: String,
    val doing: String,
    val thought: String,
    val timeLabel: String,
    val sourceLabel: String,
    val stale: Boolean,
)

/**
 * 来源文案(E3「可查看近期状态与来源」):turn=来自聊天回合(带消息区间);
 * manual=手动注入(调试);其余=规则兜底。
 */
fun statusSourceLabel(record: CharacterStatusEntity): String {
    val base = when (record.sourceKind) {
        CharacterStatusEntity.SOURCE_TURN -> {
            val start = record.sourceStartMessageId
            val end = record.sourceEndMessageId
            when {
                start != null && end != null && start != end -> "来自聊天回合(消息 #$start~#$end)"
                end != null -> "来自聊天回合(消息 #$end)"
                else -> "来自聊天回合"
            }
        }
        CharacterStatusEntity.SOURCE_MANUAL -> "手动注入(调试)"
        else -> "规则兜底"
    }
    return if (record.isStale) "$base · 已随回溯失效" else base
}

/** 生成时间文案:今天 HH:mm / 昨天 HH:mm / M月d日 HH:mm / yyyy年M月d日。 */
fun statusTimeLabel(generatedAt: Long, nowMs: Long = System.currentTimeMillis()): String {
    val cal = Calendar.getInstance().apply { timeInMillis = generatedAt }
    val now = Calendar.getInstance().apply { timeInMillis = nowMs }
    val time = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    return when {
        sameDay(cal, now) -> "今天 $time"
        isYesterday(cal, now) -> "昨天 $time"
        cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) ->
            "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日 $time"
        else -> "${cal.get(Calendar.YEAR)}年${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日 $time"
    }
}

private fun sameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

/** 跨年「昨天」判定(dayKey 差值在跨年会误判,按日期字段回退一天比对)。 */
private fun isYesterday(cal: Calendar, now: Calendar): Boolean {
    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    return cal.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
}

/** 数据库行 → 面板行(VM 与测试共用同一构造路径)。 */
fun toStatusRow(record: CharacterStatusEntity, nowMs: Long = System.currentTimeMillis()): StatusRow =
    StatusRow(
        id = record.id,
        emoji = record.emoji,
        mood = record.mood,
        doing = record.doing,
        thought = record.thought,
        timeLabel = statusTimeLabel(record.generatedAt, nowMs),
        sourceLabel = statusSourceLabel(record),
        stale = record.isStale,
    )
