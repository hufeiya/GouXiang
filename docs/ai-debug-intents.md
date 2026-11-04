# GouXiang AI 原生调试接口(ai_cmd)设计 · 落地文档

> 状态: 设计定稿,未施工。施工范围见 §7,排期见 §8。
> 参照: `/home/neethu/projects/AIAvatar-SDK/docs/ai-debug-intents.md` 及其实现
> (`app/.../AiDebug.kt` 协议解析 + `AiDebugExecutor.kt` 执行器 + MainActivity 通道)。
> 动机: `docs/maoxiang-gap-analysis.md` §0 横切缺失明确列出 **「无 ai_cmd 调试命令体系」**
> —— 各功能域文档的调试入口全部退化为 UI 手势,真机验收只能靠截图目测。

---

## 1. 目标与非目标

**目标**

1. LLM 代理通过 `adb shell am start` 的 Intent extras 直接驱动 App 内的等价按钮行为
   (发消息 / 触发主动消息 / 切风格 / 查记忆 / 生成心声与朋友圈……),替代
   「adb 截图 + 模拟点击」——**本项目目标设备是 HyperOS/MIUI,shell 触摸注入被禁**,
   这套通道是真机自动化验收的唯一可行路径(AIAvatar-SDK 已实锤)。
2. 每条命令的执行结果(成功 / 失败原因 / 状态查询输出)打印到 logcat 固定标签
   **`AIDebug`**,代理用 `adb logcat -d -s AIDebug` 读回,形成闭环。
3. 冷启动、运行中均可下发:命令入队,UI 就绪后逐条执行。
4. 为缺口报告的第 1 节断链修复(卡片端到端 / proactivity 消费 / 回溯联动删记忆 /
   失败显式化)提供**可直接观测的探针命令**,验收不再靠人眼。

**非目标**

- 不做 HTTP/TCP 调试服务(不引入端口与权限面,adb 通道够用且零新权限)。
- 不复刻 AIAvatar-SDK 的 3D 族命令(模型/场景/相机/动作/特效/碰撞)——本项目无渲染器。
- 不把 `AiConfigActivity` 里的密钥明文透出到日志(红线,见 §9)。

---

## 2. 参照架构(AIAvatar-SDK 的四件套)

| 层 | 文件 | 职责 | 移植结论 |
|---|---|---|---|
| 协议层 | `AiDebug.kt`(纯 Kotlin) | extras 常量、`AiDebugCommand` 数据类、`parseAiDebugCommand(Map)` 容错解析(数值 extra 传成字符串也能解析、布尔接受 `true/on/1`)、`aiDebugHelp()` 命令表 | **原样移植**,纯函数可 JVM 单测(参照 `AiDebugTest.kt`) |
| 通道层 | `MainActivity` + manifest | `launchMode="singleTask"` 保证重复 `am start` 路由到既有实例触发 `onNewIntent`;`Channel<AiDebugCommand>(UNLIMITED)` 入队;Compose 侧 `LaunchedEffect` 消费 | **结构照搬**,消费点从 DemoScreen 换成 MainFrame(已有 viewModel) |
| 执行层 | `AiDebugExecutor.kt` | `when(command.name)` 大分发,每条命令返回一行 String 结果;异常统一 catch | **重写执行面**:调用目标从 `AvatarController` 换成 `MainViewModel` + Repo |
| 日志契约 | 同上 | 成功 `Log.i(AIDebug, "OK <cmd>: <结果>")`,失败 `Log.e(AIDebug, "FAIL <cmd>: <原因>")`,结果与参数回显都在 | **逐字保留**(标签、格式、工具链与肌肉记忆通用) |

两个值得继承的工程细节:

- **容错优先**:LLM 常见手误(`--es ai_x 200`、`ai_loop on`)在解析层吃掉,执行层不背锅。
- **Hooks 兜底**:AIAvatar 用 `AiChatDebugHooks` 的 lambda 默认值实现「未接线屏幕返回
  一句人话而非崩溃」。本项目单 Activity 单 VM,直接把 VM 传进执行器即可,但**未实现
  命令必须回显 `FAIL xxx: not implemented yet`**,不许静默。

---

## 3. 本项目的执行面差异(为什么不能照抄命令表)

| 维度 | AIAvatar-SDK | GouXiang | 设计影响 |
|---|---|---|---|
| 状态中枢 | `DemoUiState` + `AvatarController`(渲染引擎) | `MainViewModel`(全部页面状态、Room、AI 适配器都在这) | 执行器签名 `(Context, MainViewModel, AiDebugCommand)`,不再需要 hooks 袋 |
| 视觉验证 | `screenshot` 命令(渲染帧导出) | 纯 Compose UI,`adb exec-out screencap -p` 直接可用 | **不做 screenshot 命令**,文档里写明用 adb 原生命令 |
| 异步回合 | 挂起等待渲染/网络 | `sendChatMessage` 内部协程跑 LLM 流,不阻塞调用方 | `send_chat` 语义 = 「入队即 OK」,结果用 `msgs`/`state` 轮询确认(与 AIAvatar 相同) |
| 密钥 | 设置页 prefs | `ai_config_prefs`(`AiConfigStore.kt:157`)+ BuildConfig 回落 | 状态命令只输出「已配/未配 + 掩码长度」 |

