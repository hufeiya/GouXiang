# GouXiang 界面按钮失效/未开发排查报告

> 日期：2026-10-30 · 设备：Redmi K20 Pro（`2c3769db`）· APK：debug 变体
> 方法：adb 模拟点击（`input tap`/`input swipe` 长按）+ `ai_cmd` 调试接口（`state`/`msgs`/`ai_config`/`contexts`）+ `uiautomator dump` 定位 + 源码交叉验证
> 测试前已将 **LLM 与 TTS 全量切换到火山引擎**（切换过程见 §0）

---

## 0. 前置：LLM/TTS 切火山引擎（已完成并验证）

| 项 | 值 |
|---|---|
| provider | `VOLCANO`（ai_provider_prefs 写入 `ai_config_prefs.xml`） |
| LLM | `doubao-seed-2-0-mini-260428`（视觉优先默认，`resolveLlmModel` 回落生效） |
| TTS | `seed-tts-2.0` + 音色 `zh_female_vv_uranus_bigtts` |
| LLM Key | 火山方舟 ark-…（已配） |
| TTS Key | 豆包语音 020b…（已配，与方舟互不通用） |

**链路实测**：
- LLM：`send_chat` → 3 连发回复正常落泡（火山 ark 流式 SSE 通）。
- TTS：F6 语音通话页「调试输入」发文本 → logcat `VoiceCall: volcano tts ok: 122020B @16000Hz`，音频正常播放。
- ai_config 快照：`provider=volcano llmModel=doubao-seed-2-0-mini-260428 configured=true`。

> ⚠️ 运维提醒：本次操作中 prefs 曾被误清空一次，已用 `.agents/skills/push-api-keys/` 官方脚本完整恢复 7 把 key 并回读校验。**注意 `run-as sh -c 'cat >'` 在该设备上静默失败（Permission denied / 0 字节）**，写 prefs 必须走「push 到 /data/local/tmp → `run-as cp`」或直接用 push 脚本。

---

## 1. 结论速览

全 App 共扫描 6 个页面层级、**37 个可点击/展示性按钮**。结果分布：

| 状态 | 数量 | 说明 |
|---|---|---|
| ✅ 正常工作 | 18 | 含配置页、对话模式/风格、记忆页、长按菜单、转账卡、语音通话、说明页等 |
| 🎭 纯装饰（有图标无逻辑，点击无反应） | 12 | 大多是对照猫箱视觉稿补的静态壳 |
| 🚧 占位页（整页未开发） | 2 | 底栏「发现」「创作AI+」 |
| ⚠️ 功能降级（有 UI 无完整行为） | 5 | 见 §4 详表 |

---

## 2. 逐页明细

### 2.1 首页推荐 feed（HomeFeedScreen，AI剧/推荐）

| 按钮 | 位置 | 实测 | 判定 |
|---|---|---|---|
| 顶部 ❤️（会员盾） | [77,172] | 无反应 | 🎭 纯装饰（`HomeFeedScreen.kt:91`，无 clickable；计费项有意豁免） |
| 「AI剧」tab | [391,170] | 无反应，固定高亮「推荐」 | 🎭 装饰，tab 切换未开发 |
| 🔍 搜索 | [898,172] | 无反应 | 🎭 装饰（`HomeFeedScreen.kt:127`） |
| 🔊 音量 | [985,172] | 无反应 | 🎭 装饰（`HomeFeedScreen.kt:134`） |
| ⚡ 闪电圆钮 | [994,1855] | 无反应 | 🎭 装饰 |
| ❤️361 点赞 | [565,2020] | 无反应（数字写死） | 🎭 装饰（`ActionItem` 无 onClick，`HomeFeedScreen.kt:273-282`） |
| 分享 | [645,2020] | 无反应 | 🎭 装饰 |
| 💬28 评论 | [775,2020] | 无反应 | 🎭 装饰 |
| 历史 | [1000,2020] | 无反应 | 🎭 **feed 页专属装饰**（聊天页同名按钮是活的，见 2.2） |
| 名字「苏馨玥 >」 | [150,2020] | 无反应 | ⚠️ **有意但未接线**：im 聊天页同名入口是 `openCharacterHome`，feed 页没接 |
| 消息气泡 | [400,1800] | 无反应 | ⚠️ 对照猫箱，气泡应可点进聊天，未接 |
| feed 竖滑切卡 | — | 正常（唯一活交互） | ✅ |

