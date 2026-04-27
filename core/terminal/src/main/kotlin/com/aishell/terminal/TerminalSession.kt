package com.aishell.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TerminalSession(
    private val cols: Int = 80,
    private val rows: Int = 24,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private var handle: Long = 0
    private var readJob: Job? = null

    val buffer = TerminalBuffer(cols, rows)
    val output = Channel<ByteArray>(Channel.UNLIMITED)

    fun start() {
        handle = PtyNative.createPty(cols, rows)

        readJob = scope.launch {
            val readBuffer = ByteArray(4096)
            while (isActive && handle != 0L) {
                try {
                    val n = PtyNative.readPty(handle, readBuffer)
                    if (n > 0) {
                        val data = readBuffer.copyOf(n)
                        buffer.process(data)
                        output.send(data)
                    }
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    fun write(data: ByteArray) {
        if (handle != 0L) {
            PtyNative.writePty(handle, data)
        }
    }

    fun write(text: String) {
        write(text.toByteArray())
    }

    fun resize(newCols: Int, newRows: Int) {
        if (handle != 0L) {
            PtyNative.resizePty(handle, newCols, newRows)
            buffer.resize(newCols, newRows)
        }
    }

    fun destroy() {
        readJob?.cancel()
        if (handle != 0L) {
            PtyNative.destroyPty(handle)
            handle = 0
        }
    }
}