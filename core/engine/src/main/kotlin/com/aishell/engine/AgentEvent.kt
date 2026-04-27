package com.aishell.engine

import com.aishell.domain.entity.RiskLevel
import com.aishell.domain.tool.ToolResult

sealed class AgentEvent {
    /** Streaming text delta from AI */
    data class TextDelta(val content: String) : AgentEvent()

    /** A tool call is being requested by the AI */
    data class ToolCallRequested(
        val toolName: String,
        val toolCallId: String,
        val paramsJson: String,
        val riskLevel: RiskLevel
    ) : AgentEvent()

    /** Tool execution result */
    data class ToolCallResult(
        val toolCallId: String,
        val result: ToolResult
    ) : AgentEvent()

    /** Confirmation required before executing a risky tool */
    data class ConfirmationRequired(
        val toolCallId: String,
        val toolName: String,
        val paramsJson: String,
        val riskLevel: RiskLevel,
        val onConfirm: suspend () -> Unit,
        val onCancel: suspend () -> Unit
    ) : AgentEvent()

    /** A new iteration of the agentic loop is starting */
    data class IterationStart(
        val iteration: Int,
        val maxIterations: Int
    ) : AgentEvent()

    /** An error occurred */
    data class Error(val message: String) : AgentEvent()

    /** Agent loop completed (reached max iterations or AI finished) */
    object Completed : AgentEvent()
}