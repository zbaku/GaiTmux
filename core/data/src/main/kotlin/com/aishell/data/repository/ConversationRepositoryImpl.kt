package com.aishell.data.repository

import com.aishell.data.local.dao.ConversationDao
import com.aishell.data.local.entity.ConversationEntity
import com.aishell.domain.entity.Conversation
import com.aishell.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val dao: ConversationDao
) : ConversationRepository {

    override suspend fun getAll(): Flow<List<Conversation>> =
        dao.getAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getById(id: Long): Conversation? =
        dao.getById(id)?.toDomain()

    override suspend fun insert(conversation: Conversation): Long =
        dao.insert(ConversationEntity.fromDomain(conversation))

    override suspend fun update(conversation: Conversation) =
        dao.update(ConversationEntity.fromDomain(conversation))

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun search(query: String): Flow<List<Conversation>> =
        dao.search("%$query%").map { entities -> entities.map { it.toDomain() } }
}