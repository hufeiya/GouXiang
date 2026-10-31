package com.neethu.gouxiang.ai.image

/**
 * 生图请求(竖版立绘/头像一次一张;猫箱的批量出图属于创作工作台,后续功能再扩 batch)。
 */
data class ImageGenRequest(
    /** 形象描述(prompt = appearance + 风格词缀,由调用方拼好)。 */
    val prompt: String,
    /** 尺寸,火山 seedream / 硅基 Kolors 均支持 "WxH" 字符串。 */
    val size: String = "1024x1024",
    /**
     * 参考图(`data:<mime>;base64,<payload>`,通常为角色封面)。非空时走
     * 图生图(i2i):火山 Ark seedream / 硅基 Kolors 的 `images/generations`
     * 均支持 `image` 参考图参数(封装形态差异由适配器处理)。null = 纯文生图。
     */
    val referenceImage: String? = null,
)

/** 生图结果:[urls] 为服务商临时 URL,调用方负责下载落盘缓存。 */
sealed interface ImageGenResult {
    data class Success(val urls: List<String>) : ImageGenResult
    data class Failure(val message: String) : ImageGenResult
}

/**
 * 角色形象生成通道(对齐 01-character-card.md §3.2-A 的 ImageGenAdapter 设计,
 * 简化为头像单图场景:火山 Ark 优先,硅基流动兜底)。
 */
interface ImageGenAdapter {
    suspend fun generate(request: ImageGenRequest): ImageGenResult
}
