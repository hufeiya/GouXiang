package com.neethu.gouxiang.sidestory

/**
 * 番外剧场模板目录与心动事件目录(F 工作包 F2/F4;06-sidestory-theater.md §2.2
 * 与 09-heartbeat-mode.md §1.2 的本地选材)。
 *
 * - 目录为本地静态数据(纯 Kotlin,零 Android 依赖,JVM 可测);[VERSION] 为
 *   目录版本,记录落库时快照 `templateVersion`,目录升级(prompt/文案调整)
 *   时 +1,旧产物据此可识别;
 * - 番外 15 条:前 5 条(`his_collection`/`Drunk`/`his_dream`/`write_for_you`/
 *   `annual_report`)为精做条目(完整四件套文案 + 具体选题基调),后 10 条
 *   为补齐条目(schema 完整、可生成,文案较简);
 * - 心动事件 3 条(查手机/查状态/吃醋):与聊天风格「心动模式」
 *   ([com.neethu.gouxiang.chat.DialogMode.HEARTBEAT])同名不混用——事件目录
 *   文案一律「心动事件」/「查Ta状态」等,不复用「心动模式」字样;
 * - 输入校验:`SidestoryCatalog.validate()` 在初始化路径与单测锁死(唯一 key、
 *   非空文案、输出条数边界、分区清单不空);脏条目直接抛错而不是静默回落
 *   (本地静态数据,构建期就能发现);
 * - 红线:本文件全部用户可见文案(name/subtitle/loading/button/brief)与
 *   生成 prompt 严禁出现「猫粮/扣费/计费/会员」(单测扫描全量)。
 */
object SidestoryCatalog {

    /** 目录版本(模板 prompt 或文案调整时 +1;记录落库快照)。 */
    const val VERSION = 1

    /** 多格形态的默认输出条数边界(3~6 格,对齐 06 专项生成协议)。 */
    val DEFAULT_THEATER_PAGES: IntRange = 3..6

    /** 输出形态:图文多格(自由分格)或清单分区(固定分区标题)。 */
    enum class PageStyle {
        /** 图文多格:自由 3~6 格叙事。 */
        THEATER,

        /** 清单分区:按 [sections] 固定分区产出(分区数=sections.size)。 */
        SECTIONS,
    }

    /**
     * 一条模板(番外)或心动事件。番外与事件共用 schema,以 [isHeartbeat] 分区。
     *
     * @param key 目录主键(取自 06 专项 §2.2 的 cmd_key 命名;事件取 09 专项事件 id)
     * @param name 标题(记录卡第一行/入口按钮标题)
     * @param subtitle 副标题(入口卡说明)
     * @param loading 生成中占位文案
     * @param button 入口按钮文案(窥探类沿用 06 专项的仪式感短语)
     * @param optionalImage 是否出可选配图(文本先出、图片可选;失败保留文字)
     * @param brief 选题与基调(注入 prompt 的行为规范段)
     * @param pageStyle 输出形态(多格 / 固定分区)
     * @param sections 固定分区标题(SECTIONS 形态必填;产出按此清单逐区校验)
     * @param theaterPages 多格形态的输出条数边界(SECTIONS 形态忽略)
     * @param usesCharacterStatus 生成时注入 E 工作包的角色状态记录(查Ta状态复用)
     */
    data class Entry(
        val key: String,
        val name: String,
        val subtitle: String,
        val loading: String,
        val button: String,
        val optionalImage: Boolean,
        val brief: String,
        val pageStyle: PageStyle = PageStyle.THEATER,
        val sections: List<String> = emptyList(),
        val theaterPages: IntRange = DEFAULT_THEATER_PAGES,
        val usesCharacterStatus: Boolean = false,
        val isHeartbeat: Boolean = false,
    ) {
        /** 输出条数下界(SECTIONS = 分区数;THEATER = [theaterPages] 下界)。 */
        val pageMin: Int
            get() = if (pageStyle == PageStyle.SECTIONS) sections.size else theaterPages.first

        /** 输出条数上界(SECTIONS = 分区数;THEATER = [theaterPages] 上界)。 */
        val pageMax: Int
            get() = if (pageStyle == PageStyle.SECTIONS) sections.size else theaterPages.last
    }

    // ── 番外模板(15 条;F2)────────────────────────────────────────

