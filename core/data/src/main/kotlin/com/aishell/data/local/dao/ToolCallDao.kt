package com.aishell.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aishell.data.local.entity.ToolCallEntity
import com.aishell.domain.entity.ToolCallStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ToolCallDao {
    @Query("SELECT * FROM tool_calls WHERE messageId = :messageId")
    fun getByMessage(messageId: Long): Flow<List<ToolCallEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ToolCallEntity): Long

    @Query("UPDATE tool_calls SET status = :status, result = :result WHERE id = :id")
    suspend fun updateStatus(id: Long, status: ToolCallStatus, result: String?)
}