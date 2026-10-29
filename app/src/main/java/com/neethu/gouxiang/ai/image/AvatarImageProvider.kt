package com.neethu.gouxiang.ai.image

import com.neethu.gouxiang.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 角色形象获取门面:火山 Ark seedream → 硅基流动 Kolors → 本地占位兜底。
 *
 * 缓存约定:filesDir/avatars/<characterKey>.jpg,存在即直接返回(一次生成,
 * 永久缓存,与范围说明"生成一次并缓存本地"一致)。
 *
 * 火山/硅基模型为 2026-10-28 /models 实查在售档位(seedream-5-0-flash 是
 * 生图域里当前最便宜的 flash 档,头像场景够用);失败链路逐级降级,任一级
 * 成功即下载 URL 落盘,全失败返回占位资源 id 由 UI 层画兜底图。
 */
class AvatarImageProvider(
    private val filesDir: File,
    private val arkApiKey: String = BuildConfig.VOLCANO_ARK_API_KEY,
    private val siliconflowApiKey: String = BuildConfig.SILICONFLOW_API_KEY,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val arkGen = OpenAiCompatibleImageGenAdapter(
        baseUrl = BuildConfig.VOLCANO_ARK_BASE_URL,
        apiKey = arkApiKey,
        model = ARK_IMAGE_MODEL,
    )
    private val siliconGen = OpenAiCompatibleImageGenAdapter(
        baseUrl = BuildConfig.SILICONFLOW_BASE_URL,
        apiKey = siliconflowApiKey,
        model = SILICONFLOW_IMAGE_MODEL,
    )

    /**
     * 取角色头像的本地文件。先查缓存;未命中走生成链;全部失败返回 null
     * (UI 层回落占位渐变 + 名字首字)。
     */
    suspend fun getOrGenerate(characterKey: String, prompt: String): File? =
        getOrGenerateImage(characterKey, prompt)

    /**
     * 心声卡配图(F8):同一生成链,缓存目录/尺寸不同 —— 卡片是竖版情绪图
     * (768×1024,对齐 07-chat-derivatives.md §3.3 ImageGenConfig 默认),
     * 头像是 1024×1024 方图。纯文字卡兜底路径 = 返回 null,调用方照常落库。
     */
    suspend fun getOrGenerateInnerVoiceImage(characterKey: String, prompt: String): File? =
        getOrGenerateImage(characterKey, prompt, subDir = "inner_voices", size = "768x1024")

    /**
     * 朋友圈动态配图(F9):同一生成链,缓存目录/尺寸同心声竖版
     * (基准 06 截图动态卡配图近方偏横,用横版 1024×1024 即可覆盖;
     * 时间线卡按内容自适应裁切)。纯文字卡兜底路径 = 返回 null,调用方
     * 保持 imageFile 空落库。
     */
    suspend fun getOrGenerateMomentImage(characterKey: String, prompt: String): File? =
        getOrGenerateImage(characterKey, prompt, subDir = "moments", size = "1024x1024")

    internal suspend fun getOrGenerateImage(
        characterKey: String,
        prompt: String,
        subDir: String = "avatars",
        size: String = "1024x1024",
    ): File? {
        val cache = fileFor(characterKey, subDir)
        if (cache.isFile && cache.length() > 0) return cache
        val url = firstAvailableUrl(prompt, size) ?: return null
        return downloadTo(url, cache).takeIf { it }?.let { cache }
    }

    internal fun fileFor(characterKey: String, subDir: String = "avatars"): File {
        val dir = File(filesDir, subDir).apply { if (!exists()) mkdirs() }
        return File(dir, "$characterKey.jpg")
    }

    /** 生成链:火山优先(密钥存在才试),失败换硅基,再失败返回错误摘要。 */
    internal suspend fun firstAvailableUrl(prompt: String, size: String = "1024x1024"): String? {
        if (arkApiKey.isNotBlank()) {
            when (val r = arkGen.generate(ImageGenRequest(prompt, size))) {
                is ImageGenResult.Success -> r.urls.firstOrNull()?.let { return it }
                is ImageGenResult.Failure -> Unit // 记录继续降级
            }
        }
        if (siliconflowApiKey.isNotBlank()) {
            when (val r = siliconGen.generate(ImageGenRequest(prompt, size))) {
                is ImageGenResult.Success -> r.urls.firstOrNull()?.let { return it }
                is ImageGenResult.Failure -> Unit
            }
        }
        return null
    }

    private suspend fun downloadTo(url: String, target: File): Boolean = withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val bytes = resp.body?.bytes() ?: return@use false
                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        /** Ark 生图在售模型(2026-10-28 GET /models domain=ImageGeneration 实查)。 */
        const val ARK_IMAGE_MODEL = "doubao-seedream-5-0-flash-260915"

        /** 硅基流动生图(Kolors 文生图,sk- key 可用,2026-10-28 实测)。 */
        const val SILICONFLOW_IMAGE_MODEL = "Kwai-Kolors/Kolors"
    }
}
