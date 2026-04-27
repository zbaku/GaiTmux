package com.aishell.executor

import com.aishell.domain.entity.Command
import com.aishell.domain.entity.RiskLevel
import com.aishell.domain.service.RiskAssessor
import com.aishell.domain.tool.Tool
import com.aishell.domain.tool.ToolParams
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Three-layer command router with dynamic risk assessment.
 *
 * Layer 1: Android Shell — default, no special requirements
 * Layer 2: Shizuku Elevated — system-level commands (pm, am, input, settings, etc.)
 * Layer 3: Proot Ubuntu — Linux tools (apt, python3, git, node, etc.)
 *
 * Risk is assessed per-command based on content analysis, not hardcoded per-tool.
 */
@Singleton
class CommandRouter @Inject constructor(
    private val shellTool: ShellTool
) : RiskAssessor {
    // ---- Routing ----

    /**
     * Route a raw command string to the appropriate (Tool, ToolParams) pair.
     * Determines the execution layer and parses parameters.
     */
    fun route(command: String): Pair<Tool, ToolParams> {
        return when {
            command.startsWith("adb ") -> parseAdbCommand(command)
            command.startsWith("fastboot ") -> parseFastbootCommand(command)
            needsProot(command) -> shellTool to ToolParams.ProotExec(
                command = command,
                workingDir = "/home"
            )
            else -> shellTool to ToolParams.ShellExec(command)
        }
    }

    /**
     * Determine the execution layer for a command.
     * Used by SystemPromptBuilder to inform the AI about available environments.
     */
    fun getExecutionLayer(command: String): ExecutionLayer {
        return when {
            command.startsWith("adb ") || command.startsWith("fastboot ") -> ExecutionLayer.USB
            needsProot(command) -> ExecutionLayer.PROOT_UBUNTU
            needsShizuku(command) -> ExecutionLayer.SHIZUKU_ELEVATED
            else -> ExecutionLayer.ANDROID_SHELL
        }
    }

    // ---- Risk Assessment ----

    /**
     * Dynamically assess the risk level of a tool call.
     * For shell_exec, risk depends on command content.
     * For other tools, risk is determined by the tool's own riskLevel.
     */
    override fun assessRisk(toolName: String, rawCommand: String): RiskLevel {
        if (toolName != "shell_exec" && toolName != "proot_exec") {
            // Non-shell tools have their own risk levels
            return when (toolName) {
                "fastboot_flash" -> RiskLevel.CRITICAL
                "edl_flash" -> RiskLevel.CRITICAL
                "adb_exec" -> assessAdbRisk(rawCommand)
                else -> RiskLevel.MEDIUM
            }
        }
        return assessShellRisk(rawCommand)
    }

    /**
     * Assess risk for shell commands based on content patterns.
     */
    private fun assessShellRisk(command: String): RiskLevel {
        val cmd = command.trim()
        return when {
            // CRITICAL — irreversible destruction regardless of environment
            cmd.contains(Regex("\\brm\\s+(-rf|-fr)")) -> RiskLevel.CRITICAL
            cmd.contains(Regex("\\brm\\s+-.*\\s+/")) -> RiskLevel.CRITICAL
            cmd.contains("dd if=") -> RiskLevel.CRITICAL
            cmd.contains("mkfs") -> RiskLevel.CRITICAL
            cmd.contains(Regex("\\bformat\\b")) -> RiskLevel.CRITICAL
            cmd.contains(Regex("\\bfastboot\\s+flash\\b")) -> RiskLevel.CRITICAL

            // HIGH — system modification with potential for damage
            cmd.contains(Regex("\\brm\\s+")) -> RiskLevel.HIGH
            cmd.contains(Regex("\\b(chmod|chown)\\s+[0-7]")) -> RiskLevel.HIGH
            cmd.contains(Regex("\\bmount\\b")) -> RiskLevel.HIGH
            cmd.contains(Regex("\\biptables\\b")) -> RiskLevel.HIGH
            cmd.contains(Regex("\\bsysctl\\b")) -> RiskLevel.HIGH

            // MEDIUM — file/system modifications that are generally safe
            cmd.contains(Regex("\\b(mv|cp|mkdir|touch|chmod|chown)\\b")) -> RiskLevel.MEDIUM
            cmd.contains(Regex("\\b(apt install|pip install|npm install)\\b")) -> RiskLevel.MEDIUM
            cmd.contains(Regex("\\b(adb\\s+install|adb\\s+push)\\b")) -> RiskLevel.MEDIUM
            cmd.contains(Regex("\\bsu\\b")) -> RiskLevel.MEDIUM

            // LOW — read operations that may access sensitive data
            cmd.contains(Regex("\\b(cat|head|tail|less|more)\\s+/")) -> RiskLevel.LOW
            cmd.contains(Regex("\\b(find|grep|rg)\\s+/")) -> RiskLevel.LOW

            // READ_ONLY — safe read-only operations
            else -> RiskLevel.READ_ONLY
        }
    }

    /**
     * Assess risk for ADB commands.
     */
    private fun assessAdbRisk(command: String): RiskLevel {
        return when {
            command.contains("install") -> RiskLevel.MEDIUM
            command.contains("push") -> RiskLevel.MEDIUM
            command.contains("shell rm") -> RiskLevel.HIGH
            command.contains("reboot") -> RiskLevel.HIGH
            else -> RiskLevel.READ_ONLY
        }
    }

    // ---- Command Parsing ----

    private fun parseAdbCommand(command: String): Pair<Tool, ToolParams> {
        val parts = command.removePrefix("adb ").split(Regex("\\s+"))
        return when (parts.firstOrNull()) {
            "push" -> shellTool to ToolParams.AdbPush(
                localPath = parts.getOrNull(1) ?: "",
                remotePath = parts.getOrNull(2) ?: ""
            )
            "pull" -> shellTool to ToolParams.AdbPull(
                remotePath = parts.getOrNull(1) ?: "",
                localPath = parts.getOrNull(2) ?: ""
            )
            "install" -> shellTool to ToolParams.AdbInstall(
                apkPath = parts.getOrNull(1) ?: ""
            )
            "shell" -> shellTool to ToolParams.AdbShell(
                command = parts.drop(1).joinToString(" ")
            )
            else -> shellTool to ToolParams.AdbExec(command.removePrefix("adb "))
        }
    }

    private fun parseFastbootCommand(command: String): Pair<Tool, ToolParams> {
        val parts = command.removePrefix("fastboot ").split(Regex("\\s+"))
        return when (parts.firstOrNull()) {
            "flash" -> shellTool to ToolParams.FastbootFlash(
                partition = parts.getOrNull(1) ?: "",
                imagePath = parts.getOrNull(2) ?: ""
            )
            "erase" -> shellTool to ToolParams.FastbootErase(
                partition = parts.getOrNull(1) ?: ""
            )
            "reboot" -> shellTool to ToolParams.FastbootReboot(
                target = parts.getOrNull(1) ?: "system"
            )
            "getvar" -> shellTool to ToolParams.FastbootGetVar(
                variable = parts.getOrNull(1) ?: ""
            )
            else -> shellTool to ToolParams.ShellExec(command)
        }
    }

    // ---- Environment Detection ----

    private fun needsProot(cmd: String): Boolean {
        val ubuntuTools = setOf(
            // Package management
            "apt", "dpkg", "apt-get",
            // Dev languages
            "python3", "python", "pip3", "pip",
            "node", "npm", "npx",
            "go", "cargo", "rustc",
            "gcc", "g++", "make", "cmake",
            // Network
            "ssh", "scp", "rsync",
            // VCS
            "git",
            // Download
            "curl", "wget",
            // Database
            "sqlite3", "redis-cli", "psql",
            // Other
            "docker", "jq", "vim", "nano"
        )
        val firstWord = cmd.trimStart().split(Regex("\\s+")).firstOrNull() ?: ""
        return firstWord in ubuntuTools
    }

    private fun needsShizuku(cmd: String): Boolean {
        val systemCmds = setOf(
            "pm", "am", "input", "settings", "dumpsys",
            "screencap", "cmd", "wm", "svc", "toybox"
        )
        val firstWord = cmd.trimStart().split(Regex("\\s+")).firstOrNull() ?: ""
        return firstWord in systemCmds
    }

    /** Execution layer classification */
    enum class ExecutionLayer {
        ANDROID_SHELL,
        SHIZUKU_ELEVATED,
        PROOT_UBUNTU,
        USB
    }
}