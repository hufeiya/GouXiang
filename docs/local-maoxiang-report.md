# 「本地版猫箱」实施报告

> 项目: GouXiang / 包名 `com.neethu.gouxiang` · Android (Kotlin + Jetpack Compose)
> 基准参考: `/home/neethu/projects/AIAvatar-SDK/docs/maoxiang/ui/screens/`（UI 参考图 + dumps XML）与 `/home/neethu/projects/AIAvatar-SDK/docs/maoxiang-feature-analysis.md`（功能分析）
> 报告日期: 2026-10-29

---

## 一、总览

### 1.1 做了什么

「本地版猫箱」是一个以「猫箱」App 主线体验为蓝本的本地化复刻 App：单角色（种子角色**苏馨玥**）陪伴聊天为主线，围绕参考稿 `01-home-feed` → `02-messages` → `03-chat-main` → `04-character-home` → `05-memory` / `06-moments` / `07-inner-voice` → `11-history` → `12-voice-call` → `14-phone-chat` 的页面链路，在 Android 端实现了：

- **F1 底座与首页**：底栏 5 格（首页/发现/创作AI+/消息/我的，见 `ui/nav/BottomTab.kt`）、首页剧卡流（双 tab「AI 剧|推荐」）、消息页（赞过/聊过 tab + 互动/系统通知固定行 + 会话列表）、Room 本地数据底座与种子数据。
- **F2 陪伴聊天与多泡协议**：Ark LLM 流式回复 + `MultiBubbleParser` 多泡解析（1~4 条独立短泡、泡间节奏连发、动作文字/表情包/图片/语音占位段、状态条 emoji）。
- **F3 记忆系统**：回合收尾自动总结通道（画像判定 + 事件总结）、记忆页（区块对照 `05-memory.xml`）、「立即总结」、记忆问答召回。
- **F4 手机聊天**：iMessage 风皮肤的 `mode=phone` 会话形态（语音条渲染、六宫格、说明页、主动消息、长按回溯/重新生成）。
- **F5 角色主页与对话模式**：角色主页全屏覆盖层、对话模式/回复长度弹层（09/10）、心动/细腻/长对话/深度思考/基础五模式与更短/标准/更长三档。
- **F6 语音通话**：TTS + 大字幕 + 打断 + ASR 的语音通话页（`12-voice-call`）。
- **F7 聊天历史**：历史页（`11-history`），日期分组、倒序、发新消息即时回流。
- **F8 Ta 的心声**：LLM 生成第一人称人设短句卡 + AI 配图，重启持久化。
- **F9 Ta 的朋友圈**：生成动态（文案+配图）、点赞、评论、LLM 回复，重启持久化。
- **F10 收尾修复**：语音消息格式归一化（四种写法 → 统一「•)N″」语音条）、说明页返回/防穿透、列表自动滚底、弹层副标题、首页沉浸式等 7 项收尾。

### 1.2 如何运行

```bash
cd /home/neethu/projects/GouXiang
./gradlew :app:assembleDebug        # 构建（构建期读取密钥）
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- **包名 / 入口**：`applicationId = "com.neethu.gouxiang"`，launchable-activity `com.neethu.gouxiang.MainActivity`（`app/build.gradle.kts:29`；F8 验收 aapt badging 实测同值）。
- **密钥机制**：构建期由 `app/build.gradle.kts:12-43` 读取**仓库根** `/home/neethu/projects/GouXiang/secrets.properties`（惯例同 AIAvatar-SDK，已 gitignore），注入 `BuildConfig.VOLCANO_ARK_API_KEY / SILICONFLOW_API_KEY / VOLCANO_TTS_API_KEY`；文件缺失时回落空串，代码侧把空 Key 视为「未配置」。当前仓库根 `secrets.properties` 实际存在（196 字节，10-29 09:57），含上述 3 个键。**勘误**：密钥文件是 GouXiang 仓库自己的 `secrets.properties`（按 AIAvatar-SDK 同款惯例复制/维护），并非直接读取 `/home/neethu/projects/AIAvatar-SDK/secrets.properties`——两个文件内容不同（sha256 `e722b75c…` vs `fb5f9589…`，`rootProject.file("secrets.properties")` 解析到 GouXiang 根）。
- **模型端点**：LLM 走火山引擎 Ark（`VOLCANO_ARK_BASE_URL = https://ark.cn-beijing.volces.com/api/v3`，模型 `doubao-seed-2-0-mini-260428`，`chat/ChatOrchestrator.kt:275-295`）；生图/TTS 均为**火山优先、硅基流动兜底**（`ai/image/AvatarImageProvider.kt:12,83`、`ai/speech/VoiceCallEngine.kt:35`）。

### 1.3 验收总体结论

