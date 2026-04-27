package com.aishell.domain.repository

import com.aishell.domain.entity.Message
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    suspend fun getByConversation(conversationId: Long): Flow<List<Message>>
    suspend fun insert(message: Message): Long
    suspend fun deleteByConversation(conversationId: Long)
}