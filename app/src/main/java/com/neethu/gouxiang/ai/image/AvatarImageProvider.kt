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
        referenceAsArray = true,
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
     * 头像是 1024×1024 方图。[referenceImage] 非空(角色封面文件)时以图生图
     * 保持人物长相一致;文件缺失/读取失败回落纯文生图。纯文字卡兜底路径 =
     * 返回 null,调用方照常落库。
     */
    suspend fun getOrGenerateInnerVoiceImage(
        characterKey: String,
        prompt: String,
        referenceImage: File? = null,
    ): File? = getOrGenerateImage(
        characterKey, prompt, subDir = "inner_voices", size = "768x1024",
        referenceImage = referenceImage?.takeIf { it.isFile }?.let { encodeDataUrl(it) },
    )

    /**
     * 朋友圈动态配图(F9):同一生成链,缓存目录/尺寸同心声竖版
     * (基准 06 截图动态卡配图近方偏横,用横版 1024×1024 即可覆盖;
     * 时间线卡按内容自适应裁切)。纯文字卡兜底路径 = 返回 null,调用方
     * 保持 imageFile 空落库。
     */
    suspend fun getOrGenerateMomentImage(characterKey: String, prompt: String): File? =
        getOrGenerateImage(characterKey, prompt, subDir = "moments", size = "1024x1024")

    /**
     * 番外/心动封面配图(F 工作包):同一生成链,缓存目录 filesDir/sidestory,
     * 竖版 768×1024(详情卡竖滑构图)。返回 null = 生图失败,调用方保留
     * 文字成品并把 imageStatus 标 failed。
     */
    suspend fun getOrGenerateSidestoryImage(characterKey: String, prompt: String): File? =
        getOrGenerateImage(characterKey, prompt, subDir = "sidestory", size = "768x1024")

    /**
     * 聊天衍生配图/合照(G 工作包 G1/G3):同一生成链,缓存目录 filesDir/photos,
     * 竖版 768×1024(手帐配图与合照均为竖版构图)。返回 null = 生图失败
     * (手帐保留文字成品;合照整条记失败可重试)。
     */
    suspend fun getOrGeneratePhotoImage(characterKey: String, prompt: String): File? =
        getOrGenerateImage(characterKey, prompt, subDir = "photos", size = "768x1024")

    internal suspend fun getOrGenerateImage(
        characterKey: String,
        prompt: String,
        subDir: String = "avatars",
        size: String = "1024x1024",
        referenceImage: String? = null,
    ): File? {
        val cache = fileFor(characterKey, subDir)
        if (cache.isFile && cache.length() > 0) return cache
        val url = firstAvailableUrl(prompt, size, referenceImage) ?: return null
        return downloadTo(url, cache).takeIf { it }?.let { cache }
    }

    internal fun fileFor(characterKey: String, subDir: String = "avatars"): File {
        val dir = File(filesDir, subDir).apply { if (!exists()) mkdirs() }
        return File(dir, "$characterKey.jpg")
    }

    /** 生成链:火山优先(密钥存在才试),失败换硅基,再失败返回错误摘要。 */
    internal suspend fun firstAvailableUrl(
        prompt: String,
        size: String = "1024x1024",
        referenceImage: String? = null,
    ): String? {
        if (arkApiKey.isNotBlank()) {
            when (val r = arkGen.generate(ImageGenRequest(prompt, size, referenceImage))) {
                is ImageGenResult.Success -> r.urls.firstOrNull()?.let { return it }
                is ImageGenResult.Failure -> Unit // 记录继续降级
            }
        }
        if (siliconflowApiKey.isNotBlank()) {
            when (val r = siliconGen.generate(ImageGenRequest(prompt, size, referenceImage))) {
                is ImageGenResult.Success -> r.urls.firstOrNull()?.let { return it }
                is ImageGenResult.Failure -> Unit
            }
        }
        return null
    }

    /** 本地图片 → `data:image/jpeg;base64,<payload>`(参考图统一 jpg 落盘,mime 恒 jpeg)。 */
    private fun encodeDataUrl(file: File): String? = try {
        val payload = java.util.Base64.getEncoder().encodeToString(file.readBytes())
        "data:image/jpeg;base64,$payload"
    } catch (_: Exception) {
        null
    }

    private suspend fun downloadTo(url: String, target: File): Boolean = withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            if (url.startsWith("data:")) {
                // b64_json 兼容兜底(07 文档 §3.3 双响应形态):伪 URL 不走 HTTP,
                // 直接本地 Base64 解码写盘;失败返回 false 走占位兜底。
                return@withContext decodeDataUrl(url, target) != null
            }
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val bytes = resp.body?.bytes() ?: return@use false
                writeAtomically(target, bytes)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * `data:<mime>;base64,<payload>` 伪 URL → 解码写入 [target](沿用 .tmp+rename
     * 原子写惯例)。成功返回 [target],payload 为空/base64 非法/写盘失败返回 null,
     * 由调用方映射为 false 走占位兜底。internal 供单测直接驱动该分支。
     */
    internal fun decodeDataUrl(dataUrl: String, target: File): File? {
        if (!dataUrl.startsWith("data:")) return null
        // mime 段不校验:parseUrls 只产出 base64 形态,解码只认逗号后的 payload
        val payload = dataUrl.substringAfter(',', missingDelimiterValue = "")
        if (payload.isBlank()) return null
        return try {
            writeAtomically(target, java.util.Base64.getDecoder().decode(payload))
            target
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /** .tmp 先写后 rename 的原子落盘(原 downloadTo 内联惯例,两条写入路径共用)。 */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        /** Ark 生图在售模型(2026-10-28 GET /models domain=ImageGeneration 实查)。 */
        const val ARK_IMAGE_MODEL = "doubao-seedream-5-0-flash-260915"

        /** 硅基流动生图(Kolors 文生图,sk- key 可用,2026-10-28 实测)。 */
        const val SILICONFLOW_IMAGE_MODEL = "Kwai-Kolors/Kolors"
    }
}

/**
 * parseUrls 产出的 b64_json 伪 URL 前缀,与 downloadTo 的本地解码分支共用,
 * 避免两处字面量漂移。
 */
internal const val DATA_URL_PREFIX = "data:image/png;base64,"