| 功能 | 优先级 | 结论 | 证据目录 |
|---|---|---|---|
| F1 底座·首页·消息列表 | P0 | 已通过 | `out/verify/f1/` |
| F2 陪伴聊天与多泡协议 | P0 | 修复后通过（复验 f2-r2） | `out/verify/f2/`、`out/verify/f2-r2/` |
| F3 记忆系统 | P0 | 已通过 | `out/verify/f3/` |
| F4 手机聊天 | P0 | 已通过（6/6） | `out/verify/f4/` |
| F5 角色主页与对话模式 | P0 | 修复后通过（复验 f5-re） | `out/verify/f5/`、`out/verify/f5-re/` |
| F6 语音通话 | P1 | 已通过 | `out/verify/f6/` |
| F7 聊天历史 | P1 | 已通过（4/4） | `out/verify/f7/` |
| F8 Ta 的心声 | P1 | 已通过 | `out/verify/f8/` |
| F9 Ta 的朋友圈 | P1 | 已通过（5/5） | `out/verify/f9/`（accept 子目录） |
| F10 收尾修复 | P1 | 已通过（7/7） | `out/verify/f10/` |

**重要说明**：原计划的「主线全流程端到端真机走查」**由用户中途取消**，未完成、无最终结论（走查员已完成路径前 8 站——首页/消息列表/陪伴聊天流式多泡/角色主页/记忆/弹层/手机聊天复入，部分证据留在 `out/verify/final/`，见第五节索引）。因此本项目的总体结论是：**各功能分别通过验收，端到端全流程走查未完成**。

验收约束全程遵守：验收过程未改代码、未跑构建（各功能验收报告中均有核查记录，如 f3 以 `find app/src -type f -newermt` 对照 APK 构建时间为空、f4 验收报告「未改任何代码、未跑构建」）。

---

## 二、主线功能台账

> 每条含：范围 / 实现要点 / 复用来源 / 验收结论 / 证据路径。验收结论与发现均转录自各功能验收台账（ask 提供的进度台账），证据路径均已在本机核实存在。

### F1 底座 · 首页 · 消息列表（P0）— 已通过

- **范围**：App 底座（包结构、Room 数据库、种子数据）、底栏 5 格、首页剧卡流（双 tab、全屏剧卡）、消息页（赞过/聊过 tab、互动/系统通知固定行、会话列表）。
- **实现要点**：底栏枚举 `ui/nav/BottomTab.kt`（首页/发现/创作AI+/消息/我的，注释「基准 01 截图」）；首页 `ui/home/HomeFeedScreen.kt`（顶部「AI 剧/推荐」双 tab，沉浸式叠图）；消息页 `ui/messages/MessagesScreen.kt`（固定行 1 品红铃铛互动消息、固定行 2 系统通知）；角色头像由生成链落盘本地缓存（`ai/image/AvatarImageProvider.kt`）。
- **复用来源**：参考图 `01-home-feed.png`、`02-messages.png` + dumps `01`/`02` XML；种子角色苏馨玥人设文案复用 maoxiang 功能分析（`data/SeedData.kt:20-25`）。
- **验收结论**：真机验收通过。安装/冷启动正常；首页（双 tab/全屏剧卡/底栏 5 格）与消息页（赞过聊过 tab/固定行/3 条会话行）与参考截图及 dump 结构一致；种子角色苏馨玥存在且头像为真实图像；点击会话无崩溃；全程 logcat 无 fatal。**唯一偏差**：状态栏黑色底、剧卡图片未透出状态栏（参考图为图片延伸至状态栏下），属低级视觉细节——后已在 F10 修复（首页沉浸式，见 F10 条目）。
- **证据路径**：`out/verify/f1/`（01-home.png/xml、02-messages.png/xml、03-after-tap-conversation.png/xml、logcat-full.txt 等 9 份）。

### F2 陪伴聊天与多泡协议（P0）— 修复后通过

- **范围**：聊天主页 03 全屏覆盖层；LLM 流式回复；多泡输出协议（1~4 条独立短泡连发、动作描述渲染、表情包/图片/语音占位段）；状态条（emoji 渲染）。
- **实现要点**：`chat/ChatOrchestrator.kt`（一回合 = 用户消息落库 → 组上下文 → Ark 流式请求 → 流末解析 → 泡间 900ms 间隔依次入库上屏；全链路失败安全）；`protocol/MultiBubbleProtocol.kt`（JSON 首选 + 分隔符兜底双通路，超 4 泡截断，`MAX_BUBBLES = 4`，解析永不抛异常）；`chat/ChatContextBuilder.kt`（角色人设 + 记忆 + 风格注入）。首轮验收发现两项缺陷：多泡未分阶段出现、状态条 emoji 不渲染；修复后复验通过（流式中间态由 `streamDraft` 通道补齐，`ChatOrchestrator.kt:75-79`）。
- **复用来源**：maoxiang-feature-analysis.md §6.3 多泡协议约定（`MultiBubbleProtocol.kt` 头注释引用）；参考图 `03-chat-main.png` + dumps `03-chat-main.xml`。
- **验收结论**：复验（f2-r2）通过——多泡连发（2 轮均 3 条独立短泡、分阶段出现）与状态条 emoji（🍔 正常渲染、dump 可恢复）两项缺陷均已修复；页面结构、多轮上下文、动作描述渲染、电话图标占位、logcat 无 fatal 均无回归。**注**：表情包占位因模型未触发仍未验证（按要求注明）。
- **证据路径**：首轮 `out/verify/f2/`（16 份，含 04b-status-broken-emoji-zoom.png 缺陷记录）；复验 `out/verify/f2-r2/`（10 份，含 03b-streaming-mid.png、03-reply-multi.png/xml、02b-status-zoom.png）。