---

## 4. 技术方案

### 4.1 协议层(新文件 `debug/AiDebug.kt`)

包 `com.neethu.gouxiang.debug`,内容 = AIAvatar `AiDebug.kt` 的移植 + 本项目 help 文本:

- `const val AI_LOG_TAG = "AIDebug"`(不改名,工具链通用)
- extras:`ai_cmd`(命令名,不分大小写)/ `ai_arg`(字符串参数)/ `ai_x` `ai_y` `ai_z`
  (数值参数:离线时长毫秒、条数上限、消息 id 等,按命令语义复用)/
  `ai_weight` `ai_loop`(本项目暂无消费命令,**保留解析不消费**——移植零成本,后续扩命令不用动协议)
- `data class AiDebugCommand(name, arg, x, y, z, weight, loop)` + `parseAiDebugCommand(extras: Map<String, Any?>)`
- `fun aiDebugHelp(): String` — §5 命令表的 logcat 文本版

### 4.2 通道层(改动 `MainActivity.kt` + `AndroidManifest.xml`)

1. manifest:`<activity android:name=".MainActivity">` 增加 `android:launchMode="singleTask"`。
   副作用评估:本工程只有 MainActivity 一个入口 Activity,无 `onNewIntent` 依赖方,
   singleTask 只影响「重复启动复用实例」,与现有行为兼容;`AiConfigActivity` 不受影响。
2. `MainActivity` 增加:
   - `private val aiCommands = Channel<AiDebugCommand>(Channel.UNLIMITED)`
   - `override fun onNewIntent(intent: Intent)` + `onCreate` 末尾调用 `enqueueAiCommands(intent)`;
     `enqueueAiCommands` 逻辑照抄参照实现(解析失败且带 `ai_*` extras 时 warn,
     避免 LLM 排查无从下手),另加 `setIntent(intent)`。
3. 消费点:`MainFrame` composable 内加一个 `LaunchedEffect(Unit) { for (cmd in aiCommands) { … } }`
   循环,调 `executeAiCommand(applicationContext, vm, cmd)`(§4.3)。`vm` 就是现有
   `viewModel()`,无需穿透 Compose 树。

### 4.3 执行层(新文件 `debug/AiDebugExecutor.kt`)

```kotlin
internal suspend fun executeAiCommand(
    context: Context,
    vm: MainViewModel,
    command: AiDebugCommand,
) {
    val result: String = try {
        when (command.name) { /* §5 各命令 */ }
    } catch (e: Exception) {
        Log.e(AI_LOG_TAG, "FAIL ${command.name}: ${e.message}")
        return
    }
    Log.i(AI_LOG_TAG, "OK ${command.name}: $result")   // 多行结果整段输出
}
```

- 协程宿主:消费循环本身在主线程 compose 协程内;VM 的 suspend 方法均 main-safe
  (Room/OkHttp 自带调度器),执行器内不做 `withContext` 大包裹。
- 参数解析辅助:`requireArg(command)`(缺失即抛,FAIL 行带用法提示)、
  `resolveEnum<DialogMode>(arg)` 等小工具,风格照抄参照实现的 `resolveAssetFile`。
- 不新增任何依赖、不动 `protocol/`、`chat/` 既有代码;需要的少量读数出口见 §7.3。

### 4.4 日志契约(与参照逐字对齐)

```
OK send_chat: queued "三二一" (turn events will appear in msgs)
FAIL load_xxx: No XXX matches 'foo'. Send ai_cmd=help to see options.
```

- 每条命令**恰好一行 OK 或一行 FAIL**(多行结果允许 OK 段内换行)。
- `help` 特殊:命令表打完回 `OK help: printed`。
- 后续回合级事件(泡落库、失败态)不追加日志——**查询靠 `msgs`/`state`,不靠事件流**,
  保持日志面干净可 diff。

---

## 5. 命令一览

按域分组;`P0` = 第一批施工,`P1` = 第二批,`P2` = 有前置改动的缓批。

### 5.1 通用 / 状态

