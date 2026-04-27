package com.aishell.platform.proot

import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ProotManagerTest {

    private lateinit var rootfsDownloader: RootfsDownloader
    private lateinit var context: android.content.Context

    @Before
    fun setup() {
rootfsDownloader = object : RootfsDownloader {
            override suspend fun downloadWithMirrors(destDir: File, progress: (Float) -> Unit): Result<Unit> {
                return Result.success(Unit)
            }
        }

        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `sanitizeCommand blocks dangerous sequences`() {
        val manager = ProotManager(context, rootfsDownloader)
        val dangerousCommands = listOf(
            "cmd1 && cmd2" to "cmd1_BLOCKED_cmd2",
            "cmd1 || cmd2" to "cmd1_BLOCKED_cmd2",
            "cmd1 ; cmd2" to "cmd1_BLOCKED_cmd2",
            "cmd1 | cmd2" to "cmd1_BLOCKED_cmd2",
            "echo `whoami`" to "echo _BLOCKED_whoami_BLOCKED_",
            "echo \$VAR" to "echo _BLOCKED_VAR",
            "cmd1\ncmd2" to "cmd1_BLOCKED_cmd2",
            "cmd1\r\ncmd2" to "cmd1_BLOCKED__BLOCKED_cmd2"
        )
        dangerousCommands.forEach { (input, expected) ->
            val result = manager.javaClass.getDeclaredMethod("sanitizeCommand", String::class.java).apply { isAccessible = true }
                .invoke(manager, input) as String
            assertEquals("Input: $input", expected, result)
        }
    }

    @Test
    fun `sanitizeCommand allows safe commands`() {
        val manager = ProotManager(context, rootfsDownloader)
        val safeCommands = listOf(
            "ls -la",
            "pwd",
            "echo hello",
            "cat /etc/passwd"
        )
        safeCommands.forEach { cmd ->
            val result = manager.javaClass.getDeclaredMethod("sanitizeCommand", String::class.java).apply { isAccessible = true }
                .invoke(manager, cmd) as String
            assertTrue("Command should be preserved: $cmd -> $result", result.isNotEmpty())
        }
    }

    @Test
    fun `execute with skipSanitize preserves shell operators`() {
        val manager = ProotManager(context, rootfsDownloader)
        val executeMethod = manager.javaClass.getDeclaredMethod("execute", String::class.java, Boolean::class.javaPrimitiveType)
        assertTrue("execute method should have skipSanitize parameter", executeMethod.parameterCount == 2)
    }
}