### F3 记忆系统（P0）— 已通过

- **范围**：回合收尾记忆总结（自动通道）、记忆页 05（区块对照 `05-memory.xml`）、「立即总结」、称呼/身份画像、记忆问答召回、遗忘。
- **实现要点**：`chat/ChatMemoryCoordinator.kt`（窗口/阈值/防抖，单通道 + 画像：达标时先画像后总结，任一成功即清快照覆盖前缀）；`chat/MemoryExtractor.kt`（LLM 抽取）；`chat/MemoryInjection.kt`、`chat/MemoryCapacity.kt`（注入与容量）；记忆页 `ui/memory/MemoryScreen.kt`，挂点由 `ui/MainViewModel.kt:47-52` 装配（memoryExtractor + memoryCoordinator）。
- **复用来源**：`docs/maoxiang/03-memory-upgrade.md` 记忆升级方案 + `05-memory.png`/`05-memory.xml` 参考稿。
- **验收结论**：真机验收整体通过。4 轮个人事实对话、记忆页区块对照 `05-memory.xml` 齐全、立即总结生成事件条目、称呼/身份经自动总结通道填为「小唐/安卓开发人员」、两次记忆问答均引用成功、logcat 无 fatal。**唯一偏差（设计行为非缺陷）**：手动「立即总结」按实现只走事件通道、不更新画像（称呼/身份），需自动总结通道补足；已在报告中说明。
- **证据路径**：`out/verify/f3/`（27 份：13 组 XML/PNG + `REPORT.md` + logcat-full.txt / logcat-memory-lines.txt / summarize-click-time.txt / install-info.txt）。

### F4 手机聊天（P0）— 已通过

- **范围**：`mode=phone` 会话的 iMessage 风聊天页 14、六宫格面板 13b、说明页 13c、主动消息、语音条渲染。
- **实现要点**：`ui/phone/PhoneChatScreen.kt`（iMessage 皮肤、语音条「•)N″」、自动滚动 `:104-112`）、`ui/phone/PhoneChatGuideScreen.kt`（说明页）、`protocol/PhoneProtocol.kt`（手机聊天协议与离线回归场景）；语音播放 `phone/VoicePlayer.kt`、偏好 `phone/PhoneChatPrefs.kt`。
- **复用来源**：`docs/maoxiang/04-phone-chat-shell.md` + 参考 `14-phone-chat.png`、`13c-phone-chat-guide.png`、dumps `13b-phone-chat-panel.xml`。
- **验收结论**：真机验收（设备 2c3769db，app-debug.apk `adb install -r` → Success）6/6 项通过；**1 个 medium**（语音条格式兼容：模型自发输出 `[语音：…]` 方括号格式渲染为文本泡，格式引导后 `(语音:…)` 正确渲染）与 **4 个 low**（说明页缺 BackHandler、说明页触摸穿透、列表不自动滚动、长按无即时反馈）。全部遗留项均由 F10 收尾修复并通过复验（见 F10）。
- **证据路径**：`out/verify/f4/`（110 份，含 `VERIFY-REPORT.md`、logcat-full.txt / logcat-crash-buffer.txt）。

### F5 角色主页与对话模式（P0）— 修复后通过

- **范围**：角色主页 04（状态条同源、宫格入口）、对话模式弹层 09（五模式）、回复长度弹层 10（更短/标准/更长）、主页 X 关闭。
- **实现要点**：`ui/character/CharacterHomeScreen.kt`（宫格入口 + 尾注计数，如 `:317-321` innerVoiceCount「全部 N」）；风格参数 `chat/ChatStyle.kt`（`BurstParams.resolve`，`ChatStyle.kt:97-112`：IM 下 LONGER=3~4 条×45 字等）；长短档经 `ChatContextBuilder` 注入 prompt，burst 整组下发。首轮两个 high（记忆页入口被压住、更长档无效）修复后复验通过——记忆页 overlay 渲染序问题在 `MainActivity.kt:260-263` 有实锤注释（同层后声明者在上）。
- **复用来源**：参考 `04-character-home.png`(+scroll1/2)、dumps `04-character-home.xml`/`04c-character-home.xml`；弹层对照参考图 `09-dialog-mode.png`、`10-dialog-style.png` + dumps `09-dialog-mode.xml`/`10-dialog-style.xml`（对话模式/回复风格 UI 文案）；`docs/maoxiang/09-heartbeat-mode.md`（心动模式机制）。
- **验收结论**：复验（f5-re）通过——两个 high 问题均已在实机修复（记忆页入口直接打开；更长档回复 4 条生效）；前轮通过项（聊天页/角色主页/弹层 09/10/模式 A/B/更短/状态条一致/logcat 无 fatal）全部无回归；主页 X 关闭按钮顺带修复。新增观察项 2 处 low：对话模式卡副标题被压至 5px 不可见、更长轮模型拼句重复。
- **证据路径**：首轮 `out/verify/f5/`（64 份）；复验 `out/verify/f5-re/`（33 份，含 `README.md`、logcat-crash.txt / logcat-full.txt）。

