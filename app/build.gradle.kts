import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // 输出协议模型 @Serializable + 移植自 AIAvatar-SDK 的 LLM 层
    alias(libs.plugins.kotlin.serialization)
    // Room @Database/@Dao 注解处理
    alias(libs.plugins.ksp)
}

// ── 密钥注入(惯例同 AIAvatar-SDK:仓库根 secrets.properties,已 gitignore)──
// BuildConfig.VOLCANO_ARK_API_KEY / BuildConfig.SILICONFLOW_API_KEY 供
// com.neethu.gouxiang.smoke.ArkSmokeTest 与运行时 AI 底座读取。
// 文件缺失时回落空串:代码侧把空 Key 视为"未配置",冒烟测试显式 AssumptionFail。
val secretsPropsFile = rootProject.file("secrets.properties")
val secretsProps = Properties().apply {
    if (secretsPropsFile.exists()) secretsPropsFile.inputStream().use { load(it) }
}
fun secret(key: String): String = secretsProps.getProperty(key)?.trim().orEmpty()

android {
    namespace = "com.neethu.gouxiang"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.neethu.gouxiang"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "VOLCANO_ARK_API_KEY", "\"${secret("VOLCANO_ARK_API_KEY")}\"")
        buildConfigField("String", "SILICONFLOW_API_KEY", "\"${secret("SILICONFLOW_API_KEY")}\"")
        // 语音通话 F6:豆包语音合成大模型(seed-tts-2.0)的 API Key,鉴权域独立于
        // Ark(两个控制台各发各的);缺省空串 = 硅基 TTS 兜底(与既有"空 Key 视为
        // 未配置"约定一致)
        buildConfigField("String", "VOLCANO_TTS_API_KEY", "\"${secret("VOLCANO_TTS_API_KEY")}\"")
        // 多厂商目录(我的→AI 服务配置,AiConfigStore.defaultKeyFor 的留空回落值):
        // 全量密钥见仓库根 secrets.properties;GitHub Token 仅存文件不进 APK
        buildConfigField("String", "OPENROUTER_API_KEY", "\"${secret("OPENROUTER_API_KEY")}\"")
        buildConfigField("String", "DEEPSEEK_OFFICIAL_API_KEY", "\"${secret("DEEPSEEK_OFFICIAL_API_KEY")}\"")
        buildConfigField("String", "ALIYUNBAILIAN_API_KEY", "\"${secret("ALIYUNBAILIAN_API_KEY")}\"")
        buildConfigField("String", "GROK_API_KEY", "\"${secret("GROK_API_KEY")}\"")
        buildConfigField("String", "VOLCANO_ARK_BASE_URL", "\"https://ark.cn-beijing.volces.com/api/v3\"")
        buildConfigField("String", "SILICONFLOW_BASE_URL", "\"https://api.siliconflow.cn/v1\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    // 记忆协调器在 JVM 单测里走 android.util.Log:允许返回默认值(不 mock
    // 系统类,断言逻辑本身零 Android 依赖)。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // AI 底座(LLM 流式/生图)与输出协议
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // 本地数据底座
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // 角色头像远程图加载(生成 URL → 本地缓存兜底占位图)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // 后台生成基建(docs/background-generation-plan-20261030.md):
    // DerivativeWorker 普通协程 Worker(不引入前台 Service,§6 红线)
    implementation(libs.androidx.work.runtime.ktx)
    // 通话全双工:WebRTC VAD(GMM 分类,带极小 native so,各 ABI 共 <80K,JitPack)
    implementation("com.github.gkonovalov.android-vad:webrtc:2.0.10")
    testImplementation(libs.junit)
    // GenerationPrefs/WorkManager 面的 JVM 单测(SharedPreferences 需要 Context)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // M0 收尾工作包 1:WorkManager 测试环境(可控调度/可控时钟跑 Worker 单测)
    testImplementation(libs.androidx.work.testing)
    // 冒烟测试:协程驱动流式收集
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