### 2.2 im 聊天页（ChatMainScreen，会话 1）

| 按钮 | 实测 | 判定 |
|---|---|---|
| 返回 | 正常回消息列表 | ✅ |
| 记忆 🧠 | 正常打开记忆页 | ✅ |
| 会员/搜索/音量（顶部同款三图标） | 无反应 | 🎭 纯装饰（`ChatMainScreen.kt:233/253/260`，与 feed 页同源复制） |
| 按住说话 | **点击仅切到文字输入态**，长按无录音条 | ⚠️ **语音输入未开发**（源码注释自认「占位(语音输入为后续功能)」`ChatMainScreen.kt:546`） |
| 文字输入 ⇄ 语音图标 | 切换正常 | ✅（但语音态是空壳） |
| 消息气泡长按 | **无菜单** | ⚠️ **im 页长按复制/回溯未施工**——第一批修复项「长按复制+回溯提示」只落在 phone 页（`PhoneChatScreen.kt:331,428` 有 `combinedClickable`），im 页 `ChatMainScreen.kt` 全文无 `combinedClickable` |
| 底部互动列 ❤️361/5/28/历史 | 361/5/28 无反应 🎭；**「历史」点击正常打开聊天历史页**（`MainActivity.kt:215` wired） | ✅/🎭 混合 |
| ⚡ 闪电 | 无反应 | 🎭 |

### 2.3 phone 聊天页（PhoneChatScreen，会话 2，iMessage 皮肤）

| 按钮 | 实测 | 判定 |
|---|---|---|
| ✕ 关闭 / 记忆 🧠 / … 三点 | 全部正常（三点=小手机使用说明页） | ✅ |
| 「+」扩展面板 | 正常弹出：发送图片/音乐/帖子/位置/转账/代付 | ✅ |
| 转账 → 卡片编辑器 → 发送 | 编辑器 UI 正常；`input text` 无法注入 IME 字段（adb 限制非功能问题），改用 `ai_cmd send_card` 端到端验证：卡片落库 + LLM 正常回应 | ✅（卡内金额空值可直接点发送，缺非空校验，见 §4-5） |
| 长按气泡 → 复制/重新生成/回溯 | 菜单正常弹出；「重新生成」实测 `sending=true→false`，删旧泡后新回合（326-328）生成成功 | ✅ |
| 😊 表情 / 🎤 麦克风 | 无反应（`PhoneChatScreen.kt:646-649` 纯 `Text`，无 clickable） | 🎭 装饰（对照稿三件套只做了「+」） |
| 按住说话 | phone 页**没有**「按住说话」，是常驻文字输入框 | ✅（形态不同，非缺陷） |
| 语音条「•)N″」点击播放 | 页内无现成语音条可点；机制源码在（`VoicePlayer`/TTS 占位），无法实测 | ❓ 未覆盖 |

### 2.4 消息列表页（MessagesScreen）

| 按钮 | 实测 | 判定 |
|---|---|---|
| 「赞过」tab | 无反应，固定高亮「聊过」 | 🎭 装饰（`MessagesScreen.kt:146-157` 静态 Text） |
| 相机快门圆环 / 🔍 搜索 | 无反应 | 🎭 装饰（注释「占位」） |
| 互动消息行 | 无反应 | 🎭 装饰（`MessagesScreen.kt:191` 注释「占位:互动消息页后续功能」） |
| 🚕 系统通知行 | 无反应 | 🎭 装饰（`FixedRow` 无 onClick 参数） |
| 会话行 | 正常进对应聊天（im/phone 分流正确） | ✅ |

### 2.5 角色主页（CharacterHomeScreen）

