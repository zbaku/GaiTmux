package com.aishell.ai.provider

import com.aishell.domain.entity.Message
import com.aishell.domain.service.AiChunk
import com.aishell.domain.service.AiConfig
import com.aishell.domain.service.AiProvider
import com.aishell.domain.tool.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient

class MiniMaxProvider(
    private val client: OkHttpClient
) : AiProvider {

    override val providerId = "minimax"
    override val displayName = "MiniMax (海螺 AI)"

    override suspend fun chatStream(
        config: AiConfig,
        messages: List<Message>,
        tools: List<ToolSpec>
    ): Flow<AiChunk> = flow {
        // MiniMax uses OpenAI-compatible API format
        // Delegate to OpenAiCompatibleProvider with MiniMax base URL
        val delegate = OpenAiCompatibleProvider(
            providerId = providerId,
            displayName = displayName,
            baseUrl = "https://api.minimax.chat",
            client = client
        )
        val minimaxConfig = config.copy(
            baseUrl = config.baseUrl.ifEmpty { "https://api.minimax.chat/v1" }
        )
        delegate.chatStream(minimaxConfig, messages, tools).collect { chunk ->
            emit(chunk)
        }
    }
}