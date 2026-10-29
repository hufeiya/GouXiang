package com.neethu.gouxiang.chat

import com.neethu.gouxiang.protocol.MultiBubbleParser

/**
 * 对话风格域模型(角色主页 04 宫格「对话模式/对话风格」弹层,基准 09/10):
 *
 * - [DialogMode] 对话模式五项(弹层 09):选择真实生效 = 给 system prompt 附加
 *   对应行为段([DialogMode.promptBlock]);长对话模式额外放大连发字数参数。
 *   本地版全部免费,无任何计费门禁。
 * - [ReplyLength] 回复长度三段(弹层 10):真实影响 F2 预留的连发参数
 *   ([BurstParams] min/max 条数 + 每条字数)。
 * - [ChatStyleParams] 一回合实际下发的组合(回合发起时从 [ChatPrefs] 现读)。
 *
 * 兼容性红线:默认组合(心动模式 + 适中)= F2/F4 既定行为 —— IM 连发 3 条 ×30 字、
 * 手机聊天 2~4 条 ×30 字,与弹层上线前的 prompt 逐字节一致。
 */

/**
 * 对话模式(基准 09-dialog-mode.xml 五行:title/desc 为 dump 文案,顺序一致)。
 * [promptBlock] 拼进 system prompt(在 [当前形态] 行之后、[记忆] 块之前)。
 */
enum class DialogMode(
    val title: String,
    val desc: String,
    val promptBlock: String,
) {
    HEARTBEAT(
        "心动模式",
        "增强恋爱拉扯和情感张力",
        "[对话模式] 心动模式:恋爱心动感拉满。会撒娇、会试探、会脸红,主动制造暧昧和情绪拉扯," +
            "期待回应但不过分讨好;情绪起伏写在字里行间。",
    ),
    DELICATE(
        "细腻模式",
        "增强细节描写,情感表现细腻",
        "[对话模式] 细腻模式:细节控。() 动作与场景描写更丰富细腻(神态、小动作、氛围)," +
            "情感表达真挚有层次,善于捕捉情绪的微妙变化。",
    ),
    LONG_DIALOG(
        "长对话模式",
        "超长文本输出,沉浸内容体验",
        "[对话模式] 长对话模式:超长文本输出,沉浸式内容体验。把想说的展开讲透,内容更充分," +
            "但仍保持真人聊天的节奏感,不写成作文。",
    ),
    DEEP_THINK(
        "深度思考模式",
        "限定模式,回复等待时间长",
        "[对话模式] 深度思考模式:限定模式,回复等待时间长。先在心里把事情想透再开口," +
            "回复更有条理、更有洞察,语气沉稳,偶尔给出让人意外的思考角度。",
    ),
    BASIC(
        "基础模式",
        "原灵动模式,角色聪明灵动",
        "[对话模式] 基础模式:原灵动模式,角色聪明灵动。回复轻快直接、反应快," +
            "少铺垫不绕弯,像机灵的真人朋友。",
    ),
    ;

    companion object {
        /** 默认选中项(基准 09:心动模式带勾;主页宫格副标题同步展示)。 */
        val DEFAULT = HEARTBEAT
    }
}

/** 回复长度三段(基准 10-dialog-style.xml 分段控件:更短/适中/更长)。 */
enum class ReplyLength(val title: String) {
    SHORTER("更短"),
    MEDIUM("适中"),
    LONGER("更长"),
    ;

    companion object {
        /** 默认「适中」= F2 既定连发参数,行为零回归。 */
        val DEFAULT = MEDIUM
    }
}

/**
 * 一回合的连发参数(条数区间 + 每条字数上限):
 * - min == max(固定条数)= IM 陪伴聊天语义(「连发 N 条」);
 * - min < max(区间)= 手机聊天语义(「连发 N~M 条」)。
 */
data class BurstParams(
    val minBubbles: Int,
    val maxBubbles: Int,
    val maxBubbleChars: Int,
) {
    companion object {

        /**
         * 长度 → 连发参数映射(真实影响 prompt 的硬规则与字数约束):
         * - IM:更短 2 条×18 字 / 适中 3 条×30 字(F2 默认)/ 更长 4 条×60 字;
         * - 手机:更短 2~3 条×16 字 / 适中 2~4 条×30 字(F4 默认)/ 更长 3~4 条×45 字。
         * 长对话模式叠加:每条字数上限翻倍(封顶 90)、连发顶格 4 条(手机至少 3 条)。
         */
        fun resolve(length: ReplyLength, phone: Boolean, mode: DialogMode): BurstParams {
            val base = if (phone) {
                when (length) {
                    ReplyLength.SHORTER -> BurstParams(2, 3, 16)
                    ReplyLength.MEDIUM -> BurstParams(2, 4, 30)
                    ReplyLength.LONGER -> BurstParams(3, 4, 45)
                }
            } else {
                when (length) {
                    ReplyLength.SHORTER -> BurstParams(2, 2, 18)
                    ReplyLength.MEDIUM -> BurstParams(3, 3, 30)
                    ReplyLength.LONGER -> BurstParams(4, 4, 60)
                }
            }
            if (mode != DialogMode.LONG_DIALOG) return base
            return BurstParams(
                minBubbles = if (phone) 3 else base.maxBubbles,
                maxBubbles = MultiBubbleParser.MAX_BUBBLES,
                maxBubbleChars = (base.maxBubbleChars * 2).coerceAtMost(90),
            )
        }
    }
}

/** 单泡长文兜底切分的条数上限(F2 既定 3;独立常量,切分不再随回合参数变化)。 */
const val DEFAULT_SPLIT_MAX = 3

/** 一回合实际下发的对话风格组合(主 ViewModel 回合发起时从 [ChatPrefs] 现读)。 */
data class ChatStyleParams(
    val mode: DialogMode = DialogMode.DEFAULT,
    val length: ReplyLength = ReplyLength.DEFAULT,
)
