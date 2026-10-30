# 猫箱文档 vs GouXiang 实现 · 偏差比对报告

> 比对日期: 2026-10-29
> 基准文档: `/home/neethu/projects/AIAvatar-SDK/docs/maoxiang-feature-analysis.md` + `/home/neethu/projects/AIAvatar-SDK/docs/maoxiang/01~12` 专项文档 + `docs/maoxiang/ui/README.md`
> 被核对工程: 本仓库 `/home/neethu/projects/GouXiang`（包名 `com.neethu.gouxiang`，源码基址 `app/src/main/java/com/neethu/gouxiang/`，下文相对路径均相对此基址）
> 比对方法: 6 个独立子代理按功能域并行逐条核对（共拆出约 278 条文档要求），一切结论以实际代码为准，未转抄 `docs/local-maoxiang-report.md` 的自述结论。比对规则：文档涉及 3D 虚拟人（VRM/3D 房间/3D 演出）的部分忽略；计费/猫粮/会员项按本地版「有意不做」豁免，但计费无关的行为形态（限速、轰炸、回溯联动、配额记账、敏感词兜底等）仍计入核对。
> 本文档用途: 开发排期依据。附录 A~F 为 6 份子代理完整报告原样收录，含全部 file:line 证据；第 1~4 节为汇总与优先级。

---

## 0. 总览

全量 278 条核对项：**完全符合 66 条（24%）、部分实现 46 条（17%）、实现与文档不一致 32 条（11%）、未实现 134 条（48%）**。

| 文档域 | 附录 | 核对条目 | ✅符合 | ⚠️部分 | 🔀不一致 | ❌未实现 |
|---|---|---|---|---|---|---|
| 01 角色卡 | A | 25 | 8 | 2 | 1 | 14 |
| 02 真人式输出协议 | A | 27 | 3 | 12 | 9 | 3 |
| 03 记忆升级 | B | 33 | 17 | 8 | 6 | 2 |
| 04 手机聊天 | C | 51 | 24 | 11 | 6 | 10 |
| 05 主动消息 | B | 25 | 6 | 4 | 4 | 11 |
| 06 番外剧场 | E | 9 | 0 | 0 | 0 | 9 |
| 07 聊天衍生内容 | E | 26 | 4 | 2 | 2 | 18 |
| 08 状态系统 | D | 15 | 0 | 2 | 1 | 12 |
| 09 心动模式 | D | 13 | 0 | 0 | 1 | 12 |
| 10 朋友圈 | F | 20 | 4 | 4 | 1 | 11 |
| 11 广播剧 | F | 19 | 0 | 1 | 0 | 18 |
| 12 AVG 剧情模式 | F | 15 | 0 | 0 | 1 | 14 |

未实现的 134 条里，大部分集中在 `docs/local-maoxiang-report.md` §4.2 已自declare「下一期」的范围（06/08/09/11/12 + 手帐/小说/合照 + 创作域），属计划内缺口而非意外偏差。真正需要立即关注的是第 1 节「断链类」问题。

### 横切缺失（多个域共同依赖的基建，一次补齐多处受益）

