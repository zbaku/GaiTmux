package com.aishell.domain.entity

import kotlinx.serialization.Serializable

@Serializable
data class Conversation(
    val id: Long = 0,
    val title: String,
    val providerId: String = "openai",
    val modelId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)