| 命令 | 参数 | 分期 | 作用(执行目标) |
|---|---|---|---|
| `help` | — | P0 | 打印 §5 全表到 logcat(`aiDebugHelp()`) |
| `state` | — | P0 | 一行快照:当前 tab / 当前会话(id、phone?、角色名、sending、failedTurn 有无)/ 对话风格 mode+length / ai 配置(provider+llmModel+ttsModel+asrModel,密钥只出 `已配[len=N]`)/ 未读角标数 |
| `msgs` | 可选 `ai_x`=条数(默认 20) | P0 | 当前会话最近 N 条消息:`id turn role kind 前80字`;`kind` 必须原样回显(text/sticker/img/voice/card)——**卡片断链修复的验收口** |
| `contexts` | — | P0 | 会话列表:`id mode 角色名 未读 最近摘要前20字 更新时间` |
| `open_chat` | `ai_arg`=`<会话id>` 或角色名;`ai_arg2 位次`用 `ai_x`=`1` 表 phone 模式 | P0 | 等价消息列表点进聊天(`openChat(id, phoneMode)`,`MainViewModel.kt:199`);按角色名时走 `CharacterDao.byName` |
| `open_tab` | `ai_arg`=home \| messages \| profile | P0 | 写 `selectedTab`(驱动底栏导航,MIUI 禁触摸注入时的 UI 漫游) |
| `open` | `ai_arg`=character \| memory \| innervoice \| moments \| history \| call \| guide \| aiconfig | P1 | 打开对应覆盖层(`openCharacterHome:140` / `openMemory:1177` / `openInnerVoices:816` / `openMoments:975` / `openHistory:417` / `openVoiceCall:388` / `openPhoneGuide:756`;aiconfig 直接 startActivity)。前置会话不存在时报 FAIL 带原因 |
| `back` | — | P1 | 逐层关闭当前覆盖层(closeMemory/closeMoments/…/closeChat,按当前栈顶语义判定) |

### 5.2 对话链路(02/04 号文档域)

| 命令 | 参数 | 分期 | 作用 |
|---|---|---|---|
| `send_chat` | `ai_arg`=消息文本 | P0 | `sendChatMessage(text, phoneMode=当前模式)`(`MainViewModel.kt:262`),全链路:LLM 流→断句→泡落库。回显 `queued "…"`;回合结果用 `msgs` 轮询 |
| `send_card` | `ai_arg`=`<类型> [标题\|正文]`(即行内标记去括号,如 `转账 520|谢谢你`) | P0 | 解码成 `CardPayload` 走 `sendCard()`(`MainViewModel.kt:325`)。**用户侧落库 kind 必须 =card**——缺口报告 A🔀9 断链修复的验收口 |
| `parse` | `ai_arg`=任意协议文本(如 `[卡片:转账 520][sticker:开心]`) | P0 | **纯解析探针**(不落库不请求 LLM):`MultiBubbleProtocol` 解析后逐段回显 `type=xxx 内容前20字`,验证段型识别/白名单/卡片行 |
| `retry` | — | P0 | `retryFailedTurn()`(`MainViewModel.kt:302`);无失败回合时 FAIL `no failed turn` |
| `rewind` | `ai_x`=messageId(或 `ai_arg`=last) | P0 | `rewindToMessage(id)`(`MainViewModel.kt:636`)。**验收口:回溯后 `memory_list` 里晚于回溯点的 event/commitment 应消失**(缺口 A15 联动修复后) |
| `regen` | `ai_x`=messageId,或 `ai_arg`=last | P1 | `regenerateTurn()`(`MainViewModel.kt:670`) |
| `interrupt` | — | P2 | 打断进行中回合。**前置改动**:VM 需暴露回合 Job 取消口(编排器已有 `collectJob?.cancel()`,`ChatOrchestrator.kt:353`),M3 单独施工 |
| `voice_play` | `ai_x`=messageId | P2 | `playVoice(id, text)`(`MainViewModel.kt:730`),验证 TTS 语音条合成路径 |

### 5.3 主动消息(05 号文档域)

| 命令 | 参数 | 分期 | 作用 |
|---|---|---|---|
| `proactive` | — | P0 | 触发探针(只读,D 工作包升级):按与扫描/进页完全同口径的频控判定(`debugProactiveDecision()`,账本快照 + `ProactivePolicy`)回显放行场景或拦截原因;另附用户活跃年龄与未读数 |
| `proactive_fire` | `ai_arg`=hello \| check_in \| share \| offline;`offline` 时 `ai_x`=模拟离线毫秒(默认 2h) | P0 | 直接触发 `sendProactiveMessages(kind)`(`MainViewModel.kt:590`);`offline` 走 `simulateOfflineReturn()`(`MainViewModel.kt:768`)。等价长按「…」手势,绕过 UI 等待 2h 的验收死路 |
| `proactive_backdate` | 可选 `ai_x`=回拨毫秒(默认 2h) | D | D 工作包:回拨当前会话的用户活跃时间(只动 `proactive_conversation_state` 账本,不碰消息数据),使 `proactive_scan` 能按「离线回归」命中频控判定 |
| `proactive_scan` | — | D | D 工作包:手动跑一轮后台扫描(与周期任务 `ProactiveOutboxWorker` 同链路:开关自查→频控→会话级事务预约→生成→事务收口→通知)。命中即生成并投通知;入队即 OK,结果用 `msgs`/`proactive_state`/系统通知确认 |
| `proactive_state` | — | D | D 工作包:只读输出当前会话账本——活跃时间/最近批次/未读数/今日批次计数/批次清单(状态机 PENDING→READY\|FALLBACK\|FAILED 与通知幂等门 notified、已读 seen) |

