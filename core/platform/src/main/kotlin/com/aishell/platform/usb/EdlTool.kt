package com.aishell.platform.usb

import com.aishell.domain.entity.RiskLevel
import com.aishell.domain.tool.Tool
import com.aishell.domain.tool.ToolParams
import com.aishell.domain.tool.ToolResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EdlTool @Inject constructor() : Tool {

    override val name = "edl_flash"
    override val description = "Flash partitions via EDL (Emergency Download Mode) — Qualcomm 9008"
    override val riskLevel = RiskLevel.CRITICAL

    override suspend fun execute(params: ToolParams): ToolResult {
        val edlParams = params as? ToolParams.EdlFlash
            ?: return ToolResult.failure("Invalid params for edl_flash")

        val startTime = System.currentTimeMillis()

        return try {
            // EDL flashing via libusb direct USB communication
            val output = EdlNative.flash(
                partition = edlParams.partition,
                imagePath = edlParams.imagePath
            )
            ToolResult.success(output, System.currentTimeMillis() - startTime)
        } catch (e: Exception) {
            ToolResult.failure(e.message ?: "EDL flash failed")
        }
    }

    override fun validateParams(params: ToolParams): Boolean {
        val edlParams = params as? ToolParams.EdlFlash ?: return false
        return edlParams.partition.isNotBlank() && edlParams.imagePath.isNotBlank()
    }
}

object EdlNative {
    init {
        System.loadLibrary("aishell-native")
    }

    external fun detectEdlDevice(): String  // JSON with device info
    external fun flash(partition: String, imagePath: String): String
    external fun erase(partition: String): String
    external fun reboot(): String
}