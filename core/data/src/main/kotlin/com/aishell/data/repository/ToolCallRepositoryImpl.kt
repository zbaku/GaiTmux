package com.aishell.data.repository

import com.aishell.data.local.dao.ToolCallDao
import com.aishell.data.local.entity.ToolCallEntity
import com.aishell.domain.entity.ToolCallRecord
import com.aishell.domain.entity.ToolCallStatus
import com.aishell.domain.repository.ToolCallRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolCallRepositoryImpl @Inject constructor(
    private val dao: ToolCallDao
) : ToolCallRepository {

    override suspend fun getByMessage(messageId: Long): Flow<List<ToolCallRecord>> =
        dao.getByMessage(messageId).map { entities -> entities.map { it.toDomain() } }

    override suspend fun insert(record: ToolCallRecord): Long =
        dao.insert(ToolCallEntity.fromDomain(record))

    override suspend fun updateStatus(id: Long, status: ToolCallStatus, result: String?) =
        dao.updateStatus(id, status, result)
}