### F6 语音通话（P1）— 已通过

- **范围**：语音通话页 12：入口跳转、TTS + 大字幕、打断、ASR 链路、挂断保存。
- **实现要点**：`ai/speech/VoiceCallEngine.kt`（回合状态机 LISTENING→THINKING→SPEAKING，打断 `interrupt` 回聆听，`:24-80`）；TTS 火山 seed-tts-2.0 优先（`VolcanoEngineTtsAdapter.kt`），未配置/失败落硅基 CosyVoice 兜底（`VoiceCallEngine.kt:35,227`）；ASR `OpenAiCompatibleAsrAdapter.kt`；录音 `WavRecorder.kt`；页面 `VoiceCallScreen.kt`（大字幕/提示条「聆听中/点击打断」）。
- **复用来源**：参考 `12-voice-call.png` + dumps `12-voice-call.xml`；移植自 AIAvatar-SDK 的 LLM/语音适配层（`app/build.gradle.kts:6` 注释）。
- **验收结论**：真机验收通过：入口跳转、TTS+大字幕、打断、ASR 链路（受限验证）、挂断保存、无 fatal 全部达成。
- **证据路径**：`out/verify/f6/`（19 份：01~10 截图 + 7 份 logcat 分段 + `REPORT.md`）。

### F7 聊天历史（P1）— 已通过

- **范围**：历史页 11：时钟入口、消息完整倒序 + 日期分组、可滚动可返回、新消息即时回流。
- **实现要点**：`ui/history/ChatHistoryScreen.kt`（倒序列表 + 「以上是全部历史」+ 按需加载更旧，`onLoadOlder`）；入口挂聊天页互动列「历史」（`MainActivity.kt:280-291`）。
- **复用来源**：参考 `11-history.png` + dumps `11-history.xml`。
- **验收结论**：4/4 验收点通过——时钟(历史)入口→历史页结构对照 `11-history.xml` 一致；历史消息完整含日期分组（「今天」）且可滚动可返回（6 次上滑揭示 08:06→11:12 全部轮次）；发新消息后再次进入历史页新消息即时出现（12:21 左右泡）；logcat 无 fatal（22,945 行 grep FATAL=0，crash buffer 0 字节）。非缺陷观察 2 条：主页 feed 卡上的「历史」文本点击无跳转（实际入口在陪伴聊天页）；底部 tab 第 2 格「发现」为建设中页。
- **证据路径**：`out/verify/f7/`（25 份：01~22 XML/PNG + logcat）。

### F8 Ta 的心声（P1）— 已通过

- **范围**：心声页 07：入口、LLM 生成第一人称人设短句卡 + AI 配图、持久化、无倒计时/计费红线。
- **实现要点**：`ui/innervoice/InnerVoiceScreen.kt`（半屏弹层；`InnerVoiceScreen.kt:51-53` 范围 3——tv_expire 位只显示 `yyyy.MM.dd`，无「剩余 N 天过期」；`:120-146` 本地化补充「生成心声」按钮）；生成通道 `chat/InnerVoiceGenerator.kt`（InnerVoiceChannel，`MainViewModel.kt:834-837` 与记忆抽取同款 LLM 缝）；实体 `data/InnerVoiceEntity.kt`（无锁态/过期/重 roll/计费字段）。
- **复用来源**：参考 `07-inner-voice.png` + dumps `07-inner-voice.xml`（卡四要素：img_cover/tv_title/tv_desc/tv_expire）。
- **验收结论**：真机验收通过——入口/结构对齐参考稿（同位四要素 + AI 配图）；两次真实 LLM 生成 8 张第一人称人设短句卡（引用记忆事件：抹茶拿铁/青菜粥/揉肩颈/彩铅，语气词匹配人设）；force-stop 重启后持久化（DB 行 id=1~4 逐条相同）；无倒计时/计费字样（12 个禁用词全页扫描 0 命中）；logcat 无 fatal（21,660 行 FATAL EXCEPTION=0）。偏差仅 2 处 low：生成耗时 4~7.5s 快于验收窗口下限 10s（已核实为真实火山 Ark LLM 链路，`ChatOrchestrator.kt:284-295` + `:313-315`）；心声称呼用已确立的「老公」而非直呼「小唐」（8/8 条无「唐」字，属合理人设表现/加分项）。
- **证据路径**：`out/verify/f8/`（21 份：01~09 PNG、10/11 XML、12-forbidden-words-scan.txt、13/14 logcat 检查、DB 快照 db-before-restart.db(-wal)/db-after-2nd.db(-wal)/read.db/read2.db）。

### F9 Ta 的朋友圈（P1）— 已通过

