package com.aishell.domain.tool

import kotlinx.serialization.Serializable

@Serializable
sealed class ToolParams {
    // Shell execution
    @Serializable
    data class ShellExec(
        val command: String,
        val timeout: Long = 30000,
        val workingDir: String? = null
    ) : ToolParams()

    // File operations
    @Serializable
    data class FileRead(val path: String) : ToolParams()

    @Serializable
    data class FileWrite(val path: String, val content: String) : ToolParams()

    @Serializable
    data class FileDelete(val path: String, val recursive: Boolean = false) : ToolParams()

    @Serializable
    data class FileList(val path: String, val recursive: Boolean = false) : ToolParams()

    // ADB operations
    @Serializable
    data class AdbExec(
        val command: String,
        val deviceId: String? = null
    ) : ToolParams()

    @Serializable
    data class AdbPush(
        val localPath: String,
        val remotePath: String,
        val deviceId: String? = null
    ) : ToolParams()

    @Serializable
    data class AdbPull(
        val remotePath: String,
        val localPath: String,
        val deviceId: String? = null
    ) : ToolParams()

    @Serializable
    data class AdbInstall(
        val apkPath: String,
        val deviceId: String? = null
    ) : ToolParams()

    @Serializable
    data class AdbShell(
        val command: String,
        val deviceId: String? = null
    ) : ToolParams()

    // Fastboot operations
    @Serializable
    data class FastbootFlash(
        val partition: String,
        val imagePath: String
    ) : ToolParams()

    @Serializable
    data class FastbootErase(val partition: String) : ToolParams()

    @Serializable
    data class FastbootReboot(val target: String = "system") : ToolParams()

    @Serializable
    data class FastbootGetVar(val variable: String) : ToolParams()

    // EDL operations
    @Serializable
    data class EdlFlash(
        val partition: String,
        val imagePath: String
    ) : ToolParams()

    // SSH/SFTP operations
    @Serializable
    data class SshExec(
        val host: String,
        val command: String,
        val username: String,
        val port: Int = 22
    ) : ToolParams()

    @Serializable
    data class SftpUpload(
        val host: String,
        val localPath: String,
        val remotePath: String,
        val username: String,
        val port: Int = 22
    ) : ToolParams()

    @Serializable
    data class SftpDownload(
        val host: String,
        val remotePath: String,
        val localPath: String,
        val username: String,
        val port: Int = 22
    ) : ToolParams()

    // proot operations
    @Serializable
    data class ProotExec(
        val command: String,
        val workingDir: String? = null
    ) : ToolParams()
}