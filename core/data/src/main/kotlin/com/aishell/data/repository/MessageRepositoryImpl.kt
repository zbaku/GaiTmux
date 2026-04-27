package com.aishell.data.repository

import com.aishell.data.local.dao.MessageDao
import com.aishell.data.local.entity.MessageEntity
import com.aishell.domain.entity.Message
import com.aishell.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val dao: MessageDao
) : MessageRepository {

    override suspend fun getByConversation(conversationId: Long): Flow<List<Message>> =
        dao.getByConversation(conversationId).map { entities -> entities.map { it.toDomain() } }

    override suspend fun insert(message: Message): Long =
        dao.insert(MessageEntity.fromDomain(message))

    override suspend fun deleteByConversation(conversationId: Long) =
        dao.deleteByConversation(conversationId)
}