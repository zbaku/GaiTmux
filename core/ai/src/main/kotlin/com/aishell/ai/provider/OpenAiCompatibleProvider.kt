package com.aishell.ai.provider

import com.aishell.ai.model.*
import com.aishell.domain.entity.Message
import com.aishell.domain.service.AiChunk
import com.aishell.domain.service.AiConfig
import com.aishell.domain.service.AiProvider
import com.aishell.domain.tool.ToolSpec
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

class OpenAiCompatibleProvider(
    override val providerId: String,
    override val displayName: String,
    private val baseUrl: String,
    private val client: OkHttpClient
) : AiProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun chatStream(
        config: AiConfig,
        messages: List<Message>,
        tools: List<ToolSpec>
    ): Flow<AiChunk> = channelFlow {
        val request = buildRequest(config, messages, tools)
        val httpRequest = Request.Builder()
            .url("${config.baseUrl.ifEmpty { baseUrl }}/v1/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(request.toRequestBody(jsonMediaType))
            .build()

        var eventSource: EventSource? = null

        eventSource = EventSources.createFactory(client).newEventSource(httpRequest, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    trySend(AiChunk(isDone = true))
                    eventSource.cancel()
                    return
                }

                if (data.isEmpty()) return

                try {
                    val response = json.decodeFromString<OpenAiStreamResponse>(data)
                    response.choices.firstOrNull()?.let { choice ->
                        val delta = choice.delta

                        if (delta?.content != null) {
                            trySend(AiChunk(content = delta.content))
                        }

                        delta?.toolCalls?.forEach { toolCall ->
                            trySend(AiChunk(
                                toolName = toolCall.function?.name,
                                toolCallId = toolCall.id,
                                toolParamsJson = toolCall.function?.arguments
                            ))
                        }

                        if (choice.finishReason == "stop" || choice.finishReason == "tool_calls") {
                            trySend(AiChunk(isDone = true))
                        }
                    }
                } catch (_: Exception) {
                    // Skip malformed chunks
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val errorMsg = t?.message ?: response?.body?.string()?.take(200) ?: "SSE connection failed"
                trySend(AiChunk(error = errorMsg, isDone = true))
                close()
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        })

        awaitClose { eventSource?.cancel() }
    }

    private fun buildRequest(
        config: AiConfig,
        messages: List<Message>,
        tools: List<ToolSpec>
    ): String {
        val request = OpenAiRequest(
            model = config.modelId,
            messages = messages.map { msg ->
                OpenAiMessage(
                    role = msg.role.name.lowercase(),
                    content = msg.content
                )
            },
            stream = true,
            tools = tools.map { spec ->
                OpenAiTool(
                    function = OpenAiFunction(
                        name = spec.name,
                        description = spec.description,
                        parameters = spec.parameters.toJsonObject()
                    )
                )
            }.takeIf { it.isNotEmpty() },
            temperature = config.temperature,
            maxTokens = config.maxTokens
        )
        return json.encodeToString(OpenAiRequest.serializer(), request)
    }

    private fun Map<String, Any>.toJsonObject(): kotlinx.serialization.json.JsonObject {
        val map = this.mapValues { (_, value) ->
            when (value) {
                is String -> kotlinx.serialization.json.JsonPrimitive(value)
                is Number -> kotlinx.serialization.json.JsonPrimitive(value)
                is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
                is Map<*, *> -> (value as Map<String, Any>).toJsonObject()
                else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
            }
        }
        return kotlinx.serialization.json.buildJsonObject {
            map.forEach { (key, value) -> put(key, value) }
        }
    }
}