- **范围**：朋友圈页 06：入口、生成动态（文案+配图）、点赞/评论/LLM 回复、持久化、无计费解锁红线。
- **实现要点**：`ui/moments/MomentsScreen.kt`（动态卡：圆头像+紫名+正文+配图+时间戳+❤/💬；头部渐变横幅；顶部「生成动态」胶囊替代参考图「邀请角色」）；生成与回复 `chat/MomentsGenerator.kt`（postSystemPrompt/postUserPrompt/parsePost + reply 通路，`MainViewModel.kt:929-950,1029-1034`）；实体 `data/MomentEntity.kt` + 评论 `MomentCommentEntity`。
- **复用来源**：参考 `06-moments.png` + dumps `06-moments.xml`；`docs/maoxiang/10-moments.md`。
- **验收结论**：真机（2c3769db）独立验收全部 5 项通过——入口与参考结构一致（含「Ta 的朋友圈 全部 3」尾注）；生成动态文案/配图贴合苏馨玥人设（老公/彩铅/青菜粥/抹茶拿铁，约 10s 生成第 3 条）；点赞（空心→红实心，desc「点赞」→「取消点赞」）与评论「今天也在想你」→ 约 20s LLM 回复生效；force-stop 重启后动态+点赞+评论全保留；UI dump 全文扫描「解锁/待解锁/计费/猫粮/会员/钻石…」→ NONE；logcat 无 fatal（crash buffer 0 行、主 buffer 24,211 行 grep FATAL=0）。仅 2 处 low 观感偏差：新卡顶部被横幅遮住需下滑、配图约 1:1 方图（参考近 4:3）。
- **证据路径**：`out/verify/f9/`（8 份）+ `out/verify/f9/accept/`（17 份：00~10 截图/dump + logcat-crash.txt / logcat-main.txt + `REPORT.md`）。

### F10 收尾修复（P1）— 已通过

- **范围**：F4/F5 遗留项与全局收尾共 7 项：语音消息格式归一化、说明页返回与防穿透、列表自动滚底、长按回溯/重新生成（手机聊天）、弹层副标题、首页沉浸式、快速回归 + logcat。
- **实现要点**：
  - 语音格式归一化：`protocol/MultiBubbleProtocol.kt:80-89` `voiceLineRegex` 四种写法（`(语音:…)`/`(语音：…)`/`[语音:…]`/`[语音：…]`）统一收为 VOICE 泡，渲染层只有一种「•)N″」语音条；JSON 通路 lenient 解析把方括号消费成数组定界符的场景由裸串还原正则兜底（`:128-153`）。单测 `protocol/MultiBubbleParserTest.kt`（含 `:111` 方括号全角冒号用例、`:120` 四种变体归一用例）全绿：`TEST-…MultiBubbleParserTest.xml` tests=17 / failures=0 / errors=0（副本 `out/verify/f10/47_unit_test_result.xml`）。归一化发生在解析入库时，渲染层直读 kind 字段。
  - 说明页：`ui/phone/PhoneChatScreen.kt:97` `BackHandler`；防穿透由 `MainActivity.kt:188-197` 全屏白底层点击拦截实现。
  - 自动滚底：`PhoneChatScreen.kt:104-112`（用户已在底部才跟随，上滑翻历史不打扰）。
  - 首页沉浸式：`MainActivity.kt:92-97` `contentWindowInsets = WindowInsets(0,0,0,0)`。
- **验收结论**：真机验收通过，7 项验收点全部复验：新会话语音消息（含长按重新生成促发）均渲染为「•)N″」语音条；长按弹「消息操作」菜单，重新生成产出新回复（DB turn-542c3803 旧 rows 替换）、回溯仅删该消息及其后消息；说明页返回/防穿透正常；自动滚底（含「对方正在输入中…」条贴底）；对话模式弹层副标题完整可见；首页剧卡图片延伸至状态栏下无黑条；快速回归（IM 多泡/角色主页/记忆/心声/朋友圈/语音通话入口）+ logcat 全量 22,147 行 FATAL=0 正常。**唯一遗留**：修复前构建产生的 2 条 `[语音：…]` 存量文本泡（DB id=104/107，kind='text'）仍以文本显示——属数据层历史数据，非本次缺陷。
- **证据路径**：`out/verify/f10/`（139 份：00~50 编号截图/dump + ui_*.xml 全量 + 47_unit_test_result.xml + 50_logcat_full.txt）。

---

## 三、架构说明

### 3.1 分层总图

```
app/src/main/java/com/neethu/gouxiang/
├── MainActivity.kt          # 单 Activity；所有页面 = Scaffold 之上的全屏/半屏覆盖层
├── data/                    # 数据层（Room 本地库 gouxiang.db）
├── ai/                      # AI 底座（LLM / 生图 / TTS / ASR，OpenAI 兼容协议）
├── protocol/                # 多泡输出协议（LLM 输出 → 结构化泡）
├── chat/                    # 业务编排（聊天回合/记忆/心声/朋友圈/风格）
├── ui/                      # Compose 页面（home/messages/chat/phone/memory/character/moments/innervoice/history）
└── phone/                   # 手机聊天语音播放与偏好
```

