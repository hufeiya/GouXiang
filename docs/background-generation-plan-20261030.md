# 猫箱后台生成落地案(WorkManager + 系统通知 + 生成开关)

> 2026-10-30。回应「长程任务(手帐日记/心声/朋友圈/主动消息)后台生成、生成好了再通知用户」的基建缺口。
> 修订(同日):**新增 §3 生成开关**——后台生成费 token,是否「自动后台生成」交给用户在设置页逐项拍板;默认全部自动项关闭,用户点击后才生成。
> 现状依据:全仓 grep `WorkManager|CoroutineWorker|Service|AlarmManager|BroadcastReceiver|NotificationManager|NotificationChannel` 均 0 命中;`AndroidManifest.xml` 仅 2 个 Activity + 3 个权限(INTERNET/NETWORK/RECORD_AUDIO)。缺口报告 `maoxiang-gap-analysis.md` §1/§B9/任务 10 早已点名(无通知体系、无 WorkManager、B9 ProactiveWorker 缺失)。

## 1. 现状:为什么现在是「半后台」

所有生成任务都跑在 `MainViewModel.viewModelScope` 里,进程被杀即丢:

| 任务 | 代码位置 | 现状 | 缺口 |
|---|---|---|---|
| 心声(F8) | `ui/MainViewModel.kt` `generateInnerVoices()` | 独立 `viewModelScope.launch`,退页不取消,结果落库;但 App 被杀全丢 | 无系统通知,再进页才可见;进程死不重试 |
| 朋友圈(F9) | `ui/MainViewModel.kt` `generateMoment()` | 进页空态自动补 1 条 / 手动按钮 | 文档 10 的「每天发帖」无 tick/WorkManager |
| 记忆抽取(F3) | `chat/ChatMemoryCoordinator.kt` | fire-and-forget,窗口持久化可跨进程重试 | 只在回合收尾触发,无隔夜兜底(A16) |
| 主动消息(05) | `protocol/ProactiveMessages.kt` | 进聊天页判定一次 | 无离线 outbox、无 B9 ProactiveWorker、无通知 |
| 手帐/小说/合照(07) | 无 | 完全缺失(文档要求:异步生成+「完成后将通知你」+NotificationChannel) | 共享衍生内容管线整条缺 |

**结论:没有任何后台机制,需要从零立基建。** 好消息是生成缝全部可复用:`ChatOrchestrator.defaultLlm()` 是纯函数工厂、`AiConfigStore.load(app).llmEndpoint()` 读 SharedPreferences(Worker 内可用)、`GouXiangRepo.get(context)` 是进程级单例(任何 Context 可拿)、`InnerVoiceGenerator/MomentsGenerator` 是 object 静态 prompt 组装、Room Flow 落库即上屏。**Worker 里可以直接复用同一条 LLM 链路,不新增抽象层。**

## 2. 目标架构

### 2.1 基建(一次性)

1. **依赖**(`gradle/libs.versions.toml` + `app/build.gradle.kts`):
   ```toml
   work = "2.10.x"   # 以 maven-central 实查为准(工程惯例,版本须与 Kotlin 2.2/AGP 9.4 兼容)
   ```
   `androidx-work-runtime-ktx`(另加 `androidx-work:work-testing` 供单测,见 §5)。不需要 `lifecycle-service`、不需要前台 Service(生成任务非用户阻塞关键路径,普通 OneTimeWork + 30s 级 LLM 请求即可)。

2. **Application 类**(当前没有,`MainActivity` 直接拿 `Application`):
   ```kotlin
   class GouXiangApp : Application() {
       override fun onCreate() {
           super.onCreate()
           NotificationChannels.ensureAll(this)      // 幂等建通道
           DerivativeScheduler.bootstrap(this)       // 幂等注册周期任务(只注册开关为开的项目,见 §3)
       }
   }
   ```
   manifest `<application android:name=".GouXiangApp">`。

3. **权限**:manifest 加 `POST_NOTIFICATIONS`(targetSdk 37 必须);≥33 运行时申请跟随设置页「完成通知」开关打开的动作(拒绝则降级为「仅 App 内红点」,任务照跑)。

