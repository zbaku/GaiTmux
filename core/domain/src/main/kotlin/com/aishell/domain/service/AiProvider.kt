package com.aishell.domain.service

import com.aishell.domain.entity.Message
import com.aishell.domain.tool.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
data class AiChunk(
    val content: String = "",
    val toolName: String? = null,
    val toolCallId: String? = null,
    val toolParamsJson: String? = null,
    val isDone: Boolean = false,
    val error: String? = null
)

data class AiConfig(
    val providerId: String,
    val modelId: String,
    val apiKey: String,
    val baseUrl: String,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096
)

interface AiProvider {
    val providerId: String
    val displayName: String

    suspend fun chatStream(
        config: AiConfig,
        messages: List<Message>,
        tools: List<ToolSpec>
    ): Flow<AiChunk>
}