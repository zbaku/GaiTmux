package com.aishell.engine

import com.aishell.domain.entity.ConversationContext
import com.aishell.domain.entity.Message
import com.aishell.domain.entity.RiskLevel
import com.aishell.domain.service.AiChunk
import com.aishell.domain.service.AiConfig
import com.aishell.domain.service.AiProvider
import com.aishell.domain.service.RiskAssessor
import com.aishell.domain.tool.Tool
import com.aishell.domain.tool.ToolParamsSerializer
import com.aishell.domain.tool.ToolResult
import com.aishell.domain.tool.ToolSpecGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentEngine @Inject constructor(
    private val confirmationGate: ConfirmationGate,
    private val toolRouter: ToolRouter,
    private val toolParamsSerializer: ToolParamsSerializer,
    private val toolSpecGenerator: ToolSpecGenerator,
    private val riskAssessor: RiskAssessor
) {
    companion object {
        const val DEFAULT_MAX_ITERATIONS = 20
    }

    /**
     * Execute an agentic loop: AI generates text/tool-calls, tool results are fed back,
     * until the AI signals completion or max iterations are reached.
     *
     * @param provider AI provider for streaming chat
     * @param config AI configuration (model, temperature, etc.)
     * @param messages Conversation history
     * @param tools Available tools mapped by name
     * @param context Conversation context including maxIterations and confirmationLevel
     */
    fun execute(
        provider: AiProvider,
        config: AiConfig,
        messages: List<Message>,
        tools: Map<String, Tool>,
        context: ConversationContext = ConversationContext(conversationId = 0)
    ): Flow<AgentEvent> = flow {
        val maxIterations = context.maxIterations.coerceIn(1, 100)
        var iteration = 0
        var currentMessages = messages.toMutableList()

        val toolSpecs = toolSpecGenerator.generateSpecs(tools.values.toList())

        while (iteration < maxIterations) {
            iteration++
            emit(AgentEvent.IterationStart(iteration, maxIterations))

            var hasToolCall = false
            var toolCallAccumulator: ToolCallAccumulator? = null

            provider.chatStream(config, currentMessages, toolSpecs).collect { chunk ->
                val error = chunk.error
                val toolName = chunk.toolName
                when {
                    chunk.isDone -> { /* stream ended, handled below */ }
                    error != null -> emit(AgentEvent.Error(error))
                    toolName != null -> {
                        hasToolCall = true
                        toolCallAccumulator = ToolCallAccumulator(
                            toolCallId = chunk.toolCallId ?: "",
                            toolName = toolName,
                            paramsJson = chunk.toolParamsJson ?: ""
                        )
                    }
                    chunk.content.isNotEmpty() -> emit(AgentEvent.TextDelta(chunk.content))
                }
            }

            // Process tool call if any
            val accumulator = toolCallAccumulator
            if (hasToolCall && accumulator != null) {
                val tool = tools[accumulator.toolName]
                if (tool == null) {
                    emit(AgentEvent.Error("Unknown tool: ${accumulator.toolName}"))
                    continue
                }

                // Dynamic risk assessment: use RiskAssessor for shell tools, fallback to tool's static level
                val riskLevel = if (accumulator.toolName == "shell_exec" || accumulator.toolName == "proot_exec") {
                    riskAssessor.assessRisk(accumulator.toolName, accumulator.paramsJson)
                } else {
                    tool.riskLevel
                }
                val requiresConfirmation = confirmationGate.requiresConfirmation(riskLevel)

                if (requiresConfirmation) {
                    emit(AgentEvent.ConfirmationRequired(
                        toolCallId = accumulator.toolCallId,
                        toolName = accumulator.toolName,
                        paramsJson = accumulator.paramsJson,
                        riskLevel = riskLevel,
                        onConfirm = {
                            val result = executeTool(tool, accumulator.paramsJson)
                            emit(AgentEvent.ToolCallResult(
                                toolCallId = accumulator.toolCallId,
                                result = result
                            ))
                        },
                        onCancel = {
                            emit(AgentEvent.ToolCallResult(
                                toolCallId = accumulator.toolCallId,
                                result = ToolResult.failure("User cancelled")
                            ))
                        }
                    ))
                } else {
                    val result = executeTool(tool, accumulator.paramsJson)
                    emit(AgentEvent.ToolCallResult(
                        toolCallId = accumulator.toolCallId,
                        result = result
                    ))
                }
            } else {
                // No tool call — AI finished generating text, loop ends
                break
            }
        }

        if (iteration >= maxIterations) {
            emit(AgentEvent.Error("达到最大迭代次数 ($maxIterations)，对话已终止。请简化任务或分步执行。"))
        }

        emit(AgentEvent.Completed)
    }

    private suspend fun executeTool(tool: Tool, paramsJson: String?): ToolResult {
        if (paramsJson.isNullOrBlank()) {
            return ToolResult.failure("No params provided for tool: ${tool.name}")
        }

        return try {
            val params = toolParamsSerializer.deserialize(tool.name, paramsJson)

            if (!tool.validateParams(params)) {
                return ToolResult.failure("Invalid params for tool: ${tool.name}")
            }

            tool.execute(params)
        } catch (e: IllegalArgumentException) {
            ToolResult.failure("Unknown tool: ${e.message}")
        } catch (e: Exception) {
            ToolResult.failure(e.message ?: "Tool execution error")
        }
    }

    /** Internal accumulator for streaming tool call data */
    private data class ToolCallAccumulator(
        val toolCallId: String,
        val toolName: String,
        val paramsJson: String
    )
}