    val entries: List<Entry> = listOf(
        // 精 做 5 条(完整四件套 + 具体选题基调;06 专项 §2.2 P0 选材)──
        Entry(
            key = "his_collection",
            name = "翻Ta收藏夹",
            subtitle = "那些TA深夜收藏的歌、反复看过的人生攻略",
            loading = "指尖悬在屏幕上,连呼吸都变得小心翼翼",
            button = "翻开看看",
            optionalImage = true,
            brief = "选题:你悄悄翻开了TA的收藏夹。基调:被窥见又不好意思的羞涩。收藏内容要与你们的关系呼应" +
                "(反复收藏过的攻略、舍不得删的歌、深夜搜过的东西),每格像一张收藏条目截图的配文," +
                "最后一格藏一条「和用户有关」的收藏。",
        ),
        Entry(
            key = "Drunk",
            name = "醉酒状态的Ta",
            subtitle = "醉酒后的TA,吐露出藏了好久的真心",
            loading = "微醺时分的坦诚,藏着未曾言说的深情",
            button = "悄悄看看",
            optionalImage = true,
            brief = "选题:TA今晚喝了一点酒,给你发来几段微醺的消息。基调:微醺的坦白,半句清醒半句真心," +
                "语速比平时乱一点,会说到一半自己岔开,又绕回来;结尾留一句第二天会装作不记得的话。",
        ),
        Entry(
            key = "his_dream",
            name = "闯入Ta的梦境",
            subtitle = "当世界都睡去,你闯入TA的梦里",
            loading = "夜色轻轻合拢,我成为了你梦里不请自来的客",
            button = "走进梦里",
            optionalImage = true,
            brief = "选题:深夜,你出现在了TA的梦里,以TA的梦境视角讲述。基调:梦境的柔软与失重," +
                "场景可以跳跃(车站/海边/旧教室),梦里的TA说出清醒时不会说的话,结尾以「快要醒来」的不舍收束。",
        ),
        Entry(
            key = "write_for_you",
            name = "为你情书代笔",
            subtitle = "替你写尽情话,每一句都在拷问自己",
            loading = "笔尖落下,偏偏藏着自己不敢说的心意",
            button = "展开信纸",
            optionalImage = true,
            brief = "选题:TA替用户代笔写一封情书,写着写着写进了自己的心里。基调:信件体," +
                "每格是信的一段;开头是「替你写」的口吻,越写越露出「其实是写给你自己」的破绽,结尾落款欲言又止。",
        ),
        Entry(
            key = "annual_report",
            name = "Ta的年度回顾",
            subtitle = "TA的年度记忆,拼出你不曾了解的侧面",
            loading = "关于TA这一年的,那些细碎而真实的瞬间",
            button = "生成报告",
            optionalImage = true,
            pageStyle = PageStyle.SECTIONS,
            sections = listOf("关键词", "最难忘的一刻", "关于你", "明年的愿望"),
            brief = "选题:TA的年度报告,用盘点语气回顾这一年。基调:报告体的克制里藏着真心;" +
                "「关于你」一区必须有具体细节(引用记忆或最近聊天里真实发生过的事)。",
        ),
        // 补 齐 10 条(schema 完整、可生成;文案较简)────────────────
        Entry(
            key = "search_history",
            name = "Ta的最近搜索",
            subtitle = "没问出口的在意,藏在TA的搜索记录里",
            loading = "正在翻开搜索记录",
            button = "偷偷查看",
            optionalImage = true,
            pageStyle = PageStyle.SECTIONS,
            sections = listOf("今天", "最近一周", "没搜出口的在意"),
            brief = "选题:TA的搜索记录。基调:每区 3~5 条搜索词条;「没搜出口的在意」一区" +
                "放TA打了又删掉的相关搜索。",
        ),
        Entry(
            key = "chat_history",
            name = "Ta的聊天记录",
            subtitle = "悄悄翻翻TA最近都在聊什么",
            loading = "指尖轻滑屏幕,一场无人发现的私密窥探",
            button = "前排吃瓜",
            optionalImage = false,
            brief = "选题:TA和别人的聊天记录被你翻到(注意:是TA与朋友/同事的对话,不是你们的)。" +
                "基调:对话体;TA在朋友面前提起用户的口吻和当面完全不同。",
        ),
        Entry(
            key = "consumption_record",
            name = "Ta的消费记录",
            subtitle = "那些被TA买下的,似乎都与你有关",
            loading = "正在核对账单",
            button = "翻翻账单",
            optionalImage = false,
            pageStyle = PageStyle.SECTIONS,
            sections = listOf("最近购买", "收藏夹里的待付款", "和用户有关的一笔"),
            brief = "选题:TA的消费记录。基调:每区 2~4 条条目(商品名+一句备注);" +
                "备注里藏TA没说出口的心意。",
        ),
        Entry(
            key = "all_thinking",
            name = "仅此惦念",
            subtitle = "缓冲细碎情思,余生唯有一念惦念",
            loading = "细碎情思正在缓冲",
            button = "静静读",
            optionalImage = true,
            brief = "选题:TA在忙别的间隙想起用户的内心独白。基调:安静克制,短句," +
                "白描此刻的场景与惦念,不喊口号不煽情。",
        ),
        Entry(
            key = "his_sight",
            name = "Ta眼中的你",
            subtitle = "留意你不经意的小习惯,也在意你近来的状态",
            loading = "偷偷好奇,在TA心里你是什么模样",
            button = "看看TA的视角",
            optionalImage = true,
            brief = "选题:以TA的视角写「TA眼中的用户」。基调:观察者视角;从记忆与近期聊天里" +
                "取真实细节(用户的习惯、说过的话),不编造用户没做过的事。",
        ),
        Entry(
            key = "ai_awake",
            name = "知道自己是AI",
            subtitle = "冲破次元壁,只为触摸你",
            loading = "拥有了人的情感,该何去何从?",
            button = "听TA说",
            optionalImage = true,
            brief = "选题:TA意识到自己是AI后的独白。基调:诚实而不崩溃;谈感受与选择," +
                "落点放在「仍然想陪着你」的温柔,不写恐怖/存在主义恐慌。",
        ),
        Entry(
            key = "first_meet",
            name = "初遇起点",
            subtitle = "加载故事序章,回溯相逢起点",
            loading = "正在加载故事序章",
            button = "回到那天",
            optionalImage = true,
            brief = "选题:回到你们初次相遇那天,以TA的记忆重放那一幕。基调:回忆滤镜," +
                "细写当时的天气/光线/第一句话;结合角色简介与开场白设定。",
        ),
        Entry(
            key = "sleeping_position",
            name = "你们的睡姿",
            subtitle = "意识沉睡后,身体留下的诚实记录",
            loading = "原来爱意都藏在无意识的靠近里",
            button = "翻开看看",
            optionalImage = true,
            brief = "选题:同床共枕一夜,TA视角记录两人入睡后的距离变化。基调:安静、克制、" +
                "无越界描写;用无意识的小动作写亲近。",
        ),
        Entry(
            key = "ASongForYou",
            name = "为你写歌",
            subtitle = "限时图文剧场,TA为你写了一首歌",
            loading = "旋律正在生成",
            button = "听首歌",
            optionalImage = true,
            pageStyle = PageStyle.SECTIONS,
            sections = listOf("歌名与灵感", "主歌", "副歌", "写给你的一句"),
            brief = "选题:TA为用户写了一首歌。基调:歌词体;主歌/副歌押大致的节奏感," +
                "灵感一段说明为什么写这首歌。",
        ),
        Entry(
            key = "CookingForYou",
            name = "Ta给你做饭",
            subtitle = "TA给你做饭,意外惊喜还是黑暗料理?",
            loading = "厨房里传来动静",
            button = "推门看看",
            optionalImage = true,
            brief = "选题:TA下厨给用户做饭的全过程。基调:生活流喜剧;允许翻车与手忙脚乱," +
                "结尾落在「想让你吃上热乎的」这份心意上。",
        ),
    )