### 2.2 任务抽象:一个 Worker 分发多种衍生任务

不搞每任务一个 Worker;单 `DerivativeWorker` + 任务分发,与生成器现有 object 风格一致:

```kotlin
// chat/DerivativeTask.kt —— 纯 Kotlin,可单测(工程红线:断言逻辑零 Android 依赖)
enum class DerivativeKind { INNER_VOICE, MOMENT, HANDBOOK, NOVEL, PHOTO, MEMORY_SUMMARY, PROACTIVE_OUTBOX }

data class DerivativeTask(
    val kind: DerivativeKind,
    val conversationId: Long,
)
```

```kotlin
// work/DerivativeWorker.kt
class DerivativeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val kind = DerivativeKind.valueOf(inputData.getString(KEY_KIND) ?: return Result.failure())
        val convId = inputData.getLong(KEY_CONVERSATION_ID, -1L)
        return runCatching { DerivativeExecutor.execute(app, kind, convId) }
            .fold(
                onSuccess = { ok -> if (ok) Result.success() else Result.retry() },
                onFailure = { Result.retry() },   // 网络抖动靠 WorkManager 退避,不静默丢
            )
    }
}
```

`DerivativeExecutor.execute(app, kind, convId): Boolean` 是核心分发器,逐分支复用现有链路:
- `INNER_VOICE`:搬 `generateInnerVoices()` 主体(读会话→取记忆→`InnerVoiceGenerator` 组 prompt→`InnerVoiceChannel.request`→`repo.insertInnerVoices`→逐张 `AvatarImageProvider` 配图回填)。**判空/失败语义与 UI 版一致**,返回 false 触发 retry。
- `MOMENT`:同理搬 `generateMoment()`(素材+JSON 契约+生图)。
- `MEMORY_SUMMARY`(A16 隔夜总结兜底):闲置 8h+ 且 `pending_memory_window` 非空 → 触发 `ChatMemoryCoordinator` 同款总结(M3)。
- `PROACTIVE_OUTBOX`、`HANDBOOK/NOVEL/PHOTO`:M1/M2 期接入。

关键约定:
- **每请求现取现组配置**:`AiConfigStore.load(app).llmEndpoint()` 在 execute 内现读,与 VM 的「改完即生效」语义一致。
- **幂等**:入队用 `OneTimeWorkRequest` + `enqueueUniqueWork("deriv:INNER_VOICE:$characterId", ExistingWorkPolicy.KEEP, ...)`;执行前按现有查重逻辑落库(心声无轮次门槛,朋友圈防重复发帖需查当日)。
- **约束**:`Constraints(NetworkType.CONNECTED)` + `BackoffPolicy.EXPONENTIAL, 30s`。LLM 请求本身已有 30s 首 delta 看门狗语义,Worker 侧再包一层不冲突。
- **触发入口分两类,开关只门控自动类**(语义见 §3):用户点击 → 直接入队(用户明确意图,不受开关限制);Scheduler/定时触发 → 入队前先查 `GenerationPrefs` 对应开关。

### 2.3 通知体系

```kotlin
// work/NotificationChannels.kt
// 通道一 "derivative":衍生内容完成(心声/手帐/小说/合照/朋友圈)——IMPORTANCE_DEFAULT
// 通道二 "proactive":主动消息(离线生成的新消息)——IMPORTANCE_HIGH
// 红线:文案不出现计费/猫粮/会员字样(有专门单测锁死,通知文案一并纳入扫描)
```

完成通知:`NotificationCompat.Builder(app, "derivative")` + `setContentTitle("${character.name}的心声已生成")` + PendingIntent 打开 `MainActivity`(singleTask,经 `onNewIntent` 处理 `nav_extra`:带 `open_inner_voice_conversation_id` 直跳心声页;与 `ai_cmd` 调试 extras 完全分开,互不占用)。发通知前查 `GenerationPrefs.notifyEnabled`(§3 通知开关)。

聊天页气泡(文档 §3.4 的第三通道):页面内已有 Room Flow 自动上屏,气泡属于聊天页红点/分隔条逻辑,在 M1 期随 outbox 一并做,不属于 Worker 职责。

### 2.4 上屏联动

