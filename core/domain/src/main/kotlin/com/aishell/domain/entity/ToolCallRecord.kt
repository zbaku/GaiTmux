package com.aishell.domain.entity

import kotlinx.serialization.Serializable

enum class RiskLevel(val value: Int) {
    READ_ONLY(0), LOW(1), MEDIUM(2), HIGH(3), CRITICAL(4)
}
enum class ToolCallStatus { PENDING, CONFIRMED, EXECUTING, SUCCESS, FAILED, CANCELLED }

@Serializable
data class ToolCallRecord(
    val id: Long = 0,
    val messageId: Long,
    val toolName: String,
    val params: String,
    val result: String? = null,
    val status: ToolCallStatus = ToolCallStatus.PENDING,
    val riskLevel: RiskLevel = RiskLevel.READ_ONLY
)