### 5.4 记忆(03 号文档域)

| 命令 | 参数 | 分期 | 作用 |
|---|---|---|---|
| `memory_list` | 可选 `ai_arg`=过滤词 | P0 | 当前角色记忆条目:`id [分类] 内容前60字 (updatedAt)`;末行附总数 |
| `memory_stats` | — | P0 | 总数 / 分类分布(profile·name·event·commitment 计数)/ 当前角色 |
| `memory_forget` | `ai_arg`=id(前缀唯一匹配) | P0 | `forgetMemory(id)`(`MainViewModel.kt:1248`),歧义时列出候选 |
| `memory_summarize` | — | P0 | `summarizeMemoryNow()`(`MainViewModel.kt:1225`)手动提炼,验证提炼管线不等到 5 轮阈值 |
| `memory_edit` | `ai_arg`=`<subtype> <值>` | P1 | `editMemoryProfile(subtype, value)`(`MainViewModel.kt:1238`),验收称呼类修复(缺口 A5) |

### 5.5 状态与风格(04/08/09 号文档域)

| 命令 | 参数 | 分期 | 作用 |
|---|---|---|---|
| `status` | — | P0 | 对当前会话跑 `StatusRule.generate()`(`StatusRule.kt:29`)回显 `emoji+text` 与判定依据(命中关键词 / 动作段 / 默认态)——状态条域的确定性验收口 |
| `set_style` | `ai_arg`=heartbeat \| delicate \| long_dialog \| deep_think \| basic | P0 | `selectDialogMode()`(`MainViewModel.kt:116`),持久化到 `chat_style_prefs`;`state` 回显核对 |
| `set_length` | `ai_arg`=shorter \| medium \| longer | P0 | `selectReplyLength()`(`MainViewModel.kt:122`) |

### 5.5a 角色状态(E 工作包,2026-10-31 增补)

| 命令 | 参数 | 作用 |
|---|---|---|
| `status_now` | — | 当前状态条取值 + 来源(`status record`=有生效记录 / `rule fallback`=无记录走规则兜底);与聊天页状态条同源 |
| `status_list` | `ai_x`=条数(默认 10,上限 30) | 近期状态记录:`id emoji 心情= 做= 想= \| src=turn/manual turn= 回合 消息区间 v=会话版本 \| 状态 时间`;与「TA 的状态」面板同源,含已随回溯失效(stale)行 |
| `status_inject` | `ai_arg`="心情\|正在做的事\|内心想法[\|emoji]" | 手动注入一条状态(sourceKind=manual,不锚定回合);**离线验收不依赖 LLM**;注入后聊天页状态条与面板立即生效,查库归属同 `status_list` |
| `status_refresh` | — | 立即按最近对话生成一次状态(**真实请求 LLM**;失败保持最近有效状态,返回 queued 用 `status_list` 轮询) |

E 工作包验收口径:两角色各 `status_inject` 不同状态 → `status_list` 各查各的(互不串);`rewind` 后被回溯命中来源区间的记录转 stale(面板「失效记录」区可见,`status_list` 回显 state=stale);断网聊天不崩、无记录时 `status_now` 回 `rule fallback`。

### 5.5b 任务历史(M0 收尾,2026-10-31 增补)

| 命令 | 参数 | 作用 |
|---|---|---|
| `tasks` | — | 最近任务清单:`workId前8位 来源 状态(失败分类) 类型·会话 结果ID 原因`;失败分类=网络失败/鉴权失败/超时/解析失败/用户取消/未知 |
| `task_retry` | `ai_arg`=workId前8位 | 重试一条失败任务(与「任务历史」页同一条数据路径;沿用原 requestId,不重复插卡;自动任务重试仍受开关闸门约束,被闸即如实记 skipped) |
| `task_cancel` | `ai_arg`=workId前8位 | 取消一条排队/进行中任务;已结束的任务回 `already finished` |

### 5.5c 热启动(onNewIntent)验收口径(M0 收尾修复)

应用已启动(进程存活)状态下再次 `am start` 触发 `onNewIntent`。修复后命令入队走**进程级总线**(`debug/AiDebugBus`,Activity 实例重建不丢命令),消费循环不再吞取消。复验步骤:

```bash
PKG=com.neethu.gouxiang
# ① 应用保持启动(先打开一个聊天,send_chat 作用于当前打开的会话)
adb shell am start -n $PKG/.MainActivity --es ai_cmd open_chat --es ai_arg "'苏馨玥'"
# ② 不杀应用,直接热启动下发消息
adb shell am start -n $PKG/.MainActivity --es ai_cmd send_chat --es ai_arg "'三二一'"
# ③ 双标签核对:AIDebug.Queue 是入队溯源(onNewIntent 行),AIDebug 是执行结果(OK send_chat: queued)
adb logcat -d -s AIDebug.Queue
adb logcat -d -s AIDebug
# ④ 轮询确认回合被执行
adb shell am start -n $PKG/.MainActivity --es ai_cmd msgs
```

排查口径:`AIDebug.Queue` 无行=Intent 没进 App(检查 am start 形式/MIUI 权限);有行但无 `OK send_chat`=消费侧问题(把 logcat 全量带回)。注意 `send_chat` 作用于当前打开的聊天,未打开会话时按 §9.6 返回 FAIL,需先 `open_chat`。

### 5.5d 角色创作与管理(C 工作包,2026-10-31 增补)

| 命令 | 参数 | 作用 |
|---|---|---|
| `chars` | — | 角色清单:`id 名字 source=来源 avatar=当前头像文件(-=未生成) v<头像版本>`;验收「重启后角色/头像/会话对应不串」的轮询出口 |
| `char_create` | `ai_arg`=JSON 载荷;`ai_x=1` 保存后进入聊天 | 创建角色:`{"name":"..","profile":"..","intro":"..","lines":"..","opening":"(..)台词","appearance":"..","style":".."}`。与 UI「保存并开始聊天」同一数据路径:逐字段校验(必填/长度/重名,FAIL 回逐字段原因)→ 单事务落库(角色 + 首个 IM 会话 + 开场白消息 + 手机聊天会话);同名角色已存在时 FAIL `name conflict`(与 UI 双击保存幂等同源) |
| `char_edit` | `ai_arg`=JSON 载荷(必含 `"id":N`) | 编辑既有角色:同上校验与落库;改写 `appearance`/`style` 保存后头像版本 +1、按新版本缓存键重生成(旧头像文件保留在图库可「设为当前」切回);会话与历史不动 |
| `avatar_retry` | `ai_x`=角色 id | 显式重试头像生成(断网失败后的恢复路径);结果用 `chars` 轮询 avatar 字段 |
| `open_tab` | `ai_arg`=create | 切到「创作AI+」tab(角色列表 + 新建入口;编辑页为全屏覆盖层,`char_create`/`char_edit` 直接走数据路径更稳) |

### 5.6 衍生内容(06/07/10 号文档域)

| 命令 | 参数 | 分期 | 作用 |
|---|---|---|---|
| `innervoice` | `ai_arg`=gen(省略=list) | P1 | list=心声卡清单;gen=`generateInnerVoices()`(`MainViewModel.kt:851`)。**需配生图/LLM Key**,未配 FAIL 带原因 |
| `moments` | `ai_arg`=gen \| like `<postId>` \| comment `<postId> <文本>` | P1 | 对应 `generateMoment:1027` / `toggleMomentLike:1097` / `sendMomentComment:1108` |
| `moments_list` | — | P1 | 朋友圈帖子清单:`id 点赞数 评论数 文本前40字` |

### 5.6c 朋友圈扩展与发现(H 工作包,2026-10-31 增补)

| 命令 | 参数 | 作用 |
|---|---|---|
| `moments_list` | — | **多角色合并时间线**(用户帖 + 参与角色动态,查询层权限过滤后):`id 作者(avatar/user) 赞0/1 评N 「文本前40字」`;真实计数,无伪造社交数据 |
| `moments` | `ai_arg`=gen \| post `<文本>` \| like `<postId>` \| comment `<postId> <文本>` \| del_post `<postId>` \| del_comment `<commentId>` | gen=对当前打开聊天生成一条角色动态(入队即 OK);post=用户发帖(落库即上屏,互评轮异步跑,授权角色各评一条、一轮为止);like/comment/del_* 与 UI 同一数据路径(只删用户自己的帖/评论) |
| `moments_perm` | `ai_arg`=`<角色名\|id> <joined\|see\|interact\|daily> <on\|off>` | H2 逐角色权限:参与/看用户动态/评论互动/角色级自动日更(写 `characters` 对应列;自动任务在入队+执行双重拦截,合并时间线与互评候选即时重过滤;手动「生成动态」不受限) |

H 工作包验收 SOP:`open_chat <角色>` → `moments post 今天去了海边` → 轮询 `moments_list`(用户帖出现且授权角色各新增一条 avatar 评论,一轮为止,重发 `moments post` 不会无限互评)→ `moments_perm <角色> interact off` 后再 `moments post`(该角色不再评论)→ `moments_perm <角色> daily off`(自动日更跳过该角色)→ `del_post`/`del_comment` 清场对拍;`open_tab discover`(需 open_tab 支持 discover,或 UI 点击「发现」)看本地角色浏览/搜索/历史。

