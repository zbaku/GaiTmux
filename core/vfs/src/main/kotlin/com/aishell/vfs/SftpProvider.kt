package com.aishell.vfs

import com.aishell.domain.entity.VfsFile
import com.aishell.domain.entity.VfsScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

class SftpProvider @Inject constructor() : VfsProvider {

    override val scheme = VfsScheme.SFTP
    private var handle: Long = 0

    override suspend fun connect(config: VfsConfig): Result<Unit> {
        return try {
            handle = SftpNative.connect(
                host = config.host,
                port = config.port,
                username = config.username,
                password = config.password,
                privateKey = config.privateKey
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun disconnect() {
        if (handle != 0L) {
            SftpNative.disconnect(handle)
            handle = 0
        }
    }

    override suspend fun isConnected(): Boolean = handle != 0L

    override suspend fun list(path: String): Flow<VfsFile> = flow<VfsFile> {
        if (handle == 0L) throw Exception("Not connected")
        val json = SftpNative.listDir(handle, path)
        // Parse JSON and emit VfsFile entries
        // Full implementation with kotlinx.serialization
    }.flowOn(Dispatchers.IO)

    override suspend fun read(path: String): Flow<ByteArray> = flow {
        if (handle == 0L) throw Exception("Not connected")
        val data = SftpNative.readFile(handle, path)
        emit(data)
    }.flowOn(Dispatchers.IO)

    override suspend fun write(path: String, data: Flow<ByteArray>): Result<Unit> {
        if (handle == 0L) return Result.failure(Exception("Not connected"))
        return try {
            SftpNative.startWrite(handle, path)
            data.collect { chunk -> SftpNative.writeChunk(handle, chunk) }
            SftpNative.endWrite(handle)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun delete(path: String): Result<Unit> {
        if (handle == 0L) return Result.failure(Exception("Not connected"))
        return try {
            SftpNative.delete(handle, path)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun mkdir(path: String): Result<Unit> {
        if (handle == 0L) return Result.failure(Exception("Not connected"))
        return try {
            SftpNative.mkdir(handle, path)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun exists(path: String): Boolean {
        if (handle == 0L) return false
        return SftpNative.exists(handle, path)
    }

    override suspend fun stat(path: String): VfsFile? {
        if (handle == 0L) return null
        val json = SftpNative.stat(handle, path)
        // Parse JSON to VfsFile
        return null
    }
}

object SftpNative {
    init {
        System.loadLibrary("aishell-native")
    }

    external fun connect(host: String, port: Int, username: String, password: String, privateKey: String): Long
    external fun disconnect(handle: Long)
    external fun listDir(handle: Long, path: String): String
    external fun readFile(handle: Long, path: String): ByteArray
    external fun startWrite(handle: Long, path: String)
    external fun writeChunk(handle: Long, data: ByteArray)
    external fun endWrite(handle: Long)
    external fun delete(handle: Long, path: String)
    external fun mkdir(handle: Long, path: String)
    external fun exists(handle: Long, path: String): Boolean
    external fun stat(handle: Long, path: String): String
}