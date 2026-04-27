package com.aishell.domain.tool

import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolParamsSerializer @Inject constructor() {

    fun deserialize(toolName: String, jsonParams: String): ToolParams {
        val json = Json.parseToJsonElement(jsonParams).jsonObject

        return when (toolName) {
            "shell_exec" -> ToolParams.ShellExec(
                command = json["command"]?.jsonPrimitive?.content ?: "",
                timeout = json["timeout"]?.jsonPrimitive?.longOrNull ?: 30000,
                workingDir = json["working_dir"]?.jsonPrimitive?.contentOrNull
            )

            "file_read" -> ToolParams.FileRead(
                path = json["path"]?.jsonPrimitive?.content ?: ""
            )

            "file_write" -> ToolParams.FileWrite(
                path = json["path"]?.jsonPrimitive?.content ?: "",
                content = json["content"]?.jsonPrimitive?.content ?: ""
            )

            "file_delete" -> ToolParams.FileDelete(
                path = json["path"]?.jsonPrimitive?.content ?: "",
                recursive = json["recursive"]?.jsonPrimitive?.booleanOrNull ?: false
            )

            "adb_exec" -> ToolParams.AdbExec(
                command = json["command"]?.jsonPrimitive?.content ?: "",
                deviceId = json["device_id"]?.jsonPrimitive?.contentOrNull
            )

            "adb_push" -> ToolParams.AdbPush(
                localPath = json["local_path"]?.jsonPrimitive?.content ?: "",
                remotePath = json["remote_path"]?.jsonPrimitive?.content ?: "",
                deviceId = json["device_id"]?.jsonPrimitive?.contentOrNull
            )

            "fastboot_flash" -> ToolParams.FastbootFlash(
                partition = json["partition"]?.jsonPrimitive?.content ?: "",
                imagePath = json["image_path"]?.jsonPrimitive?.content ?: ""
            )

            "ssh_exec" -> ToolParams.SshExec(
                host = json["host"]?.jsonPrimitive?.content ?: "",
                command = json["command"]?.jsonPrimitive?.content ?: "",
                username = json["username"]?.jsonPrimitive?.content ?: "",
                port = json["port"]?.jsonPrimitive?.intOrNull ?: 22
            )

            "edl_flash" -> ToolParams.EdlFlash(
                partition = json["partition"]?.jsonPrimitive?.content ?: "",
                imagePath = json["image_path"]?.jsonPrimitive?.content ?: ""
            )

            else -> throw IllegalArgumentException("Unknown tool: $toolName")
        }
    }
}