- **无通知体系**：全工程无 NotificationChannel/POST_NOTIFICATIONS（manifest 仅 INTERNET/NETWORK/RECORD_AUDIO），影响 05 主动消息推送、06/07/09 异步完成通知、11 新期推送。
- **无 WorkManager / 后台任务**：影响 05 离线周期生成、10 每日发帖、03 隔夜总结兜底。
- **无 assets 目录**：`app/src/main/assets/` 不存在，影响 06 模板库 templates.json、02 表情包目录 stickers.json、01 风格目录 image_styles.json、12 剧本 assets/story/*.json。
- **无 LLM 工具注册体系**：文档的 update_status / start_heartbeat / start_sidestory / list_radio_drama / story_status 等工具缝全部挂不上。
- **无 ai_cmd 调试命令体系**：各文档的调试命令均改为 UI 手势替代（目前仅长按手机聊天「…」模拟离线回归一处）。

---

## 1. 实现了但实际不通 / 与文档行为相反（P0 修复，改动小收益大）

1. **卡片消息端到端断链**（02/04 号文档核心要求，附录 A🔀9 + C/A1）。prompt 教模型输出 `[卡片:转账 520|备注]`（`chat/ChatContextBuilder.kt:95-96`），但 `MultiBubbleParser` 行内约定只有 sticker/img/voice、**不识别 card**（`protocol/MultiBubbleProtocol.kt:176-228`）——模型输出的卡片退化为纯文本泡；用户侧 `sendCard` 也以 `kind="text"` 原文落库（`ui/MainViewModel.kt:277-280`）。`parseCardLine`（`protocol/PhoneProtocol.kt:104`）、`CardBody` 渲染（`ui/phone/PhoneChatScreen.kt:383,415-454`）、`CardPayload.summary()` 全部是无生产者的死代码，只有单测在跑。**修法：入库时判卡置 `kind="card"` 并解码回填（`ui/MainViewModel.kt:254-262` 用户消息 + `chat/ChatOrchestrator.kt:320-326` storageKey 映射补 card）。**
2. **日常分享泡（proactivity）在 JSON 通路被静默丢弃**（附录 A🔀4 + C/H2）。解析器支持 `proactivity` 字段（`MultiBubbleProtocol.kt:112-121`），但编排器只消费 `reply.bubbles`（`chat/ChatOrchestrator.kt:158-159`）——模型按对象格式 `{"bubbles":[…],"proactivity":{…}}` 输出时分享泡丢失。**修法：编排器追加消费 `reply.proactivity`。**
3. **回溯不联动删记忆**（附录 B/A15）。03 号文档任务 7 要求回溯时删除晚于回溯点的 event/commitment 记忆；`rewindToMessage` 只删消息行（`ui/MainViewModel.kt:564-573`），不触碰 memories 表——回溯后已总结的事件记忆成为「幽灵记忆」继续注入 prompt。**修法：`MemorySource` 补 `deleteWhere(sinceMs, categories)` 并在回溯路径调用。**
4. **回合失败被伪装成角色回复，无重试路径**（附录 A🔀5 + B/B15）。文档要求「30s 无首 delta → 显式失败态 + 消息发送失败，请点击重试」；实现是无看门狗（仅 OkHttp readTimeout 60s，`ai/adapter/OpenAiCompatibleLlmAdapter.kt:46`），流失败时追加「(戳了戳你)……信号有点糟」兜底泡正常落库（`chat/ChatOrchestrator.kt:265-267,301`）。另：回合进行中点发送会清空草稿并静默丢弃（`ui/phone/PhoneChatScreen.kt:587-590` → `ui/MainViewModel.kt:249`）。
5. **头像生图没拼风格词缀**（附录 A🔀1）。`ensureAvatars` 只传 `appearance`（`ui/MainViewModel.kt:688-700`），`appearanceStyle` 仅心声配图（:782-784）和朋友圈配图（:959）拼了——角色主形象不受卡面风格约束。**修法：prompt = appearance + appearanceStyle。**
6. **种子「我的称呼」分类存错**（附录 B/A5）。文档定义称呼=profile+subtype="name"，种子存成 `category="relationship"`（`data/SeedData.kt:44`），导致新装机记忆页「我的称呼」显示「暂无」（`ui/memory/MemoryScreen.kt:274` 只认 profile+name，见 `chat/MemoryExtractor.kt:163-170`）。**修法：改种子分类 + 存量数据迁移。**
7. **()动作无白名单过滤**（附录 A🔀3）。文档要求「白名单动作转 Action 段，非白名单保留为文本」，实现是任意 ≤40 字括号内容全量剥离渲染成斜体（`MultiBubbleProtocol.kt:67`，渲染 `ui/phone/PhoneChatScreen.kt:457-468`）——普通括号文本会被误伤。附带：**「纯标点段丢弃」未做**（仅 `filter { it.isNotEmpty() }`，`MultiBubbleProtocol.kt:177`）。

## 2. 文档定义的行为形态被收敛/替换（多数可接受，建议逐项决策：修 or 显式声明裁剪）

- **记忆域**（附录 B）：双通道触发分层（5 轮画像 / 15 轮总结、先 Summarizer 后 Extractor）没做，只有单一 5 轮档且执行顺序相反（`chat/ChatMemoryCoordinator.kt:47,73,144-150`）；两通道共用快照、任一成功清整段窗口（:140,152-157），失败通道失去重试素材；容量默认 20×1f 而非文档断言的 50×2f（`chat/MemoryCapacity.kt:18-22`）；无余弦去重（仅逐字相同，`chat/MemoryExtractor.kt:241-262`）；`FADED_SCORE=0.45` 是死常量（`MemoryCapacity.kt:25`）、`MemoryInjection.query` 参数收下不用（`chat/MemoryInjection.kt:30`）——**「记忆问答召回」实际是全量注入 TopN 而非检索**；「清空记忆」`clearAll` 已实现但零挂接（`MemoryExtractor.kt:233-235`）；「自动提炼记忆」开关恒 true 无写入点。
- **主动消息**（附录 B）：整段收敛为「进手机聊天页时判定一次」（首次 hello / 距最后消息 ≥2h，`protocol/ProactiveMessages.kt:66-77` + `ui/MainViewModel.kt:205-215,507-518`），离线堆积 outbox、WorkManager 后台生成、系统通知、「离线期间发来 N 条」分隔条、日上限/安静时段/批次门组全部缺失——「上线后被消息轰炸」的文档核心场景实际不存在，上限 3 条 vs 文档 8 条。`phone/PhoneChatPrefs.kt` 整个是写而不读的死配置。
- **限速降速**（附录 C/F1-F3 + A❌22）：文档把「回复速度降低、降速≠禁言」定义为保留的行为形态（五态机 Normal/SlowEarlyWarning/SlowDown/RunningOut/Recover + 可关闭的仪式感配额），实现完全没有。计费货币可裁，此行为形态文档明说要留，需显式决策。
- **朋友圈**（附录 F）：按需单角色版可用（生成/图文/点赞/楼中楼/持久化），但每日自动发帖、角色互评修罗场、用户发帖、权限三开关、成员/配额/敏感词兜底（后两项文档标 P0 且与计费无关）都没有。数据 schema 为注释自述的单角色裁剪子集（`data/MomentEntity.kt`）。
- **心声**（附录 E）：已实现（4✅），但素材是全量记忆注入而非检索 top3；一次生成 2~4 卡 vs 文档单卡模型；「收下」「设为聊天背景」「角色开关」三项缺；生图响应不解析 b64_json。
- **失败兜底形态**：文档的「加载失败 toast」被替换为角色口吻兜底泡（附录 C/I2，体验上更拟人，可接受但需记录）。
- **参数口径**（附录 A🔀2 + C/H1/J）：连发区间文档 1~4 条（手机 1~5 条），实现 IM 硬性「必须 3 条」、手机 2~4 条（`chat/ChatContextBuilder.kt:76-91`）；泡间固定 900ms vs 文档 600~1200ms 随机（`ChatOrchestrator.kt:41`）；单泡 ≥20 字即强制切分 vs 文档 >60 字才兜底（`ChatOrchestrator.kt:307`）；连体气泡圆角梯度（`lastInGroup` 算了但 UI 不消费）；组间距 2dp 反而小于组内 5dp，分组聚拢感与文档相反（`PhoneChatScreen.kt:131,311`）；语音条时长公式码点÷4 而非字素簇÷语速系数（`protocol/PhoneProtocol.kt:122-125`）。
- **杂项**：会话列表摘要直接用最后一泡裸文本，语音泡会泄露转写全文（文档要求「发来一段语音」类降级摘要，附录 A⚠️18）；卡片类型集合实现为 IMAGE 替代文档 GOODS（附录 C/A2）；长按菜单缺「复制」（文档 v1 最小项，附录 C/G1）；回溯后无「记录已变化」提示。

## 3. 整体为零的板块（多为计划内，按文档排期即可）

| 板块 | 文档 | 现状 | 关键前置 |
|---|---|---|---|
| 番外剧场 | 06 | 全无（模板库/存储/生成器/抽卡/UI） | 无 LLM 工具注册、无 assets 目录 |
| 手帐/小说/合照 | 07 | 全无（衍生内容管线 schema/调度器/通知/冲突区全缺） | 同上 + 通知体系 |
| 状态系统 | 08 | 仅状态条一层皮（`chat/StatusRule.kt` 确定性关键词规则），状态记录管线/面板/回溯联动为零 | 记忆协调器回合收尾缝已存在（`ChatOrchestrator.kt:67` memoryHook），可照此挂 statusTracker |
| 心动模式五件套 | 09 | 零；现有「心动模式」只是对话风格 prompt 段（`chat/ChatStyle.kt:28-33`），与文档的心动指令事件族同名异义；聊天页红点为纯装饰 | 复用 06 剧场管线 + 心声生成器（`chat/InnerVoiceGenerator.kt` 的宽松 JSON 解析可复用） |
| 广播剧 | 11 | 零；仅 TTS 底座可复用（`ai/speech/SpeechTypes.kt:21-38` 句级接口 + 火山/硅基双引擎），多音色编排/播放器/后台收听全缺 | manifest 无前台服务声明 |
| AVG 剧情模式 | 12 | 零；首页「AI 剧」剧卡是静态视觉壳（互动数硬编码，`ui/home/HomeFeedScreen.kt:261-264`），无任何剧入口 | 无 assets/story、无 story.db |
| 角色创作域 | 01 | 全无（创建向导/AI 建卡/校验器/图库/I2I/风格目录/音色/卡导入导出）；底栏「创作AI+」为占位页 | 单角色种子架构下暂无消费场景 |

## 4. 建议开发优先级

1. **断链修复**（第 1 节 ①②）：纯消费侧接线，改动最小。
2. **数据正确性**（第 1 节 ③⑥ + 附录 B/A15、A5）：回溯联动删记忆 + 种子称呼分类修复（含存量迁移）。
3. **体验正确性**（第 1 节 ④⑤⑦）：失败态显式化 + 发送中不丢草稿、头像拼风格词缀、()白名单 + 纯标点过滤。
4. **决策项**（第 2 节）：离线轰炸 outbox、限速五态机、记忆触发分层/快照回退/容量参数、朋友圈用户发帖+敏感词——每项先决定「补」还是「声明裁剪」，补的按附录对应条目施工。
5. **排期项**（第 3 节）：推荐顺序 06 剧场 → 08 状态系统（两者共享「定时总结产出 + 记忆画像注入」底座，且 `ChatOrchestrator.kt:67` 的回合收尾缝已存在）→ 09 心动（复用剧场管线）。广播剧/AVG 需先补通知体系与 assets 基建。

---
---

# 附录 A：01 角色卡 + 02 真人式输出协议（子代理完整报告）

**路径约定**：文档 = `/home/neethu/projects/AIAvatar-SDK/docs/maoxiang/01-character-card.md`(下称 01 文档)、`…/02-real-person-protocol.md`(下称 02 文档)；代码基址 `<src>` = `app/src/main/java/com/neethu/gouxiang/`，行号均为实际文件行号。

## 一、总体符合度与统计

**总体描述**：GouXiang 对 02 文档(真人式输出协议)的**协议核心链路实现得相当完整**——四能力段型、健壮解析、连发 prompt、「正在输入」、静默语音条、主动消息、卡片面板均在，偏差集中在协议形态细节(线格式、白名单、未读、超时)与若干断链(卡片解析、proactivity 消费)；对 01 文档(角色卡)**只实现了"已存在角色卡的字段与消费侧"**(字段 schema 主体、四字段用途差异、生图单图链)，**整个创作域(创建向导、AI 建卡、校验器、图库、I2I、风格目录、音色)整体缺失**，属大范围未实现而非细节偏差。

| 文档 | 核对条目 | ✅符合 | ⚠️部分 | 🔀不一致 | ❌未实现 |
|---|---|---|---|---|---|
| 01 角色卡(剔除 3D/发布审核等文档自declare不实现项) | 25 | 8 | 2 | 1 | 14 |
| 02 输出协议 | 27 | 3 | 12 | 9 | 3 |
| **合计** | **52** | **11** | **14** | **10** | **17** |

说明：01 文档 §1.5 发布/审核流，文档 §5.6 已自declare"本地不实现"，不计入；02 文档 §1.5 chunk 协议字段(chunk_seq 等)是猫箱服务端协议，文档 §3.3 已定本地以 TextDelta 为源，不计入。

## 二、逐条偏差清单

### 01 文档(角色卡与形象生成)

**🔀1. appearanceStyle 风格词缀未拼进角色头像生图 prompt**
- 文档：§1.4 生图输入四要素含「风格」；§3.2-B appearance → 生图 prompt「已拼接风格词缀」。
- 代码：`<src>/ui/MainViewModel.kt:688-700` `ensureAvatars` 的 `prompt = appearance`，只传形象描述；仅心声配图(`MainViewModel.kt:782-784`)和朋友圈配图(`:959`)拼了 `appearanceStyle`。
- 差异：字段存在(`data/CharacterEntity.kt:34`)、种子有值(「写实摄影风」)，但角色头像这条主链路风格词缀被丢弃，生图结果不受卡面风格约束。

**⚠️2. 用户扮演角色名称/设定(userPlayer)无 schema 字段，注入为记忆种子替代实现**
- 文档：§1.2 用户侧画像字段(「用户扮演角色名称/设定」，"会作为其他角色的已知信息")；§3.2-B `LocalCharacterDraft.userPlayerName/userPlayerRole`；§3.3.2 激活卡时写入 PROFILE/RELATIONSHIP、换卡清理。
- 代码：全工程 grep `userPlayer/扮演角色` 0 命中，`CharacterEntity` 无此字段；行为形态由种子记忆替代——`data/SeedData.kt:44-51`(relationship「我的称呼：老公」/profile「我的身份：…」)，经 `<src>/chat/MemoryInjection.kt:44-48` 注入 prompt。
- 差异：「画像进 prompt」的目标行为达成，但卡级字段、写入点、换卡清理机制均无(单角色固定种子下换卡场景不存在，影响有限)。

**❌3. 角色音色(tone/dubbing)无任何实现**
- 文档：§1.2 tone 字段(dubbing/language/pitch/speed 等)；§3.1 卡级音色绑定(CardBinding.voice)。
- 代码：`CharacterEntity` 无音色字段；语音条播放用系统 TTS 固定简体中文(`<src>/phone/VoicePlayer.kt:42`)，语音通话 TTS 配置与角色卡无关联。

**❌4-10. 创作域整体缺失(7 条)** — 底栏「创作AI+」为占位页(`<src>/ui/nav/BottomTab.kt` CREATE 项，PlaceholderPage)，以下全部无代码：
- 四步创建向导(CREATE/MATERIAL/PROLOGUE/PREVIEW、切步校验)——文档 §1.2、§3.2-C T2；
- AI 一句话建卡(generateDraft/DraftGenResult 五字段 JSON+解析降级)——§1.1、§3.2-B T4；
- 模板选择(TemplateItem id/title/emoji、换模板确认)——§1.1；
- 异步生成+通知(createAIStatus/drawAIStatus)——§1.1/§1.4(本地头像生成是前台 await 单图，无状态机无通知)；
- AI 建卡/帮写每日限额及「耗尽不锁死、降级手填」行为形态——§1.1(limitBody/storyAI_limitBody)【文档有、代码无，可能属本地版有意裁剪——计费相关，但"降级手填"这一行为形态也随创作域一起缺失】；
- 表单校验器(九类错误码：必填/前后空白/仅空白/特殊字符/字数超限/查重 + WordsLimitField 式限额)——§1.3、§3.2 T6(grep 无任何 Validator)；
- 猫箱卡 schema 编解码/酒馆卡互通(MaoxiangCardCodec/LocalCharacterDraft)——§3.2-B T1(无导入导出)。

**❌11-14. 生图管线缩水项(4 条)**——适配器本体符合：`<src>/ai/image/ImageGenAdapter.kt` + `OpenAiCompatibleImageGenAdapter.kt` + 火山→硅基降级(`<src>/ai/image/AvatarImageProvider.kt:84-98`)，✅；但文档 §3.2-A 的参数面/产物面缺失：
- 批量生成(batch=4)+ 部分失败补齐(redrawAI)——单图一次生成永久缓存(`AvatarImageProvider.kt:46-47,72-73`；`ImageGenRequest` 仅 prompt+size，文件自注"后续功能再扩 batch")。
- 图库(收藏/删除/设当前/改写再生成 I2I 整图退化/重新生成/下载)——无 CharacterImageLibrary，无图库 UI。
- 风格目录 `assets/image_styles.json` + 风格选择器 + 「修改风格后重新生成」确认——无(工程无 assets 目录，find 实证)。
- 参考图 I2I(image_refer_face/style、三路降级文案、能力矩阵)——`ImageGenRequest` 无参考图参数；生图模型选择(模型下拉/四档描述)也无，模型硬编码常量(`AvatarImageProvider.kt:121,124`)。

**⚠️15. 生成失败/违规态文案缺失**
- 文档：§1.4 failedDraw/quotaAI/imagesFailed_redrawAI；§2.1 违规态可降级为「生成失败」。
- 代码：生成失败静默留占位(`MainViewModel.kt:688` 注释"失败留在 map 外=UI 占位"、`AvatarImageProvider` 返回 null)，无失败/违规文案条目；配额文案属计费裁剪，失败提示则连裁剪理由都不充分。

**符合项(不展开)**：角色名/设定/intro/台词示例/开场白/形象描述六字段与「四字段用途差异」核心 schema 全部正确——profile 进 prompt 且种子人设含「称呼对话者为『用户』」(`<src>/chat/ChatContextBuilder.kt:59-60`、`data/SeedData.kt:24`)；intro 仅公开展示、绝不进 prompt(CharacterHomeScreen.kt:260、HomeFeedScreen.kt:254，ChatContextBuilder 无 intro 引用)；台词示例 few-shot 注入取 3 行(ChatContextBuilder.kt:61-66)；开场白为首条消息且 () 动作独立存储渲染(SeedData.kt:33-41)；identity 按 §3.2-B 本地设计口径并入 profile，一致。

### 02 文档(真人式输出协议)

**🔀1. 线格式：`<sep>` 分段符未采用，改为 JSON 首选 + 换行/`|||` 兜底**
- 文档：§3.3 线格式明确「`<sep>` 单独一行分段」+ 防误伤规则(裸 `<sep>` 不切)。
- 代码：`<src>/protocol/MultiBubbleProtocol.kt:53-57,91-97,176-177` — JSON 数组/对象首选，兜底按换行或 `|||` 切段；无 `<sep>` 标记。
- 差异：功能等价(分段/占位/终段判定都有，单测 17 例锁死)，但与文档定型的 wire format 不一致；文档 §2.2「05 号文档切到 `<sep>`」的统一前提在 GouXiang 不成立。

**🔀2. 连发区间与文档「1~4 条」不一致**
- 文档：§3.4 协议块「一次回复拆成 1~4 条短消息」，`maxSegments=4` 为上限语义。
- 代码：`<src>/chat/ChatContextBuilder.kt:76-91` — IM 形态硬规则「必须输出 3 条」(固定条数，测试 `<src>/app/src/test/.../PhoneChatTest.kt:44-51` 断言「连发 3 条」且不得出现 2~4 区间)，手机形态 2~4 条。
- 差异：下限被抬到 2~3 且措辞为硬性「必须」，非文档的 1~4 软区间；≤30 字约束则一致(默认 maxBubbleChars=30，ChatContextBuilder.kt:53)。

**🔀3. ()动作无白名单过滤，任意括号内容均被剥离渲染**
- 文档：§3.3 规则 4「白名单动作转 Action 段，非白名单保留为文本」(防误伤普通括号)。
- 代码：`MultiBubbleProtocol.kt:67` `actionRegex` 对任意 ≤40 字括号内容全量提取；渲染一律斜体灰字(`<src>/ui/phone/PhoneChatScreen.kt:457-468`)。
- 差异：普通括号文本(非动作)会被误剥离；(语音:…) 有特判(:183-199)，但白名单机制整体不存在。

**🔀4. 日常分享段(proactivity)在 JSON 对象通路被编排器丢弃**
- 文档：§3.3 PhoneReply/MultiBubbleReply 语义、§1.2 PS:820「随时分享生活日常」；协议允许 0~1 条独立一泡。
- 代码：`MultiBubbleProtocol.kt:112-113,121` 解析出 `proactivity` 泡，但 `<src>/chat/ChatOrchestrator.kt:158-159` 只消费 `reply.bubbles`——若模型按对象格式 `{"bubbles":[…],"proactivity":{…}}` 输出，分享泡静默丢失(fallback 通路按普通行合并成 TEXT 泡，不丢)。
- 差异：prompt 教了「单独一行」(ChatContextBuilder.kt:98)故主路径可用，JSON 对象通路存在丢弃缺陷。

**🔀5. 30s 看门狗与失败态缺失，失败降级为角色语感伪回复**
- 文档：§3.5/§1.8 「30s 无首 delta → TurnFailed + 『消息发送失败，请点击重试』」(parallel_player_messageError)；§2.1 直接复用该文案。
- 代码：无看门狗(仅 OkHttp readTimeout 60s，`<src>/ai/adapter/OpenAiCompatibleLlmAdapter.kt:46`)；流 Error 时追加兜底文案「(戳了戳你)……信号有点糟，再说一遍好不好？」并作为正常 assistant 泡落库(`ChatOrchestrator.kt:265-267,301`)；UI 无失败态/重试入口(PhoneChatScreen 仅「会话不存在」态)。
- 差异：失败被伪装成角色回复，用户无感知也无重试路径；与文档「显式失败+重试」语义相反。

**🔀6. 落库/回放格式为结构化列，非文档的「占位符保留文本」**
- 文档：§3.5 落库格式——每段一行、Voice 存 `[voice]转写`、Sticker/Image 存 `[sticker:名]`、Action 存 `(动作)`、占位符保留供模型 few-shot 自维持。
- 代码：每泡一行 ✅(`ChatOrchestrator.persistBubbles:188-208`)，但类型存 `kind` 列、动作存 `actionsJson` 列、content 存裸描述；回放时 `encodeBubbleLine` 重建行内标记(`<src>/chat/ChatContextBuilder.kt:122-138`)。
- 差异：语义等价(模型在历史里同样看到占位标记)，存储形态与文档规定不同；`<sep>` 因本就不存在而自动满足「永不落库」。

**🔀7. 语音条时长公式不同**
- 文档：§1.4/§3.6 `max(1, graphemeCount/speedCoefficient)`，BreakIterator 字素计数。
- 代码：`<src>/protocol/PhoneProtocol.kt:122-125` `voiceDurationSec` = 码点数 ÷4字每秒 向上取整，截断 1..60。
- 差异：未用字素簇(emoji/组合字符会算多)，无语速系数位；作为展示推导可接受，但与文档公式不符。

**🔀8. 「+」面板 v1 图片入口与文档不符(无相册/相机/权限弹窗)**
- 文档：§3.6 v1 图片=走相册/相机(`send(text, images)` 多模态)+ 首次相册权限 rationale 弹窗(parallel_permission_read_dialog_*，%s 代角色名)。
- 代码：图片入口是「图片描述」文本卡(`<src>/ui/phone/PhoneChatScreen.kt:709` CardComposer)，无相册/相机、无权限弹窗(全工程无 permission rationale)；位置/音乐/帖子/转账/代付五卡齐 ✅，转账免责文案「该转账不会用到你的真实财产」✅(:769)，位置卡无拟真免责文案(文档"同拟真免责思路"未做，minor)。

**🔀9. 卡片消息([卡片：…])解析链断裂——渲染层就绪、生产侧缺失**
- 文档：§1.4-B ContentType.DynamicCard、§1.9 五件套卡片、§2.2 照抄枚举语义。
- 代码：prompt 教模型输出 `[卡片：转账 520|备注]` 等(ChatContextBuilder.kt:95-96)，但 `MultiBubbleParser` 行内约定只有 sticker/img/voice/action，**无 card 规则**——模型卡片行会落为 TEXT 泡按原文渲染；`storageKey` 映射不含 card(`ChatOrchestrator.kt:320-326`)，用户面板卡片经 `sendCard` 以 kind="text" 原文落库(`<src>/ui/MainViewModel.kt:277-280`)。`parseCardLine`(`protocol/PhoneProtocol.kt:104`)在产线代码零调用；`kind="card"` 的渲染(CardBody，PhoneChatScreen.kt:383)、解码(MainViewModel.kt:1279,1300)与回放(ChatContextBuilder.kt:128)全部是**无生产者的死链**。
- 差异：端到端"LLM 输出卡片 → 卡片泡"这条路径实际不通，仅协议层 roundtrip 有单测(PhoneChatTest card 用例为纯函数测试)。

**⚠️10. 表情包：目录/名单校验/未知名降级全无，渲染为 emoji+描述**
- 文档：§3.3 规则 2 名单校验(未知名丢弃+灰字)、§3.6 `assets/phone/stickers.json` 目录 + 按名取图。
- 代码：无 assets 目录(find 实证)；sticker 泡渲染为固定 😄 emoji + 描述小字(PhoneChatScreen.kt:472-477)；任何名字都通过，无校验。占位渲染与段型解析本身有(✅ 部分)。

**⚠️11. 图片段未接生图，也无点击看大图**
- 文档：§3.6 图片泡=占位框+description 灰字，生成完换图(ImageGenAdapter 缝)；§1.4 点击看大图。
- 代码：`ImageBody` 为 🖼️ emoji+描述(PhoneChatScreen.kt:479-485)，无点击交互；`AvatarImageProvider` 未用于聊天 img 泡(仅头像/心声/朋友圈)。

**⚠️12. 语音条：静默渲染与点击播放 ✅，未读红点/预合成缓存 ❌，合成引擎与文档不符**
- 文档：§3.3 规则 3/§3.6 未读红点(AudioVoiceContent.unRead)、点击合成(火山/Edge TTS 适配器)、预合成缓存。
- 代码：静默渲染 ✅(VoiceBody 只显示「•)N″」不外显转写，PhoneChatScreen.kt:391-412)、点击播放 ✅；但合成用 Android 系统 TTS(`<src>/phone/VoicePlayer.kt:33-42`,Locale.SIMPLIFIED_CHINESE)，非 VolcanoEngine/EdgeTts 适配器；无未读状态，无预合成缓存(每次点击现场合成)。

**⚠️13. 空段丢弃 ✅，纯标点段未丢弃**
- 文档：§3.3 规则 5「空段/纯标点段丢弃」。
- 代码：`MultiBubbleProtocol.kt:177` 仅 `filter { it.isNotEmpty() }`，「。。。」类纯标点会成为 TEXT 泡。

**⚠️14. 协议块钉住 system ✅，stickerNames 注入缺失**
- 文档：§3.4 协议块含「表情名只能用： {stickerNames}」。
- 代码：协议块在 system prompt(ChatContextBuilder.kt:92-111，位置语义与文档钉住前缀一致，[当前形态] 行也有 ：109-111)；但无表情目录故 `{stickerNames}` 注入不存在。

**⚠️15. 打断/被顶替回合语义无挂载点**
- 文档：§3.5 分段器随回合创建/丢弃、被打断回合整回合不落库。
- 代码：聊天回合无打断机制(`sendChatMessage` 以 `state.sending` 防重入，MainViewModel.kt:249；无 supersede 路径)，文档要求的行为无从挂钩(非错误，但文档有、代码无)。

**⚠️16. 渲染事件模型不同 + LazyColumn 无稳定 key**
- 文档：§3.5 `PhoneSegmentReady(index,id,kind,text)`(id=回合起点-段内序号)、§5.2 要求 LazyColumn `key=id` 保证连发性能。
- 代码：以 Room 流 + `onAssistantSaved` 回调 + `streamDraft` StateFlow 替代(MainViewModel.kt:473-500，能力等价)；消息列表 `items()` 未带 key(PhoneChatScreen.kt:136-144，仅 typing/failed/bottom-pad 有 key)。

**⚠️17. 连体气泡圆角梯度未实现(lastInGroup 是死字段)**
- 文档：§3.6 相邻同 role Text 泡首条圆角上大下小、末条反之(isHead/isTail 纯 UI 推导)。
- 代码：分组 ✅(turnId 聚组+首泡头像+组内 2/5dp 间距，`toBubbleItems` MainViewModel.kt:1267-1313，BubbleGroupingTest 覆盖)；但 `lastInGroup` 计算后**无任何 UI 消费**(全工程 grep 仅赋值处)，手机页所有泡统一 `RoundedCornerShape(18.dp)`(PhoneChatScreen.kt:359)。

**⚠️18. 会话外摘要未用「发来一段语音/表情/图片」文案**
- 文档：§2.1 parallel_phone_toast_emoji/image/voicemessage 直接复用(列表/通知预览)。
- 代码：会话行摘要=最后一泡裸文本(`ChatOrchestrator.kt:206` `touchConversation(conversationId, bubble.text, …)`；MessagesScreen.kt:266 展示)——语音泡的摘要会把**转写全文**泄露到消息列表，表情/图片泡摘要=占位描述。

**⚠️19. 回溯有功能、无协议提示与版本轴**
- 文档：§1.8/§2.1 回溯后提示「因为回溯或从头开始行为，手机聊天记录发生变化」+ version_id 轴。
- 代码：回溯=删除该条及之后消息(`MainViewModel.rewindToMessage:564-573`)+ 确认弹窗，功能形态 ✅；提示文案无，无版本轴(本地删除式实现，可接受但文档文案未复用)。

**⚠️20. 输入校验：空输入静默忽略，无字数上限与提示文案**
- 文档：§2.1 parallel_phone_input_toast「输入不能为空或超出字数限制」。
- 代码：`MainViewModel.kt:249` `if (body.isEmpty() || state.sending) return`，无任何提示，无输入字数上限校验。

**⚠️21. 主动消息来源标注为 turnId 前缀替代**
- 文档：§1.4-C SourceDialogueType.Active* 家族(主动消息来源标注)。
- 代码：`turnId = "proactive-${UUID}"`(`ChatOrchestrator.kt:139`)用于触发判定与记忆前缀(:174-178「(TA 主动发来)」只进记忆摘要)；消息表无来源字段，回放/渲染不区分主动与普通回复。触发/分档/条数行为本体符合(ProactiveMessages 纯函数+单测)。

**❌22. 限速仪式感五态机(QuotaMode/PhoneQuotaGate)未实现**
- 文档：§3.4-D 五态 Normal/SlowEarlyWarning/SlowDown/RunningOut/Recover + phoneLine「【回复节奏】…」+ §1.7 MessageLimitStatus 形状照抄。
- 代码：全工程无 quota/限速/配额逻辑(grep 实证，仅注释声明"不配额")。【文档有、代码无，可能属本地版有意裁剪——文档亦标注 QuotaMode.OFF 为默认；但五态机+降速文案是 T7 交付物】随附：深度思考「(想了想)」逐轮提示(文档可选项)也未做。

**❌23. 协议开关与设置/调试项未实现**
- 文档：§3.4 `PhoneProtocolOptions.enabled`(关=现行为逐字节不变)、§3.7 设置键 `phone_protocol_enabled`/`phone_sticker_pack`、调试命令 `phone_state/phone_raw/phone_quota`。
- 代码：无协议开关(IM/手机两形态都强制带协议，无"关=单泡旧行为"模式)；设置仅有对话模式/长度(ChatPrefs)与离线阈值(PhoneChatPrefs)；调试仅有长按「…」模拟离线回归(PhoneChatScreen.kt:270-283)，覆盖文档三命令之一的触发面。【文档有、代码无(部分属形态取舍)】

**符合项(不展开)**：四能力段型+Action、解析健壮性五规则(畸形 JSON 回落/超 4 截断 MAX_BUBBLES=4/kind 不识别→TEXT/空回复兜底/无分隔单段合法，MultiBubbleParserTest 17 例)、「对方正在输入中...」首包前显示且首字到达即切换为流式草稿+光标(PhoneChatScreen.kt:489-515，ChatOrchestrator.streamDraft:78-79)、单泡长文兜底切分保证连发观感(ChatOrchestrator.kt:216-254)、主动消息三场景与冷热分档(ProactiveMessages.kt:34-77)、协议不出现计费字样的红线(PhoneChatTest.kt:53-60)。

## 三、文档未要求但代码存在的相关行为(过度实现/私加逻辑)

1. **单泡切分阈值比文档激进**：文档 §5.2② 只在「全回复无分隔**且**长度 >2×maxCharsPerSegment(=60)」时兜底切分；代码对任何 ≥20 字的单 TEXT 泡都强制切(`ChatOrchestrator.kt:307` SPLIT_MIN_CHARS=20)，即使模型已按协议输出了合法单短泡之外的中长单泡也会被再切。属实现私定参数，未按文档阈值。
2. **LLM 流 5xx 静默重试一次**：`OpenAiCompatibleLlmAdapter.kt`(writer 块)对未产出任何事件前的 HTTP 5xx/瞬时 IO 失败自动重发一次——文档无此要求，属相关容错私加(有注释约束"已有可见输出后绝不重试"，风险可控，列出备查)。
3. **泡间 900ms 人工连发延迟**：`ChatOrchestrator.kt:41` `bubbleGapMs=900L`——文档 §5.1 明确连发节奏策略在猫箱服务端、本地未定；代码自定 900ms 间隔，属合理自定但文档未规定。
4. **对话模式五选/回复长度三档**(ChatStyle.kt)与**状态条关键词伪规则**(StatusRule.kt)：超出 02 文档范围(分属 09/10 号及 03 截图域)，非本两文档要求，不计偏差，仅提示其 prompt 注入([对话模式] 块，ChatContextBuilder.kt:114)是协议块之外的超集注入。
5. **IM 陪伴聊天与 phone 双形态并存**(`ChatContextBuilder.phoneStyle` 线程内可变开关 + `withPhoneStyle`)：文档 02 只定义手机聊天单形态；双形态参数化是 GouXiang 自有设计，且导致同一协议有两套连发口径(见 🔀2)。

**本附录结论摘要**：52 条核对项中 11 条符合、14 条部分实现、10 条实现与文档不一致、17 条未实现。最值得优先处理的三处：(a) 02 文档卡片消息解析断链(🔀9，prompt 教了 `[卡片:…]` 但解析器不识别，渲染层为死代码)；(b) proactivity 分享泡在 JSON 通路被丢弃(🔀4)；(c) 01 文档头像生图未拼风格词缀(🔀1)。创作域(01 文档 14 条 ❌)属整块功能未开工，建议按文档 T1-T6 任务序单独排期。

---
---

# 附录 B：03 记忆升级 + 05 主动消息（子代理完整报告）

以下所有结论均以实际代码为准（已通读两份文档全文与 `app/src/main/java/com/neethu/gouxiang/` 下全部相关源码及单测）。计费/猫粮/会员/广告类条目按约定豁免（03 §1.7 配额、§1.8 翻倍门禁与广告解锁、05 §1.7 额度降速「明日4点」）；两文档无 3D/VRM 条目。

## 一、条目总数与统计

| 文档 | 核对条目 | ✅符合 | ⚠️部分实现 | ❌未实现 | 🔀不一致 |
|---|---|---|---|---|---|
| 03-memory-upgrade（记忆升级） | 33 | 17 | 8 | 2 | 6 |
| 05-proactive-messages（主动消息） | 25 | 6 | 4 | 11 | 4 |

- 03 号文档的**主链路（画像/事件总结/容量渐忘/注入/记忆页 UI）整体落地且质量较高**，缺口集中在「双通道触发分层、快照回退语义、回溯联动记忆删除、UI 分界行」。
- 05 号文档被实现方主动「收敛」（`ProactiveMessages.kt:4-11` 自述「三通道收敛为本地版两条」），**离线堆积账本（outbox）、后台周期生成、通知、回放分隔条、媒体降级、话题追问等轰炸核心机制整段缺失**，仅保留「进入手机聊天页时按阈值判定一次并连发」的简化形态。任务点名的「回归时连发」以变体存在（🔀），「离线消息堆积」未实现（❌）。

## 二、逐条偏差清单（仅 ⚠️/❌/🔀）

### A. 03-memory-upgrade.md（记忆升级）

**A1 🔀 双通道触发分层缺失，触发顺序与文档相反**
- 文档：§3.2c「达 everyTurns(5) 走 Extractor；达 summaryEveryTurns(15) 时先 Summarizer 后 Extractor（顺序固定）」，`DEFAULT_SUMMARY_EVERY_TURNS=15`，`memory_config` 扩第三参 `summaryTurns`（任务 4）。
- 代码：只有一个触发档（`everyTurns=5`，`chat/ChatMemoryCoordinator.kt:47,73`），达标时画像与总结两通道同时跑，无 15 轮总结档；执行顺序为**先画像后总结**（`ChatMemoryCoordinator.kt:144-150`），与文档「先 Summarizer 后 Extractor」相反（代码注释 ：36 反而引用了 03 §3.2c，但引的是自拟的「总结=经历层」说法，非文档原文）。

**A2 🔀 「两次独立快照互不清窗」改为共用快照、任一成功清整段**
- 文档：§3.2c「Summarizer 用快照 A（前 15 轮），Extractor 用快照 B（全部）；各自按自身成功与否回退——A 成功只删 A 覆盖的前缀，B 失败时窗口剩余部分留给下次提炼」。
- 代码：两通道共用同一份全量快照（`ChatMemoryCoordinator.kt:140`），`profileOk || summaryOk` 任一为真即按快照长度清掉整段窗口（:152-157）。失败通道的内容随窗口一起被清空，失去「下次重试」的素材；代码注释自辩「靠清单去重在下次重试」，但窗口已空、同段对话不会再来，该辩护对提炼通道不成立。

**A3 🔀 容量默认值与文档不符（20×1f vs 50×2f）**
- 文档：§3.2d「capacityBase=50、capacityMultiplierVip=2f；任务 5 断言③翻倍系数本期死配置恒 2f」。
- 代码：`BASE = 20`、`MULTIPLIER = 1f`（`chat/MemoryCapacity.kt:18-22`）。文档 §3.2d 虽写「50 为本地版默认」，但 2f 是任务 5 的显式断言项，实现取 1f 等于容量为 20 条，无翻倍语义。

**A4 🔀 「update_profile 工具」未按工具通道实现（能力由画像判定通道覆盖）**
- 文档：§3.2e 新增第四个 LLM 工具 `update_profile(name/identity)`，注册进会话工具挂载缝，用户说「以后叫我 X」时调用（任务 6）。
- 代码：本项目无任何工具注册体系；该能力由画像判定通道承担（`chat/MemoryExtractor.kt:310-328`，prompt 明确「用户明确说『叫我X/我是你Y』时更新」，:410）。用户可感知效果等价，但机制与文档不同。

**A5 🔀 种子数据「我的称呼」分类错误，画像区初始为「暂无」**
- 文档：§3.2b「我的称呼」= category=profile + subtype="name"（用户要 AI 怎么称呼我），与 relationship nickname 是两个方向、各占一行。
- 代码：种子把称呼行存为 `category="relationship"`（`data/SeedData.kt:44`：`MemoryEntity(category="relationship", content="我的称呼:老公")`），而 `MemoryBook.profile()` 只认 profile+subtype=name/identity（`MemoryExtractor.kt:163-170`）。结果：新装机的记忆页「我的称呼」显示「暂无」（`ui/memory/MemoryScreen.kt:274`），种子里的称呼只以「(关系)」形式进 prompt 注入，不进画像区——与猫箱「 UserName 画像条」语义不一致。

**A6 🔀 心声链路与 03 §3.3 约定不同（无 seed 投递、无开关、入口不在记忆页）**
- 文档：§3.3「EventSummarizer 每次产出事件记忆后把『最近 3 条 event + 人设』投递给心声管线；`memoryCardEnabled` 开关（默认关）；MemorySheetPanel 底部心声入口卡 CTA『开启』」（任务 9）。
- 代码：心声是独立半屏页（入口在角色主页占位卡，`ui/MainViewModel.kt:704-738`），由用户手动点「生成心声」按需生成（:753-819），取材为全量记忆 + 最近对话（`chat/InnerVoiceGenerator.kt:54-57` 注入 `MemoryInjection.block`）；无「总结完成自动投递 seed」、无 `memoryCardEnabled` 开关、记忆页无入口卡。功能本体存在（归 07 号域），但 03 声明的三个记忆侧需求均未按约定落地。

**A7 ⚠️ 画像通道无独立短轮门槛（judgeMinChars=16）**
- 文档：§3.2b ProfileTracker 构造参 `judgeMinChars(=16,短轮次不烧判定请求)`。
- 代码：只有全局静默门 `SILENCE_GATE_CHARS=12` 作用于整个窗口（`ChatMemoryCoordinator.kt:77,110-115`），画像通道本身无门槛；窗口凑满 5 轮寒暄（每轮 ≥3 字）即可触发画像请求。

**A8 ⚠️ 机械去重只有「category+content 逐字相同」，无余弦/embedding 去重**
- 文档：§3.2b/§3.2c「写路径去重走 MemoryDedup(0.92 余弦)」、风险 7 同款。
- 代码：`MemoryBook.upsertDeduped` 仅做 `category == mem.category && content == mem.content` 精确串匹配（`MemoryExtractor.kt:241-262`）；无 embedding 通道，语义近似的重复事件不会被合并，仅靠 LLM 清单去重（:357-361 现有事件进 prompt，:395「重复的经历不要重复输出」）兜底。

**A9 ⚠️ faded「0.45× 打分沉底、不进常驻槽」无承载（死常量 + 伪召回参数）**
- 文档：§3.2d「search() 增：status=faded 打 0.45×score；faded 不进常驻槽」。
- 代码：`FADED_SCORE = 0.45f` 已定义但全工程无任何消费点（`MemoryCapacity.kt:25`）；`MemoryInjection.block` 的 `query` 参数收下后从未使用（`MemoryInjection.kt:30`）——不存在检索打分，注入就是「时间倒序 TopN(6) + faded 另取 4 条」（:34-37）。降权实际只靠注入顺序沉底 + 后缀，无文档所述的打分语义。

**A10 ⚠️ 事件记忆无 `source` 字段（盖章缺 source="auto"）**
- 文档：§3.2c「客户端盖章 category/importance/source="auto"/subtype=''」。
- 代码：`MemoryEntity` 无 source 列（`data/MemoryEntity.kt:19-36`），盖章只写 category=event、importance=5（`MemoryExtractor.kt:363-380`）。功能无实损（本地无按 source 的逻辑），属 schema 缺项。

**A11 ⚠️ 「自动提炼记忆」开关 / profileSheetEnabled 无接线（autoSummarize 恒 true）**
- 文档：§3.2(h)「Options 加 profileSheetEnabled + 运行时 setProfileSheetEnabled；设置开关『自动提炼记忆』同时管 Summarizer」。
- 代码：`ChatMemoryCoordinator.autoSummarize` 存在但恒为 true、无任何写入点（`ChatMemoryCoordinator.kt:60`）；本项目无设置页，用户无法关闭自动总结（手动「立即总结」不受它约束的功能倒是存在）。

**A12 ⚠️ 重启/清空记忆入口缺失（clearAll 已备未挂接）**
- 文档：§3.2c「重启清空走既有 clear()（`parallel_restart_notice_subtitle` 全清语义）」、任务 11 验收「回溯只丢 event/commitment(画像保留)」。
- 代码：`MemoryBook.clearAll(characterId)` 已实现但零调用方（`MemoryExtractor.kt:233-235`，注释自认「语义预留」）；角色主页/设置均无「清空对话与记忆」入口，`parallel_restart_notice_subtitle` 的确认弹窗不存在。

**A13 ⚠️ 记忆页七态机未按文档保留门禁两态定义**
- 文档：§3.2g「门禁两态(NoRights/NoAssets)本地版恒不出现但**保留 sealed 定义**以便后续商业化回归」。
- 代码：`MemoryUiState` 收敛为布尔字段（loading/generating/loadFailed），注释明确「门禁两态……不建模」（`ui/memory/MemoryUiState.kt:8-11`），无 sealed 结构可回归。

**A14 ⚠️ 记忆页「说明」帮助入口为空占位**
- 文档：§3.2g 布局首行「[说明 parallel_memory_bot_tips]」帮助弹层（风险 9 还要求用它与 faded 后缀对齐口径）。
- 代码：顶栏说明位是一个空 `Box(Modifier.size(22.dp))`（`ui/memory/MemoryScreen.kt:126-127`，注释「占位」），无弹层。

**A15 ❌ 回溯联动删除记忆（event/commitment 晚于回溯点的条目）未实现**
- 文档：§3.2c「对话回溯时删 category∈{event,commitment} 且 updatedAt>回溯点 的条目，画像/称呼/身份/偏好/关系类不动」（任务 7；`MemoryStore.deleteWhere(sinceMs, categories)`）。
- 代码：`MainViewModel.rewindToMessage` 只删消息行并刷会话行（`ui/MainViewModel.kt:564-573`），完全不触碰 memories 表；`MemorySource` 无 deleteWhere（`MemoryExtractor.kt:137-146`）。回溯后已总结的事件记忆成为「幽灵记忆」继续被注入——这是文档明确要求而彻底缺失的行为。

**A16 ❌ 隔夜总结兜底（闲置 8h+ 补一次 Summarizer）未实现**
- 文档：§3.2c「另挂 GreetingScheduler 空闲轮询做『隔夜总结』兜底——会话闲置 8h 以上且窗口非空补一次」。
- 代码：无任何闲置调度；窗口只在回合收尾触发（`ChatMemoryCoordinator.kt:101-118`），久未聊天时持久化窗口只会一直躺着。

**A17 ⚠️ ai_cmd 扩展（profile_get/profile_set/memory_fade/memory_config 三参）不存在**
- 文档：§3.2(h) 表 + 任务 10。
- 代码：本项目无 ai_cmd/调试命令体系（对应调试改为 UI 手势：长按手机聊天顶栏「…」模拟离线回归）；`configure(summaryTurns)` 第三参不存在，`everyTurns/minChars` 是构造期定死的 val（`ChatMemoryCoordinator.kt:52-57`，运行时不可调）。

*03 号已核对且符合的代表性证据（不计入偏差）：subtype 字段与迁移（`data/MemoryEntity.kt:25-29`、`data/GouXiangDatabase.kt:44-52`）、分带不落库现场算（`MemoryCapacity.band:49-64`）、注入后缀「(这段记忆已经模糊)」（`MemoryCapacity.kt:74` + `MemoryInjection.kt:56`）、容量条 X/Y 与 usedCapped 钳制（`MemoryScreen.kt:337` + `MemoryCapacity.kt:33`）、遗忘确认三连（`MemoryScreen.kt:207-224`）、称呼/身份编辑与特殊字符校验（`MemoryScreen.kt:417,448`）、静默门 12 字/窗口硬顶 20 轮/解析失败保留窗口（`ChatMemoryCoordinator.kt:77,80`、`MemoryExtractor.kt:301`）、绝不安造红线（`ProfilePrompts.system:411-413`）、单测覆盖（`app/src/test/java/com/neethu/gouxiang/chat/MemorySystemTest.kt` 17 例）。*

### B. 05-proactive-messages.md（主动消息）

**B1 🔀 触发模型整体收敛：三通道 → 「进手机聊天页时判定一次」（离线堆积→现场生成）**
- 文档：§1.3/§3.2 三通道（离线期服务端/Worker 堆积生成入 outbox；回归 drain 回放 ≤8 条；前台 pollForeground 节拍）。
- 代码：仅两个触发点——该会话从未有过主动回合（`turnId` 无 `proactive-` 前缀）→ FIRST_HELLO；或距上次对话（任意消息，`conv.lastAt`）≥2h → CHECK_IN/DAILY_SHARE（`protocol/ProactiveMessages.kt:66-77`、`ui/MainViewModel.kt:507-518`）。触发时机仅在**打开手机聊天页瞬间**（`MainViewModel.kt:205-215`，`openChat` 内 phoneMode 分支），生成是当场发起 LLM 回合而非回放存量。离线期间什么也不产生。

**B2 🔀 Kind 集合与决策规则不一致：新增 FIRST_HELLO，缺 FOLLOW_UP/MEDIA_STUB**
- 文档：§3.2B `ProactiveKind{DAILY_SHARE, CHECK_IN, FOLLOW_UP, MEDIA_STUB}`；前台有 lastTopic 时 40% 概率追问。
- 代码：`Kind{FIRST_HELLO, CHECK_IN, DAILY_SHARE}`（`ProactiveMessages.kt:28`）。12h 冷档判 CHECK_IN 与文档一致（:19,76）；但无 FOLLOW_UP（依赖 lastTopic，见 B6），MEDIA_STUB 不由调度器挑选的约定无从谈起（本地连 prompt 场景指令都不含媒体标记，`sceneLine:34-47`）。

**B3 🔀 回归连发条数上限 3 vs 文档 offlineBatchMax=8**
- 文档：§3.2C `offlineBatchMax=8`（单次回归回放上限）。
- 代码：`MAX_INITIAL = 3`、`MAX_CATCH_UP = 3`（`ProactiveMessages.kt:22-25`），且是 prompt 软约束（「连发 1~3 条」），经 F4 手机档协议落成 2~4 泡。

**B4 🔀 「---」分段最小协议 → 复用 F2 多泡协议**
- 文档：§3.2B①一次生成按单独一行「---」拆段。
- 代码：直接走 `respondProactively` → `MultiBubbleParser`（换行/JSON 多泡协议，`chat/ChatOrchestrator.kt:112-128`）。用户可见效果等价（多条连发），分段实现不同（文档允许 02 就绪后替换，此处属可接受的等价替代，仍列为差异）。

**B5 ❌ 离线堆积账本 ProactiveOutbox（Room 表、去重、drain、clearAll）整段缺失**
- 文档：§3.2A + 任务 T1。
- 代码：全工程 grep 无 outbox/ProactiveEntry/ProactiveOutboxStore（已验证），无 `proactive_outbox` 表（`data/GouXiangDatabase.kt` 8 实体无此项）。「离线消息堆积」这一任务点名的行为不存在。

**B6 ❌ lastTopic 话题记忆与 FOLLOW_UP 素材链缺失**
- 文档：§3.2B「TurnCompleted 后取最后一条 user 消息截短存 `KEY_LAST_TOPIC`(≤60 字)」。
- 代码：无该 prefs 键、无采集逻辑（`phone/PhoneChatPrefs.kt` 仅 1 个阈值键）。

**B7 ❌ OfflineGate（前后台门/5min 抖动豁免）缺失，离线时长口径不同**
- 文档：§3.2C 以退后台/回前台时刻计算离线时长，5 分钟抖动豁免。
- 代码：离线时长 = `now - conv.lastAt`（最后一条任意消息时间，`MainViewModel.kt:212`），无前后台状态、无豁免窗。

**B8 ❌ ProactiveEngine 参数组与门组缺失（日上限/安静时段/批次上限/回放间隔）**
- 文档：§3.2C `Config{offlineBatchMax=8, replayIntervalMs=2500, inAppBatchMax=2, dailyCap=5, coldMinutes=60, hotMinutes=24h}`；§3.2E ProactivePlanner 门组（enabled/quiet 22-9/dailyCap/堆积上限/前台宽限）。
- 代码：无任何日上限、安静时段、批次上限；「回放间隔」实际是 F2 通用泡间 900ms（`ChatOrchestrator.kt:41`）而非轰炸专配。角色可在深夜被反复触发主动消息且无频次上限（唯一软上限是 2h 阈值 + 每次进页一次）。

**B9 ❌ ProactiveWorker（WorkManager 离线周期生成）缺失**
- 文档：§3.2E + 任务 T4。
- 代码：无 WorkManager 依赖与任何 Worker（已 grep 验证）；App 退到后台/被杀后完全静默。

**B10 ❌ 通知整段缺失（双渠道/push_title 标题「你收到…的 N 条消息」/push_text_default 正文/深链只带 id/对账规则）**
- 文档：§3.2E、§3.4 数据流。
- 代码：无 Notification/渠道/深链代码（`MessagesScreen.kt:76` 的铃铛只是固定行图标）。离线触达能力为零，也没有文档风险节要求的「角标 + 会话列表摘要条」兜底。

**B11 ❌ 「离线期间 Ta 发来了 N 条消息」回归分隔条缺失**
- 文档：§3.2F（句式 `parallel_moments_newmessage_num`，插入模型 TipsSessionMessage）。
- 代码：消息流只有气泡组（`ui/phone/PhoneChatScreen.kt:136-144`），无任何分隔条/灰字系统行。

**B12 ❌ MEDIA_STUB 媒体降级灰字（「发来一张图片」摘要条）缺失**
- 文档：§3.2B③/§3.2F：离线回放时媒体消息降级为文字摘要条。
- 代码：主动回合若模型自发输出 `[sticker:]/[img:]` 会按普通表情/图片泡渲染（`PhoneChatScreen.kt:384-385`），场景指令（`ProactiveMessages.sceneLine`）也不引导产出媒体标记；无摘要降级机制。

**B13 ❌ 回溯/重启清空堆积 + 提示（`parallel_phone_restart_toast` 语义）缺失**
- 文档：§3.2A `clearAll(reason)`、§1.7 边界「回溯/重启后离线堆积失效 + 提示」、§3.2F「上下文重建后主动消息队列清空的提示」。
- 代码：无 outbox 可清；回溯（`MainViewModel.kt:564-573`）与回溯确认弹窗（`PhoneChatScreen.kt:200-215`）均无任何「记录已变化」提示，也不联动记忆（同 A15）。

**B14 ⚠️ 离线阈值「可配」未接线：PhoneChatPrefs 是死代码**
- 文档：§3.2C/B「offlineThresholdMs 可配」的本地收敛声明（`ProactiveMessages.kt:15`「默认 2 小时，可配」）。
- 代码：`PhoneChatPrefs.offlineThresholdMs/setOfflineThresholdMs` 读写齐全（`phone/PhoneChatPrefs.kt:20-30`）但全工程无调用方；`MainViewModel.thresholdMs` 是私有 var，初始化为常量后再无人改（`MainViewModel.kt:639-640`）。

**B15 ⚠️ 30s 流式看门狗缺失（仅 HTTP 层 60s readTimeout）**
- 文档：§1.6/§1.7 ChatTimerInterceptor 30s 超时兜底、§2.2 列为复用参数参考。
- 代码：无看门狗；LLM 适配器有 `readTimeout(60s)`（`ai/adapter/OpenAiCompatibleLlmAdapter.kt:46`），流中途挂起仍可能长时间卡住「正在输入」态。

**B16 ⚠️ 「我在你离开时发了 N 条消息」记忆回执行缺失（有相关替代）**
- 文档：§3.3 集成点「每批回放后写一条回执进记忆」。
- 代码：无回执行；但主动回合文本会以「(TA 主动发来)」前缀进入记忆提炼窗口（`ChatOrchestrator.kt:170-179`），主动内容可沉淀为事件记忆——方向相关、载体不同。

**B17 ⚠️ 通知权限引导三件套 + 频控（首轮 20 条后引导/拒绝冷却 3 天/2 次转 14 天）缺失**
- 文档：§3.2F 回归引导 + 任务 T6。
- 代码：无引导弹窗与频控（本地也无通知可引导；按非计费项仍如实列出）。

*05 号已核对且符合的代表性证据：WAITING 态「对方正在输入中...」（`PhoneChatScreen.kt:501`，且流式草稿直接可见超出文档）；失败兜底上屏（`ChatOrchestrator.kt:161,304` PROACTIVE_FALLBACK_TEXT，`PhoneChatTest.kt:141-149` 锁定）；mockPush 等价调试口（长按「…」→ `simulateOfflineReturn`，`PhoneChatScreen.kt:271-283` + `MainViewModel.kt:673-685`）；多条消息同组归属（turnId 前缀 `proactive-` 分组连发，`ChatOrchestrator.kt:139,188-209`）；单条长度约束（手机档 ≤24 字，严于文档 30 字）。*

## 三、文档未要求但代码中存在的相关行为（过度实现/私加逻辑）

1. **「立即总结」手动按钮**（`MemoryScreen.kt:342-357` + `ChatMemoryCoordinator.summarizeNow:171-192`）：03 号文档无此 UI；且它只走事件总结通道、不更新画像（项目自述报告 F3 也注明），成功后清全窗口——该「手动成功清全窗」语义在文档的快照规则之外。
2. **FIRST_HELLO 首次进入主动打招呼**（`ProactiveMessages.kt:22,35-38,61-73`）：05 号文档场景集里没有「首次进入先开口」这一档，属本地新增场景（含"该角色从未主动过则必触发"的一次性判定，`MainViewModel.kt:510`）。
3. **主动消息回合可「重新生成」**（`MainViewModel.kt:594-619`：无用户输入可归因时重走主动场景，固定指令「(刚刚那条没说好，重新组织一下)」）：05 号文档的 WAITING/FAILED 生命周期里没有 regenerate 分支，属合理的本地扩展。
4. **死代码/死配置**（建议清理，避免误读为已实现能力）：`MemoryCapacity.FADED_SCORE`（无消费）、`MemoryInjection.block` 的 `query` 参数（未使用，使「记忆问答召回」实为全量注入而非检索）、`PhoneChatPrefs` 全部（未接线）、`MainViewModel.thresholdMs`（恒默认值）、`MemoryBook.clearAll`（未挂接）、`ChatMemoryCoordinator.windowLine`（仅单测消费）。
5. **种子事件记忆预置**（`SeedData.kt:52-58`：苏馨玥预置一条 event「两人一起看了新房…」）：03 号文档的记忆均由总结产生，种子直接预置事件属演示数据私加（同时它把称呼放错分类，见 A5）。

## 四、结论要点

- 03 号文档主链可用，修复优先级建议：**A15 回溯联动删记忆 > A1/A2 触发分层与快照回退 > A3 容量参数 > A5 种子称呼分类 > A9/A12**。
- 05 号文档当前是「进页触发版」而非「离线轰炸版」：若验收口径要求「离线消息堆积、回归时连发」，需补 **B5(outbox)/B1(堆积→回放)/B8(频次与安静门)/B10(通知)（可选）**；若接受本地收敛口径，则 B 系大多为有意裁剪，但 B14（死配置）与 B13（回溯无提示）仍应修复以免误导。

---
---

# 附录 C：04 手机聊天（子代理完整报告）

**路径缩写**(均为 `app/src/main/java/com/neethu/gouxiang/` 下)：
- `[Screen]` = `ui/phone/PhoneChatScreen.kt` · `[Guide]` = `ui/phone/PhoneChatGuideScreen.kt`
- `[Proto]` = `protocol/PhoneProtocol.kt` · `[Multi]` = `protocol/MultiBubbleProtocol.kt` · `[Proa]` = `protocol/ProactiveMessages.kt`
- `[VM]` = `ui/MainViewModel.kt` · `[Orch]` = `chat/ChatOrchestrator.kt` · `[Ctx]` = `chat/ChatContextBuilder.kt` · `[Style]` = `chat/ChatStyle.kt`
- `[Prefs]` = `phone/PhoneChatPrefs.kt` · `[Voice]` = `phone/VoicePlayer.kt` · `[Main]` = `MainActivity.kt` · `[Daos]` = `data/Daos.kt`

## 1. 统计

共拆出 **51 条**可独立验证的具体要求(不含纯猫箱逆向描述、3D、计费货币本体)：

| 结论 | 条数 | 占比 |
|---|---|---|
| ✅ 符合 | 24 | 47% |
| ⚠️ 部分实现 | 11 | 22% |
| ❌ 未实现 | 10 | 20% |
| 🔀 与文档不一致 | 6 | 12% |

✅ 的主要覆盖：iMessage 皮肤骨架(顶栏 ✕+名字+「由 AI 生成」+「…」、左白右黄泡、组内共享头像)、语音条「•)N″」全链路(4 种格式归一化、~4字/秒时长、TTS 播放/占位)、六宫格六件套、转账/代付/位置表单(含「该转账不会用到你的真实财产」声明行 `[Screen]:766-773`)、13c 说明页结构与三条内容(免费说明改写符合"无计费"红线)、主动消息三档触发(首次 hello/2h 阈值/12h 冷热分档，`[Proa]:16-77`)、长按回溯/重新生成、回合失败兜底、「对方正在输入中...」、输出协议主体(连发短消息/≤30字/媒体占位/()动作)。

## 2. 逐条偏差清单(仅 ⚠️/❌/🔀，按主题分组)

### A. 最高严重度：卡片消息端到端断链

**A1. ❌ 卡片消息没有任何写入路径，kind=card 渲染分支为死代码**
- 文档：§3.2.B/§3.2.E——用户表单提交编码为标记后发送并入 `PhoneMessageStore`，AI 侧 `[卡片:类型|参数]` 由解析器解码为 Card；§2.1 `card_send` 复用。
- 代码：`parseCardLine` 在主代码**零调用**(`[Proto]:104-113` 仅有定义)；`[VM]:277-280` `sendCard` 把载荷编码成 `[卡片:转账 520|备注]` 后走 `sendChatMessage`，而 `[VM]:254-262` 用户消息硬编码 `kind = "text"`——卡片以**原始标记文本**显示在黄泡里；`MultiBubbleParser` 不识别「[卡片:…]」(`[Multi]:176-228` 只有 sticker/img/voice 三种行内约定)，模型按 prompt 教学输出的卡片标记(`[Ctx]:95-97`)同样退化为纯文本泡。`CardBody`/`kind=="card"` 分支(`[Screen]:383,415-454`；`[VM]:1279,1300`)不可达。
- 差异：协议定义、编解码、单测(`PhoneChatTest.kt:151-222`)俱全，但缺最后一环——入库时判卡置 `kind="card"`/解码回填，整条卡片消息类型实际未打通。

**A2. 🔀 卡片类型集合：文档六型含「商品」，工程六型含「图片」**
- 文档：§3.2.B `PhoneCardType { TRANSFER, PAY_FOR, GOODS, LOCATION, MUSIC, POST_LINK }`；§1.5 商品信息输入(PS:806-807)。
- 代码：`PhoneCardKind` = IMAGE/MUSIC/POST/LOCATION/TRANSFER/PAY_FOR(`[Proto]:22-40`)，全工程「商品/GOODS」零命中。
- 差异：工程跟随 UI 基准 13b(「发送图片」而非商品)，与文档 schema 不一致；商品卡(名称+AI 补全价格)整体缺失。

### B. 特殊消息渲染

**B1. ⚠️ 表情包：占位渲染，且形态与文档相反**
- 文档：§1.3 表情包=「大图无气泡」；§3.2.F「Sticker 全宽无气泡」；§3.2.F StickerPanel(assets 24 张，json `{id,asset,name,width,height}`)。
- 代码：`StickerBody` 渲染为气泡内 😄 emoji + 单行描述(`[Screen]:471-477`，外层仍有白/黄泡底 `[Screen]:360-379`)；无任何贴图资源与面板。
- 差异：占位可接受，但"全宽无气泡"的形态要求未实现，且无真实贴图。

**B2. ❌ 表情面板未实现**
- 文档：§3.2.F StickerPanel(P0 内置 24 张)；§1.5 表情面板选图上行。
- 代码：输入条 😊 为纯 `Text`，无点击行为(`[Screen]:566`)。
- 差异：入口存在但不可交互，面板/资源清单整体缺失。

**B3. ⚠️ 图片消息：纯占位，生图能力未接入**
- 文档：§3.2.B Image{imagePath, caption=画外音, genPrompt}，07 管线未落码时允许 caption-only 降级；§1.5 画外音浮层(bot_sendpicture 族)。
- 代码：`ImageBody` = 🖼️ emoji + 单行描述(`[Screen]:479-485`)；聊天 img 泡不接生图，无画外音浮层。
- 差异：工程其余页面(心声 `[VM]:787-798`、朋友圈 `[VM]:964-975`)**已具备**生图 provider，手机聊天 img 未复用——文档允许的"未落码降级"前提在本工程已不成立。

**B4. ❌ 用户发图 ≠ 相册选图**
- 文档：§1.5 图片输入=相册选图→上传状态机。
- 代码：「发送图片」打开的是"图片描述"文本表单(`[Screen]:709`)，无相册、无上传链路。
- 差异：被文本描述卡替代。

**B5. ⚠️ 转账/代付卡片无状态机**
- 文档：§3.2.B `Transfer.settled`(双向已收)/`PayFor.accepted`(请求型待确认三态)。
- 代码：`CardPayload` 仅 kind/title/note 三个字段(`[Proto]:46-53`)，卡片为静态展示。
- 差异：转账"给 Ta/Ta 转来"两态、代付"确认成交"语义均无承载字段。

**B6. ⚠️ 金额为自由文本，无校验**
- 文档：§3.2.E 金额统一分(Long)，表单层做元转换与校验(对齐 `input_toast`)。
- 代码：金额存于 `title: String`(`[Proto]:49-52`)，输入"abc"也可发送；仅空值时按钮置灰(`[Screen]:778-782`)，无数值校验、无错误 toast。
- 差异：金额无类型约束(注：本地版无真实扣费，类型化金额属 schema 规范性偏差)。

**B7. ❌ 链接类(音乐/帖子)无"粘贴链接→解析"链路**
- 文档：§1.5/§3.2.F 粘贴链接→服务端/本地解析出标题摘要，失败统一 `parallel_phone_fetch_fail_toast`(§2.1 标"复用")。
- 代码：音乐/帖子表单是普通文本框(`[Screen]:710-711`)，全工程无 URL 解析、无 `fetch_fail` 类 toast。
- 差异：统一交互模式(链接输入+失败兜底)整体缺失。

### C. 输入面板

**C1. ⚠️ 括号动作一键 () 插入缺失**
- 文档：§1.5 手机聊天形态=sticker+brackets+extPanel 三开关，`BracketInsertSelectionResolver` 一键插入 ()。
- 代码：输入条仅 😊/🎤/＋(`[Screen]:565-577`)，无 () 插入钮；()动作只在模型输出侧实现(`[Ctx]:97`)。
- 差异：三开关缺二(表情、括号)。

**C2. ⚠️ 输入条 🎤(声波/语音入口)为装饰**
- 文档：UI README 14「底部『自由输入…』+表情/声波/+」；§1.5 语音输入(按住录音上行)。
- 代码：`Text("🎤")` 无点击(`[Screen]:568`)。
- 差异：语音条**接收/播放**已实现，但用户**发送**语音入口缺失。

### D. 说明页(13c)

✅ 结构与内容符合(入口「…」`[Screen]:260-284`；黄底 banner+条目 `[Guide]:87-131`；免费说明三则为本地化改写符合"无猫粮/扣费"红线；长按回溯/重新生成条目 `[Guide]:121-124` 与实际功能一致)。无偏差。

### E. 主动消息 / 轰炸

**E1. ❌ 离线轰炸无系统通知、无后台积累**
- 文档：§2.1 `push_title`(你收到「%s」的 %d 条手机消息)/`push_text_default` 标"复用"；§3.3 通知渠道复用双渠道骨架；§3.3 主动消息由 outbox 为事实源、离线堆积。
- 代码：全工程无 Notification 使用；主动消息**仅在进入手机聊天页那一刻**判定触发(`[VM]:207-215, 507-518`)，上限 3 条(`[Proa]:22-25`)。
- 差异：「上线后被轰炸」的推送体验未实现，收敛为"进页触发"；文档标注消费 05 号 ProactiveEngine，该引擎工程内亦无对应物。

**E2. ⚠️ PhoneChatPrefs 写而不读(死配置)**
- 文档：§3.2.F PhoneChatPrefs 五键；§3.3 阈值可配。
- 代码：`[Prefs]:20-30` 读写器齐全但**零调用方**；`[VM]:640` 用硬编码默认 2h(`private var thresholdMs`)，无设置页入口(工程亦无设置页)。
- 差异：文档 5 键只落了 1 键且未接线，"可配置"不成立。

**E3. 🔀 首条打招呼：固定模板 → LLM 场景生成**
- 文档：§2.1 `parallel_phone_bot_sayhi=我是%s` 标"复用"。
- 代码：`FIRST_HELLO` 场景指令让模型自主连发 1~3 条开场(`[Proa]:35-38`)，手机会话种子为空壳会话行(`[Daos]:283-293`)。
- 差异：功能等价(进页即被打招呼)，但模板文案与「你已添加了Ta」仪式感(见 E4)都未用。

**E4. ❌ 「你已添加了%s,开始聊天吧」新会话系统提示缺失**
- 文档：§2.1 `sym_addnewfriend` 标"复用"(新会话第一条系统提示)。
- 代码：全工程无该文案、无系统提示行类型(消息只有 user/assistant 两种 role)。

### F. 限速 / 降速(计费无关行为形态)

**F1. ❌ PHONE_LIMITED 降速态整体未实现**
- 文档：§3.2.A `PhoneModeState`(PHONE_LIMITED、consumeReply、limitedUntilMs=次日08:00、90% 预警≤3)；§3.2.C ⑤ 降速注入(每条≤15字、间隔拉长、语气敷衍)；§3.4「降速≠禁言」的行为保留(仪式感配额，设置页可调/关)。
- 代码：全工程无 quota/limited/降速 任何实现(仅注释声明"本地版无猫粮、不配额" `[Proa]:11`)。
- 差异：计费货币可有意去除，但文档明确把"降速"定义为**保留的行为形态**(含可关闭的仪式感配额)，现完全缺失。

**F2. ❌ 限速文案族缺失**
- 文档：§2.1 `parallel_phone_overuse_block_tips`(回复速度降低，明日4点回复，改写复用)、切换/恢复 toast 族(§3.4 改写「已生效，今日不限次」等)。
- 代码：无对应字符串。

**F3. 🔀 模式状态机：文档设计为可切换模式，工程改为会话静态属性**
- 文档：§3.2.A `PhoneMode{BASIC,PHONE,PHONE_LIMITED}`+switchTo/modeGeneration；§3.3 InputMode.PHONE+设置开关组；§2.1 模式切换 toast 族。
- 代码：形态=会话行属性 `mode=="phone"`(`[Daos]:287`；`GouXiangRepo.kt:241` `ConversationRow.isPhoneChat`)，从消息列表角标进入(`MessagesScreen.kt:105-109,248-259`；`[Main]:121-126,162-183`)；无运行时切换、无 PhoneModeState、无设置开关。
- 差异：与 UI 基准(第二条会话带角标)一致、与文档架构设计不一致；后果是 F1/F2 的配额降速无处挂载、`PhoneModeChanged` 事件无对应物。

### G. 长按操作

**G1. ⚠️ 长按菜单缺「复制」**
- 文档：§3.2.F 长按操作条(复制/撤回——本地 v1 只做复制)。
- 代码：长按弹「回溯/重新生成」(`[Screen]:793-815`)，无复制项。
- 差异：回溯/重新生成超出 v1 范围(但为 13c 明示功能，属合理超集)；文档点名的最小项「复制」反而缺失。

**G2. ⚠️ 回溯后无「记录已变化」提示**
- 文档：§2.1 `parallel_phone_restart_toast`(因为回溯…手机聊天记录发生变化)标"复用"。
- 代码：`rewindToMessage` 静默删除该条及之后消息并刷新会话行(`[VM]:564-573`；`GouXiangRepo.kt:192-193,207-215`)，无 toast。
- 差异：回溯本体 ✅(含二次确认弹窗)，提示反馈缺失。

### H. 输出协议(§3.2.C)

**H1. 🔀 连发区间 2~4 条，文档为 1~5 条**
- 文档：§3.2.C ①「一次回复 1~5 条短消息，每条 ≤30 字」。
- 代码：手机档 `2 to 4`×30 字(`[Ctx]:79`；`[Style]:98-103`，更长档 3~4×45)；`[Ctx]:70-71` 注释自称"对齐 §3.2.C 1~5 条"。
- 差异：实现比文档更窄(下限抬高到 2)，注释与文档表述不符；属参数级偏差。

**H2. ⚠️ 「0~1 条无关日常分享」在 JSON 输出形态下被丢弃**
- 文档：§3.2.C ③ 每次回复可附带 0~1 条日常分享。
- 代码：prompt 有此规则(`[Ctx]:98`)；解析器支持 `proactivity` 字段(`[Multi]:112-121`)但编排器只消费 `reply.bubbles`(`[Orch]:158-159`)——模型用 `{"bubbles":[…],"proactivity":{…}}` 输出时该泡静默丢失；换行兜底形态下则混入普通泡(视觉可接受)。
- 差异：半实现：教学有、解析有、消费断。

**H3. ❌(并入 A1)②媒体占位中 `[卡片:…]` 教学有效、解析无效**——见 A1。

### I. 其他文案复用

**I1. ❌ 输入校验反馈缺失**
- 文档：§2.1 `parallel_phone_input_toast`(输入不能为空或超出字数限制)标"复用"。
- 代码：空输入静默 no-op(`[VM]:249`)；无字数上限；且**回合进行中点击发送会清空草稿并静默丢弃**(`[Screen]:587-590` 清 draft → `[VM]:249` sending 拦截直接 return)。

**I2. 🔀 回合失败：无「加载失败」toast，改为角色语气兜底泡**
- 文档：§2.1 `parallel_phone_input_fail=加载失败` 标"复用"(回合失败 toast)。
- 代码：流失败/空回复产角色口吻兜底泡「(戳了戳你)……信号有点糟，再说一遍好不好？」(`[Orch]:265-267,301`；主动回合 `[Orch]:304`)。
- 差异：形态不同(泡 vs toast)，体验上更拟人；计为偏差但属可接受替代。

**I3. ❌ 降级摘要四件套缺失**
- 文档：§2.1 `toast_emoji/image/voicemessage`、`previous_version_card`(「发来一个表情/一张图片/一段语音」「发送了一张卡片」)标"复用"(通知/分隔条/未知卡兜底)。
- 代码：无这些摘要；未知卡解析失败显示原始标记文本(见 A1)；会话行摘要格式 `[转账] 520` 来自 `CardPayload.summary()`，而该方法主代码**零调用**(仅测试用)。

### J. 连发节奏与竞态(§3.3/§5.2)

**J1. ⚠️ 泡间隔固定 900ms，非文档的 600~1200ms 随机**
- 文档：§3.3 回合出口「条间隔 600~1200ms 随机，参照打字机七字段语义」。
- 代码：`bubbleGapMs = 900L` 固定值(`[Orch]:41,195`)。
- 差异：值在区间内但无随机抖动，节奏感单一。

**J2. ⚠️ 批次回放竞态：防了打断，但用户输入会静默丢失**
- 文档：§5.2 缓解——回放中发送延迟到 IDLE+「Ta 还有 N 条没说完」手动展开兜底。
- 代码：`sending=true` 覆盖整回合(含逐泡入库)，新发送被静默拦截(`[VM]:249`)，无排队、无「还有 N 条」提示；叠加 I1 的丢草稿问题。
- 差异：避免了"半批悬挂"(文档担心的场景不会发生)，但用户侧无任何反馈通道。

**J3. 🔀 语音条不自动连播，改为点击播放**
- 文档：§3.3 回合出口「语音条逐条 session.speak()」；§3.2.B Voice.audioPath 到达即合成。
- 代码：语音条点击才 TTS(`[Screen]:369-375`；`[VM]:634-637`；`[Voice]:51-81`)，回合结束无自动播放，无预合成。
- 差异：点击播放与 14 基准 UI(点语音条)一致，但偏离 §3.3 的自动回放设计；副作用是 AI 发语音条时用户无提示音。

**J4. ⚠️ 连发分组的间距语义反转**
- 文档：§3.2.F/§2.2 组间距 **>** 组内距(组内共享头像块聚拢)。
- 代码：组间 `spacedBy(2.dp)`(`[Screen]:131`)，组内泡间 5dp(`[Screen]:311`)——组间反而更近，用户消息与回复首泡几乎贴合，分组聚拢感缺失(共享头像本身 ✅)。

### K. 存储与事件(设计蓝图级，记录不苛责)

**K1. 🔀 存储：文档的 ConversationStore+phone_messages.db 双轨 → 工程单表 messages+kind 列**
- 文档：§3.2.B Text/Voice 走既有文本行(前缀标记 [语音]/[表情])，Card/Image/Sticker 另存独立 Room 库。
- 代码：全部消息统一存 `messages` 表，类型由 `kind`(text/sticker/img/voice)区分、卡片载荷存 `actionsJson`(`[Orch]:319-326`；`MessageEntity.kt:23-31`)。
- 差异：结构不同但功能等价(历史回放 `[Ctx]:122-138` 同样按 kind 还原)，工程自有架构下合理。

## 3. 代码里有、文档未要求的相关行为

1. **调试入口**：长按顶栏「…」触发"模拟离线回归"主动消息(Toast 即时确认)(`[Screen]:270-283`；`[VM]:673-685`)——文档 §3.3 的调试命令(ai_cmd 族)在本工程的替代形态。
2. **回溯二次确认弹窗**(`[Screen]:200-215`)与说明页 BackHandler/触摸防穿透(`[Guide]:50`；`[Main]:188-197`)——超出文档的体验加固。
3. **对话模式(09)/回复长度(10)与手机连发参数联动**：`[Style]:97-116` 手机档 更短 2~3×16 / 适中 2~4×30 / 更长 3~4×45，长对话模式叠加翻倍——文档无此联动，属工程 F5 的自然扩展。
4. **语音条格式鲁棒性**：`(语音:…)/[语音:…]/[语音：…]/(语音：…)` 四种写法统一归一(`[Multi]:80-89,131-153`)——文档只定义单一写法，工程做了实机驱动的容错。
5. **单泡长文确定性切分**(沿标点切 2~3 条，`[Orch]:216-254`)与**空回复角色化兜底**——文档未要求，实为多泡观感的保底。
6. **死代码簇**(集成缺失副产物，建议关注)：`parseCardLine`、`CardPayload.summary()`、`PhoneChatPrefs` 全部读写器、`CardBody` 渲染分支均无生产调用方。
7. **发送中整回合阻塞**(见 J2/I1)：防半批悬挂的实现方式与文档缓解策略不同。

## 4. 总结

工程在**视觉壳(14)、六宫格(13b)、说明页(13c)、语音条链路、主动消息三档触发、长按回溯/重新生成**上与文档高度一致且多处超出(格式归一化、自动滚底、失败兜底)。最关键的缺口是 **A1 卡片消息端到端断链**(渲染层已就绪但无写入方，用户与 AI 的卡片都退化为原始标记文本)——这是文档点名的特殊消息类型中唯一"看起来有、实际不通"的一项；其次是**表情包/图片仅占位、离线轰炸无推送、限速降速态完全缺失**(后者含仪式感配额的行为形态，若属有意裁剪建议在工程文档中显式声明)。

---
---

# 附录 D：08 状态系统 + 09 心动模式（子代理完整报告）

**总述**：GouXiang 对这两个功能只落地了「状态条的一层皮」(确定性规则生成的 emoji+短句，聊天页/角色主页共用)和「『心动模式』四个字的对话模式 prompt 行为段」。08 号文档要求的**状态记录管线(生成/存储/面板/回溯联动)为零**，09 号文档要求的**心动事件五件套(查手机/查状态/吃醋/交换礼物/惊喜小剧场+心动记录)为零**。全库 grep `Sidestory|checkphone|jealous|peekintoheart|exchangegift|StoryHeart|agent_status|StatusRecord|StatusStore|StatusSummarizer|StatusLedger` 均零命中；Room 库(GouXiangDatabase.kt，version 4)实体表仅 characters/conversations/messages/memories/pending_memory_window/inner_voices/moments/moment_comments，无任何状态表或心动记录表。

代码路径省略公共前缀 `app/src/main/java/com/neethu/gouxiang/`；文档指 `/home/neethu/projects/AIAvatar-SDK/docs/maoxiang/08-status-system.md` 与 `09-heartbeat-mode.md`。

## 一、统计

### 08 状态系统(按文档 §3「本地版架构设计」+ §4 任务拆解的落地要求，拆 15 条)

| 结论 | 数量 | 条目 |
|---|---|---|
| ✅ 符合 | 0 | — |
| ⚠️ 部分实现 | 2 | 锚点条交互、状态条同源(限「聊天页↔角色主页」两入口) |
| 🔀 不一致 | 1 | 状态条产生方式(规则即时算 vs 总结定时产生) |
| ❌ 未实现 | 12 | 数据模型/状态机/存储层/区间锚定/详情生成/文案层/收尾缝挂点/system 注入/工具/事件/回溯联动/StatusPanel(+ai_cmd 调试命令并入) |

### 09 心动模式(拆 13 条:P0 12 条 + P2 1 条)

| 结论 | 数量 | 条目 |
|---|---|---|
| ✅ 符合 | 0 | — |
| ⚠️ 部分实现 | 0 | — |
| 🔀 不一致 | 1 | 「心动模式」命名冲突(实现的对话模式 ≠ 文档的心动指令事件族) |
| ❌ 未实现 | 12 | 事件目录/事件记录落库/生成器与四事件 prompt/礼物输入流/小剧场抽 20/心动记录页/事件面板与详情卡/配额账本/工具注册/红点入口/完成通知/回溯快照化(P2 三事件并入，文档本排 P2) |

## 二、逐条偏差清单

### 文档 08(状态系统)

**08-1【🔀】状态条的产生方式：文档要求「状态由 LLM 总结定时产生」**
- 文档位置：§3.2(c) StatusSummarizer「替代猫箱服务端定时任务」、§3.3「与记忆管线共用 MemoryCoordinator 窗口快照与触发判定」、§4 任务 3/4。
- 代码现状：`chat/StatusRule.kt:24-53` —— 状态条由**确定性关键词规则**即时生成(最近一条用户消息含「吃/睡/画/班/电影/想/出门」→ 固定 emoji+短句；否则取最近 assistant 动作或兜底「💤 在等你来聊天」)。注释自认「起步版:确定性关键词规则……后续功能可替换为 LLM 定时总结」。
- 差异说明：无 LLM 请求、无定时/阈值触发、不挂 `ChatMemoryCoordinator.onTurnCompleted`(该钩子只喂记忆，`chat/ChatMemoryCoordinator.kt:101-118`)、不落库、无 GEN_DOING/SUCCESS/FAILED 状态机、无 ≥8 轮冷却、无「正在加载中」态。文档 §5-9 的 GEN_DOING 启动清扫、§1.1 reviewState 审核过滤也随之无载体。ChatOrchestrator 的回合收尾缝(`chat/ChatOrchestrator.kt:62-67, 175-179` memoryHook)与文档要求的 statusTracker 挂点同形，但只接了记忆(MainViewModel.kt:60-65)。

**08-2【❌】状态记录数据模型与状态机(AgentStatusRecord / StatusRecordState / Room 表 agent_status_records)**
- 文档位置：§3.2(a)(字段级 schema:recordId/emoji/statusMessage/themeColor/state 七值/reviewState/bizTime/startRound/endRound/startMsgId/endMsgId/consumed/detailText)、§4 任务 1。
- 代码现状：状态数据只有 `chat/StatusRule.kt:15-20` 的 `StatusLine(emoji, text)` 两个瞬时字段;`data/GouXiangDatabase.kt:11-24` 无对应实体表。
- 差异说明:themeColor、bizTime、consumed、detailText、状态七值、审核态、分页(cursor+has_more)全部缺失；状态不可回看、无历史记录流。

**08-3【❌】StatusStore / RoomStatusStore 存储层(add/list/current/markState/markBacktracked/markConsumed/release/clearConflict/clear)**
- 文档位置：§3.2(b)、§4 任务 1。
- 代码现状：不存在(全库零命中)。
- 差异说明：无任何状态读写 API。

**08-4【❌】对话区间锚定(startRound/endRound + msgId 缝 lastAppendedIds 或轮号降级)**
- 文档位置：§3.2(a) 与 §3.2(c)「区间锚定的 id 从哪来」、§4 任务 5。
- 代码现状：StatusLine 无区间字段；消息行 id 仅用于长按回溯/重新生成定位(`ui/MainViewModel.kt:564-573, 580-632`)。
- 差异说明：「查看对话跳原文区间」的前提数据完全不存在。

**08-5【❌】状态详情生成(StatusLedger.consume 记账 + StatusSummarizer.generateDetail 内心独白 + 失败退配额)**
- 文档位置：§3.2(d)、§4 任务 6。
- 代码现状：无 Ledger、无 generateDetail(grep `Ledger|consume|配额|解锁` 仅命中注释性"无猫粮"声明，如 `data/MomentEntity.kt:16`)。
- 差异说明：计费/猫粮部分按本地版约定有意不做，予以豁免；但**计费无关**的「解锁后生成 1~3 段内心独白详情 + 失败显示『生成失败，请重试』」行为也未实现。

**08-6【❌】StatusTexts zh/en(总结 prompt「反讨好条款」/详情 prompt/UI 文案「TA 的状态/状态记录/冲突状态记录/时间轴分组」等)**
- 文档位置：§3.2(e)、§2.1、§4 任务 2。
- 代码现状：不存在；工程无文案层，状态短句硬编码在 StatusRule.kt:37-51。
- 差异说明:prompt 侧(含「状态必须是此刻可信的，不要硬编」反讨好条款)与 UI 文案侧均缺。

**08-7【❌】回合收尾缝挂状态生成 + `statusEnabled` 开关/setStatusEnabled**
- 文档位置：§3.2(f) 表行 1、§4 任务 4。
- 代码现状:`chat/ChatOrchestrator.kt:67` 仅有 `memoryHook`；无 statusTracker 字段、无开关。
- 差异说明：缝位存在且已被记忆占用，状态侧未挂。

**08-8【❌】system prompt 注入 `[当前状态]` 行(AI 知道自己此刻状态，回答「你在干嘛」不出戏)**
- 文档位置：§3.2(f) 表行 2(注入块，排 relationship 之后)、§4 任务 8。
- 代码现状:`chat/ChatContextBuilder.kt:49-119` systemPrompt 组装序为 [人设]/[台词示例]/[输出协议]/[当前形态]/[对话模式]/[记忆]，**无任何状态注入块**；且该函数不接收 StatusLine 参数。
- 差异说明：LLM 完全看不到状态条内容。

**08-9【❌】`update_status` / `get_status` 工具注册**
- 文档位置：§3.2(f) 表行 3、§4 任务 7。
- 代码现状：工程无工具注册体系(grep ToolRegistry/update_status/get_status 零命中)。
- 差异说明：LLM 不能主动更新或自查状态。

**08-10【❌】`StatusUpdated` / `StatusEmotionApplied` 事件(响应式刷新，免轮询)**
- 文档位置：§3.2(f) 表行 4(3D 表情联动部分按约定忽略)。
- 代码现状：无事件流；状态条刷新靠 Room `observeMessages` 消息流每轮回调里重算(`ui/MainViewModel.kt:149-153, 216-221`)。
- 差异说明：UI 刷新行为近似达成(消息落库即重算)，但这是「由消息推导」而非「状态记录事件」，状态被清库/历史变化时无独立语义。

**08-11【❌】回溯联动(markBacktracked 裁剪、冲突区「冲突状态记录」、一键清理)**
- 文档位置：§3.2(c) 回溯联动、§3.2(g)、§4 任务 5。
- 代码现状：回溯**删除消息**的能力已存在(F4:`ui/MainViewModel.kt:564-573` rewindToMessage → `data/GouXiangRepo.kt:192-193` deleteFromMessage → refreshConversationTail)，但没有任何状态记录可被标记，无冲突区、无清理入口。
- 差异说明：文档 §3.2(g) 的最小路径(clearHistory 全转冲突)也未接。

**08-12【❌】StatusPanel 面板(当前态卡/时间轴分组「今天/昨天…」/失败条/长按删除/解锁流程/冲突区)**
- 文档位置：§3.2(h)、§4 任务 9。
- 代码现状：不存在。聊天页状态条点击是空占位:`ui/chat/ChatMainScreen.kt:268-271` `clickable { /* 占位:状态面板后续功能 */ }`；角色主页状态条胶囊无点击(`ui/character/CharacterHomeScreen.kt:189-214` 该 Row 未挂 clickable)；主页宫格(`CharacterHomeScreen.kt:271-294`)只有 对话模式/对话风格/Ta 的记忆 + 装扮/心声/朋友圈占位卡，无「TA 的状态」入口。
- 差异说明:面板整块缺失；「今天/昨天」日期分组逻辑虽存在(`ui/MainViewModel.kt:441-452`)，但属聊天历史页(F7)，与状态时间轴无关。

**08-13【⚠️】锚点条交互(位置=输入框上方、点击开面板、键盘展开隐藏)**
- 文档位置：§3.2(h) 锚点(两态仲裁：有状态→显示、键盘展开→隐藏)。
- 代码现状：状态条胶囊存在于输入区上方(`ui/chat/ChatMainScreen.kt:183` StatusPill 位于列表与输入条之间)，位置与文档一致；但点击为空占位(见 08-12)，无键盘展开隐藏逻辑。
- 差异说明：缺「点击开面板」与「键盘态隐藏」两行为。

**08-14【⚠️】状态条与面板同源 / 多入口数据一致**
- 文档位置：§1.0/§3.2(h)(面板与锚点同一数据源)。
- 代码现状：聊天页与角色主页的状态条**确已同源**——两处都由 `StatusRule.generate(character, messages)` 基于 `repo.observeMessages(conversationId)` 同一流计算(`ui/MainViewModel.kt:149-153` 与 `:216-221`；接线见 MainActivity.kt:202-210 注释)。
- 差异说明：同源仅在「两个状态条入口」之间成立；文档要求的「状态条 ↔ 状态面板」同源因面板不存在而无从验证。

**08-15【❌】ai_cmd 调试命令四条(status_list/status_now/status_force/status_backtrack)**
- 文档位置：§3.2(h) 末、§4 任务 10。
- 代码现状：工程无 ai_cmd/调试命令体系。
- 差异说明：验收工具链缺失(影响可测性，不影响用户功能)。

### 文档 09(心动模式)

**09-0【🔀】「心动模式」命名冲突：实现的对话模式 ≠ 文档的心动指令事件族**
- 文档位置：§1.1「心动模式(心动指令)是付费情感事件族」、§1.2 事件目录、§3 整章。
- 代码现状:`chat/ChatStyle.kt:28-33` `DialogMode.HEARTBEAT("心动模式","增强恋爱拉扯和情感张力","[对话模式] 心动模式:恋爱心动感拉满…")`，默认选中(`:61-63` DEFAULT=HEARTBEAT)，经 `chat/ChatContextBuilder.kt:114` 注入 prompt，弹层入口在 `ui/character/CharacterStyleSheets.kt:115-121` + `ui/character/CharacterHomeScreen.kt:278-283`；单测锁定 `app/src/test/java/com/neethu/gouxiang/chat/DialogStyleTest.kt:29-40, 54-61, 185`。
- 差异说明：文档 09 的「心动模式」= 查手机/查状态/吃醋/交换礼物/小剧场的**付费情感事件族**(入口红点→事件面板→异步生成→心动记录)；实现里的「心动模式」只是**对话风格 prompt 行为段**(且本地全免费)。同名异义，五件套一项都没做。它复刻的是 UI 参考稿 `09-dialog-mode` 弹层，不是功能文档 09。

**09-1【❌】事件目录 HeartbeatEventDef/HeartbeatCatalog(P0 五事件四件套：标题/按钮/intro/记录句 + 分区清单)**
- 文档位置：§3.2(a)、§4 T1。
- 代码现状：不存在(grep `checkphone|peekintoheart|jealous|exchangegift|sidestory` 零命中)。
- 差异说明：「查Ta手机/查Ta状态/让Ta吃醋/交换礼物/惊喜小剧场」目录及四件套文案全缺；唯一沾边的是 `ui/messages/MessagesScreen.kt:89` 一条静态装饰文案「『心动指令』新用户9折券限时领」(复刻参考截图，无功能)。

**09-2【❌】事件记录 schema 与落库(HeartbeatEventRecord、isHeartbeat/heartbeatEventId 列、ackedAt 未读位派生)**
- 文档位置：§3.2(b)、§4 T3。
- 代码现状：无表无实体(GouXiangDatabase.kt 实体清单见总述)。
- 差异说明：记录不持久化 → 心动记录 Tab、三态(载入中/点击查看/载入失败)、空态「暂无心动记录」全部无载体。

**09-3【❌】HeartbeatGenerator + HeartbeatPrompts(查手机 11 区 JSON / 查状态 4 区 JSON(内心独白/心情/状态/位置)/ 吃醋多格 / 礼物两段式；非流式、temperature 覆盖、宽松 JSON 解析、敏感词分支)**
- 文档位置：§3.3、§4 T2。
- 代码现状：不存在。
- 差异说明:五事件一种都没实现;prompt 骨架(含「被Ta在乎」证据感、独白必含未说出口内容、回礼呼应等行为规范)全缺。

**09-4【❌】交换礼物输入流(礼物名输入框+留言卡+intro 三联「已和{name}走过了{num}天」+ WAIT_INPUT 占位+「礼物打包中」)**
- 文档位置：§3.3 礼物两段式、§3.5-3、§4 T5。
- 代码现状：不存在;`daysTogether` 相识天数无任何实现(无 RelationshipTracker 等价物)。

**09-5【❌】惊喜小剧场 sidestory_batch(一次抽 20、归档心动记录)**
- 文档位置：§1.2 辅助组、§3.3 末、§4 T8。
- 代码现状：不存在;文档声明其依赖 06 号文档 Sidestory* 家族(文档自认设计稿依赖)，工程亦零命中。

**09-6【❌】心动记录页(列表 + 记录卡三态 + 空态「暂无心动记录/快去选择心动指令吧」)**
- 文档位置：§1.4 末段、§2.1、§4 T5/T6。
- 代码现状：不存在；角色主页(`CharacterHomeScreen.kt:296-335`)仅心声/朋友圈两张占位卡，无「心动记录」卡或 Tab。

**09-7【❌】HeartbeatPanel 事件面板 + HeartbeatEventCard 详情卡(查手机 11 分区折叠/查状态四分区/吃醋礼物多格竖滑/「由 AI 生成」角标)**
- 文档位置：§3.5-2、§4 T5。
- 代码现状：不存在;`PanelType` 等价物(MainActivity 覆盖层族)无 HEARTBEAT 项。

**09-8【❌】HeartbeatLedger 配额账本(每角色每日 N 次免费、失败 release 退回、键含 contextId 分片)**
- 文档位置：§3.4、§4 T4。
- 代码现状：不存在。
- 差异说明：按任务规则，猫粮/真扣费项豁免；但「免费次数记账 + 失败原路退回次数」是文档明确定义的**计费无关行为骨架**(本地版只有次数轨)，同样未实现。

**09-9【❌】工具注册 `start_heartbeat(eventId, gift_name?, greeting?)` / `list_heartbeat_records()` + 开关键**
- 文档位置：§3.5-1、§4 T6。
- 代码现状：工程无工具注册体系，LLM 不能主动提议心动事件。

**09-10【❌】红点胶囊入口(StoryHeartStatus 未读态：emoji+一句话+未读红点，点击直达)**
- 文档位置：§1.1-2、§3.5-2(红点)、§4 T9。
- 代码现状：聊天页输入条「+」上有一个**纯静态装饰红点**(`ui/chat/ChatMainScreen.kt:564-570`「红点角标(参考图)」)，无未读数据源、无点击行为。
- 差异说明：视觉近似但功能为零。

**09-11【❌】完成通知(heartbeat_notify 渠道 + 深链 EXTRA_PENDING_HEARTBEAT_ID + 失败「次数已退回」副标题)**
- 文档位置：§3.5-4、§4 T7。
- 代码现状：工程无通知渠道/Worker(grep notify/Notification 仅语音播放器无关命中)。

**09-12【❌】「内容随聊天记录变化」回溯冲突处理(P0 快照化决策)**
- 文档位置：§1.6-2、§5-3。
- 代码现状：无心动事件记录，自然无快照化/冲突语义。

**09-13【❌(P2,文档本排后)】P2 事件：看Ta日记 / 查看前世 / 看Ta主页**
- 文档位置：§1.6-7(P2 决策)、§4 T11。
- 代码现状：不存在。属预期内未排期，单列备查。

## 三、代码中存在但文档(08/09)未要求的相关行为

1. **心声页 F8(`chat/InnerVoiceGenerator.kt:30-61` + `ui/innervoice/`)**:LLM 生成 2~4 条第一人称内心短句卡 + AI 配图，入口在角色主页「Ta 的心声」。它实现的是 07 号文档功能，但其「第一人称内心独白」产物与 09「查Ta状态」的内心独白分区、08「状态详情独白」素材最近——若后续补 09-3，可复用其宽松 JSON 解析(`firstJsonObject`，InnerVoiceGenerator.kt:102-128)与通道(`InnerVoiceChannel`，:165-199)；当前二者零接线。
2. **状态条防代理对劈裂约束**:`chat/StatusRule.kt:10-14, 58-66` 与 `ui/chat/ChatMainScreen.kt:257-261` 明确 emoji/text 分字段、按码点截断(实机 uiautomator dump 崩溃教训)。文档未要求，但与文档「emoji+短句一体渲染」的落地质量相关，是后续接 LLM 生成时必须保留的约束。
3. **回溯基建已具备但未接状态**:`data/Daos.kt:110` deleteFromMessage(按行 id 裁剪该条及之后)+ `data/GouXiangRepo.kt:207` refreshConversationTail，语义接近文档 08 要求的 `truncateTo`；另有长按「重新生成」(`ui/MainViewModel.kt:580-632`)。文档 08 的回溯三件套中「按轮回溯删除」这半件实际已存在，缺的只是 `rollbackTo` 命名/返回被裁消息 + `markBacktracked` 状态联动。
4. **朋友圈 F9(`chat/MomentsGenerator.kt`、`ui/moments/`)**:记忆驱动的角色动态+评论，属 06/07 号文档域；其「文字先行、配图异步回填、失败保纯文字」的降级纪律与 09 生成器要求的失败安全同构，可作参照。
5. **消息页系统通知行的「心动指令」文案**(`ui/messages/MessagesScreen.kt:89`)：仅 UI 复刻装饰，易被误读为心动功能入口，实际无任何实现支撑——若后续实现 09，此处是现成的自然入口位。

---
---

# 附录 E：06 番外剧场 + 07 聊天衍生内容（子代理完整报告）

## 一、要求条目统计

| 文档 | 统计口径 | 已实现 ✅ | 部分 ⚠️ | 不一致 🔀 | 未实现 ❌ |
|---|---|---|---|---|---|
| 06 番外剧场 | 9 条(8 条必做 + 1 条文档标「可选」) | 0 | 0 | 0 | **9** |
| 07 聊天衍生内容 | 26 条(共享管线 9 + 手帐 3 + 小说 2 + 合照 4 + 心声 8) | 4 | 2 | 2 | **18** |

总体结论：**06 剧场功能在工程中完全不存在**(全仓含 Kotlin/XML/JSON/assets 检索 `sidestory/剧场/手帐/chatcmd/模板/抽卡/切换角色` 均 0 命中，`app/src/main/assets/` 目录不存在)。**07 中仅「心声」已实现**(F8)，手帐/小说/合照三个功能与其共享的衍生内容管线(schema/调度器/通知/冲突区)全部缺失；生图 adapter 与心声共享管线已落地。

计费/猫粮项(抽卡计费、按期解锁、退款、重roll额度、配额)按比对规则一律忽略不计；心声 tv_expire 位只显示日期无过期，符合工程「范围 3 红线」且与「计费不做」一致。

## 二、06 番外剧场——逐条偏差清单(全部 ❌)

1. **模板库 templates.json(88 主模板)+ SidestoryTemplate/Catalog 解析 + 脏 JSON 回落 EMPTY + 单测**
   - 文档位置：§3.2、任务 T1(§4)；窥探类模板仪式感按钮文案复用属此数据(§1.6-6)。
   - 代码现状：`app/src/main/assets/` 目录不存在，无任何 `Sidestory*` 类。
   - 差异：模板库、解析器、单测均未建立。

2. **记录存储与状态机:SidestoryRecord/SidestoryStatus(8 态裁 6 态)+ Room `sidestory_records` 表 + `records: StateFlow`/`updateStatus`(单条刷新，对齐 UpdateItemState)/`list` 分页/`ack` 已读**
   - 文档位置：§3.3、§3.5-3、任务 T3；记录快照化(§1.6-4)属此。
   - 代码现状:`data/GouXiangDatabase.kt` 仅 8 实体(Character/Conversation/Message/Memory/PendingMemoryWindow/InnerVoice/Moment/MomentComment)，无 sidestory 表。
   - 差异：状态机、持久化、单条刷新事件流全部缺失。

3. **生成器 SidestoryGenerator + SidestoryPrompts:独立非流式请求、单人模板 prompt(人设/用户画像/近期聊天 40 轮/userInput 注入)、一次输出多格结构化 JSON(3~6 格，每格 text+imagePrompt≤60 字、不出现真实姓名)、平衡括号+lenient 解析、格式坏重试一次、敏感词→SECURITY_FAIL、WaitUserInput 补写(chat_history_ugc)**
   - 文档位置：§3.4、任务 T2。
   - 代码现状：无任何剧场生成器;prompt 骨架(含「()场景动作」「imagePrompt 只描述画面」等规范)未移植。
   - 差异：整个生成链缺失(工程里的 `InnerVoiceGenerator`/`MomentsGenerator` 是心声/朋友圈专用，与剧场模板无关)。

4. **多角色模板分支(the_way_they_see_you / found_their_phone_story):逐角色人设注入、角色间互动记录、每格 `speaker` 白名单校验、切换角色视图、{name} 眼中的 Ta 们标题**
   - 文档位置：§1.6-7、§3.4。
   - 代码现状：无多角色编排；种子仅单角色苏馨玥(`data/SeedData.kt`)。
   - 差异：完全未做。

5. **惊喜小剧场抽卡:pool 字段校验规则、均匀随机抽 20(池不足全取、random 缝)、批抽 20 串行队列、盲盒候选名 candidates→落定 title、完成通知**
   - 文档位置：§3.4、任务 T6。
   - 代码现状：无(`抽卡/惊喜/盲盒/候选` 全仓 0 命中)。抽卡计费本身属忽略项，但**抽卡行为与盲盒候选/落定机制是计费无关项**，同样未做。

6. **集成接线：SidestoryWiring 两工具(`start_sidestory`/`list_sidestory_records`)+ 开关 + 聊天面板占位卡;`ai_cmd sidestory` 调试命令**
   - 文档位置：§3.5、任务 T4/T5。
   - 代码现状：无工具注册机制(`ai_cmd` 全仓 0 命中，该工程无 LLM 工具通道概念)。
   - 差异：LLM 主动提议剧场与记录回灌两条缝均不存在。

7. **UI:SidestoryPanel(单人/多人两签九宫格)+ SidestoryCard(多格竖滑详情 + GENERATING/SUCCESS/FAILED 三态 + 已读 + 「由 AI 生成」合规角标)+ 记录页两 Tab(心动记录/入戏记录)**
   - 文档位置：§3.5、任务 T4；合规角标必带(§1.6-5)。
   - 代码现状：无任何剧场页面;「由 AI 生成」角标全仓 0 命中。
   - 差异：完全未做。

8. **记忆联动：生成前注入记忆画像(相对猫箱的本地优势点)**
   - 文档位置：§3.5-4。
   - 代码现状：无消费方(记忆注入机制本身已在 `chat/MemoryInjection.kt` 存在，但剧场未接入)。

9. **(可选)本地限流 toast「当前使用人数过多，请稍后再来」**
   - 文档位置：§1.6-1(文档标注「可选」)。
   - 代码现状：无。缺失属可接受，列出仅为完整性。

> 附注：06 §3.1 表明剧场生图「属 08 号文档管线，本功能只消费其产物」——该共用生图管线已存在(`ai/image/AvatarImageProvider.kt`、`OpenAiCompatibleImageGenAdapter.kt`)，属前置满足，但无任何剧场代码消费它。06 §3.6 3D 演出按规则忽略。

## 三、07 聊天衍生内容——已实现项(✅,心声 F8)

- **心声情绪卡 prompt→生图**:`chat/InnerVoiceGenerator.kt:46-61`(system prompt=人设+记忆注入+单行 JSON 输出协议)+ `ui/MainViewModel.kt:781-797`(image prompt=appearance+情绪(title,echo)+appearanceStyle+「柔和电影感光影，情绪特写，竖版构图」)+ `ai/image/AvatarImageProvider.kt:54-55`(768×1024 竖版，对齐文档 §3.3 ImageGenConfig 默认值)。
- **心声卡四要素 img_cover/tv_title/tv_desc/tv_expire**:`ui/innervoice/InnerVoiceScreen.kt:201-259`(左竖版配图 86×132 + 标题 + 正文 + 日期行);tv_expire 位按范围 3 只显 `yyyy.MM.dd` 无「剩余 N 天过期」(`:251-256`、`formatVoiceDate :262-263`)，计费时效有意不做，符合规则。
- **两阶段生成(WaitText→WaitImage 的本地等价)+ 纯文字卡兜底**:`ui/MainViewModel.kt:774-798`(文字先行落库、生图成功逐张 `setInnerVoiceImage` 回填、失败保持纯文字卡)、`data/InnerVoiceEntity.kt:13-15`。
- **空态文案复用官方组(memory_card_page_empty_title/desc)**:`InnerVoiceScreen.kt:171-192`(「暂无Ta的心声」/「将自动根据对话内容，生成你们的专属回忆，再多聊聊吧」)。
- 另：心声**无轮次门槛**与文档 §1.5-1「心声无此文案」一致(门槛本就只属于手帐/小说/合照);prompt 无计费/过期字样有专门红线测试(`app/src/test/java/com/neethu/gouxiang/chat/InnerVoiceTest.kt:117-124`)。

## 07 偏差清单(⚠️/🔀/❌)

### 共享管线

1. ⚠️ **OpenAI 兼容生图 adapter:POST {base}/images/generations、b64_json/url 双响应形态、永不抛异常→错误文本**(文档 §3.3)
   - 代码现状:`ai/image/OpenAiCompatibleImageGenAdapter.kt:40`(端点拼接)、`:43-64`(catch Throwable→Failure 容错口径符合)、`:81-91`。
   - 缺：响应只解析 `data[].url`/`images[].url` 两种 URL 形态,**未解析 b64_json**;`ImageGenResult` 无 `revisedPrompt` 字段。

2. 🔀 **ImageGenAdapter 接口形态:generate(prompt, ImageGenConfig{baseUrl,apiKey,model,width,height,extraBody}) + Result{url,revisedPrompt,rawError}**(文档 §3.3)
   - 代码现状:`ai/image/ImageGenAdapter.kt:6-25`——`ImageGenRequest{prompt,size}` + `ImageGenResult{Success(urls)/Failure(message)}`,adapter 按「一实例一端点」构造(端点/模型在构造期固定,`OpenAiCompatibleImageGenAdapter.kt:30-38`),非按调用传 config。
   - 差异：签名与文档不同但功能等价(调用方按端点实例化，size 承担宽高)；缺 extraBody 透传与 revisedPrompt。

3. ❌ **DerivativeItem/SessionState/TurnLog/Quota schema + DerivativeStore 接口(§3.5 方法集)+ derivatives.db 独立库**(文档 §3.5、任务 1)
   - 代码现状：无 `derivatives` 包;Room 库仅 `gouxiang.db` 8 实体(`data/GouXiangDatabase.kt`)。

4. ❌ **DerivativeScheduler:攒批窗口(默认 30 轮)/轮次门槛(minRounds 默认 10)/turn_log 回溯冲突扫描/冲突区进出/blocked 恢复**，及**轮次门槛提示「当前聊天轮次过少，再聊聊天吧」**(文档 §1.5-1、§3.3、任务 2)
   - 代码现状：无;全仓无轮次门槛逻辑(仅记忆协调器自己的总结窗口阈值 `chat/ChatMemoryCoordinator.kt:31-51`，与衍生内容无关)。

5. ❌ **异步完成通知「退出不影响加载，完成后将通知你」:DerivativeNotifier(NotificationChannel + 系统通知 + 聊天页气泡)**(文档 §1.5-2、§3.4、任务 10)
   - 代码现状：全仓无 `NotificationManager`/`NotificationChannel`(grep 0 命中)。

6. ⚠️ **心声侧的「生成中，你可以离开此页面…」+ 完成推送「Ta的心声 已生成/点击查看」**(文档 §1.4、§2.1 直接复用组)
   - 代码现状:`generateInnerVoices` 跑在独立 `viewModelScope.launch`(`ui/MainViewModel.kt:753-819`)，不随 `closeInnerVoices()` 的 `innerVoiceJob` 取消——**页内退出后生成与配图回填实际会继续、结果落库**，「退出不影响」行为上部分成立；但**无系统通知、无完成提示、无聊天页气泡**，再进页才可见。
   - 差异：缺通知通道与「生成中可离开」的显式文案/状态。

7. ❌ **回溯冲突管理:markBacktracked→区间相交标 BACKTRACKED 入冲突区、冲突章禁跳原文/禁重写、历史冲突归档区(原第{index}章)、删除冲突章恢复更新**(文档 §1.5-4)
   - 代码现状：工程已有回溯能力 `rewindToMessage`(`ui/MainViewModel.kt:564-573`，删该条及之后消息)，但**未接任何衍生内容冲突标记**；无 turn_log 表、无冲突区。
   - 注：此条对手帐/小说是硬要求；心声(图片卡)在文档中不带 dialogue_range，不受此影响。

8. ❌ **跳转原文:round→turnLog→Room 消息定位(±1 条误差)、回溯章禁跳 toast**(文档 §3.4、任务 5)
   - 代码现状：无(载体手帐/小说缺失；MessageEntity 亦无轮次序号，文档指出的定位缺口未按 turn_log 方案补)。

9. ❌ **调试命令 `ai_cmd derivative_list/generate/conflict`**(文档任务 12)
   - 代码现状：无 ai_cmd 机制。

### 手帐(整体 ❌)

10. ❌ **聊天转手帐功能整体：入口+轮次门槛+介绍卡(Ta写下的手帐/立即体验)+异步生成+推送更新**(文档 §1.1)——`手帐/handbook/chattohandbook` 全仓 0 命中。
11. ❌ **手帐篇章结构:LLM 单请求输出 `chapters[{title≤12, mood≤8, overview≤60, content≤800 第二人称+()动作, image_prompt, start_round, end_round}]`,单请求 ≤3 篇、弱模型关自动生成**(文档 §3.3)——无。
12. ❌ **手帐 UI:目录页(锁/红点/冲突三态)+ 详情 + 重写表单(篇名/心情/概览字数校验)+ 冲突区删除确认**(文档任务 4、§2.2)——无。

### 小说(整体 ❌)

13. ❌ **聊天转小说：固定 20 轮/章切分、逐章生成、handoff 衔接协议(上章 title/overview/末 300 字)、失败续跑(最后成功章 endRound+1)**(文档 §3.3、任务 9)——`novel/小说/recordtonovel` 全仓 0 命中。
14. ❌ **小说 schema 与阅读页：章状态机(含 SecurityFailed 本地等价态)、章末群像 comment(其他角色一句吐槽 ≤30 字)、翻页阅读/解锁下 N 章(解锁计费项忽略，阅读结构仍属必做)**(文档 §1.2、§3.3、任务 9)——无。

### 合照(整体 ❌)

15. ❌ **聊天转合照 A 链路：「基于最近 N 轮对话生成」prompt 组装、重新生成、错误态文案**(文档 §1.3A、任务 7)——`合照/chattoselfie/selfie/coshoot` 全仓 0 命中;`ui/moments/` 是朋友圈(10 号文档范畴)，不是合照。
16. ❌ **聊天页合照气泡入口(文档允许 P0 用面板入口替代)**(文档 §3.4、任务 7)——无任何入口。
17. ❌ **设为聊天背景(方案 A:UI 层平面贴图)+ 还原默认背景**(文档 §3.4、任务 7;心声卡动作 `parallel_card_set_as_chat_background` 同走此缝)——全仓无「背景」功能(仅 `CharacterEntity.kt:22` 注释字样)。
18. ❌ **合拍工作台本地裁剪版：内置 4~6 个合拍模板(模版即 prompt)+ 自定义主题文字生图(不上传照片)**(文档 §1.3B 取舍、§3.3、任务 7)——无。上传真人照片/人脸识别链路文档明确不做，忽略。

### 心声(部分偏差)

19. 🔀 **素材口径：角色人设 + 近期事件记忆 top3(MemoryRetriever.search)+ 心情**(文档 §3.3 心声 prompt)
   - 代码现状:`chat/InnerVoiceGenerator.kt:46-51` + `chat/MemoryInjection.kt:30-60`——注入为**画像/关系 + 事件 Top6(NORMAL_MAX=6)+ 容量外 faded Top4**(`MemoryInjection.kt:21-24`)，另拼最近对话回合(`InnerVoiceGenerator.kt:64-72`)；无 query 检索式 top3(`block(query="")` 不做检索筛选)。
   - 差异：方向一致(记忆喂 prompt)但检索机制与条数与文档不同，注入面是文档口径的超集。

20. 🔀 **「一张角色情绪卡」的单卡模型**
   - 代码现状：一次「生成心声」产出 **2~4 条心声卡、每条各配一张图**(`InnerVoiceGenerator.kt:35-36` MIN/MAX_VOICES、`MainViewModel.kt:774-798` 逐张配图)，文档口径为单卡(对应猫箱单买一张 MemoryCard)。
   - 差异：本地批量为有意扩展(报告 F8 亦如此陈述)，但与文档「一张情绪卡」结构不一致。

21. ❌ **卡片动作「收下」(parallel_card_receive,WaitReceive→Received 的本地 USE 等价)**(文档 §2.1、任务 8 明确列出 查看/收下/设为背景)
   - 代码现状：只有「查看」(列表卡展示)；无收下/领取交互(`收下` 全仓 0 命中)；设为背景见上文第 17 条。

22. ❌ **「角色可生成 Ta 的心声」角色设置开关(parallel_memorycard_toggle_label,文档标直接复用)**(文档 §1.4、§2.1)
   - 代码现状：无该开关；角色主页功能卡(`ui/character/CharacterHomeScreen.kt:322-324`)只有心声入口，无作者/角色级 enable 控制。

## 四、代码中存在但文档(06/07)未要求的相关行为

1. **心声手动触发按钮「生成心声」**(`InnerVoiceScreen.kt:136-152`、`MainViewModel.kt:753`)：文档中心声生成由服务端 enable 驱动(挂在记忆/资产页)，本地为页内手动按钮，入口挂在角色主页占位卡(「Ta 的心声 · 全部 N」尾注，`CharacterHomeScreen.kt:322-324`、`MainViewModel.kt:143-148`)——本地化取舍，报告 F8 已声明。
2. **朋友圈(F9)整套功能**(`chat/MomentsGenerator.kt`、`ui/moments/MomentsScreen.kt`、`MainViewModel.kt:877-1063`):动态生成(文案+配图)、点赞、评论 + LLM 回复、首进无动态自动生成一条；与心声共用同一 LLM 通道与生图管线(`MomentsChannel`/`getOrGenerateMomentImage`)。属 10 号文档范畴，不在 06/07 比对范围，但确认其中**没有**合照能力。
3. **双尺寸键兼容**:`OpenAiCompatibleImageGenAdapter.buildRequestBody` 同时下发 `size` 与 `image_size`(`OpenAiCompatibleImageGenAdapter.kt:67-78`)，为火山/硅基两家实测兼容，文档未要求。
4. **同一角色仅一张配图缓存**:`AvatarImageProvider.fileFor` 以 `characterKey.jpg` 命名(`AvatarImageProvider.kt:78-81`)，心声按 `inner-voice-<id>` 每卡独立文件、朋友圈按 `moment-<postId>`——无按 prompt 变更失效/重roll 换图机制(重roll 属计费项，忽略；但同一 id 永不复生成属文档未定义行为)。
5. **心声生成通道 temperature 覆盖(0.8)与失败安全**(返回 null 永不抛，`InnerVoiceGenerator.kt:171-198`):符合文档 §3.3「独立非流式请求」纪律，但文档未规定温度值。

## 五、一句话总结

心声已按 07 文档口径落地(4✅)，但有 2 处实现口径偏差(记忆检索方式、单卡→2~4 卡批量)、2 处部分缺失(b64_json 响应、完成通知)；心声的「收下/设为背景/角色开关」与全部共享管线、手帐、小说、合照、06 剧场整体均为 ❌ 未实现。

---
---

# 附录 F：10 朋友圈 + 11 广播剧 + 12 AVG 剧情模式（子代理完整报告）

## 一、总体统计

| 文档 | 核对条目数 | ✅符合 | ⚠️部分实现 | ❌未实现 | 🔀不一致 |
|---|---|---|---|---|---|
| 10-moments.md（朋友圈） | 20 | 4 | 4 | 11 | 1 |
| 11-audio-drama.md（广播剧） | 19 | 0 | 1 | 18 | 0 |
| 12-avg-story-mode.md（AVG 剧情模式） | 15 | 0 | 0 | 14 | 1 |

结论速览：**朋友圈实现了「按需单角色版」核心子集（生成/图文/点赞/评论回复）；广播剧与 AVG 剧情模式在工程中零实现**——全库 grep `drama/radio/broadcast/avg/chapter/剧情/story` 仅命中 1 处 UI 注释（`ui/character/CharacterHomeScreen.kt:180` `tv_story_title`），manifest 亦无前台服务/媒体会话/通知相关声明（`app/src/main/AndroidManifest.xml` 仅 INTERNET/NETWORK/RECORD_AUDIO 三项权限）。

## 二、逐条偏差清单

### A. 10-moments.md（朋友圈，20 条中 16 条偏差）

**A1 ⚠️ 每日自动发帖节奏（§1.3/T4：每日发布、异步生成、错峰时隙、静默窗顺延、MomentsRig 每 60s tick、冷启动补偿）**
- 现状：生成只有两条触发路径——首进空态自动补 1 条（`ui/MainViewModel.kt:894-898`，`posts.isEmpty() && !autoGenerated → generateMoment`）和右上「生成动态」按钮（`ui/moments/MomentsScreen.kt:152-174`）。全工程无 tick/定时循环/WorkManager/Alarm、无 `lastPlannedEpochDay` 补偿、无 `momentsEnabled` prefs。
- 差异：文档要求「角色每天会发布图文」的日任务模型，实现为「进入页面按需生成一条」，每日节奏/错峰/静默窗全部缺失。

**A2 ❌ 角色互评与「修罗场」（§1.4/T4：其他角色互评、mention 加权×2、关系权重、对抗交锋轮、单帖总轮≤8 硬守卫）**
- 现状：评论只有「用户评 → 该帖角色回一条」一条链路（`MainViewModel.kt:1010-1063`）；实体注释明示「本地版只有『角色回复用户评论』一层」（`data/MomentEntity.kt:40-41`）。种子角色虽有 2 个（`data/SeedData.kt:22,64` 苏馨玥/洛瑶），但朋友圈按 characterId 分片，互不相见，无任何 AI-AI 互动代码。
- 差异：多角色编排、修罗场、@ 提及加权、交锋轮守卫全部未实现。

**A3 ❌ 用户发帖（§1.5/T7：发布新动态、公开/私密切换、提及@、受众选择、字数/空内容校验）**
- 现状：朋友圈页底部仅评论输入条（`MomentsScreen.kt:435-494`），无发布页、无用户帖 authorKind="user" 的动态写路径（`MomentEntity.kt:8-11` 注释自述 authorKind 恒 avatar）。
- 差异：文档「用户也是 Feed 参与者」整块缺失。

**A4 ❌ 用户发帖配额与校验（§1.5/§1.9：今日还可发 {num} 条、配额=f(成员数)、每日预算表、敏感词本地表拦截）**
- 现状：无 `moments_budget` 表（`data/GouXiangDatabase.kt:75-100` 仅 moments/moment_comments 两表）、无任何配额计数与输入校验（正文可为空串发送无拦截——`MomentsScreen.kt:284-290` 仅 `isNotEmpty` 检查评论，无发帖侧校验）、无敏感词表。
- 差异：计费豁免不覆盖此项——配额与本地敏感词拦截是计费无关行为，文档 §5 风险 6 亦列为 P0 要求。

**A5 ❌ 权限三开关（§1.6/T5：allow_ai_post / allow_ai_interaction / allow_ai_reply_user + 角色管理面板）**
- 现状：无任何 permissions 字段；回复开关被硬编码为恒开并写进注释（`chat/MomentsGenerator.kt:88-89`「三开关里 allow_ai_reply_user 的语义，本地版恒开」）。
- 差异：三开关及管理面板未建模。

**A6 ❌ 邀请/成员管理（§1.7/T7：邀请面板「聊过过滤」、人数上限、成员卡/到期/续期仪式）**
- 现状：无邀请流程；入口位由「邀请角色」改为「生成动态」并注释「本地版范围 5:不实现邀请/计费」（`MomentsScreen.kt:59-61`）。
- 差异：计费部分豁免，但「只邀聊过的角色」等计费无关行为也未做（工程以单角色页形态绕开了成员概念）。

**A7 ❌ 冻结/封禁/内容安全兜底（§1.8/§5 风险 6：FROZEN 状态、safetyState=hidden、AI 帖 safetycheck 关键词兜底）**
- 现状：无 MemberStatus/hidden 字段（`MomentEntity.kt:15-16` 明示不建模）、全库无「敏感/hidden/safety」逻辑。
- 差异：内容安全兜底与冻结语义缺失。

**A8 ❌ Membership / Unread 双红点 / 预算数据模型（§1.2/§1.9）**
- 现状：无成员表、无未读计数、无消息红点（`GouXiangDatabase.kt:18-33` 仅 4 类 DAO，无 member/budget/unread）。
- 差异：双红点入口（新动态/消息两类未读）整块缺失。

**A9 ⚠️ 入口契约（§1.2/§2.2：主页入口 + EntranceState/badge + 角色详情页「Ta 的朋友圈」入口）**
- 现状：仅有角色主页占位卡入口「Ta 的朋友圈 · 全部 N」（`ui/character/CharacterHomeScreen.kt:329-333`，计数 `MainViewModel.kt:145-148`）；主页（HomeFeed/底栏）无朋友圈入口、无红点/徽标模型。
- 差异：入口收敛为单一角色主页入口，主页级入口与红点契约缺失。

**A10 ⚠️ MomentsScreen 页面族（§3.3/T6/T7：Feed/详情/邀请/发布/管理四页 + 首次进入引导气泡）**
- 现状：只有单页 Feed（评论内联展开，`MomentsScreen.kt:392-423`），无独立详情页、无引导气泡。
- 差异：页面族裁剪为 1 页；详情/发布/邀请/管理页与引导缺失。

**A11 ⚠️ 输出协议兜底：InlineTagExtractor 剥标签（§3.3 Composer：「Composer 内联跑一遍 InlineTagExtractor 兜底剥标签」）**
- 现状：全库无 InlineTagExtractor 等价物；`MomentsGenerator.parsePost`（`MomentsGenerator.kt:131-137`）只做「截平衡 JSON + clamp」，`<emo:>/<act:>` 类残留标签不会被剥除。
- 差异：防模型残留标签的兜底层缺失（prompt 侧仅有「禁提 AI」条款 `MomentsGenerator.kt:66-67`）。

**A12 ❌ 历史动态分页/无限流（§1.3：历史载入 + 下拉加载）**
- 现状：DAO 全量 observe（`data/Daos.kt:200` `observeForCharacter`），无分页。
- 差异：动态量大时无渐进加载路径（低权重）。

**A13 ❌ ai_cmd 调试命令（T9：moments_state/list/gen/invite/tick 五命令）**
- 现状：无任何 debug 命令面。
- 差异：调试可观测性缺失（工具项，低权重）。

**A14 ❌ P1 项：生成完成本地通知、高热帖记忆回写（§3.5/T12）**
- 现状：均无（manifest 无 POST_NOTIFICATIONS）。
- 差异：文档标注可选/P1，缺失但属计划内（低权重）。

**A15 🔀 数据 schema 与「多角色 feed」形态（§1.9/§3.3 MomentsModels：Post{images 0..9, visibility, mentionedIds, likedBy[], audienceIds, safetyState, GenerationMeta}、Comment{replyToCommentId}、Membership）**
- 现状：实现为注释声明的单角色裁剪子集——`MomentEntity(id/characterId/text/imagePrompt/imageFile/likedByMe/at)`、`MomentCommentEntity(postId/authorKind/replyToId/text/at)`（`data/MomentEntity.kt:22-63`）；likedBy 数组坍缩为 `likedByMe` 布尔、images 坍缩为单图 `imageFile`、无 GenerationMeta 审计字段。
- 差异：字段级裁剪是有意为之且注释自述，但与文档 schema 不一致（尤其「多角色时间线」收敛为「单角色时间线」，feed 无成员头像行）。

**A16 ✅ 项（列作对照，不进偏差清单）**：图文双介质（文字先行+imagePrompt 生图异步回填+纯文字兜底，`MomentsGenerator.kt:34-39`、`MainViewModel.kt:956-975`、`MomentsScreen.kt:352-363`）；素材=人设+记忆+最近对话（`MomentsGenerator.kt:49-83`）；≤120 字/()/第一人称/禁提 AI 协议（`MomentsGenerator.kt:60-67`）；楼中楼一层回复（文档允许深度≤2，`MomentEntity.kt:58`、`MainViewModel.kt:1035-1042`）。无计费红线执行到位（`app/src/test/java/com/neethu/gouxiang/chat/MomentsTest.kt:126-135` 断言 prompt 无猫粮/解锁/邀请字样）。

### B. 11-audio-drama.md（广播剧，19 条中 18 条 ❌、1 条 ⚠️）

工程中广播剧功能**整体未实现**，无任何 `radiodrama`/`radio`/`chapter` 代码。逐条：

- **B1 ❌ 聊天页入口「转广播剧」+ 三态徽标（§1.2/§3.4-4）**：聊天页（`ui/phone/PhoneChatScreen.kt`、`ui/chat/ChatMainScreen.kt`）无该功能位。
- **B2 ❌ 轮次门槛拦截（§1.2-2）**：无。
- **B3 ❌ 期列表/解锁态/锁标（§1.4：Locked/Finished/批量解锁；计费本身豁免）**：无章节列表概念。
- **B4 ❌ 异步生成 + 退出不阻塞 + 完成通知（§1.2-4/§3.2-(6) 推送闭环）**：无生成任务、无通知（manifest 无 POST_NOTIFICATIONS）。
- **B5 ❌ 脚本结构：对话轨 + 独立旁白 + 独立内心独白轨（§1.3 核心：ScriptLine/scriptLines/monologueLines、Speaker 四轨）**：无对应数据模型。
- **B6 ❌ 每期取材区间 dialogueRange（§1.3：消息 id 区间；前置 T0「消息 ID 面暴露」）**：`data/MessageEntity.kt` 有自增 id，但无 StoredMessage/messagesWithId 等取材面，也无区间记录。
- **B7 ❌ 章节状态机（§1.4：ChapterGenStatus 五态/ChapterShowStatus/Backtracked 一等状态）**：无任何枚举/状态机。
- **B8 ❌ 跟随聊天持续更新（§3.2-(6)：RadioDramaUpdater 挂 onTurnCompleted，副标题承诺「将持续更新」）**：`ChatOrchestrator` 的回合收尾钩子（`MainViewModel.kt:60-65` memoryHook）只接记忆协调器，无剧更新器。
- **B9 ❌ 配音三槽设置（§1.5/§3.3：角色/旁白/我的配音、沿用声音设置、声音设置弹层）**：无。
- **B10 ⚠️ 多音色逐行合成底座（§3.1/§3.2-(3) 的依赖前提）**：底层 TTS 具备换音色能力——`ai/speech/SpeechTypes.kt:21-38`（`TtsConfig.voice/speed/extraParams`）、`SpeechTypes.kt:62-64`（`TtsAdapter.synthesize` 句级接口）、`ai/speech/VolcanoEngineTtsAdapter.kt:58,177,192`（speaker 取自 config.voice）+ 硅基兜底（`ai/speech/VoiceCallEngine.kt:276-278`）。但仅服务于语音通话且音色硬编码单值（`VoiceCallEngine.kt:215` `DEFAULT_SPEAKER`、`:235` `SILICONFLOW_VOICE=…anna`）；无音色清单、无逐行编排器、无重采样拼接。**多音色编排（合成器本体）缺失。**
- **B11 ❌ 播放器状态机 + 顺序队列 + 行级 seek（§3.2-(4) RadioDramaEngine）**：无。
- **B12 ❌ 熄屏后台收听（§1.6/§3.3：前台服务 + MediaSessionCompat + manifest `foregroundServiceType="mediaPlayback"`）**：全库 grep foreground/MediaSession/Media3 零命中，manifest 无相关权限与 service 声明。
- **B13 ❌ 整期 WAV 落盘缓存/切行（§3.2-(6.5)）**：无。
- **B14 ❌ 回溯冲突管理（§1.4：isBacktracked/历史冲突区/冲突拦截；v1 方案 A 虽推迟标记，但文档 T8 仍要求冲突区空态占位）**：无。
- **B15 ❌ LLM 工具 list_radio_drama（§3.4-3）**：无工具注册体系内该项。
- **B16 ❌ UI（§3.3：RadioDramaPage 章节列表+播放页+分轨字幕、RadioDramaVoiceSheet）**：无。
- **B17 ❌ 音色清单/语速语调/试听 UI（§2.1 parallel_voice_* 组）**：无任何音色选择/试听界面。
- **B18 ❌ 文案/空态接入（§2.1 全量 88 条、空态三连）**：无。
- **B19 ❌ 新期完成推送文案（§3.2-(6)）**：无。

（音色克隆 §1.7：文档 v1 明确不做、仅字段预留，不计入偏差。）

### C. 12-avg-story-mode.md（AVG 剧情模式，15 条中 14 条 ❌、1 条 🔀）

工程中 AVG/剧情模式**整体未实现**，无 `orchestrator/story`、`story.db`、StoryScreen 等任何对应物（全库 grep `avg/剧情/story/chapter/章节/存档/结局/选项` 零代码命中）。

- **C1 ❌ 三玩法形态 challenge/novel/openworld（§1.0/§3.2 mode 字段）**
- **C2 ❌ 剧本资产 assets/story/*.json + StoryScript @Serializable 解析（§3.2/T1）**：`app/src/main/assets/` 下无 story 目录。
- **C3 ❌ story.db 存档层：story_saves/story_logs 两表、StoryStore、章节/节点/待选/挑战轮存档字段（§3.3/T2）**
- **C4 ❌ StoryDirector 推进器（§3.4/T3：start/chooseOption/continueChat/restart/rewindTo/regenerateLast/rewriteLast/jumpToChapter 八 API）**
- **C5 ❌ 卡片流七类型（§3.3 SegmentKind：NARRATION/NPC/PLAYER/CHOICE/CHAPTER_TARGET/ENDING/CONTINUE_CHAT）**
- **C6 ❌ 选项组交互：choices 全量+已选同存（ChoicesContent 语义）、自由输入 MANUAL_INPUT、plotHint 注入（§3.4 规则 2/3）**
- **C7 ❌ 章节推进：章节目标卡 objective/objectiveVisible、挑战限轮 maxRounds、successJump/failJump（§3.4 规则 4）**
- **C8 ❌ StoryJudge 判定器（§3.5/T4：章节目标/挑战判定、轮数短路、跑题→DEVIATE）**
- **C9 ❌ 结局系统：EndingKind 四型、结局卡文案×按钮映射、unlockedEndings 解锁计数（§3.4 规则 6/T5）**
- **C10 ❌ 回溯/重写/重启：保留截断行、选项重放、prefill 快径、2s 防抖与边界守卫（§3.4 规则 5/T6）**
- **C11 ❌ UI：StoryScreen 卡流/ChoiceBar 选项条/剧情信息条、StoryCard 六态状态条（§3.7-3/T7）**
- **C12 ❌ 完结后续聊/自由探索 continueChat（§3.4/T5）**
- **C13 ❌ ai_cmd 六命令 story_start/state/pick/rewind/restart/endings（§3.7-3）**
- **C14 ❌ 集成：上下文入口「剧情」按钮、StoryWiring 工具 story_status/story_set_objective、prefs（§3.7-2/4/5/T8）**
- **C15 🔀 首页「剧卡」入口（任务指定核对点 ui/home/HomeFeedScreen.kt）**
  - 现状：`ui/home/HomeFeedScreen.kt:103`「AI 剧」tab 仅为静态文案；剧卡（`:142-269`）是纯视觉复刻——互动数硬编码「361/5/28/历史」（`:261-264`），介绍条为固定串「本地版 · 角色形象由 AI 生成」（`MainActivity.kt:107`），卡片与开场白气泡均不可点入任何剧情玩法。
  - 差异：文档的剧入口应进入 AVG 引擎；实现是装饰性壳，无任何剧相关行为。

## 三、代码中有、文档未要求的相关行为

1. **首进空态自动生成一条动态**（`MainViewModel.kt:894-898`，`autoGenerated` 防循环）——本地自创的「范围 3」按需生成，替代文档的每日服务端任务（见 A1）。
2. **评论生成失败插兜底占位行**「……(刚刚没想好说什么)」（`MainViewModel.kt:1035-1043,1049-1058`）——文档对应语义是 `comment_fail_toast` 失败 toast；实现改为落一条角色侧占位评论，保证评论区不悬空。
3. **「生成动态」黄胶囊替代「邀请角色」入口**（`MomentsScreen.kt:152-174`）——入口语义置换，已并入 A6/A15。
4. **朋友圈配图 prompt 组装规则**：`character.appearanceStyle + imagePrompt + "生活随拍,柔和自然光"`，固定 1024×1024（`MainViewModel.kt:957-963`、`ai/image/AvatarImageProvider.kt:56-60`），生图链火山 seedream→硅基 Kolors→纯文字兜底。
5. **评论防重入**：`replyingToId` 挂靠动态、生成期间输入条禁用（`MainViewModel.kt:1013`、`MomentsScreen.kt:279`）。
6. **首页剧卡互动数据为硬编码展示值**（`HomeFeedScreen.kt:261-264`），文档未要求，但易被误读为真实剧数据。

## 四、关键结论

- **朋友圈（文档 10）**：按需单角色版已可用（生成/图文/点赞/楼中楼回复/持久化），与文档的差距集中在「每日自动化、多角色互评、用户发帖、权限/成员/配额/安全模型」四块，其中用户发帖配额与敏感词兜底属文档 P0 且与计费无关。
- **广播剧（文档 11）**：仅 TTS 底座（可换音色的句级合成接口 + 火山/硅基双引擎）可复用，剧集/编排/播放/后台收听全链缺失；manifest 无前台服务，熄屏后台收听无任何基础。
- **AVG 剧情模式（文档 12）**：零实现；首页「AI 剧」剧卡为纯静态视觉壳，无功能入口。
- 佐证：工程自述 `docs/local-maoxiang-report.md:217` 亦将「广播剧 11、短剧模式 12」列为下一期范围，与代码现状一致。
