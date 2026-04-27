package com.aishell.vfs

import com.aishell.domain.entity.VfsFile
import com.aishell.domain.entity.VfsScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * MTP (Media Transfer Protocol) provider — skeleton implementation.
 * Full implementation requires Android MTP host API integration.
 */
class MtpProvider : VfsProvider {

    override val scheme = VfsScheme.MTP

    override suspend fun connect(config: VfsConfig): Result<Unit> {
        // TODO: Implement MTP device connection via Android MTP host API
        return Result.failure(NotImplementedError("MTP provider not yet implemented"))
    }

    override suspend fun disconnect() {
        // TODO: Close MTP device connection
    }

    override suspend fun isConnected(): Boolean = false

    override suspend fun list(path: String): Flow<VfsFile> = flow<VfsFile> {
        throw NotImplementedError("MTP provider not yet implemented")
    }.flowOn(Dispatchers.IO)

    override suspend fun read(path: String): Flow<ByteArray> = flow<ByteArray> {
        throw NotImplementedError("MTP provider not yet implemented")
    }.flowOn(Dispatchers.IO)

    override suspend fun write(path: String, data: Flow<ByteArray>): Result<Unit> {
        return Result.failure(NotImplementedError("MTP provider not yet implemented"))
    }

    override suspend fun delete(path: String): Result<Unit> {
        return Result.failure(NotImplementedError("MTP provider not yet implemented"))
    }

    override suspend fun mkdir(path: String): Result<Unit> {
        return Result.failure(NotImplementedError("MTP provider not yet implemented"))
    }

    override suspend fun exists(path: String): Boolean = false

    override suspend fun stat(path: String): VfsFile? = null
}