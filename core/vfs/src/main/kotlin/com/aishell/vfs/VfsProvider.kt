package com.aishell.vfs

import com.aishell.domain.entity.VfsFile
import com.aishell.domain.entity.VfsScheme
import kotlinx.coroutines.flow.Flow

interface VfsProvider {
    val scheme: VfsScheme

    suspend fun connect(config: VfsConfig): Result<Unit>
    suspend fun disconnect()
    suspend fun isConnected(): Boolean

    suspend fun list(path: String): Flow<VfsFile>
    suspend fun read(path: String): Flow<ByteArray>
    suspend fun write(path: String, data: Flow<ByteArray>): Result<Unit>
    suspend fun delete(path: String): Result<Unit>
    suspend fun mkdir(path: String): Result<Unit>
    suspend fun exists(path: String): Boolean
    suspend fun stat(path: String): VfsFile?
}

data class VfsConfig(
    val scheme: VfsScheme = VfsScheme.FILE,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val shareName: String = "",
    val privateKey: String = "",
)