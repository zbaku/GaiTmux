package com.aishell.domain.repository

import com.aishell.domain.entity.ToolCallRecord
import com.aishell.domain.entity.ToolCallStatus
import kotlinx.coroutines.flow.Flow

interface ToolCallRepository {
    suspend fun getByMessage(messageId: Long): Flow<List<ToolCallRecord>>
    suspend fun insert(record: ToolCallRecord): Long
    suspend fun updateStatus(id: Long, status: ToolCallStatus, result: String?)
}