### 5.6a 番外剧场与心动事件(F 工作包,2026-10-31 增补)

| 命令 | 参数 | 作用 |
|---|---|---|
| `sidestory_keys` | — | 番外模板(15)与心动事件(3:`checkphone`/`peekintoheart`/`jealous`)的 key 清单,`sidestory_generate` 的参数来源 |
| `sidestory_list` | `ai_x`=条数(默认20) | 当前会话的番外/心动记录:`id kind key 状态 配图 请求ID前8位 「标题」`;状态机 generating→succeeded/failed |
| `sidestory_generate` | `ai_arg`=key | 按 key 生成一条(与 UI 按钮同一数据路径:事务内原子去重→落 generating 行→入队 Worker);**入队即 OK,结果用 `sidestory_list` 轮询**;会真实请求 LLM,断网时任务与记录一致转 failed |
| `sidestory_retry` | `ai_x`=记录 id | 重试一条 failed 记录(断网失败后的恢复路径;同一 requestId 幂等重跑) |
| `sidestory_delete` | `ai_x`=记录 id | 删除一条记录(列表与库一致;配图文件一并清理) |
| `open_sidestory` / `close_sidestory` | — | 打开/关闭番外页(当前会话;uiautomator dump 验证「心动事件/番外模板/记录」三区) |

F 工作包验收 SOP:`open_chat <角色>` → `sidestory_keys` → `sidestory_generate his_collection` 与 `sidestory_generate checkphone` 各一条 → 轮询 `sidestory_list`(状态转 succeeded)→ `sidestory_delete <id>` 对拍;断网失败恢复 = 断网后 `sidestory_generate` → `sidestory_list` 见 failed → 联网后 `sidestory_retry`。

### 5.6b 手帐/小说/合照与心声交互(G 工作包,2026-10-31 增补)

| 命令 | 参数 | 作用 |
|---|---|---|
| `handbook_generate` | `ai_arg`=turn5 \| turn10 \| all(缺省 turn10) | 按预设回合范围生成一条手帐(与 UI「聊天范围」chips 同一数据路径:事务内原子去重→按 turn 分组快照来源区间与会话版本→落 generating 行→入队 Worker);**入队即 OK,结果用 `derivative_list` 轮询**;会真实请求 LLM,断网时任务与记录一致转 failed |
| `novel_generate` | `ai_arg`=short \| medium \| long(缺省 medium) | 按长度档(短篇 2~3 章/中篇 4~5 章/长篇 6~8 章)生成一部多章小说;LLM 输出章数越出所选档边界判解析失败(不静默裁剪) |
| `photo_generate` | `ai_arg`=合照描述文本(必填,≤60字) | 二维文生图合照(角色形象+描述;无参考图/身份保持能力,UI 明示不承诺一致性);失败整条记 failed 可重试 |
| `derivative_list` | `ai_x`=条数(默认20) | 当前会话的手帐/小说/合照记录:`id kind 状态 配图 [stale] 请求ID前8位 「标题」`;`stale`=来源历史已回溯 |
| `derivative_retry` | `ai_x`=记录 id | 重试一条 failed 记录(同一 requestId 幂等重跑) |
| `derivative_delete` | `ai_x`=记录 id | 删除一条记录(列表与库一致;配图文件一并清理) |
| `open_handbook` / `close_handbook`、`open_novel` / `close_novel`、`open_photo` / `close_photo` | — | 打开/关闭对应页(当前会话;uiautomator dump 验证「参数区/记录」两区与合照描述输入) |
| `innervoice_switch` | `ai_arg`=on \| off | 当前角色的「自动生成心声」开关(G4 角色级,写 `characters.innerVoiceAuto`;需先 `open innervoice`)。关→自动批量扫描跳过该角色且执行期二次拦截;点击「生成心声」不受限 |
| `innervoice_list` | — | 当前聊天角色的心声卡（含失效状态、时间和摘要） |
| `content_state` | — | 当前聊天尚未消费的成功回合数、发现进度、失败与退避、未读数，以及心声/朋友圈全局和角色开关 |
| `content_list` | — | 当前聊天的候选标题、正文和图片状态、结果 ID、自动生成与提醒状态 |
| `content_open` | `ai_arg`=候选 ID | 打开当前聊天的候选，沿用界面的正文生成路径 |
| `content_auto` | `ai_arg`=voice\|moment on\|off | 通过设置页同一方法设置心声或朋友圈全局正文自动生成开关 |
| `innervoice_delete` | `ai_x`=心声 id | 删除一张心声卡(G4 内容管理;配图文件一并清理) |