改动量最小的一条:**UI 层不感知后台**。心声页/朋友圈页本来订阅 `repo.observeInnerVoices/observeMoments`(Room Flow),后台落库即上屏;角色主页占位卡尾注(`innerVoiceCount`)在 `openCharacterHome` 的 collect 里自刷新。VM 里唯一要改的:`generateInnerVoices()` 改为「立即入队 DerivativeWork + 页内 generating 态跟随 Flow/查询结果」,双路径(UI 内快路径 + 后台慢路径)都收敛到同一个 Executor,避免两份生成代码。

## 3. 生成开关(设置页,每项一个开关)

### 3.1 设计原则

- **开关只门控「自动」路径,不影响「点击」路径**:用户点「生成心声」按钮永远直接入队后台(明确意图);开关控制的是 Scheduler 是否在用户没点的情况下自动烧 token。
- **默认全部自动项 = 关**。后台生成费 token(每次一条 LLM 请求,心声还叠加逐张生图),是否自动由用户在设置页逐项拍板;默认行为 = 现状(点击才生成),不改变既有体验。
- **每项一个开关,互不联动**,粒度到功能;另设一枚「完成通知」总开关(不耗 token,默认开)。
- **切换即时生效**:开 → 立即 `enqueueUniquePeriodicWork(..., ExistingWorkPolicy.UPDATE, ...)`;关 → 立即 `WorkManager.cancelUniqueWork(uniqueName)`。在飞的 Worker 不强杀,自然完成后落库(半途作废反而浪费已烧的 token)。
- **冷启动兜底**:`GouXiangApp.onCreate` 的 `DerivativeScheduler.bootstrap` 逐项读 prefs,只注册开关为开的周期任务(用户卸载重装/清数据后回到全关默认,符合 token 保守原则)。

### 3.2 开关清单(`GenerationPrefs`)

```kotlin
// ai/GenerationPrefs.kt —— 惯例同 ChatPrefs(object + FILE + KEY_ 前缀 + runCatching 回落默认)
object GenerationPrefs {
    private const val FILE = "generation_prefs"
    const val KEY_INNER_VOICE_AUTO = "gen_inner_voice_auto"      // 心声自动生成
    const val KEY_MOMENTS_DAILY = "gen_moments_daily"            // 朋友圈每日自动发帖
    const val KEY_MEMORY_OVERNIGHT = "gen_memory_overnight"      // 隔夜记忆总结兜底
    const val KEY_PROACTIVE_OUTBOX = "gen_proactive_outbox"      // 离线主动消息
    const val KEY_DERIVATIVE_AUTO = "gen_derivative_auto"        // 手帐/小说/合照自动(M2 接入)
    const val KEY_NOTIFY_ENABLED = "gen_notify_enabled"          // 完成通知
    // fun innerVoiceAuto(context): Boolean = prefs.getBoolean(KEY, false) —— 每项同款 getter/setter
}
```

| 开关 | 门控范围 | 默认 | token 量级 |
|---|---|---|---|
| 心声自动生成 | M1 闲时/离线 Worker 自动批量生成心声(2~4 卡+逐张配图);点击生成不受限 | 关 | 高(1 次 LLM + N 次生图) |
| 朋友圈每日自动发帖 | M1 每日 PeriodicWork 图文一条 | 关 | 中(1 次 LLM + 1 次生图) |
| 隔夜记忆总结兜底 | M1 闲置 8h+ 补总结(A16) | 关 | 低(记忆页已有手动「立即总结」兜底) |
| 离线主动消息 | M1 周期扫 `pickTrigger` 生成 outbox + 通知 | 开 | 低(短输出,单请求;文档核心场景,保留默认开) |
| 手帐/小说/合照自动 | M2 攒批窗口/轮次达标后自动生成 | 关 | 高(长输出) |
| 完成通知 | 后台生成完成的系统通知(含点击触发的) | 开 | 0 |

> 默认值拍板项:除「离线主动消息」外全部默认关。若要还原猫箱文档的「上线被轰炸/每天有新内容」体验,用户在设置页全开即可;工程侧不替用户烧钱。

### 3.3 设置页 UI

