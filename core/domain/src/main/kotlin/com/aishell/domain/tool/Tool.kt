package com.aishell.domain.tool

import com.aishell.domain.entity.RiskLevel

interface Tool {
    val name: String
    val description: String
    val riskLevel: RiskLevel

    suspend fun execute(params: ToolParams): ToolResult
    fun validateParams(params: ToolParams): Boolean
}