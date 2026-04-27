package com.aishell.domain.tool

import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>
)

@Singleton
class ToolSpecGenerator @Inject constructor() {

    fun generateSpecs(tools: List<Tool>): List<ToolSpec> {
        return tools.map { tool ->
            ToolSpec(
                name = tool.name,
                description = tool.description,
                parameters = generateParams(tool.name)
            )
        }
    }

    private fun generateParams(toolName: String): Map<String, Any> {
        return when (toolName) {
            "shell_exec" -> mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "command" to mapOf("type" to "string", "description" to "Shell command to execute"),
                    "timeout" to mapOf("type" to "integer", "description" to "Timeout in ms", "default" to 30000),
                    "working_dir" to mapOf("type" to "string", "description" to "Working directory")
                ),
                "required" to listOf("command")
            )

            "file_read" -> mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string", "description" to "File path to read")
                ),
                "required" to listOf("path")
            )

            "file_write" -> mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string", "description" to "File path to write"),
                    "content" to mapOf("type" to "string", "description" to "Content to write")
                ),
                "required" to listOf("path", "content")
            )

            "file_delete" -> mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string", "description" to "File path to delete"),
                    "recursive" to mapOf("type" to "boolean", "description" to "Delete recursively", "default" to false)
                ),
                "required" to listOf("path")
            )

            "adb_exec" -> mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "command" to mapOf("type" to "string", "description" to "ADB command"),
                    "device_id" to mapOf("type" to "string", "description" to "Target device ID")
                ),
                "required" to listOf("command")
            )

            "fastboot_flash" -> mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "partition" to mapOf("type" to "string", "description" to "Partition name (boot/recovery/system/vendor)"),
                    "image_path" to mapOf("type" to "string", "description" to "Path to flash image file")
                ),
                "required" to listOf("partition", "image_path")
            )

            "ssh_exec" -> mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "host" to mapOf("type" to "string", "description" to "SSH host"),
                    "command" to mapOf("type" to "string", "description" to "Command to execute"),
                    "username" to mapOf("type" to "string", "description" to "SSH username"),
                    "port" to mapOf("type" to "integer", "description" to "SSH port", "default" to 22)
                ),
                "required" to listOf("host", "command", "username")
            )

            "edl_flash" -> mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "partition" to mapOf("type" to "string", "description" to "Partition name"),
                    "image_path" to mapOf("type" to "string", "description" to "Path to flash image")
                ),
                "required" to listOf("partition", "image_path")
            )

            else -> mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        }
    }
}