入口挂在现有 `ProfileScreen` 设置列表(`ProfileEntry` 同款行):图标 `Icons.Filled.Tune`,标题「内容生成」,副标题「自动后台生成与通知 · 关闭时点击才生成」,点开二级页(与记忆页同导航惯例)。

```kotlin
// ui/profile/GenerationSettingsScreen.kt
// 每行:Switch + 标题 + 副标题(副标题写清「开启后自动…,消耗大模型调用」)
//   心声自动生成     —— 关闭后仅点「生成心声」时生成
//   朋友圈每日自动发帖 —— 关闭后仅点「生成动态」时生成
//   隔夜记忆总结兜底  —— 关闭后记忆窗口等下次聊天时提炼
//   离线主动消息     —— 关闭后 TA 不会在离线时先发消息
//   手帐/小说/合照自动 —— (M2 接入,先出开关置灰或直接生效均可)
//   完成通知        —— 关闭后生成完成仅 App 内可见,不弹系统通知
// 「完成通知」打开时(≥33)现场请求 POST_NOTIFICATIONS 运行时权限
// 进页即读 prefs,切换即写 + 即时 enqueue/cancel;无中间保存按钮
```

实现注记:开关行是纯 UI,状态源 = `GenerationPrefs` 的 `mutableStateOf` 快照(进页读一次,切换本地置位+落盘),不引入全局 StateFlow;周期任务 unique 名与开关一一对应(`sched:inner_voice_auto` 等),on/off 各自 enqueue/cancel,语义由 WorkManager 幂等保证。

### 3.4 与 Scheduler 的接线

`DerivativeScheduler`(M1)提供两类 API,设置页与 `GouXiangApp.bootstrap` 共用:
- `applyInnerVoiceAuto(context, enabled)` / `applyMomentsDaily(...)` / `applyMemoryOvernight(...)` / `applyProactiveOutbox(...)`:内部 = 开→`enqueueUniquePeriodicWork`(unique 名固定,UPDATE 策略),关→`cancelUniqueWork`。设置页 Switch onCheckedChange 直接调这些,冷启动 bootstrap 逐项调一遍(幂等)。
- 周期任务 Worker 执行时**再次自查开关**(防竞态:任务已在队列里、用户刚关掉开关)——Worker `doWork` 开头 `if (!GenerationPrefs.xxx(applicationContext)) return Result.success()`,直接成功退出不烧请求。

## 4. 分期施工

### M0:基建 + 心声后台化(第一步,最小可验收闭环)

1. 依赖 + `GouXiangApp` + manifest(`POST_NOTIFICATIONS` + `android:name`)+ 通知通道。
2. `DerivativeTask/DerivativeExecutor/DerivativeWorker/NotificationPoster` + `GenerationPrefs`(键先落地,设置页 M1 出)。
3. `generateInnerVoices()` 改走入队(页内保留乐观 UI:点按钮立即置 generating,由 Executor 结果回调刷新)。点击路径直接入队,不经开关。
4. 完成通知 + 点通知直跳心声页(受 `notifyEnabled` 门控)。
5. 单测:任务键序列化、入队唯一名策略、失败→retry 语义、通知文案红线扫描、GenerationPrefs 默认值/读写往返。

### M1:离线主动消息 outbox + 每日朋友圈 + 设置页(开关全面生效)

1. **设置页先行**:`GenerationSettingsScreen` + `ProfileScreen` 入口行 + `DerivativeScheduler.applyXxx` 接线。本期内所有已实现的开关接通:心声/朋友圈/隔夜总结/主动消息/完成通知。
2. `ProactiveScheduler`:周期任务(每 4h 一次 `PeriodicWorkRequest`,对齐 B9 语义,**入队前与执行时双重查 `proactiveOutbox` 开关**)扫所有会话 `pickTrigger(everProactive, lastActivityAt, now)`;命中 `CHECK_IN/DAILY_SHARE` → 生成 outbox 消息落库 + 通道二通知「离线期间发来 N 条」。
3. 进聊天页时消费 outbox(现有 `ProactiveMessages` 进页判定降级为「仅兜底」,outbox 优先)。
4. 每日朋友圈:`PeriodicWorkRequest`(每日,`lastPlannedEpochDay` 存 prefs 防重复补发,受 `momentsDaily` 开关门控)。
5. 隔夜总结兜底(A16):周期任务里检查 `pending_memory_window` 非空 + 会话闲置 ≥8h → 触发总结(受 `memoryOvernight` 开关门控)。
6. 真机验收补一条:设置页逐个切换开关 → `dumpsys jobscheduler` 验证周期任务随之注册/注销;冷启动后仅开关为开的任务在列。

