package com.aishell.platform.usb.model

data class FlashResult(
    val success: Boolean,
    val partition: String,
    val message: String,
    val duration: Long = 0
) {
    companion object {
        fun ok(partition: String, duration: Long = 0) = FlashResult(
            success = true,
            partition = partition,
            message = "Flashed $partition successfully",
            duration = duration
        )

        fun fail(partition: String, error: String) = FlashResult(
            success = false,
            partition = partition,
            message = error
        )
    }
}