| 按钮 | 实测 | 判定 |
|---|---|---|
| 对话模式宫格（心动/细腻/长对话/深想） | 正常，选择面板可用 | ✅ |
| 对话风格宫格（长度三档+完成） | 正常 | ✅ |
| Ta 的记忆宫格 | 正常（面板叠加层） | ✅ |
| Ta 的心声条「全部 8 ›」 | 点击后回退到聊天页（入口在页面底部，实测被关闭按钮区域截住，行为异常） | ⚠️ 需复测：心声入口可能点击区域错位 |
| 「+ 关注」胶囊 | 点击无任何变化 | 🎭 **静态装饰**（源码注释明说「本地版静态」`CharacterHomeScreen.kt:239`） |
| 💤 状态条「在等你来聊天 ›」 | 无反应 | 🎭（状态系统 08 号文档整块未开发） |

### 2.6 底栏 + 我的页

| 按钮 | 实测 | 判定 |
|---|---|---|
| 底栏 首页/消息/我的 | 正常 | ✅ |
| 底栏 **发现** | 「页面建设中,敬请期待」 | 🚧 占位页（`PlaceholderPage`） |
| 底栏 **创作AI+** | 「页面建设中,敬请期待」 | 🚧 占位页（缺口报告：01 创作域 14 条全部 ❌ 未开工） |
| 我的 → AI 服务配置 | 全链路正常（服务商/模型/Key/TTS 展开） | ✅ |
| 首页 tab 顶部「通话/联系人/营业厅」 | 均为系统应用壳跳转（拨号/联系人/小米营业厅真 app），非 GouXiang 自身功能 | ✅（形态如此） |

---

## 3. 「失效按钮」完整清单（12 个纯装饰 + 2 个占位页）

按页面归组（均为**有 UI 无逻辑**，用户可点但零反馈）：

1. **首页 feed（9 个）**：会员 ❤️、AI剧 tab、搜索 🔍、音量 🔊、⚡、点赞、分享、评论、历史、名字入口、消息气泡（其中「历史」和「名字」对照猫箱应该是活的）
2. **im 聊天页（4 个）**：会员、搜索、音量、⚡
3. **消息列表页（4 个）**：赞过 tab、相机、搜索、互动消息、系统通知
4. **角色主页（2 个）**：关注、💤 状态条
5. **phone 聊天页（2 个）**：😊、🎤
6. **底栏（2 个整页）**：发现、创作AI+

---

## 4. 五个「功能降级」问题（比装饰按钮更值得修）

| # | 问题 | 证据 | 影响 |
|---|---|---|---|
| 1 | **im 聊天页长按无菜单**（复制/回溯/重新生成都没有） | `ChatMainScreen.kt` 无 `combinedClickable`；phone 页已实现（`PhoneChatScreen.kt:331`）。缺口报告 G1 只点了 phone 页缺「复制」，**im 页整块缺失没被报告覆盖** | im 是主对话场景，回溯/复制是高频操作 |
| 2 | **「按住说话」两端都是空壳** | im 页点击只切文字态（`ChatMainScreen.kt:546` 注释自认占位）；录音→ASR→发送的链路不存在（ASR 适配器已有，仅服务 F6 语音通话） | 语音输入是猫箱核心交互之一 |
| 3 | **feed 页「名字>」入口未接 `openCharacterHome`** | `HomeFeedScreen.kt` 名字行无 clickable；im 页同元素是活的（`MainActivity.kt:209`） | 用户从首页无法进入角色主页 |
| 4 | **feed 互动列全死 + 数字写死** | `ActionItem` 无 onClick 参数，361/5/28 是硬编码文案 | 对照稿里点赞/分享/历史应为活入口 |
| 5 | **转账卡金额可空发送** | 卡片编辑器「发送」按钮无金额非空校验（`CardComposer`），空金额卡能上屏（实测渲染「转账 ¥0」） | 数据质量；对照稿应有校验 |

另有一个低优先级疑点：角色主页「Ta 的心声」条目点击后疑似落入关闭区（见 §2.5），建议复测确认。

---

## 5. 落地方案

### 5.1 优先级 P0（第一批收尾，1~2 天）

**F1. im 页长按菜单对齐 phone 页**
- 改动：`ChatMainScreen.kt` 消息气泡 `clickable` → `combinedClickable(onLongClick = …)`；复用 phone 页 `LongPressDialog` 逻辑抽公共组件（复制 → `ClipboardManager`，回溯/重新生成 → 已有 `vm.rewindTo`/`vm.regenerateTurn`，均 VM 现成出口）。
- 验收：`ai_cmd rewind` / `retry` + 手工长按冒烟；补一条 Compose UI 单测锁住 im 页菜单三件套。

