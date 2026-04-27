package com.aishell.platform.proot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import javax.inject.Inject

interface RootfsDownloader {
    suspend fun downloadWithMirrors(destDir: File, progress: (Float) -> Unit): Result<Unit>
}

class DefaultRootfsDownloader @Inject constructor(
    private val client: OkHttpClient
) : RootfsDownloader {
    private val mirrors = listOf(
        RootfsUrls.UBUNTU_NOBLE_MIRROR_TUNA  // 清华 TUNA（Ubuntu 24.04 Noble）
    )

    /** XZ 压缩比估算值（解压后大小 / 压缩后大小） */
    private companion object {
        const val XZ_COMPRESSION_RATIO = 4.0f
    }

    suspend fun download(
        url: String,
        destDir: File,
        progress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("下载失败: ${response.code}")
            }

            val body = response.body ?: throw Exception("响应体为空")
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            val tempFile = File(destDir.parent, "rootfs.tar.xz")
            // 删除可能存在的旧临时文件
            if (tempFile.exists()) {
                tempFile.delete()
            }

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        if (totalBytes > 0) {
                            withContext(Dispatchers.Main) {
                                progress(downloadedBytes.toFloat() / totalBytes * 0.5f)
                            }
                        }
                    }
                }
            }

            progress(0.5f)

            // 使用压缩比估算解压后总大小，避免双重扫描
            val estimatedTotalSize = if (totalBytes > 0) {
                (totalBytes * XZ_COMPRESSION_RATIO).toLong().coerceAtLeast(1L)
            } else {
                // 无法估算时设为 1，避免除零
                1L
            }

            extractTarXz(tempFile, destDir, estimatedTotalSize) { extractedBytes ->
                progress(0.5f + (extractedBytes.toFloat() / estimatedTotalSize * 0.5f))
            }

            tempFile.delete()
            progress(1.0f)
        }
    }

    override suspend fun downloadWithMirrors(
      destDir: File,
      progress: (Float) -> Unit
  ): Result<Unit> {
      for (url in mirrors) {
          try {
              download(url, destDir, progress)
              return Result.success(Unit)
          } catch (e: Exception) {
              continue
          }
      }
      return Result.failure(Exception("所有下载源都失败了"))
  }

    private fun extractTarXz(
        tarFile: File,
        destDir: File,
        totalSize: Long,
        onProgress: (Long) -> Unit
    ) {
        destDir.mkdirs()

        XZCompressorInputStream(tarFile.inputStream()).use { xzInput ->
            val tar = TarArchiveInputStream(xzInput)

            tar.use { tarInput ->
                var entry = tarInput.nextTarEntry
                var extractedBytes = 0L

                while (entry != null) {
                    // 安全检查：防止路径遍历攻击
                    val entryName = entry.name
                    if (entryName.contains("..") || entryName.startsWith("/")) {
                        // 跳过危险路径
                        entry = tarInput.nextTarEntry
                        continue
                    }

                    val file = File(destDir, entryName)

                    // 二次安全检查：确保文件不会写到 destDir 之外
                    val canonicalDestDir = destDir.canonicalPath
                    val canonicalFile = file.canonicalPath
                    if (!canonicalFile.startsWith(canonicalDestDir + File.separator)) {
                        entry = tarInput.nextTarEntry
                        continue
                    }

                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { fos ->
                            tarInput.copyTo(fos)
                        }
                        extractedBytes += entry.size
                        onProgress(extractedBytes.coerceAtMost(totalSize))
                    }

                    entry = tarInput.nextTarEntry
                }
            }
        }
    }
}

object RootfsUrls {
    // Ubuntu Noble (24.04 LTS) ARM64 - 清华 TUNA
    const val UBUNTU_NOBLE_MIRROR_TUNA =
        "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cloud-images/noble/current/" +
        "noble-server-cloudimg-arm64-root.tar.xz"
}