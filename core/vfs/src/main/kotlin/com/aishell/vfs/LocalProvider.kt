package com.aishell.vfs

import com.aishell.domain.entity.VfsFile
import com.aishell.domain.entity.VfsScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import javax.inject.Inject

class LocalProvider @Inject constructor() : VfsProvider {

    override val scheme = VfsScheme.FILE

    override suspend fun connect(config: VfsConfig) = Result.success(Unit)
    override suspend fun disconnect() {}
    override suspend fun isConnected() = true

    override suspend fun list(path: String): Flow<VfsFile> = flow {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return@flow

        dir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { file ->
            emit(VfsFile(
                path = file.absolutePath,
                name = file.name,
                isDirectory = file.isDirectory,
                size = if (file.isFile) file.length() else 0,
                modifiedAt = file.lastModified(),
                permissions = getPermissions(file),
                mimeType = getMimeType(file)
            ))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun read(path: String): Flow<ByteArray> = flow {
        val file = File(path)
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                emit(buffer.copyOf(read))
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun write(path: String, data: Flow<ByteArray>): Result<Unit> {
        return try {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.outputStream().use { output ->
                data.collect { chunk -> output.write(chunk) }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun delete(path: String) = try {
        File(path).deleteRecursively()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun mkdir(path: String) = try {
        File(path).mkdirs()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun exists(path: String) = File(path).exists()

    override suspend fun stat(path: String): VfsFile? {
        val file = File(path)
        return if (file.exists()) VfsFile(
            path = file.absolutePath,
            name = file.name,
            isDirectory = file.isDirectory,
            size = if (file.isFile) file.length() else 0,
            modifiedAt = file.lastModified(),
            permissions = getPermissions(file)
        ) else null
    }

    private fun getPermissions(file: File): String {
        return "${if (file.canRead()) "r" else "-"}${if (file.canWrite()) "w" else "-"}${if (file.canExecute()) "x" else "-"}"
    }

    private fun getMimeType(file: File): String? {
        return java.net.URLConnection.guessContentTypeFromName(file.name)
    }
}