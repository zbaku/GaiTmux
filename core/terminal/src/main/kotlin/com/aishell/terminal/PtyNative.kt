package com.aishell.terminal

object PtyNative {
    init {
        System.loadLibrary("aishell-native")
    }

    external fun createPty(cols: Int, rows: Int): Long
    external fun destroyPty(handle: Long)
    external fun writePty(handle: Long, data: ByteArray): Int
    external fun readPty(handle: Long, buffer: ByteArray): Int
    external fun resizePty(handle: Long, cols: Int, rows: Int)
}