package com.aishell.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.aishell.domain.entity.Conversation

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val providerId: String,
    val modelId: String,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toDomain() = Conversation(id, title, providerId, modelId, createdAt, updatedAt)

    companion object {
        fun fromDomain(domain: Conversation) = ConversationEntity(
            domain.id, domain.title, domain.providerId,
            domain.modelId, domain.createdAt, domain.updatedAt
        )
    }
}