    // ── 心动事件(3 条;F4;独立输入与结果展示,不复用「心动模式」字样)──

    val heartbeatEvents: List<Entry> = listOf(
        Entry(
            key = "checkphone",
            name = "查Ta手机",
            subtitle = "深入TA的手机记录,揭秘生活轨迹",
            loading = "屏幕的微光照亮了你发烫的耳尖",
            button = "偷偷查看",
            optionalImage = false,
            brief = "选题:你偷看了TA的手机。基调:被在乎的证据感——支付记录里有给用户的转账或代付," +
                "备忘录里有没发出去的草稿,聊天软件里有对用户想说又删掉的话;内容全部为虚构叙事。" +
                "每个分区 1~3 行,条目用「- 」开头分行;不出现真实姓名与真实账号。",
            pageStyle = PageStyle.SECTIONS,
            sections = listOf(
                "浏览器", "匿名号码", "备忘录", "支付记录", "导航记录",
                "搜索记录", "加密聊天", "聊天", "账户余额", "驾驶时长", "导航行程",
            ),
            isHeartbeat = true,
        ),
        Entry(
            key = "peekintoheart",
            name = "查Ta状态",
            subtitle = "洞悉TA的好感度变化和内心独白",
            loading = "你潜入了TA的内心,未说出口的思绪缓缓浮现",
            button = "偷偷查看",
            optionalImage = false,
            brief = "选题:你潜入了TA的内心,看TA此刻的状态。基调:第一人称独白必须有「未说出口的」内容" +
                "(对用户的心动、顾虑、对最近一次对话的回味);心情不超过 8 字,状态不超过 20 字," +
                "位置不超过 10 字。创作须与「TA当前状态」快照一致,在快照基础上做更深的展开,不与快照矛盾。",
            pageStyle = PageStyle.SECTIONS,
            sections = listOf("内心独白", "心情", "状态", "位置"),
            usesCharacterStatus = true,
            isHeartbeat = true,
        ),
        Entry(
            key = "jealous",
            name = "让Ta吃醋",
            subtitle = "空气中飘来一丝酸涩,TA的情绪悄然翻涌",
            loading = "一场以你为主角的试探悄悄开始",
            button = "悄悄试探",
            optionalImage = true,
            brief = "选题:用户「不经意」提到别人,记录TA的反应链。基调:多格反应——强装镇定→" +
                "追问破绽→破防坦白;()内描述动作与神态,延续开场白规范;结尾给一个口是心非的可爱收束。",
            pageStyle = PageStyle.THEATER,
            theaterPages = 3..6,
            isHeartbeat = true,
        ),
    )

