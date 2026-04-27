package com.aishell.executor

import com.aishell.domain.entity.RiskLevel
import com.aishell.domain.tool.Tool
import com.aishell.domain.tool.ToolParams
import com.aishell.domain.tool.ToolResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShellTool @Inject constructor() : Tool {
    override val name = "shell_exec"
    override val description = "Execute a shell command on the device"
    /**
     * Default risk level for shell_exec. Actual risk is assessed dynamically
     * by CommandRouter.assessRisk() based on the command content.
     * This default is used when the tool is referenced outside CommandRouter context.
     */
    override val riskLevel = RiskLevel.MEDIUM

    override suspend fun execute(params: ToolParams): ToolResult {
        val shellParams = params as? ToolParams.ShellExec
            ?: return ToolResult.failure("Invalid params for shell_exec")

        val startTime = System.currentTimeMillis()

        return try {
            val process = ProcessBuilder()
                .command("sh", "-c", shellParams.command)
                .apply {
                    shellParams.workingDir?.let { directory(java.io.File(it)) }
                    redirectErrorStream(true)
                }
                .start()

            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            val duration = System.currentTimeMillis() - startTime

            if (process.exitValue() == 0) {
                ToolResult.success(output.trimEnd(), duration)
            } else {
                ToolResult.failure(output.trimEnd(), process.exitValue())
            }
        } catch (e: Exception) {
            ToolResult.failure(e.message ?: "Unknown error")
        }
    }

    override fun validateParams(params: ToolParams): Boolean {
        return params is ToolParams.ShellExec && params.command.isNotBlank()
    }
}