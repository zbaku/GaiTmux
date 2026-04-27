package com.aishell.vfs

import com.aishell.domain.entity.VfsFile
import com.aishell.domain.entity.VfsScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * SMB/CIFS provider — skeleton implementation.
 * Full implementation requires JCIFS or smbj integration.
 */
class SmbProvider : VfsProvider {

    override val scheme = VfsScheme.SMB

    private var connected = false

    override suspend fun connect(config: VfsConfig): Result<Unit> {
        // TODO: Implement SMB connection via JCIFS/smbj
        // config.domain and config.shareName are used for SMB
        return Result.failure(NotImplementedError("SMB provider not yet implemented"))
    }

    override suspend fun disconnect() {
        connected = false
    }

    override suspend fun isConnected(): Boolean = connected

    override suspend fun list(path: String): Flow<VfsFile> = flow<VfsFile> {
        throw NotImplementedError("SMB provider not yet implemented")
    }.flowOn(Dispatchers.IO)

    override suspend fun read(path: String): Flow<ByteArray> = flow<ByteArray> {
        throw NotImplementedError("SMB provider not yet implemented")
    }.flowOn(Dispatchers.IO)

    override suspend fun write(path: String, data: Flow<ByteArray>): Result<Unit> {
        return Result.failure(NotImplementedError("SMB provider not yet implemented"))
    }

    override suspend fun delete(path: String): Result<Unit> {
        return Result.failure(NotImplementedError("SMB provider not yet implemented"))
    }

    override suspend fun mkdir(path: String): Result<Unit> {
        return Result.failure(NotImplementedError("SMB provider not yet implemented"))
    }

    override suspend fun exists(path: String): Boolean = false

    override suspend fun stat(path: String): VfsFile? = null
}