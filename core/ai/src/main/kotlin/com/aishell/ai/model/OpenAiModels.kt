package com.aishell.ai.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val stream: Boolean = true,
    val tools: List<OpenAiTool>? = null,
    val temperature: Float? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null
)

@Serializable
data class OpenAiMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenAiToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null
)

@Serializable
data class OpenAiToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenAiFunctionCall
)

@Serializable
data class OpenAiFunctionCall(
    val name: String,
    val arguments: String
)

@Serializable
data class OpenAiTool(
    val type: String = "function",
    val function: OpenAiFunction
)

@Serializable
data class OpenAiFunction(
    val name: String,
    val description: String,
    val parameters: kotlinx.serialization.json.JsonObject
)

@Serializable
data class OpenAiStreamResponse(
    val id: String? = null,
    val choices: List<OpenAiChoice> = emptyList()
)

@Serializable
data class OpenAiChoice(
    val index: Int = 0,
    val delta: OpenAiDelta? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class OpenAiDelta(
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenAiToolCallDelta>? = null
)

@Serializable
data class OpenAiToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: OpenAiFunctionDelta? = null
)

@Serializable
data class OpenAiFunctionDelta(
    val name: String? = null,
    val arguments: String? = null
)