**F2. 转账卡金额非空校验**
- 改动：`CardComposer` 发送按钮 `enabled = amount.isNotBlank()`（转账/代付两型）；红色提示文案。
- 验收：单测 + 真机空值点击。

### 5.2 优先级 P1（对齐猫箱核心交互，2~4 天）

**F3. feed 页名字入口 + 历史按钮接线**
- `HomeFeedScreen` 增 `onOpenCharacterHome`/`onOpenHistory` 回调，`MainActivity.kt:173` 的 HomeFeed 分支传入 `vm.openCharacterHome(chatId)`/`vm.openHistory(chatId)`（与 im 页同款接线，代码 10 行内）。
- 点赞/分享/评论/⚡：按本地版红线（无计费/无社交后端），建议**显式声明裁剪**——在缺口报告补一条「有意不做」，同时把数字写死改为从会话真实互动数取值（如无则隐藏数字列），避免「看起来坏」。

**F4. 「按住说话」语音输入（复用 F6 管线）**
- 现成件：`VoiceCallEngine` 的录音 WAV 采集 + `OpenAiCompatibleAsrAdapter`（硅基 Qwen3-ASR）可直接抽出来。
- 方案：im/phone 输入条「按住说话」按下=录音（同 F6 的 WAV 格式与误触丢弃 `MIN_WAV_BYTES`），松开=ASR 转写填入草稿框（不直接发送，对齐猫箱「转写可改」）；ASR 失败 Toast 兜底。
- 工作量：录音复用 ~0.5 天，UI 态机 0.5 天，ASR 接线 0.5 天。

### 5.3 优先级 P2（排期项，跟随缺口报告既定顺序）

| 项 | 归属 |
|---|---|
| 「赞过」tab、互动消息页、系统通知点开 | 消息域扩展，缺口报告未列，建议声明裁剪或并入 09 心动模式排期 |
| 角色「关注」、💤 状态条点击 | 08 状态系统整块（报告建议顺序 06 剧场→08 状态），状态条点击自然落地 |
| 「发现」「创作AI+」占位页 | 01 创作域 T1-T6 任务序（整块功能，单独排期） |
| 😊/🎤（phone 输入条） | 😊 可并入 F4 的表情面板小项；🎤 并入 F4 |
| AI剧 tab / 音量 / 搜索（feed） | 声明裁剪（feed 单卡形态下无意义） |

### 5.4 顺手项

- ai_cmd 补 P1 清单里的 `set_provider`/`set_llm_model`（本次切火山是手写 prefs 完成的，补上后 provider 切换可一条命令验收，也避免再手碰 prefs）。
- push-api-keys 技能已验证可随时恢复 prefs，建议在 SKILL.md 加一句「切换 provider 需手写 `ai_provider`/`ai_llm_model`/`ai_tts_model` 键」的操作备忘。

---

## 6. 测试现场记录（复现用）

```bash
# 切火山(prefs 写入,务必 push→cp 两步)
adb push prefs.xml /data/local/tmp/ && adb shell run-as com.neethu.gouxiang \
  cp /data/local/tmp/prefs.xml /data/data/com.neethu.gouxiang/shared_prefs/ai_config_prefs.xml

# 验证配置与链路
adb shell am start -n com.neethu.gouxiang/.MainActivity --es ai_cmd ai_config
adb shell am start -n com.neethu.gouxiang/.MainActivity --es ai_cmd open_chat --es ai_arg 1
adb shell am start -n com.neethu.gouxiang/.MainActivity --es ai_cmd send_chat --es ai_arg "你好"
adb shell am start -n com.neethu.gouxiang/.MainActivity --es ai_cmd msgs 3
# TTS: 进 F6 通话页「调试输入」发文本,logcat 过滤 VoiceCall
adb logcat -d | grep -E "volcano tts|AIDebug"
```

当前设备保持火山配置未回滚。全程零崩溃（`FATAL` 计数 0）。

---

## 7. P0/P1 落地记录（2026-10-30 同日施工，全部真机验收）

报告 §5.1/§5.2 四项当日施工完成，构建/单测/真机冒烟全绿（`FATAL` 计数 0）。