### 3.2 数据层（Room）

- **文件**：`data/GouXiangDatabase.kt`（库名 `gouxiang.db`，8 实体 9 DAO，含 MIGRATION_1_2/2_3/3_4）、`data/GouXiangRepo.kt`（Repo 门面）、`data/Daos.kt`、`data/SeedData.kt` + `data/CharacterSeed.kt`（种子：苏馨玥及其预置记忆）、实体 `CharacterEntity / ConversationEntity / MessageEntity / MemoryEntity (+PendingMemoryWindowEntity) / InnerVoiceEntity / MomentEntity (+MomentCommentEntity)`。
- **要点**：消息 `kind` 字段在解析入库时定稿（text/sticker/img/voice），渲染层直读不重判（F10 结论）；头像/配图等生成产物落 `filesDir` 本地缓存，DB 存路径；心声/朋友圈数据均在 force-stop 重启后完整保留（f8/f9 验收 DB 快照佐证）。

### 3.3 AI 底座（火山优先、硅基兜底）

- **LLM**：`ai/adapter/LlmAdapter.kt`（接口/配置）+ `OpenAiCompatibleLlmAdapter.kt`（Ark 流式 `streamChat`）+ `ToolCallAccumulator.kt`；模型 `doubao-seed-2-0-mini-260428`（`chat/ChatOrchestrator.kt:295`），端点 `BuildConfig.VOLCANO_ARK_BASE_URL`（`:275,285`）。
- **生图**：`ai/image/AvatarImageProvider.kt` 门面——火山 Ark seedream → 硅基 Kwai-Kolors（`OpenAiCompatibleImageGenAdapter.kt`）→ 本地占位兜底；成功即下载 URL 落盘。
- **语音**：`ai/speech/` 下 `VolcanoEngineTtsAdapter.kt`（豆包 seed-tts-2.0，独立鉴权域 VOLCANO_TTS_API_KEY）优先，未配置/失败落硅基 CosyVoice（`OpenAiCompatibleTtsAdapter.kt`，`VoiceCallEngine.kt:227` 空键兜底日志）；ASR `OpenAiCompatibleAsrAdapter.kt`；通话引擎 `VoiceCallEngine.kt`（回合状态机 + 打断）。
- **约定**：空 Key 一律视为「未配置」并走兜底，冒烟测试（`smoke/ArkSmokeTest.kt`）对未配置显式 AssumptionFail（`app/build.gradle.kts:14-15` 注释）。

### 3.4 多泡输出协议

- **文件**：`protocol/MultiBubbleProtocol.kt`（协议与解析器）、`protocol/ActionsJson.kt`（动作序列化）、`protocol/PhoneProtocol.kt`（手机聊天协议/离线回归）、`protocol/ProactiveMessages.kt`（主动消息场景）。
- **协议**：一次回复 = `MultiBubbleReply`（1~4 条 `Bubble`，`proactivity` 日常分享 0~1 条独立泡）。段类型 TEXT / STICKER / IMAGE / VOICE；TEXT 内嵌 `(动作)` 剥离为 `actions` 列表。
- **双通路解析**（`parse()`，`MultiBubbleProtocol.kt:91-97`）：A 通路 JSON 数组/对象（lenient，`ignoreUnknownKeys`）；B 通路换行/`|||` 分段兜底 + 行内约定 `[sticker:…]/[img:…]/(语音:…)/(动作)`。解析永不抛异常：畸形 JSON → 回落 B；超 4 泡截断；kind 不识别 → TEXT；空结果 → 整体兜底为一条 TEXT。
- **语音归一化**（F4 medium 修复）：`voiceLineRegex`（`:80-83`）+ 裸串还原（`:87-89,143-152`）把四种写法统一为 VOICE 泡，渲染层只有一种「•)N″」语音条。
- **解析时机**：流末整段解析而非逐 delta（占位符可能被任意拆分，半截占位符会被当正文；`chat/ChatOrchestrator.kt:30-31`）；泡间节奏（900ms）在回调侧补齐（`bubbleGapMs = 900L`，`:41`）。流式中间态经 `streamDraft` StateFlow 供 UI「正在打字」（`:75-79`）。

### 3.5 页面结构（单 Activity 覆盖层模型）

