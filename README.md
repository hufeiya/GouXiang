# 狗箱 GouXiang

一款 Android AI 角色陪伴应用。在人物大厅寻找感兴趣的角色，通过文字或语音聊天，也让对话慢慢变成记忆、动态和故事。

![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)

## 界面预览

<!-- 上传截图后，可将下方占位文字替换为 <img src="图片地址" width="240" alt="界面名称" />。 -->

| 人物大厅 | 陪伴聊天 | 语音通话 |
| :---: | :---: | :---: |
| 图片待上传 | 图片待上传 | 图片待上传 |
| 发现角色，查看介绍与开场白 | 用文字或语音开启一段对话 | 听见角色的声音 |

| 朋友圈 | 角色记忆 | 对话故事 |
| :---: | :---: | :---: |
| 图片待上传 | 图片待上传 | 图片待上传 |
| 分享动态，发现角色的日常 | 留下对话中的重要片段 | 将聊天延伸成手账、小说等内容 |

## 功能一览

- **人物大厅**：搜索和浏览预置人物，查看角色介绍与开场白，选择喜欢的人物开始聊天；也可以创建和编辑自己的角色。
- **陪伴聊天**：支持文字、语音输入、回复朗读和手机聊天界面；可以查看历史、重新生成回复或回溯对话。
- **语音通话**：与角色进行语音交流，支持角色音色选择。语音识别和合成能力取决于所选服务及设备配置。
- **记忆与状态**：从对话中记录角色的记忆，查看角色状态，让后续交流延续已有的故事。
- **角色日常**：在朋友圈分享动态、发现角色动态，也可以查看角色心声、番外等内容。
- **对话衍生**：随对话发现手账和小说等内容，或生成图片并设为聊天背景。合照使用二维文生图，不保证人物形象一致。
- **多种 AI 服务**：支持硅基流动、火山引擎、OpenRouter、DeepSeek、阿里云百炼、Grok，以及自定义 OpenAI 兼容大模型端点。不同服务商的模型和语音能力有所不同。

## 快速开始

### 环境要求

- Android Studio 和 Android SDK 37
- Android 10（API 29）及以上的设备或模拟器
- 项目使用 Gradle Wrapper 9.6.0，Gradle 守护进程配置为 JDK 25；首次构建需要联网下载工具链和依赖

### 构建与安装

用 Android Studio 打开项目根目录，等待 Gradle 同步完成后运行 `app`；也可以在根目录执行：

```bash
./gradlew assembleDebug
```

构建成功后，调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。连接设备后也可以通过 `./gradlew installDebug` 安装。

### 配置 AI 服务

1. 启动应用，按照首次引导配置服务；也可稍后进入「我的 → AI 服务配置」。
2. 选择大模型服务商，填写对应的 API Key 和模型。使用自定义服务时，填写 OpenAI 兼容的 Base URL 和模型名。
3. 按需配置语音合成和语音识别，再从人物大厅选择角色开始聊天。

火山方舟的大模型 Key 和豆包语音 Key 分别申请、分别填写。使用语音输入或通话时需要授予麦克风权限；后台任务完成通知需要通知权限。云端服务的可用性、费用和模型权限以对应服务商为准。

本地开发也可以在项目根目录创建 `secrets.properties`，按需填写默认 Key；文件不存在时仍可构建，并在应用内配置。

```properties
VOLCANO_ARK_API_KEY=你的火山方舟Key
VOLCANO_TTS_API_KEY=你的豆包语音Key
SILICONFLOW_API_KEY=你的硅基流动Key
```

该文件已被 Git 忽略，但构建时填写的 Key 会写入 APK，仅适合本地调试，请勿公开分发含有密钥的 APK。

## 技术栈

| 领域 | 使用技术 |
| --- | --- |
| 应用与界面 | Kotlin、Jetpack Compose、Material 3 |
| 数据存储 | Room、SharedPreferences |
| AI 与网络 | OkHttp、Kotlin Coroutines、Kotlin Serialization |
| 后台任务与图片 | WorkManager、Coil |
| 语音 | 设备语音能力、云端语音服务、WebRTC VAD |

## 项目结构

```text
GouXiang/
├── app/
│   └── src/main/
│       ├── java/com/neethu/gouxiang/  # 界面、AI、数据与业务逻辑
│       ├── assets/catalog/            # 预置人物目录
│       └── res/                       # 图片、主题等 Android 资源
├── docs/                              # 功能设计与项目文档
├── gradle/                            # Gradle Wrapper 与版本目录
├── build.gradle.kts
└── settings.gradle.kts
```

人物目录的使用和音色匹配说明见[人物大厅文档](docs/character-lobby.md)。

## 人物卡片来源与版权

部分预置人物卡片来自猫箱平台，人物信息中已注明原作者。相关人物设定、文字和图片等内容的权利归各自权利人所有，感谢创作者们的分享。

如果你是相关权利人，认为收录的内容侵犯了你的权益，或不希望作品出现在本项目中，请通过本仓库的 Issues 联系，并提供人物名称或链接等信息；我们会及时核实并处理，包括按要求删除相关内容。

## 测试

在项目根目录运行本地单元测试：

```bash
./gradlew testDebugUnitTest
```

## 致谢

项目的 AI 服务配置与部分能力参考并移植自 [AIAvatar-SDK](https://github.com/hufeiya/AIAvatar-SDK)，感谢相关项目和开源社区。