### F1 im 页长按菜单（P0）✅

- 抽公共组件 `ui/chat/MessageActionsDialog.kt`（`MessageActionsDialog` + `RewindConfirmDialog` + `rewindSnippetShared`），phone 页原私有 `RewindDialog`/`rewindSnippet` 改为调用公共版（单测锁两页同源口径）。
- `ChatMainScreen.kt` 气泡 `combinedClickable(onLongClick)`（新增 `onRewind`/`onRegenerate` 参数），MainActivity 接 `vm.rewindToMessage`/`vm.regenerateTurn`。
- 真机验证：长按弹「消息操作」三件套（复制/回溯/重新生成）→ 回溯二次确认弹窗 → 确认后 msgs 显示会话清空、状态条回落「💤 在等你来聊天」。
- 设备端 Compose instrumented 测试在 MIUI（Redmi K20 Pro/Android 11）上 Espresso idle 挂起（连最小 `Text` 冒烟都卡），改用 JVM 单测锁组件同源 + adb 冒烟覆盖 UI 行为。

### F2 转账卡金额非空校验（P0）✅

四层同口径：
1. 协议层：`CardPayload.sendable`（转账/代付 `title.isNotBlank()`，其余类型不受限）——`CardSendableTest` 5 条单测。
2. UI 层：`CardComposer` 空金额显示红提示「请输入转账金额」+ 发送钮文案「请输入金额后发送」（转账/代付两型）。
3. VM 兜底：`sendCard` 拦 `!sendable`（UI 校验漏网的最后防线）。
4. ai_cmd：`send_card` 空金额显式 `FAIL: 转账 requires a non-blank amount`（不再静默 no-op 误判成功）。

真机验证：phone 页转账编辑器空金额 → 红提示 + 禁用文案；`input text 520` 后红提示消失恢复「发送」；`ai_cmd send_card 转账`（空金额）→ FAIL，`转账 520|备注` → OK 落库 kind=card。

### F3 feed 页名字入口 + 历史接线（P1）✅

- `HomeFeedScreen` 增 `onOpenCharacterHome`/`onOpenHistory` 回调，`MainActivity` 传 `vm.openCharacterHome`/`vm.openHistory`（feed 卡对应 im 会话，`conversationId > 0` 才响应）。
- **有意裁剪声明**（报告 §5.3，无计费/无社交后端红线）：feed 点赞/分享/评论/⚡ 不接行为；硬编码互动数列（361/5/28）整列移除，只保留「历史」活入口——避免「看起来坏」。

真机验证：feed 页点「苏馨玥 >」进角色主页（宫格齐全）；点「历史」开聊天历史页（日期分组正常）；uiautomator 确认「361」从 dump 消失。

### F4 按住说话·语音输入（P1）✅

复用 F6 管线（`WavRecorder` 16-bit PCM 16kHz WAV + 硅基 Qwen3-ASR，Key/模型解析与 `VoiceCallEngine` 同口径）：

- VM 侧新增 `voiceInputStart/Release/Cancel` 状态机：按下开录 → 松手 ASR 转写 → `voiceInputTranscribed` 事件；**转写结果填草稿框不直接发送**（对齐猫箱「转写可改」），失败/没听清 Toast 兜底；`closeChat` 顺带 `cancelVoiceInput`（防录音器悬挂）。
- im 页「按住说话」从「点击切文字态」改为真录音（`pointerInput` 按压手势，按住高亮「松开 转写」，滑出取消）；phone 页 🎤 从纯装饰同款接线。
- 麦克风权限懒申请（首次按下弹系统弹窗，不打扰进页）。

真机验证：首按弹 MIUI 录音权限 → 授权后再按住 1.2s 松手 → **环境音被 ASR 转写为「嗯。」自动填入草稿框并切文字态**（对话列表无新泡 = 未直接发送，符合设计）；音为空/失败走「没听清」Toast。

### 测试资产

- `app/src/test/.../protocol/CardSendableTest.kt`：F2 金额校验 5 条。
- `app/src/test/.../ui/MessageActionsDialogTest.kt`：F1 两页同源口径 2 条。
- `testDebugUnitTest` 全绿；MIUI instrumented 环境问题记录在案（不阻塞）。
