package com.neethu.gouxiang.ui.character

import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.protocol.MultiBubbleParser
import kotlinx.serialization.Serializable

/**
 * 角色创建/编辑表单的数据与校验(C 工作包 C1)。
 *
 * 字段用途差异遵循 01-character-card.md §1.2 的核心结论(与 [CharacterEntity]
 * 的注释同源):profile 进对话 prompt、用「用户」称呼对话者;intro 是公开展示
 * 文案、用「你」称呼;exampleLines 是 few-shot 演示;openingLine 是首条消息、
 * () 描述动作;appearance 喂生图模型(风格词缀独立存)。
 *
 * 校验规则对齐猫箱 CheckResultType 的可用子集:必填缺失 / 仅空格 / 前后空格 /
 * 字数超限 / 名称重复(01-character-card.md §1.3);错误文案常量集中在
 * [CharacterFormCopy],文案红线单测(敏感商业字样)对它逐条扫描。
 */

/** 表单草稿(新建/编辑共用;id=0 表示新建,>0 为被编辑的角色行 id)。 */
@Serializable
data class CharacterDraft(
    val id: Long = 0,
    val name: String = "",
    val profile: String = "",
    val intro: String = "",
    val exampleLines: String = "",
    val openingLine: String = "",
    val appearance: String = "",
    val appearanceStyle: String = "",
) {
    /** 落库实体组装([createdAt] 只在新建时有意义,编辑时被事务忽略)。 */
    fun toEntity(createdAt: Long): CharacterEntity = CharacterEntity(
        id = id,
        name = name,
        profile = profile,
        intro = intro,
        exampleLines = exampleLines,
        openingLine = openingLine,
        appearance = appearance,
        appearanceStyle = appearanceStyle,
        createdAt = createdAt,
    )
}

/** 各字段字数上限(本地常量;猫箱为服务端下发 field_words_limit_map,本地取自定值)。 */
object CharacterFormLimits {
    const val NAME_MAX = 24
    const val PROFILE_MAX = 2000
    const val INTRO_MAX = 500
    const val LINES_MAX = 1000
    const val OPENING_MAX = 500
    const val APPEARANCE_MAX = 300
    const val STYLE_MAX = 50
}

/** 逐字段错误文案(空 = 该字段无错);由 [CharacterFormValidator] 产出。 */
data class CharacterFieldErrors(
    val name: String? = null,
    val profile: String? = null,
    val intro: String? = null,
    val exampleLines: String? = null,
    val openingLine: String? = null,
    val appearance: String? = null,
    val appearanceStyle: String? = null,
) {
    val hasErrors: Boolean
        get() = name != null || profile != null || intro != null || exampleLines != null ||
            openingLine != null || appearance != null || appearanceStyle != null

    companion object {
        val NONE = CharacterFieldErrors()
    }
}

/**
 * 表单校验器(纯函数,JVM 单测锁定):
 * - name/profile/openingLine 必填;缺失、仅空格、前后带空格分别给出明确提示;
 * - 全部字段做长度上限校验;
 * - 名称重复用 [nameOwnerId](调用方预查的占用角色 id,null=无占用)判定;
 *   编辑自身(id 相同)不算重复。
 */
object CharacterFormValidator {

    fun validate(
        draft: CharacterDraft,
        nameOwnerId: Long? = null,
    ): CharacterFieldErrors {
        val name = when {
            draft.name.isEmpty() -> CharacterFormCopy.ERR_NAME_REQUIRED
            draft.name.isBlank() -> CharacterFormCopy.ERR_ONLY_BLANKS
            draft.name.trim() != draft.name -> CharacterFormCopy.ERR_EDGE_BLANKS
            draft.name.length > CharacterFormLimits.NAME_MAX ->
                CharacterFormCopy.errTooLong("角色名", CharacterFormLimits.NAME_MAX)
            else -> null
        } ?: run {
            // 名称占用校验:只在基本格式通过后进行
            if (nameOwnerId != null && nameOwnerId != draft.id) CharacterFormCopy.ERR_NAME_TAKEN else null
        }
        return CharacterFieldErrors(
            name = name,
            profile = validateText(
                draft.profile,
                required = true,
                max = CharacterFormLimits.PROFILE_MAX,
                requiredMessage = CharacterFormCopy.ERR_PROFILE_REQUIRED,
            ),
            intro = validateText(
                draft.intro,
                required = false,
                max = CharacterFormLimits.INTRO_MAX,
                requiredMessage = null,
            ),
            exampleLines = validateText(
                draft.exampleLines,
                required = false,
                max = CharacterFormLimits.LINES_MAX,
                requiredMessage = null,
            ),
            openingLine = validateText(
                draft.openingLine,
                required = true,
                max = CharacterFormLimits.OPENING_MAX,
                requiredMessage = CharacterFormCopy.ERR_OPENING_REQUIRED,
            ),
            appearance = validateText(
                draft.appearance,
                required = false,
                max = CharacterFormLimits.APPEARANCE_MAX,
                requiredMessage = null,
            ),
            appearanceStyle = validateText(
                draft.appearanceStyle,
                required = false,
                max = CharacterFormLimits.STYLE_MAX,
                requiredMessage = null,
            ),
        )
    }