G 工作包验收 SOP:`open_chat <角色>` → `handbook_generate turn10`、`novel_generate medium`、`photo_generate 天台看晚霞` 各一条 → 轮询 `derivative_list`(状态转 succeeded)→ `derivative_delete <id>` 对拍;断网失败恢复 = 断网后 `photo_generate` → `derivative_list` 见 failed → 联网后 `derivative_retry`。心声开关验收 = `open innervoice` → `innervoice_switch off` → dump 心声页开关为关,且心声自动批量(若全局开关开)跳过该角色。

### 5.7 AI 底座(ai_config_prefs / 语音)

| 命令 | 参数 | 分期 | 作用 |
|---|---|---|---|
| `ai_config` | — | P0 | 已解析配置快照:provider / llmModel(含目录回落)/ ttsProvider+ttsModel+voice / asrModel / 各家密钥**只出 `已配[len=N]` 或 `未配`** / BuildConfig 回落是否生效(`AiConfigStore.load`,键名见 `AiConfigStore.kt:133-145`) |
| `set_provider` | `ai_arg`=siliconflow \| volcano \| openrouter \| deepseek \| bailian \| custom | P1 | 写 `ai_config_prefs`(与 `AiConfigActivity` 同一条 `AiConfigStore.save` 路径);编排器每回合现读配置,即时生效无需重建 |
| `set_llm_model` | `ai_arg`=模型 id | P1 | 同上,清单校验回落语义同配置页 |
| `tts_test` | `ai_arg`=文本 | P2 | 走 `TtsSpeaker` 合成播放一段,验证 TTS 底座(火山/硅基双引擎) |
| `asr` | `ai_arg`=音频文件路径(先 `adb push` 到应用外部目录) | P2 | 走 `OpenAiCompatibleAsrAdapter` 转写,文本回显 logcat |
| `call_state` | — | P2 | 语音通话引擎快照(需先 `open call`) |

---

## 6. 调试循环(真机 SOP)

```bash
PKG=com.neethu.gouxiang

# ① 查状态(冷启动会先拉起应用再执行)
adb shell am start -n $PKG/.MainActivity --es ai_cmd state
adb logcat -d -s AIDebug

# ② 发一条消息,短轮询确认落库(单次等待 ≤3s,总预算 4 分钟)
adb shell am start -n $PKG/.MainActivity --es ai_cmd send_chat --es ai_arg "'三二一'"
adb shell am start -n $PKG/.MainActivity --es ai_cmd msgs
adb logcat -d -s AIDebug | tail -5

# ③ 卡片断链验收(缺口 A🔀9):解析探针 + 用户侧发卡
adb shell am start -n $PKG/.MainActivity --es ai_cmd parse --es ai_arg "'[卡片:转账 520|谢谢你]'"
adb shell am start -n $PKG/.MainActivity --es ai_cmd send_card --es ai_arg "'转账 520|谢谢你'"
adb shell am start -n $PKG/.MainActivity --es ai_cmd msgs      # 看 kind 是否 =card

# ④ 主动消息验收(缺口 05:无需真等 2h)
adb shell am start -n $PKG/.MainActivity --es ai_cmd proactive
adb shell am start -n $PKG/.MainActivity --es ai_cmd proactive_fire --es ai_arg hello

# ⑤ 记忆链路验收(缺口 A15/A5)
adb shell am start -n $PKG/.MainActivity --es ai_cmd memory_list
adb shell am start -n $PKG/.MainActivity --es ai_cmd memory_summarize

# ⑥ UI 漫游(触摸注入被禁,用命令导航)+ 截屏人眼复核
adb shell am start -n $PKG/.MainActivity --es ai_cmd open_tab --es ai_arg messages
adb exec-out screencap -p > /tmp/screen.png
```

**与快速真机测试纪律对齐**(项目约定):所有验收脚本短轮询 `logcat -d`、单次等待 ≤3 秒、
单场景总预算 4 分钟;`send_chat` 的回合结果是**轮询 `msgs` 确认**,不靠长休眠等 LLM。

文件名/文本含空格时**必须加内层引号**(`adb shell` 在设备端会再分一次词):
`--es ai_arg "'转账 520|备注'"`。

---

## 7. 施工清单(文件级)

### 7.1 新增

| 文件 | 内容 |
|---|---|
| `app/src/main/java/com/neethu/gouxiang/debug/AiDebug.kt` | §4.1 协议层(常量/数据类/解析/help) |
| `app/src/main/java/com/neethu/gouxiang/debug/AiDebugExecutor.kt` | §4.3 执行分发,按 §5 命令分期填充 |
| `app/src/test/java/com/neethu/gouxiang/debug/AiDebugTest.kt` | 解析器 JVM 单测(移植参照 `AiDebugTest.kt`:数值串容错、布尔变体、缺失 ai_cmd 返 null、weight clamp) |

