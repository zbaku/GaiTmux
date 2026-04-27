package com.aishell.domain.entity

import kotlinx.serialization.Serializable

/**
 * Represents the accumulated context for a conversation session.
 * Passed to AgentEngine to maintain state across tool call iterations.
 */
@Serializable
data class ConversationContext(
    val conversationId: Long,
    val providerId: String = "openai",
    val modelId: String = "",
    val confirmationLevel: ConfirmationLevel = ConfirmationLevel.NORMAL,
    val maxIterations: Int = 20,
    val currentIteration: Int = 0,
    val deviceInfo: DeviceInfo = DeviceInfo(),
    val shellCapabilities: ShellCapabilities = ShellCapabilities()
)

@Serializable
data class DeviceInfo(
    val model: String = "",
    val androidVersion: String = "",
    val sdkVersion: Int = 0,
    val isRooted: Boolean = false,
    val hasShizuku: Boolean = false,
    val hasProot: Boolean = false
)

@Serializable
data class ShellCapabilities(
    val supportsAdb: Boolean = false,
    val supportsFastboot: Boolean = false,
    val supportsEdl: Boolean = false,
    val supportsSsh: Boolean = false,
    val supportsProot: Boolean = false,
    val supportsShizuku: Boolean = false
)

@Serializable
enum class ConfirmationLevel {
    LENIENT,
    NORMAL,
    STRICT
}