package com.aishell.domain.tool

import kotlinx.serialization.Serializable

@Serializable
data class ToolResult(
    val success: Boolean,
    val output: String,
    val error: String? = null,
    val exitCode: Int = 0,
    val duration: Long = 0,
    val requiresConfirmation: Boolean = false
) {
    companion object {
        fun success(output: String, duration: Long = 0) = ToolResult(
            success = true,
            output = output,
            duration = duration
        )

        fun failure(error: String, exitCode: Int = 1) = ToolResult(
            success = false,
            output = "",
            error = error,
            exitCode = exitCode
        )
    }
}