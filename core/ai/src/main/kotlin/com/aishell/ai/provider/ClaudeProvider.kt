package com.aishell.ai.provider

import com.aishell.ai.model.*
import com.aishell.domain.entity.Message
import com.aishell.domain.entity.MessageRole
import com.aishell.domain.service.AiChunk
import com.aishell.domain.service.AiConfig
import com.aishell.domain.service.AiProvider
import com.aishell.domain.tool.ToolSpec
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

class ClaudeProvider(
    private val client: OkHttpClient
) : AiProvider {

    override val providerId = "claude"
    override val displayName = "Claude (Anthropic)"

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun chatStream(
        config: AiConfig,
        messages: List<Message>,
        tools: List<ToolSpec>
    ): Flow<AiChunk> = channelFlow {
        val claudeMessages = messages.filter { it.role != MessageRole.SYSTEM }
        val systemPrompt = messages.firstOrNull { it.role == MessageRole.SYSTEM }?.content

        val body = buildClaudeRequest(config, claudeMessages, systemPrompt, tools)
        val httpRequest = Request.Builder()
            .url("${config.baseUrl.ifEmpty { "https://api.anthropic.com" }}/v1/messages")
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(JsonElement.serializer(), body).toRequestBody(jsonMediaType))
            .build()

        var eventSource: EventSource? = null

        eventSource = EventSources.createFactory(client).newEventSource(httpRequest, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                when (type) {
                    "content_block_delta" -> {
                        if (data.isEmpty()) return
                        try {
                            val delta = json.parseToJsonElement(data).jsonObject
                            val text = delta["delta"]?.jsonObject?.get("texttext")?.jsonPrimitive?.content
                            if (text != null) {
                                trySend(AiChunk(content = text))
                            }
                        } catch (_: Exception) {}
                    }
                    "message_stop" -> {
                        trySend(AiChunk(isDone = true))
                        eventSource.cancel()
                    }
                    "error" -> {
                        trySend(AiChunk(error = data, isDone = true))
                        eventSource.cancel()
                    }
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

    private fun buildClaudeRequest(
        config: AiConfig,
        messages: List<Message>,
        systemPrompt: String?,
        tools: List<ToolSpec>
    ): JsonElement = buildJsonObject {
        put("model", config.modelId.ifEmpty { "claude-3-5-sonnet-20241022" })
        put("max_tokens", config.maxTokens)
        put("stream", true)
        systemPrompt?.let { put("system", it) }
        put("messages", JsonArray(messages.map { msg ->
            buildJsonObject {
                put("role", msg.role.name.lowercase())
                put("content", msg.content ?: "")
            }
        }))
    }
}