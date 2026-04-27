package com.aishell.engine

import com.aishell.domain.entity.RiskLevel
import com.aishell.domain.tool.Tool
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin routing layer over ToolRegistry.
 * Provides tool lookup and risk level queries.
 * ToolRegistry handles the actual tool storage and registration.
 */
@Singleton
class ToolRouter @Inject constructor(
    private val toolRegistry: ToolRegistry
) {
    fun get(name: String): Tool? = toolRegistry.get(name)

    fun getAll(): List<Tool> = toolRegistry.getAll()

    fun getRiskLevel(name: String): RiskLevel {
        return toolRegistry.get(name)?.riskLevel ?: RiskLevel.READ_ONLY
    }
}