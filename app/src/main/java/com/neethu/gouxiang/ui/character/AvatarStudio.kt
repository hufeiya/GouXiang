package com.neethu.gouxiang.ui.character

import com.neethu.gouxiang.data.CharacterEntity

/**
 * 角色头像生成状态(C 工作包 C3:生成中/失败/重试对用户可见)。
 */
sealed interface AvatarGenState {
    /** 未开始/无任务(占位图状态)。 */
    data object Idle : AvatarGenState

    /** 生成中(网络请求进行;网络不在任何数据库事务内)。 */
    data object Generating : AvatarGenState

    /** 失败(带可读原因;保留手工卡可用,重试入口可再次发起)。 */
    data class Failed(val reason: String) : AvatarGenState

    /** 就绪(本地文件已落盘)。 */
    data object Ready : AvatarGenState
}

/**
 * 版本化头像缓存键(C3):`character-<id>`(v0,兼容既有磁盘缓存命名)或
 * `character-<id>-v<N>`(N≥1,改写形象/风格保存后递增)。同一键只生成一次;
 * 版本变化 → 新键 → 新文件,旧头像不会被错误复用。
 */
fun avatarCacheKey(characterId: Long, avatarVersion: Long): String =
    if (avatarVersion <= 0L) "character-$characterId" else "character-$characterId-v$avatarVersion"

/**
 * 编辑页(新建/编辑表单)的 UI 状态:字段逐项可编辑、逐字段错误、保存中与
 * 整页错误提示。字段 setter 见 [com.neethu.gouxiang.ui.MainViewModel] 的
 * `updateCharacterField`;保存走 `saveCharacterFromEditor`。
 */
data class CharacterEditorUi(
    /** 被编辑的角色行 id(0 = 新建)。 */
    val editingCharacterId: Long = 0,
    /** 打开时的完整角色，供角色级设置读取稳定的来源标识。 */
    val originalCharacter: CharacterEntity? = null,
    val name: String = "",
    val profile: String = "",
    val intro: String = "",
    val exampleLines: String = "",
    val openingLine: String = "",
    val appearance: String = "",
    val appearanceStyle: String = "",
    /** 打开页时库内原值(判断形象/风格是否被改写,驱动头像版本化重生成)。 */
    val originalAppearance: String = "",
    val originalAppearanceStyle: String = "",
    /** 逐字段校验错误(空串字段 = 无错)。 */
    val errors: CharacterFieldErrors = CharacterFieldErrors.NONE,
    val saving: Boolean = false,
    /** 整页错误(重名冲突/事务失败;null=无)。 */
    val errorMessage: String? = null,
    /** 新建路径恢复了上次草稿(顶部一次性提示)。 */
    val draftRestored: Boolean = false,
    val showPreview: Boolean = false,
) {
    val isEditing: Boolean get() = editingCharacterId > 0

    fun toDraft(): CharacterDraft = CharacterDraft(
        id = editingCharacterId,
        name = name,
        profile = profile,
        intro = intro,
        exampleLines = exampleLines,
        openingLine = openingLine,
        appearance = appearance,
        appearanceStyle = appearanceStyle,
    )
}

/** 编辑页可编辑字段(逐项 setter 的定位键)。 */
enum class CharacterField { NAME, PROFILE, INTRO, LINES, OPENING, APPEARANCE, STYLE }

/**
 * 角色保存结果(UI 按钮与 ai_cmd 调试命令共用的数据路径产出)。
 */
sealed interface CharacterSaveOutcome {
    /** 落库成功:[characterId]/[conversationId] 供直接进入续聊,[created]=新建。 */
    data class Ok(val characterId: Long, val conversationId: Long, val created: Boolean) :
        CharacterSaveOutcome

    /** 校验未过:逐字段错误已产出,未触碰数据库。 */
    data class Invalid(val errors: CharacterFieldErrors) : CharacterSaveOutcome

    /** 同名冲突(事务内拦截,含「新建双重保存」的第二笔)。 */
    data object Conflict : CharacterSaveOutcome

    /** 数据层失败(事务异常等)。 */
    data class Failure(val reason: String) : CharacterSaveOutcome
}
