package com.aishell.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.aishell.domain.entity.RiskLevel
import com.aishell.domain.entity.ToolCallRecord
import com.aishell.domain.entity.ToolCallStatus

@Entity(
    tableName = "tool_calls",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("messageId")]
)
data class ToolCallEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val messageId: Long,
    val toolName: String,
    val params: String,
    val result: String? = null,
    val status: ToolCallStatus = ToolCallStatus.PENDING,
    val riskLevel: RiskLevel = RiskLevel.READ_ONLY
) {
    fun toDomain() = ToolCallRecord(id, messageId, toolName, params, result, status, riskLevel)

    companion object {
        fun fromDomain(domain: ToolCallRecord) = ToolCallEntity(
            domain.id, domain.messageId, domain.toolName,
            domain.params, domain.result, domain.status, domain.riskLevel
        )
    }
}