- **文件**：`MainActivity.kt`（`MainFrame` 组合根，`:54-306`）、`ui/MainViewModel.kt`（全部页面状态 StateFlow + 业务装配，66 个 kt 中最大的编排文件）。
- **模型**：无 Navigation 库；每页为 `chatId/memoryId/characterHomeId/innerVoiceId/momentsId/voiceCallId/historyId` 等状态驱动的全屏/半屏 `Box` 覆盖层，**同层后声明者在上**（渲染序经过验收实锤校准：记忆页必须在角色主页之后声明，`:260-263`；心声页 `:231-242`、朋友圈页 `:244-258`、历史页 `:280-291`、语音通话页 `:293-305` 均按此排序）。
- **页面清单**（与参考稿编号对应）：
  - 首页 `ui/home/HomeFeedScreen.kt`（01）· 消息 `ui/messages/MessagesScreen.kt`（02）
  - 陪伴聊天 `ui/chat/ChatMainScreen.kt`（03）· 手机聊天 `ui/phone/PhoneChatScreen.kt` + `PhoneChatGuideScreen.kt`（13b/13c/14）
  - 角色主页 `ui/character/CharacterHomeScreen.kt`（04，风格表 `ui/character/CharacterStyleSheets.kt`）· 记忆页 `ui/memory/MemoryScreen.kt`（05）· 朋友圈 `ui/moments/MomentsScreen.kt`（06）· 心声 `ui/innervoice/InnerVoiceScreen.kt`（07）· 历史 `ui/history/ChatHistoryScreen.kt`（11）· 语音通话 `ai/speech/VoiceCallScreen.kt`（12）· 占位页 `ui/PlaceholderPage.kt` + 底栏 `ui/GouXiangBottomBar.kt`。
- **单测**：`app/src/test/java/com/neethu/gouxiang/`（protocol/ChatOrchestratorTest/DialogStyleTest/InnerVoiceTest/MemorySystemTest/MomentsTest/PhoneChatTest/BubbleGroupingTest/ArkSmokeTest 等 12 个 kt）。

---

## 四、遗留问题与后续路线

### 4.1 已知遗留（验收实锤，均 low 级）

| # | 问题 | 来源 | 备注 |
|---|---|---|---|
| 1 | **端到端全流程走查未完成** | 计划项 | 原计划「主线全流程端到端真机走查」由用户中途取消（走查员已完成前 8 站：首页/消息列表/陪伴聊天流式多泡/角色主页/记忆/弹层/手机聊天复入，未出最终结论）。目前结论为各功能分别通过验收 |
| 2 | 2 条 `[语音：…]` 存量文本泡仍以文本显示 | F10 | 修复前构建产生的数据层历史数据（id=104/107，kind='text'），归一化只作用于新入库消息；非本次缺陷 |
| 3 | 表情包占位未验证 | F2 | 多泡协议 STICKER 段实现已有（含单测），但模型验收期间未自发触发，真实触发路径未实机验证 |
| 4 | 对话模式卡副标题被压至 5px 不可见 | F5 复验 | low；弹层视觉细节 |
| 5 | 更长档回复模型偶发拼句重复 | F5 复验 | low；prompt 约束层面 |
| 6 | 朋友圈新卡顶部遮挡、配图约 1:1（参考近 4:3） | F9 | low；布局观感 |
| 7 | 心声生成耗时快于验收窗口下限 10s | F8 | low；非缺陷（真实 LLM 链路已核实），仅记录 |
| 8 | 心声称呼用「老公」而非直呼「小唐」 | F8 | low；加分项未硬性达成，属合理人设表现 |
| 9 | 非缺陷观察：主页 feed 卡「历史」文本无跳转；底栏「发现/创作AI+/我的」为建设中占位 | F7/F1 | 按本地版范围设定 |

### 4.2 后续路线（建议优先级）

1. **补做端到端走查**：以 `out/verify/final/` 已完成的 8 站为基础续走主线（手机聊天复入 → 历史 → 语音通话 → 心声 → 朋友圈），出最终全流程结论，弥合「分功能通过」与「整体体验」之间的空档。
2. **存量数据兼容**：为新/旧消息增加一次性 kind 回填迁移（对 DB 内 `[语音：…]`/`(语音:…)` 文本按归一化规则重判），清掉遗留 #2。
3. **表情包实机验证**：在 prompt 中引导一次 STICKER 触发，补 F2 唯一未验证占位段的实机证据。
4. **视觉打磨**：F5 弹层副标题、F9 朋友圈配图比例与新卡遮挡、各 low 级观感项统一走一轮 UI 细节 pass。
5. **能力延伸（参考稿中未纳入本版范围）**：`docs/maoxiang/` 下的换装 08、小剧场 06、广播剧 11、短剧模式 12、心动模式深化等可作下一期；「发现/创作AI+/我的」三个占位 tab 接入真实内容。

---

## 五、实测证据索引

全部证据位于 `/home/neethu/projects/GouXiang/out/verify/`（共 13 个子目录，483 份文件）。关键截图与 dump 按功能索引如下；`*.xml` 为 uiautomator dump，`*.png` 为截图，各目录均有对应 logcat 产物与验收报告（如 f3 `REPORT.md`、f4 `VERIFY-REPORT.md`、f5-re `README.md`、f9 `REPORT.md` / `accept/REPORT.md`）。

