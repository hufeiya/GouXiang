package com.neethu.gouxiang.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * () 动作列表与 messages.actionsJson 列的互转。
 *
 * 失败安全:畸形 JSON → 空表(解码)/"[]"(编码),绝不抛异常——
 * 调用方(聊天页气泡渲染、上下文编码)都拿得到可用值。
 */
object ActionsJson {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun encode(actions: List<String>): String = try {
        json.encodeToString(actions)
    } catch (_: Exception) {
        "[]"
    }

    fun decode(raw: String): List<String> = try {
        (json.parseToJsonElement(raw) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }
}
