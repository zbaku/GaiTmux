package com.aishell.domain.entity

import kotlinx.serialization.Serializable

enum class MessageRole { USER, ASSISTANT, SYSTEM, TOOL }

@Serializable
data class Message(
    val id: Long = 0,
    val conversationId: Long,
    val role: MessageRole,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)