    /** 单字段通用校验:必填/仅空格/前后空格/超长。 */
    private fun validateText(
        value: String,
        required: Boolean,
        max: Int,
        requiredMessage: String?,
    ): String? = when {
        value.isEmpty() -> if (required) requiredMessage else null
        value.isBlank() -> CharacterFormCopy.ERR_ONLY_BLANKS
        value.trim() != value -> CharacterFormCopy.ERR_EDGE_BLANKS
        value.length > max -> CharacterFormCopy.errTooLong(null, max)
        else -> null
    }
}

/** 表单/创作页文案与错误提示(集中声明,文案红线单测逐条扫描)。 */
object CharacterFormCopy {
    const val ERR_NAME_REQUIRED = "请填写角色名称"
    const val ERR_PROFILE_REQUIRED = "请填写角色设定"
    const val ERR_OPENING_REQUIRED = "请填写开场白"
    const val ERR_ONLY_BLANKS = "不能仅含空格"
    const val ERR_EDGE_BLANKS = "文本内容的前后不能含有空格"
    const val ERR_NAME_TAKEN = "已有同名角色,请换一个名称"

    fun errTooLong(field: String?, max: Int): String =
        if (field == null) "超出字数上限(最多 $max 字)" else "${field}超出字数上限(最多 $max 字)"

    // ── 创作页 / 编辑页文案 ──────────────────────────────────────────
    const val STUDIO_TITLE = "创作AI+"
    const val STUDIO_NEW_BUTTON = "+ 新建角色"
    const val STUDIO_EMPTY_HINT = "还没有自己创作的角色,点上面按钮开始"
    const val EDITOR_TITLE_NEW = "新建角色"
    const val EDITOR_TITLE_EDIT = "编辑角色"
    const val LABEL_NAME = "角色名"
    const val LABEL_PROFILE = "角色设定"
    const val HINT_PROFILE = "角色的性格、身份、说话风格、跟用户的关系等,请用「用户」称呼对话者"
    const val LABEL_INTRO = "角色简介(选填)"
    const val HINT_INTRO = "描述角色与用户的相遇场景及背景,会对外展示,请用「你」称呼用户"
    const val LABEL_LINES = "台词示例(选填)"
    const val HINT_LINES = "角色按照人设风格可能会说的台词"
    const val LABEL_OPENING = "开场白"
    const val HINT_OPENING = "作为角色说的第一句话,可使用()描述动作或场景"
    const val LABEL_APPEARANCE = "形象描述"
    const val HINT_APPEARANCE = "描述性别、发型、面部表情、肤色、服饰等形象特征"
    const val LABEL_STYLE = "绘图风格"
    const val HINT_STYLE = "可自由输入风格词,如水墨风、盲盒风等"
    const val BUTTON_SAVE_AND_CHAT = "保存并开始聊天"
    const val BUTTON_SAVE = "保存修改"
    const val BUTTON_PREVIEW = "预览"
    const val BUTTON_EDIT_BACK = "返回编辑"
    const val DRAFT_RESTORED = "已恢复上次未保存的草稿"
    const val DRAFT_SAVED_QUIETLY = "草稿已自动保存"
    const val SAVING = "保存中…"
    const val SAVE_CONFLICT = "已有同名角色,保存未生效"
    const val SAVE_FAILED = "保存失败,请重试"
    const val AVATAR_PENDING = "保存后自动生成头像"
    const val AVATAR_GENERATING = "头像生成中…"
    const val AVATAR_FAILED = "头像生成失败"
    const val AVATAR_RETRY = "重试"
    const val AVATAR_NO_APPEARANCE = "先填写形象描述,保存后才会生成头像"
    const val GALLERY_TITLE = "本地图片"
    const val GALLERY_EMPTY = "暂无本地图片"
    const val GALLERY_CURRENT = "当前形象"
    const val GALLERY_SET_CURRENT = "设为当前"
    const val GALLERY_DELETE = "删除"
    const val GALLERY_SET_DONE = "已设为当前形象"
    const val GALLERY_DELETE_DONE = "图片已删除"
    const val EDITOR_APPEARANCE_CHANGED_HINT = "形象已改写,保存后将重新生成头像"
    const val ROW_CHAT = "聊天"
    const val ROW_PHONE = "手机聊天"
    const val ROW_EDIT = "编辑"
    const val ROW_IM_MISSING = "会话缺失,进入编辑页保存一次即可补建"

    /** 绘图风格词目录(内置建议 + 自由输入;01-character-card.md §1.4 风格词可自由输入)。 */
    val STYLE_PRESETS = listOf(
        "写实摄影风",
        "日系动漫风",
        "水墨风",
        "盲盒风",
        "厚涂插画风",
        "像素风",
    )
}

/**
 * 开场白 → (剥离动作后的正文, () 动作列表)。
 *
 * 与输出协议同源:动作段匹配复用 [MultiBubbleParser.actionRegex](全/半角括号,
 * 1~40 字),正文 = 逐段剥掉动作片段后的剩余文本(去首尾空白)。开场白是首条
 * 消息,落库形态与种子角色一致:content 不含 ()、actionsJson 存动作数组。
 */
fun splitOpeningLine(raw: String): Pair<String, List<String>> {
    val actions = MultiBubbleParser.actionRegex.findAll(raw).map { it.groupValues[1].trim() }.toList()
    val content = MultiBubbleParser.actionRegex.replace(raw, "").trim()
    return content to actions
}