### 7.2 修改

| 文件 | 改动 |
|---|---|
| `app/src/main/AndroidManifest.xml` | MainActivity 加 `android:launchMode="singleTask"` |
| `app/src/main/java/com/neethu/gouxiang/MainActivity.kt` | `aiCommands` Channel + `onNewIntent`/`enqueueAiCommands`(参照 `MainActivity.kt:241-258`)+ `MainFrame` 内消费循环 |

### 7.3 MainViewModel 需新增的只读出口(P0 期一次性加齐,均为查询不改状态)

1. `val currentTab: BottomGrid`(或直接读既有 `selectedTab`)——`state` 用;
2. `suspend fun debugMessages(conversationId: Long, limit: Int): List<MessageEntity>`
   ——`msgs`/`contexts` 用(穿透 repo 到 `MessageDao.recentForConversation`,`Daos.kt:78`);
3. `val debugConversationId: Long?`(当前 chatId 的只读暴露)——`msgs` 默认目标。

其余命令全部命中既有公开方法(§5 表内行号即证),执行器不改 `chat/`、`protocol/`、`data/`。

---

## 8. 分期与验收

| 期 | 范围 | 完成判据(真机,含 §6 循环) |
|---|---|---|
| **M0 基建** | 7.1 三文件 + 7.2 两改动 + P0 里的 `help`/`state`/`open_tab`/`contexts` | 冷启动下发 `state` 出快照;运行中二次 `am start` 不重建界面(`onNewIntent` 生效);JVM 单测绿 |
| **M1 对话链路** | `send_chat`/`msgs`/`open_chat`/`send_card`/`retry`/`rewind` + `parse` 探针 + 5.3 主动消息族 + 5.4 记忆族 + 5.5 全部 | §6 ②~⑤ 全通;`msgs` 的 kind 回显能实锤卡片断链修复;`rewind` 后 `memory_list` 联动可观测 |
| **M2 配置与衍生** | `ai_config`/`set_provider`/`set_llm_model` + `open`/`back` + 5.6 心声/朋友圈 + `memory_edit`/`regen`/`status` 补齐 | 切服务商后下一回合即走新端点(`ai_config` 回显核对);心声/朋友圈命令真机出内容 |
| **M3 缓批** | `interrupt`(VM 加取消口)/ `voice_play`/`tts_test`/`asr`/`call_state` | 各自链路真机验证 |

---

## 9. 风险与红线

1. **密钥绝不落日志**:`ai_config` 只输出掩码长度;`set_provider` 等 FAIL 信息里不得
   回显 extra 原文中的 key 样内容。参照实现无此问题(密钥不进任何命令),本设计从
   第一天守住。
2. **launchMode 迁移**:singleTask 会清掉其上新 Intent 之外的既有栈行为;本工程
   单 Activity 且无 documentLaunchMode/锁屏续登场景,评估无回归——真机 M0 验收项
   专门跑一次「聊天页打开状态下被再次 launch,页面不重置」。
3. **主线程纪律**:执行器整体在主协程;任何命令不许加 `Thread.sleep` 等待(回合
   结果一律轮询查询命令获取)。
4. **LLM 手误容错**在解析层兜底,执行层对未知命令统一 `FAIL <cmd>: unknown command,
   send ai_cmd=help`(返回命令表行数提示,不给栈)。
5. **幂等性**:`open_chat`/`open_tab`/风格类命令重复执行无副作用;`send_chat`/
   `proactive_fire`/`memory_summarize` 天然非幂等,文档与 help 里标注「会真实产生
   消息/请求」,提醒代理别在循环里重试成功过的命令。
6. **多会话语义**:所有「当前会话」类命令(`msgs`/`rewind`/`memory_*`)作用于
   当前打开的聊天;未打开时 FAIL `no open chat — send ai_cmd=open_chat <id|name> first`。

---

## 附:与缺口报告的映射速查

| 缺口报告条目 | 依赖的本接口命令 |
|---|---|
| A🔀9 卡片端到端断链(§1.1) | `parse` / `send_card` / `msgs`(kind 回显) |
| A🔀4 proactivity 丢弃(§1.2) | `proactive_fire share` + `msgs` |
| A15 回溯不删记忆(§1.3) | `rewind` + `memory_list` 前后对拍 |
| A🔀5 失败伪装成功(§1.4) | `state`(failedTurn)/`retry`/`msgs` |
| B/A5 种子称呼分类错(§1.6) | `memory_list` + `memory_edit` |
| A🔀3 ()白名单缺失(§1.7) | `parse` 探针逐例对拍 |
| 05 主动消息域验收 | `proactive` / `proactive_fire`(免等 2h) |
| 横切「无 ai_cmd 调试命令体系」 | 本文档全部 |
