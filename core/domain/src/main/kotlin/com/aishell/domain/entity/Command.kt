package com.aishell.domain.entity

import kotlinx.serialization.Serializable

/**
 * Represents a parsed command with its execution context.
 * Used by CommandRouter to determine routing strategy and risk level.
 */
@Serializable
data class Command(
    val raw: String,
    val program: String,
    val args: List<String> = emptyList(),
    val workingDir: String? = null,
    val env: Map<String, String> = emptyMap()
) {
    companion object {
        fun parse(rawCommand: String): Command {
            val parts = rawCommand.trim().split(Regex("\\s+"))
            val program = parts.firstOrNull() ?: ""
            val args = parts.drop(1)
            return Command(raw = rawCommand, program = program, args = args)
        }
    }

    val isRootCommand: Boolean
        get() = program == "su" || args.firstOrNull() == "su"

    val isAdbCommand: Boolean
        get() = program == "adb" || raw.startsWith("adb ")

    val isFastbootCommand: Boolean
        get() = program == "fastboot" || raw.startsWith("fastboot ")
}