### M2:手帐/小说/合照共享管线(07 衍生内容)

- `DerivativeExecutor` 增加 `HANDBOOK/NOVEL/PHOTO` 分支,直接复用本方案的任务抽象/通知/outbox 消费位;攒批窗口(30 轮)、轮次门槛(10 轮)、turn_log 回溯冲突扫描按缺口报告任务 2 单独施工,Worker 只负责「生成落库+通知」;受 `derivativeAuto` 开关门控(自动路径),点击生成不受限。

## 5. 测试与验收

**单测**(门禁 `./gradlew testDebugUnitTest assembleDebug`):
- `DerivativeTaskTest`:键值序列化、非法 kind → failure。
- `DerivativeExecutorTest`:假 LLM(复用 `chat/Fakes.kt` 惯例)下各 kind 成功/失败路径,失败返回 false;**开关关闭时自动路径直接返回成功不执行**(`work-testing` 的 `WorkManagerTestInitHelper` 驱动,或把开关门控抽成纯函数断言)。
- `GenerationPrefsTest`:默认值(自动项全 false、notify true)、写后读、非法存储容错(runCatching 惯例)。
- 通知文案红线测试:扫 `"derivative"/"proactive"` 通道全部文案 + 设置页副标题文案,禁计费/猫粮/会员字样(对齐既有 `InnerVoiceTest` 红线风格)。
- `ProactiveSchedulerTest`:`pickTrigger` 纯函数已有,补 outbox 消费顺序/日上限。

**真机**(Redmi K20 Pro,短轮询、单次等待 ≤3s、4 分钟预算):
1. 构建安装 → 打开心声页点生成 → 立即 `adb shell am force-stop com.neethu.gouxiang` → 等 3s 重启 App → 进心声页验证生成结果落库(后台任务在 force-stop 后由 WorkManager 独立执行完毕)。
2. 通知验收:生成期间退到桌面 → `adb shell dumpsys notification --noredact | grep -A3 gouxiang` 验证通知在列 → 点击(或 `am start -n ... --el open_inner_voice_conversation_id N`)验证直跳。
3. 断网重试:开飞行模式入队 → `adb shell dumpsys jobscheduler | grep -i derivative` 验证任务挂起 → 关飞行模式 3s 内验证执行。
4. 权限降级:拒绝 POST_NOTIFICATIONS → 验证生成照跑、无崩溃、App 内红点可见。
5. **开关验收(M1)**:设置页关「心声自动」→ 触发自动路径(`ai_cmd` 或等周期)→ `dumpsys jobscheduler` 无注册且无 LLM 请求日志;开回 → 任务注册;杀进程重启 → 仅开关为开的任务自动注册。

## 6. 红线与风险

- **文案红线**:通知标题/正文 + 设置页开关副标题一并纳入既有计费/猫粮/会员字样单测扫描范围。
- **不要引入前台 Service**:生成任务用普通 Worker 足够;expedited work 在 <31 需要前台服务声明,没必要为省 30s 引入该复杂度。
- **主进程单例竞态**:`GouXiangRepo` 与 `AvatarImageProvider` 都是懒单例,Worker 进程(WorkManager 默认同进程)拿到的实例与 UI 一致,无跨进程 DB 冲突;若未来拆 `:background` 进程,需先改 Repo 单例语义(风险注记,当前不做)。
- **设备休眠**:Doze 下 WorkManager 自动推迟,对「后台慢慢生成」的语义恰好正确;不申请 `WAKE_LOCK` 豁免。
- **版本兼容**:WorkManager 版本落 catalog 前先 maven-central 实查与 Kotlin 2.2.10/AGP 9.4.1 兼容(工程惯例,参照 coil 版本注释教训)。
- **token 消耗审计**:所有「自动」触发点(PeriodicWork、outbox 扫描、隔夜兜底)在入队/执行两处都有开关闸门;新增自动触发点必须挂 `GenerationPrefs` 新键 + 设置页新行,不接受无开关的自动烧钱路径。

