package com.aishell.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.aishell.domain.entity.Message
import com.aishell.domain.entity.MessageRole

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val conversationId: Long,
    val role: MessageRole,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val timestamp: Long
) {
    fun toDomain() = Message(id, conversationId, role, content, toolCallId, toolName, timestamp)

    companion object {
        fun fromDomain(domain: Message) = MessageEntity(
            domain.id, domain.conversationId, domain.role,
            domain.content, domain.toolCallId, domain.toolName, domain.timestamp
        )
    }
}