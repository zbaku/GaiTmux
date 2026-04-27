package com.aishell.platform.usb

import com.aishell.domain.entity.RiskLevel
import com.aishell.domain.tool.Tool
import com.aishell.domain.tool.ToolParams
import com.aishell.domain.tool.ToolResult
import com.aishell.platform.usb.model.UsbDevice
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FastbootTool @Inject constructor(
    private val fastbootManager: FastbootManager
) : Tool {

    override val name = "fastboot_flash"
    override val description = "Flash partitions using Fastboot protocol (CRITICAL)"
    override val riskLevel = RiskLevel.CRITICAL

    override suspend fun execute(params: ToolParams): ToolResult {
        val startTime = System.currentTimeMillis()

        return when (params) {
            is ToolParams.FastbootFlash -> {
                val device = fastbootManager.getDevice()
                    ?: return ToolResult.failure("No device in fastboot mode")
                val output = fastbootManager.flashPartition(params.partition, params.imagePath)
                ToolResult.success(output, System.currentTimeMillis() - startTime)
            }
            is ToolParams.FastbootErase -> {
                val device = fastbootManager.getDevice()
                    ?: return ToolResult.failure("No device in fastboot mode")
                val output = fastbootManager.erasePartition(params.partition)
                ToolResult.success(output, System.currentTimeMillis() - startTime)
            }
            is ToolParams.FastbootReboot -> {
                val output = fastbootManager.reboot(params.target)
                ToolResult.success(output, System.currentTimeMillis() - startTime)
            }
            is ToolParams.FastbootGetVar -> {
                val output = fastbootManager.getVar(params.variable)
                ToolResult.success(output, System.currentTimeMillis() - startTime)
            }
            else -> ToolResult.failure("Invalid params for fastboot_flash")
        }
    }

    override fun validateParams(params: ToolParams): Boolean {
        return when (params) {
            is ToolParams.FastbootFlash -> params.partition.isNotBlank() && params.imagePath.isNotBlank()
            is ToolParams.FastbootErase -> params.partition.isNotBlank()
            is ToolParams.FastbootReboot -> true
            is ToolParams.FastbootGetVar -> params.variable.isNotBlank()
            else -> false
        }
    }
}

@Singleton
class FastbootManager @Inject constructor() {
    private var nativeHandle: Long = 0
    private var currentDevice: UsbDevice? = null

    suspend fun scanDevices(): List<UsbDevice> {
        val json = FastbootNative.scanDevices(nativeHandle)
        return emptyList()
    }

    fun getDevice(): UsbDevice? = currentDevice

    suspend fun flashPartition(partition: String, imagePath: String): String {
        return FastbootNative.flash(nativeHandle, partition, imagePath)
    }

    suspend fun erasePartition(partition: String): String {
        return FastbootNative.erase(nativeHandle, partition)
    }

    suspend fun reboot(mode: String = "normal"): String {
        return FastbootNative.reboot(nativeHandle, mode)
    }

    suspend fun unlockBootloader(): String {
        return FastbootNative.oem(nativeHandle, "unlock")
    }

    suspend fun getVar(name: String): String {
        return FastbootNative.getVar(nativeHandle, name)
    }
}

object FastbootNative {
    init {
        System.loadLibrary("aishell-native")
    }

    external fun init(): Long
    external fun scanDevices(handle: Long): String
    external fun flash(handle: Long, partition: String, image: String): String
    external fun erase(handle: Long, partition: String): String
    external fun reboot(handle: Long, mode: String): String
    external fun oem(handle: Long, command: String): String
    external fun getVar(handle: Long, name: String): String
}