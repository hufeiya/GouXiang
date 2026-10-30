package com.neethu.gouxiang.debug

/**
 * AI 原生调试协议(docs/ai-debug-intents.md §4.1,移植自 AIAvatar-SDK 的
 * AiDebug.kt)。
 *
 * LLM 代理通过 `adb shell am start` 给 [com.neethu.gouxiang.MainActivity] 挂
 * 以下 extras 下发命令;命令入队、UI 就绪后执行,结果逐条打在 [AI_LOG_TAG]
 * 标签下,代理用 `adb logcat -d -s AIDebug` 读回形成闭环:
 *
 * ```
 * adb shell am start -n com.neethu.gouxiang/.MainActivity --es ai_cmd state
 * adb logcat -d -s AIDebug
 * ```
 *
 * 完整命令表:`docs/ai-debug-intents.md` §5(也可 `ai_cmd=help` 打到 logcat)。
 */

/** logcat 固定标签(与 AIAvatar-SDK 同名,工具链/脚本/肌肉记忆通用)。 */
const val AI_LOG_TAG = "AIDebug"

const val EXTRA_AI_CMD = "ai_cmd"
const val EXTRA_AI_ARG = "ai_arg"
const val EXTRA_AI_X = "ai_x"
const val EXTRA_AI_Y = "ai_y"
const val EXTRA_AI_Z = "ai_z"
const val EXTRA_AI_WEIGHT = "ai_weight"
const val EXTRA_AI_LOOP = "ai_loop"

/** 全部 [EXTRA_AI_*] 键(用于识别「带了 ai extras 但命令缺失」的畸形 Intent)。 */
val AI_EXTRA_KEYS = listOf(
    EXTRA_AI_CMD, EXTRA_AI_ARG, EXTRA_AI_X, EXTRA_AI_Y, EXTRA_AI_Z,
    EXTRA_AI_WEIGHT, EXTRA_AI_LOOP,
)

/**
 * 一条解析后的调试命令。
 *
 * @param name  命令名(`ai_cmd`),不分大小写。
 * @param arg   字符串参数(`ai_arg`):消息文本、会话 id/角色名、tab 名、
 *              风格名、卡片描述等,按命令语义复用。
 * @param x/y/z 数值参数(`ai_x`/`ai_y`/`ai_z`):离线时长毫秒、条数上限、
 *              消息 id 等,按命令语义复用。
 * @param weight 权重 0..1(`ai_weight`);本项目暂无消费命令,保留解析不消费。
 * @param loop  循环开关(`ai_loop`);本项目暂无消费命令,保留解析不消费。
 */
data class AiDebugCommand(
    val name: String,
    val arg: String? = null,
    val x: Float? = null,
    val y: Float? = null,
    val z: Float? = null,
    val weight: Float? = null,
    val loop: Boolean = true,
)

/**
 * 从 Intent extras(展平成 map)解析命令。无有效 [EXTRA_AI_CMD] 时返回 null。
 *
 * LLM 手误容错(解析层兜底,执行层不背锅):数值 extra 传成字符串也能解析
 * (`--es ai_x 200`),布尔接受 `true/on/1` 与 `false/off/0`。
 */
fun parseAiDebugCommand(extras: Map<String, Any?>): AiDebugCommand? {
    val name = (extras[EXTRA_AI_CMD] as? String)?.trim()?.lowercase().orEmpty()
    if (name.isEmpty()) return null
    return AiDebugCommand(
        name = name,
        arg = (extras[EXTRA_AI_ARG] as? String)?.trim()?.ifEmpty { null },
        x = extras[EXTRA_AI_X].asFloat(),
        y = extras[EXTRA_AI_Y].asFloat(),
        z = extras[EXTRA_AI_Z].asFloat(),
        weight = extras[EXTRA_AI_WEIGHT].asFloat()?.coerceIn(0f, 1f),
        loop = extras[EXTRA_AI_LOOP].asBoolean() ?: true,
    )
}

private fun Any?.asFloat(): Float? = when (this) {
    null -> null
    is Number -> toFloat()
    is String -> toFloatOrNull()
    else -> null
}

private fun Any?.asBoolean(): Boolean? = when (this) {
    null -> null
    is Boolean -> this
    is String -> when (lowercase()) {
        "true", "on", "1" -> true
        "false", "off", "0" -> false
        else -> null
    }
    else -> null
}

/** 完整命令表(`help` 命令打到 logcat 的文本)。 */
fun aiDebugHelp(): String = """
    |AI debug interface — intent extras: ai_cmd, ai_arg, ai_x (数值参数按命令复用)
    |
    |Query:
    |  help                     Print this help
    |  state                    One-line snapshot: tab / chat(id,phone,角色,sending,
    |                           failedTurn) / style mode+length / ai 配置(密钥只出
    |                           已配[len=N]) / 未读角标
    |  msgs [ai_x=N]            当前会话最近 N 条(默认20): id turn role kind 前80字
    |  contexts                 会话列表: id mode 角色名 未读 摘要 时间
    |  open_chat <arg>          会话id 或 角色名;ai_x=1 表 phone 模式进入
    |  open_tab <arg>           home | messages | profile
    |  ai_config                已解析配置快照(provider/模型/密钥掩码长度)
    |Chat (send_chat/send_card 会真实产生消息与 LLM 请求,不要循环重试成功过的命令):
    |  send_chat <arg>          发一条用户消息(入队即 OK;回合结果用 msgs 轮询确认)
    |  send_card <arg>          发卡片: "<类型> [标题|备注]" 如 转账 520|谢谢你;
    |                           用户侧落库 kind=card(需在手机聊天页)
    |  parse <arg>              纯协议解析探针(不落库不请求):逐段回显 type+内容前20字
    |  retry                    重试失败回合;无失败回合时 FAIL
    |  rewind <ai_x=id|arg=last>回溯到该消息(删除该条及之后);验收联动删记忆用
    |                           memory_list 前后对拍
    |Proactive (等价长按「…」手势,免等 2h):
    |  proactive                只读探针: 最近消息年龄 + pickTrigger 判定(kind|null)
    |  proactive_fire <arg>     hello | check_in | share;arg=offline 时 ai_x=模拟
    |                           离线毫秒(默认2h)
    |Memory:
    |  memory_list [arg]        当前角色记忆: id [分类] 内容前60字 (时间);arg=过滤词
    |  memory_stats             总数/分类分布/当前角色
    |  memory_forget <arg>      按 id 前缀遗忘一条;歧义时列出候选
    |  memory_summarize         立即总结(不等 5 轮阈值;会真实请求 LLM)
    |Style:
    |  status                   当前会话状态条(StatusRule): emoji+text+判定依据
    |  set_style <arg>          heartbeat | delicate | long_dialog | deep_think | basic
    |  set_length <arg>         shorter | medium | longer
    |
    |Example:
    |  adb shell am start -n com.neethu.gouxiang/.MainActivity --es ai_cmd state
    |  adb logcat -d -s AIDebug
""".trimMargin()