| 功能 | 关键截图 | 关键 dump / 文本 | logcat 与其他 |
|---|---|---|---|
| F1（f1/，9 份） | `01-home.png`（首页剧卡）、`02-messages.png`（消息页）、`03-after-tap-conversation.png`、`02-avatars-crop.png`（头像实图） | `01-home.xml`、`02-messages.xml`、`03-after-tap.xml` | `logcat-full.txt`、`logcat-app-24901.txt` |
| F2（f2/ 16 份；f2-r2/ 10 份） | f2 `02-chat-main.png`、`04b-status-broken-emoji-zoom.png`（缺陷）、`04c-reply-bubble-zoom.png`；f2-r2 `02b-status-zoom.png`（修复）、`03-reply-multi.png`、`03b-streaming-mid.png`（流式中间态） | f2-r2 `03-reply-multi.xml`、`04-reply2-multi.xml`、`02-chat-main.xml` | — |
| F3（f3/，27 份） | `08-memory-initial.png`、`09-summarize-event-added.png`、`11-memory-after-auto.png`、`12-memory-profile-filled.png`、`13-recall-name.png` | `04~07-round*-sent.xml`（4 轮对话）、`08~13-memory*.xml`、`REPORT.md`、`summarize-click-time.txt`、`install-info.txt` | `logcat-full.txt`、`logcat-memory-lines.txt` |
| F4（f4/，110 份） | `02-phonechat-enter.png`、`03-phonechat.png`（14 图对照）、六宫格/说明页/主动消息系列截图 | `00-launch-ui.xml`、`01-msglist-ui.xml`、`02/03-phonechat-ui.xml` 等 | `VERIFY-REPORT.md`、`logcat-full.txt`、`logcat-crash-buffer.txt` |
| F5（f5/ 64 份；f5-re/ 33 份） | f5 `05-companion-chat.png`、角色主页系列；f5-re `04-memory-page.png`（入口修复）、`08-longer-reply.png`（4 条生效）、`09-style-longer-selected.png`、`09-style-crop.png` | f5-re `06b-style-sheet.xml`、`10-mode-sheet-regression.xml`、`14/15-shorter-*.xml`、`16-hb-tired.xml`、`19-restore-heartbeat.xml`、`README.md` | f5-re `logcat-full.txt`、`logcat-crash.txt` |
| F6（f6/，19 份） | `01_voice_call_page_initial.png`、`03_reply_big_subtitle_playing.png`（大字幕）、`05_asr_round_reply.png`（ASR）、`06_after_hangup_chat_saved.png`、`08/09/10_interrupt*.png`（打断） | `REPORT.md`、`reference_12-voice-call.png` | 7 份 logcat 分段（tts/asr/interrupt/hangup/full_session） |
| F7（f7/，25 份） | `06-history-initial.png`、`13-history-top.png`（对应 `12-history-top2.xml`「以上是全部历史」）、`22-history-with-new-message.png`（重启后新消息回流） | `04-companion-chat.xml`、`05-history-page.xml`、`07~12-history-scroll*.xml`（6 次滚动）、`15-new-message-sent.xml`、`21-history-after-new-top5.xml`、`14-back-to-chat.xml` | — |
| F8（f8/，21 份） | `02-character-home.png`（入口卡）、`05-inner-voice-open.png`（空态）、`06-generated.png`、`11-inner-voice-batch2.png`、`08-after-restart.png`、`09-after-restart-scrolled.png` | `10-inner-voice-ui.xml`、`11-inner-voice-batch2.xml`、`12-forbidden-words-scan.txt`、`14-logcat-fatal-check.txt`、`REPORT.md` | `13-logcat-full.txt`；DB 快照 `db-before-restart.db(-wal)` / `db-after-2nd.db(-wal)` / `read.db` / `read2.db` |
| F9（f9/ 8 份；accept/ 17 份） | accept `01-charhome.png`、`02-moments-entered.png`、`03-generated.png`、`04-liked.png`（红心）、`05b-comment-sent.png`、`06-reply.png`、`07-reenter.png`（重启后保留）、`09-reenter-bottom.png` | accept `10-ui-dump-final.xml`（红线扫描 NONE）、`REPORT.md`（accept 与顶层各一份） | accept `logcat-crash.txt`（0 行）、`logcat-main.txt`（24,211 行） |
| F10（f10/，139 份） | `30_pc_guide_open.png`（说明页）`25_pc_longpress_dialog.png`、`26_pc_regen.png`、`28_pc_rewind_confirm.png`、`35_modesheet.png`（副标题）、`40_voicecall.png`、`44_autoscroll_sent.png`、`46_bracket_voice_reply.png`（语音条）、`48_im_multibubble.png`、`49_home_immersive.png`（沉浸式） | `ui_pc_*.xml` / `ui_modesheet.xml` / `ui_voicecall.xml` 等全量 dump | `47_unit_test_result.xml`（17/0/0）、`50_logcat_full.txt`（22,147 行） |
| 端到端走查（final/，13 份，**未完成**） | `01-home.png`、`02-messages.png`、`03-chat-main.png`、`03-stream-t1~t4.png`（流式四连拍）、`03-scrollup.png`、`14-phone-chat-reenter.png` | `01/02/03-*.xml`、`03-after-reply.xml` | 无最终结论 |

---

*报告依据：进度验收台账（ask 提供）+ 本机核实的源码路径与证据目录清单。报告中验收结论均为各功能验收时的实测记录；本报告撰写过程未重跑任何构建或真机验证，仅做文件与代码路径核对。*
