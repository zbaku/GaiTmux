package com.aishell.domain.repository

import com.aishell.domain.entity.Conversation
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    suspend fun getAll(): Flow<List<Conversation>>
    suspend fun getById(id: Long): Conversation?
    suspend fun insert(conversation: Conversation): Long
    suspend fun update(conversation: Conversation)
    suspend fun delete(id: Long)
    suspend fun search(query: String): Flow<List<Conversation>>
}