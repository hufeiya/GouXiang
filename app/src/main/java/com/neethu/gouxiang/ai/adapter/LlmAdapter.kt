package com.neethu.gouxiang.ai.adapter

import kotlinx.serialization.json.JsonObject

/** Conversation message roles following the OpenAI chat-completions convention. */
enum class ChatRole { SYSTEM, USER, ASSISTANT, TOOL }

/**
 * A tool call the model requested. [id] is the server-generated `call_xxx` id
 * that must be echoed back on the matching `role=tool` message; [argumentsJson]
 * is the fully accumulated arguments string — possibly invalid JSON, the
 * executor owns the error handling.
 */
data class ToolCallRequest(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

/** A single conversation message sent to / received from an LLM. */
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    /**
     * Images attached to this message as data URLs, sent via the OpenAI
     * multimodal `content` array (`image_url` parts). Only meaningful on USER
     * messages for vision-capable models; keep empty for text-only models
     * (they reject array-form content with 400).
     */
    val images: List<String> = emptyList(),
    /**
     * Tool calls this ASSISTANT message carries. Serialized as OpenAI
     * `assistant.tool_calls`; the following TOOL messages must reference these
     * ids in order.
     */
    val toolCalls: List<ToolCallRequest> = emptyList(),
    /** For role=TOOL: the [ToolCallRequest.id] this result answers. */
    val toolCallId: String? = null,
)

/**
 * One callable tool advertised to the model (OpenAI function-calling shape).
 * [parametersSchema] is the JSON Schema for the arguments object.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    /** e.g. `{"type":"object","properties":{…},"required":[…]}` (kotlinx JsonObject). */
    val parametersSchema: JsonObject,
    val systemHint: String? = null,
)

/**
 * Connection + sampling parameters for a chat-completions request.
 *
 * [baseUrl] should include the version segment, e.g. Ark 的
 * `https://ark.cn-beijing.volces.com/api/v3`。The adapter appends
 * `/chat/completions`.
 */
data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Float = 0.8f,
    val topP: Float = 1.0f,
    val maxTokens: Int = 1024,
    val extraHeaders: Map<String, String> = emptyMap(),
    /** Merged verbatim into the JSON request body (e.g. provider-specific knobs). */
    val extraBody: JsonObject? = null,
    /**
     * Tools advertised to the model. Empty (default) = the `tools`/`tool_choice`
     * keys are NOT sent at all — strict endpoints reject empty tool arrays with 400.
     */
    val tools: List<ToolSpec> = emptyList(),
    /** OpenAI `tool_choice`: `"auto"` | `"none"` | `"required"`. null = key omitted. */
    val toolChoice: String? = null,
)

/**
 * Token usage reported in the stream's final chunk
 * (`stream_options.include_usage`). `cachedTokens > 0` means the provider
 * served part of the prefix from its cache; `null` = not reported.
 */
data class LlmUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val cachedTokens: Int? = null,
)

/** Streaming events emitted by [LlmAdapter]. */
sealed interface LlmStreamEvent {
    /** An incremental piece of the assistant reply text. */
    data class TextDelta(val text: String) : LlmStreamEvent

    /**
     * The model started a tool call ([index] is the parallel-call slot within
     * this stream). Fired once the id+name become visible.
     */
    data class ToolCallStarted(val index: Int, val id: String, val name: String) : LlmStreamEvent

    /** An incremental piece of a tool call's JSON arguments (observability/progress UI). */
    data class ToolCallArguments(val index: Int, val fragment: String) : LlmStreamEvent

    /**
     * The stream finished. [reason] is the provider finish reason, e.g. `stop`;
     * [usage] carries the token counts when reported. [toolCalls] holds the
     * fully accumulated tool calls — non-empty means the model wants tools
     * executed regardless of the [reason] literal.
     */
    data class Finish(
        val reason: String?,
        val usage: LlmUsage? = null,
        val toolCalls: List<ToolCallRequest> = emptyList(),
    ) : LlmStreamEvent

    /** The stream failed. Delivered as an event (the Flow also completes normally). */
    data class Error(val throwable: Throwable) : LlmStreamEvent
}

/** Pluggable LLM backend speaking the OpenAI chat-completions wire protocol. */
interface LlmAdapter {
    fun streamChat(messages: List<ChatMessage>, config: LlmConfig): kotlinx.coroutines.flow.Flow<LlmStreamEvent>
}
