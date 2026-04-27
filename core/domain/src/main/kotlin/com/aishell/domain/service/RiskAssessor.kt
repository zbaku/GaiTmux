package com.aishell.domain.service

import com.aishell.domain.entity.RiskLevel

/**
 * Strategy interface for dynamic risk assessment of tool calls.
 * Implemented by CommandRouter in the executor module.
 * Injected into AgentEngine for runtime risk evaluation.
 */
interface RiskAssessor {
    fun assessRisk(toolName: String, rawCommand: String): RiskLevel
}