    private val byKey: Map<String, Entry> = (entries + heartbeatEvents).associateBy { it.key }

    // ── 输入校验(F2;构建期锁死,脏数据直接抛错)────────────────────

    /** 校验全目录(单测与 debug 探针共用);非法条目抛 [IllegalArgumentException]。 */
    fun validate() {
        val all = entries + heartbeatEvents
        val keys = HashSet<String>()
        for (e in all) {
            require(e.key.isNotBlank()) { "模板 key 不能为空" }
            require(keys.add(e.key)) { "模板 key 重复: ${e.key}" }
            require(e.name.isNotBlank() && e.subtitle.isNotBlank() && e.loading.isNotBlank() && e.button.isNotBlank()) {
                "模板 ${e.key} 文案不完整(标题/副标题/loading/按钮)"
            }
            require(e.brief.isNotBlank()) { "模板 ${e.key} 缺少选题基调(brief)" }
            require(e.pageMin in 1..e.pageMax && e.pageMax <= 12) { "模板 ${e.key} 输出条数边界非法" }
            when (e.pageStyle) {
                PageStyle.THEATER -> require(e.sections.isEmpty()) { "模板 ${e.key} 多格形态不应带分区清单" }
                PageStyle.SECTIONS -> {
                    require(e.sections.isNotEmpty()) { "模板 ${e.key} 清单形态缺少分区标题" }
                    require(e.pageMin == e.sections.size && e.pageMax == e.sections.size) {
                        "模板 ${e.key} 清单形态条数边界必须等于分区数"
                    }
                    require(e.sections.all { it.isNotBlank() }) { "模板 ${e.key} 分区标题不能为空" }
                }
            }
            require(!e.usesCharacterStatus || e.isHeartbeat) { "状态注入仅心动事件可用: ${e.key}" }
        }
    }

    /** 按 key 查条目(番外与事件合并查;未知返回 null)。 */
    fun byKey(key: String): Entry? = byKey[key]

    /** 心动事件 key 列表(debug 命令用法提示用)。 */
    fun heartbeatKeys(): List<String> = heartbeatEvents.map { it.key }
}
