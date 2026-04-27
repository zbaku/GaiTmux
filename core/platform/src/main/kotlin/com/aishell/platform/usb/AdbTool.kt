package com.aishell.platform.usb

import com.aishell.domain.entity.RiskLevel
import com.aishell.domain.tool.Tool
import com.aishell.domain.tool.ToolParams
import com.aishell.domain.tool.ToolResult
import com.aishell.platform.usb.model.UsbDevice
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AdbTool @Inject constructor(
    private val adbManager: AdbManager
) : Tool {

    override val name = "adb_exec"
    override val description = "Execute ADB commands on connected Android devices"
    override val riskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(params: ToolParams): ToolResult {
        val startTime = System.currentTimeMillis()

        return when (params) {
            is ToolParams.AdbExec -> {
                val device = adbManager.getDevice(params.deviceId)
                    ?: return ToolResult.failure("Device not found: ${params.deviceId}")
                val output = adbManager.executeCommand(device, params.command)
                ToolResult.success(output, System.currentTimeMillis() - startTime)
            }
            is ToolParams.AdbShell -> {
                val device = adbManager.getDevice(params.deviceId)
                    ?: return ToolResult.failure("Device not found")
                val output = adbManager.executeCommand(device, params.command)
                ToolResult.success(output, System.currentTimeMillis() - startTime)
            }
            is ToolParams.AdbPush -> {
                val device = adbManager.getDevice(params.deviceId)
                    ?: return ToolResult.failure("Device not found")
                val output = adbManager.pushFile(device, params.localPath, params.remotePath)
                ToolResult.success(output, System.currentTimeMillis() - startTime)
            }
            is ToolParams.AdbPull -> {
                val device = adbManager.getDevice(params.deviceId)
                    ?: return ToolResult.failure("Device not found")
                val output = adbManager.pullFile(device, params.remotePath, params.localPath)
                ToolResult.success(output, System.currentTimeMillis() - startTime)
            }
            is ToolParams.AdbInstall -> {
                val device = adbManager.getDevice(params.deviceId)
                    ?: return ToolResult.failure("Device not found")
                val output = adbManager.installApk(device, params.apkPath)
                ToolResult.success(output, System.currentTimeMillis() - startTime)
            }
            else -> ToolResult.failure("Invalid params for adb_exec")
        }
    }

    override fun validateParams(params: ToolParams): Boolean {
        return params is ToolParams.AdbExec ||
                params is ToolParams.AdbShell ||
                params is ToolParams.AdbPush ||
                params is ToolParams.AdbPull ||
                params is ToolParams.AdbInstall
    }
}

@Singleton
class AdbManager @Inject constructor() {
    private val devices = mutableMapOf<String, UsbDevice>()
    private var nativeHandle: Long = 0

    suspend fun scanDevices(): List<UsbDevice> {
        val json = AdbNative.scanDevices(nativeHandle)
        // Parse JSON and update devices map
        return devices.values.toList()
    }

    fun getDevice(deviceId: String?): UsbDevice? {
        return if (deviceId == null) devices.values.firstOrNull()
        else devices[deviceId]
    }

    suspend fun executeCommand(device: UsbDevice, command: String): String {
        return AdbNative.execute(nativeHandle, device.deviceId, command)
    }

    suspend fun pushFile(device: UsbDevice, localPath: String, remotePath: String): String {
        return AdbNative.push(nativeHandle, device.deviceId, localPath, remotePath)
    }

    suspend fun pullFile(device: UsbDevice, remotePath: String, localPath: String): String {
        return AdbNative.pull(nativeHandle, device.deviceId, remotePath, localPath)
    }

    suspend fun installApk(device: UsbDevice, apkPath: String): String {
        return AdbNative.install(nativeHandle, device.deviceId, apkPath)
    }

    suspend fun reboot(device: UsbDevice, mode: String): String {
        return AdbNative.reboot(nativeHandle, device.deviceId, mode)
    }
}

object AdbNative {
    init {
        System.loadLibrary("aishell-native")
    }

    external fun init(): Long
    external fun scanDevices(handle: Long): String
    external fun execute(handle: Long, deviceId: String, command: String): String
    external fun push(handle: Long, deviceId: String, local: String, remote: String): String
    external fun pull(handle: Long, deviceId: String, remote: String, local: String): String
    external fun install(handle: Long, deviceId: String, apk: String): String
    external fun reboot(handle: Long, deviceId: String, mode: String): String
}