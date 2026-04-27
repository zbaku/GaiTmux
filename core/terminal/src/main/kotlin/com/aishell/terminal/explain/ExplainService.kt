package com.aishell.terminal.explain

import com.aishell.domain.entity.Message
import com.aishell.domain.entity.MessageRole
import com.aishell.domain.service.AiChunk
import com.aishell.domain.service.AiConfig
import com.aishell.domain.service.AiProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ExplainService @Inject constructor(
    private val provider: AiProvider,
    private val config: AiConfig
) {
    private val systemPrompt = """
        你是一个 Linux 命令解释助手。用户会输入一个命令，你需要：
        1. 用简洁的中文解释命令的作用
        2. 如果有风险，明确警告
        3. 解释每个参数的含义
        4. 如果有更安全的替代方案，提供建议

        回复格式：
        📋 命令解释：[一句话总结]
        ⚠️ 风险评估：[无/低/中/高] + [说明]
        📖 参数详解：[逐个参数解释]
    """.trimIndent()

    suspend fun explain(command: String): Flow<String> {
        val messages = listOf(
            Message(
                conversationId = 0,
                role = MessageRole.SYSTEM,
                content = systemPrompt
            ),
            Message(
                conversationId = 0,
                role = MessageRole.USER,
                content = command
            )
        )

        return provider.chatStream(config, messages, emptyList())
            .map { chunk ->
                if (chunk.isDone) ""
                else if (chunk.error != null) "[错误] ${chunk.error}"
                else chunk.content
            }
    }
}
