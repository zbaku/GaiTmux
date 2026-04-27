package com.aishell.platform.proot

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProotManager @Inject constructor(
    private val context: Context,
    private val rootfsDownloader: RootfsDownloader
) {
    private val rootfsDir by lazy {
        File(context.filesDir, "ubuntu")
    }

    private val prootFile by lazy {
        File(context.filesDir, "proot").also { file ->
            if (!file.exists()) {
                extractProotFromAssets(file)
            }
            file.setExecutable(true)
        }
    }

    /** 从 assets 提取 proot */
    private fun extractProotFromAssets(destFile: File) {
        context.assets.open("proot").use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    suspend fun isReady(): Boolean {
        return File(rootfsDir, "bin/bash").exists()
    }

    suspend fun install(progress: (Float) -> Unit): Result<Unit> {
        return try {
            rootfsDir.parentFile?.mkdirs()

            rootfsDownloader.downloadWithMirrors(
                destDir = rootfsDir,
                progress = progress
            )

            // 运行初始化脚本
            val initResult = runInitScript()
            if (initResult.isFailure) {
                return Result.failure(initResult.exceptionOrNull() ?: Exception("初始化失败"))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 运行初始化脚本 */
    private suspend fun runInitScript(): Result<Unit> {
        val initScript = extractInitScript()
        return try {
            val result = StringBuilder()
            execute("chmod +x '$initScript' && '$initScript'", skipSanitize = true).collect { line ->
                if (line.startsWith("[EXIT:")) {
                    val code = line.removePrefix("[EXIT:").removeSuffix("]").toInt()
                    if (code != 0) {
                        throw RuntimeException("初始化脚本退出码: $code\n$result")
                    }
                } else {
                    Log.i(TAG, "[init] $line")
                    result.appendLine(line)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "初始化脚本执行失败", e)
            Result.failure(e)
        }
    }

    /** 从 assets 提取初始化脚本到 rootfs */
    private fun extractInitScript(): String {
        val scriptFile = File(rootfsDir, "init-aishell.sh")
        if (!scriptFile.exists()) {
            context.assets.open("scripts/init.sh").use { input ->
                FileOutputStream(scriptFile).use { output ->
                    input.copyTo(output)
                }
            }
            scriptFile.setExecutable(true)
        }
        return scriptFile.absolutePath
    }

    fun execute(command: String, skipSanitize: Boolean = false): Flow<String> = flow {
        val cmd = if (skipSanitize) command else sanitizeCommand(command)
        val process = ProcessBuilder()
            .command(
                prootFile.absolutePath,
                "-0",                             // 假装 root
                "-r", rootfsDir.absolutePath,     // rootfs 路径
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "/bin/sh", "-c", cmd
            )
            .redirectErrorStream(true)
            .start()

        process.inputStream.bufferedReader().use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                emit(line!!)
            }
        }

        val exitCode = process.waitFor()
        // 将退出码作为最后一行 emit，由调用方决定如何处理
        emit("[EXIT:$exitCode]")
    }

    /** 清理命令，防止注入攻击 */
    private fun sanitizeCommand(command: String): String {
        val result = StringBuilder()
        var i = 0
        while (i < command.length) {
            // 检查双字符运算符
            val remaining = command.length - i
            if (remaining >= 2) {
                val twoChar = command.substring(i, i + 2)
                when (twoChar) {
                    "&&", "||" -> {
                        // 跳过前面的空格
                        while (result.isNotEmpty() && result.last() == ' ') {
                            result.deleteCharAt(result.length - 1)
                        }
                        result.append("_BLOCKED_")
                        i += 2
                        // 跳过后面的空格
                        while (i < command.length && command[i] == ' ') i++
                        continue
                    }
                }
            }
            // 检查单字符危险字符
            when (command[i]) {
                '|', ';' -> {
                    // 跳过前面的空格
                    while (result.isNotEmpty() && result.last() == ' ') {
                        result.deleteCharAt(result.length - 1)
                    }
                    result.append("_BLOCKED_")
                    i++
                    // 跳过后面的空格
                    while (i < command.length && command[i] == ' ') i++
                }
                '`', '$', '\n', '\r' -> {
                    // 不消费空格，直接替换
                    result.append("_BLOCKED_")
                    i++
                }
                else -> {
                    result.append(command[i])
                    i++
                }
            }
        }
        return result.toString()
    }

    companion object {
        private const val TAG = "ProotManager"
    }
}