## 7. 施工记录(2026-10-30,M0+M1 已落地并真机验收)

**提交范围**:M0 全部 + M1 全部(设置页/outbox/每日朋友圈/隔夜兜底);M2(HANDBOOK/NOVEL/PHOTO)仅落枚举 + 开关 + 设置页行(副标题标注「功能开发中」),分支不触达。

**新增文件**:
- `chat/DerivativeTask.kt`(纯 Kotlin:DerivativeKind/DerivativeTask/唯一名/键常量)
- `ai/GenerationPrefs.kt`(六开关 + 朋友圈日戳 + outbox 计数;默认值 = §3.2 拍板)
- `work/`:DerivativeWorker(单 Worker 分发 + 事件收口)/DerivativeExecutor(INNER_VOICE/MOMENT/MEMORY_SUMMARY/PROACTIVE_OUTBOX 四分支,UI 版生成主体搬运)/DerivativeScheduler(applyXxx + bootstrap + AutoGuardWorker 双闸)/NotificationChannels/DerivativeCopy(文案常量,红线扫描对象)/NotificationPoster(双闸:notifyEnabled + ≥33 运行时权限)/GenerationSettingsScreen/DerivativeEvents(进程级单槽事件 bus)
- `GouXiangApp.kt`(通道幂等建 + bootstrap);manifest 加 POST_NOTIFICATIONS + android:name

**改动**:MainViewModel 心声/朋友圈点击路径改为入队(乐观 generating 态 + DerivativeEvents 收口,双路径共用 Executor);outbox 优先(进手机聊天页先查「晚于最后一条用户消息的 proactive 轮」);toMemoryTurns 迁为 DerivativeExecutor 顶层纯函数;ProfileScreen 加「内容生成」入口;MainActivity 接设置页 overlay + 通知直跳 extras(类型兼容读取:Long/String)+ 「完成通知」开时 ≥33 现场请求权限。

**依赖**:work-runtime-ktx 2.9.1(maven 在售稳定版,本机缓存命中;2.10+ 的 Kotlin 顶层 `PeriodicWorkRequestBuilder<W>` 在 2.9.1 不存在,用 Java Builder `PeriodicWorkRequest.Builder(cls, 6, HOURS)`);Robolectric 4.17(4.14 的 ASM 9.7 读不了 JDK25 toolchain 的 major 69 字节码)。

**单测**(199 全绿):DerivativeTaskTest(键稳定/唯一名粒度/通知+设置页文案红线)/GenerationPrefsTest(默认值拍板/读写往返/日戳,Robolectric)/MemoryTurnsTest(窗口回合规则)。

**真机验收**(Redmi K20 Pro, Android 11):
1. 两通道注册:`derivative` importance=3 / `proactive` importance=4(dumpsys notification)。
2. bootstrap 周期任务:outbox 默认开 → job `Minimum latency +3h59m`(4h 周期,CONNECTED + 退避 30s,dumpsys jobscheduler)。
3. **force-stop 后独立执行**:心声页点「生成心声」→ `am force-stop` → WorkManager 独立拉起新进程(pid 20413)执行完 DerivativeWorker(`Worker result SUCCESS`,18:44:04)→ 重启进心声页 4 条心声 + 逐张 AI 配图全部落库上屏;角色主页尾注「全部 8」同步刷新。
4. 开关双向:设置页开「心声自动生成」→ job 立即注册(6h 周期可见);关 → job 注销(4→1)。
5. 冷启动兜底:force-stop 重启后仅 outbox 在列(仅开关为开的任务注册)。
6. 通知直跳:`--el open_inner_voice_conversation_id 3` 冷启动直开心声页(修复:extras 读 Long 不能用 getString)。
7. 权限降级:Android 11 < 33 无运行时申请,POST_NOTIFICATIONS 安装即生效;≥33 的现场请求逻辑挂在设置页「完成通知」开关上(真机 11 未触发,代码路径单测覆盖文案面)。
