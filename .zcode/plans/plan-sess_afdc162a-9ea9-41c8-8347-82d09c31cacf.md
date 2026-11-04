# 通话页全双工改造：去按住说话 + VAD 断句/打断/主动接话

## Context

通话页（`VoiceCallScreen` + `VoiceCallEngine`）目前是半双工回合制：按住说话→松手整段 WAV→硅基 ASR→LLM→火山 TTS。需求改为**全双工**：

1. **去掉按住说话条**，麦克风全程常开
2. **说话即打断 TTS**（barge-in）——难点是麦克风会听到手机外放的 TTS 造成自触发；采用**系统 AEC**（`VOICE_COMMUNICATION` 音源 + `AcousticEchoCanceler`，Android 内置 WebRTC 同源回声消除）+ **WebRTC VAD 库** + 打断去抖
3. **断句回复**：说完话安静 N1 秒（滑块 0-10s，默认 2s）→ 送 ASR 回复
4. **主动接话**：一轮结束后用户持续沉默 N2 秒（滑块 0-10s，默认 5s）→ 人物主动找话题；连续主动上限 3 次防独白，用户说话即重置
5. 两个滑块放「我的 → AI 服务配置」新「语音通话」分区，持久化 `ai_config_prefs`

关键现成设施（复用，不重造）：`ChatOrchestrator.respondProactively()`（主动消息回合，sceneLine 不落库不进上下文）、`WavRecorder.pcmToWav()`（public companion 函数）、引擎现有 `interrupt()/runTurn` 接力语义、`AiConfigStore` 每回合现取配置模式。

## 改动清单

### 1. 依赖（2 个文件）
- `settings.gradle.kts`：`dependencyResolutionManagement.repositories` 加 `maven("https://jitpack.io")`
- `app/build.gradle.kts`：加 `implementation("com.github.gkonovalov.android-vad:webrtc:2.0.10")`（158KB 纯 JVM，minSdk 21 < 项目 29，逐帧 `isSpeech(ShortArray)` API）

### 2. 新文件 `ai/speech/CallMicStreamer.kt`
全双工麦克风流（替代通话页里的 `WavRecorder` 批量录音，聊天页两处 `WavRecorder` 不动）：
- `AudioRecord`：`SOURCE_VOICE_COMMUNICATION`，16k/单声道/16-bit；session 上 `AcousticEchoCanceler`+`NoiseSuppressor`（create 失败静默降级）
- 20ms 帧（320 samples）读循环；`VadWebRTC(SAMPLE_RATE_16K, FRAME_SIZE_320, Mode.VERY_AGGRESSIVE)` 逐帧分类
- 纯逻辑内部类 `UtteranceEndpointer`（不碰 Android，可 JVM 单测）：
  - 300ms pre-roll 环形缓冲（首字不丢）
  - 连续 3 帧语音 → 收集开始（含 pre-roll）；静音累计 ≥ N1（0s 时用内置 400ms hangover 下限）→ 断句完成，回吐 PCM 窗口
  - SPEAKING 态 barge-in 去抖：滑窗多数帧为语音（约 200ms）才判「用户真在说」，防 AEC 残余
  - 单句上限 60s 强制断句
- 事件经 Channel 投递给引擎协程：`onSpeechStart / onBargeIn / onUtteranceComplete(pcm) / onLevel`

### 3. `VoiceCallEngine.kt` 重构
- 删 `holdStart/holdRelease/WavRecorder`，换 `CallMicStreamer`；`start()` 启流（幂等）、`close()` 停流
- 事件驱动状态机（Phase 枚举不变）：
  - `onUtteranceComplete` → 既有 `runTurn`（ASR→THINKING→SPEAKING）；回合进行中新 utterance 进单槽 pending，回合结束后接力（沿用 join 语义）
  - SPEAKING 中 `onBargeIn` → 复用 `interrupt()`（`speaker.stop()` 回聆听），收集不中断
  - 播完/打断回 LISTENING → 武装 idle 计时 N2；fire → `proactiveRound()`；用户说话/回合启动即取消；连续主动 ≥3 停止直到用户再开口
  - N1/N2 每次武装计时现取 `aiConfigSource()`
- `UiState`：`holdActive` → `userSpeaking: Boolean`；新增独立 `micLevel: StateFlow<Float>`（供波形，避免 UiState 高频重组）
- tipsText 适配：SPEAKING→「说话即可打断」
- 新构造参数 `proactiveRound: suspend () -> List<Bubble>`（默认空实现）

### 4. `ai/config/AiConfigStore.kt`
- `AiConfig` 加 `callEndSilenceS: Int = 2`、`callIdleSpeakS: Int = 5`
- 键 `ai_call_end_silence_s` / `ai_call_idle_speak_s`，load/save 加读写（clamp 0..10）

### 5. `ui/aiconfig/AiConfigActivity.kt`
ASR 分区后新增「语音通话」`CollapsibleSection`，新增 `SliderRow` composable（M3 `Slider`，项目首个 Slider）：
- 「断句停顿」0-10 步进 1，副标题「说完话等几秒再回复，0=说完立刻」
- 「静默主动接话」0-10 步进 1，副标题「沉默几秒后人物主动找话题」
- 值展示"N 秒"，`onValueChangeFinished` 按页面现有 save 模式落库

### 6. `ui/MainViewModel.kt`（voiceCallEngine 工厂）
- 加 `proactiveRound` 接线：查 conv/character → `orchestrator.respondProactively(convId, character.id, sceneLine="（语音通话中对方一直沉默，主动找话题接一句，简短口语）", burst=短连发, phoneStyle=true)` → 与 `runChatRound` 同款的 `_chatUi` 回写 + `refreshChatBubbles(convId)` → 返回 bubbles（`Failed`/`degraded` 返回空列表，引擎不播）

### 7. `VoiceCallScreen.kt`
- 删按住说话条（234-264 行）及其 press 手势
- 进页 `if (requestMicrophonePermission()) engine.start()`，拒绝则 notice 提示
- `userSpeaking` 时提示条「聆听中…」+ 小型实时波形（60ms 采样 micLevel，照 HoldWaveform 思路内联）
- 保留：整页点击打断（兜底）、挂断键、DebugInput（gate 改 `phase == LISTENING`）

### 8. JVM 单测
- `UtteranceEndpointer` 纯逻辑：合成帧序列验证 断句窗口/barge-in 去抖/pre-roll 完整/短噪声不触发/60s 强断
- AiConfig 新键读写 round-trip

## 验证

1. `./gradlew :app:testDebugUnitTest :app:assembleDebug`
2. 真机 adb 冒烟（遵守快速真机测试纪律：短轮询、单次等待 ≤3s、4 分钟预算）：
   - 进通话页不按任何键直接说话 → 约 2s 后人物回复
   - TTS 朗读中开口 → 立即停播开始听（barge-in）
   - 一轮结束后沉默 → 约 5s 人物主动接话；连续沉默 3 次后不再独白
   - 外放最大音量下人物朗读不自我打断（AEC+去抖）
   - 配置页滑块改 N1/N2 → 回通话页立即生效
3. 按 `TYPE/ABSTRACT` 规范提交 commit

## 风险与对策
- MIUI 部分机型 `VOICE_COMMUNICATION` 采集音量偏低或 AEC 质量差 → barge-in 去抖兜底 + VERY_AGGRESSIVE 模式降误报；真机冒烟重点验此项
- ASR 为整段上传非流式，断句后 1-2s 识别延迟属预期
- 主动接话完全复用 F4 主动消息链路，无新 LLM 依赖