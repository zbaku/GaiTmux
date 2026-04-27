package com.aishell.platform.usb.model

enum class UsbDeviceState {
    DISCONNECTED,
    CONNECTED,
    AUTHORIZED,
    UNAUTHORIZED,
    UNKNOWN
}

enum class UsbDeviceMode {
    ADB,
    FASTBOOT,
    EDL,
    MTP,
    SERIAL,
}

data class UsbDevice(
    val deviceId: String,
    val vendorId: Int,
    val productId: Int,
    val vendorName: String? = null,
    val productName: String? = null,
    val serialNumber: String? = null,
    val mode: UsbDeviceMode = UsbDeviceMode.ADB,
    val state: UsbDeviceState = UsbDeviceState.DISCONNECTED,
) {
    companion object {
        // Qualcomm
        const val QUALCOMM_VID = 0x05C6
        const val QUALCOMM_EDL_PID = 0x9008
        const val QUALCOMM_FASTBOOT_PID = 0x0016

        // MediaTek
        const val MEDIATEK_VID = 0x0E8D
        const val MEDIATEK_PRELOADER_PID = 0x0003

        // Samsung
        const val SAMSUNG_VID = 0x04E8

        // Xiaomi
        const val XIAOMI_VID = 0x2717

        // Google
        const val GOOGLE_VID = 0x18D1

        fun isEdlMode(vid: Int, pid: Int): Boolean =
            vid == QUALCOMM_VID && pid == QUALCOMM_EDL_PID

        fun isFastbootMode(vid: Int, pid: Int): Boolean =
            pid == QUALCOMM_FASTBOOT_PID || pid == MEDIATEK_PRELOADER_PID

        fun detectMode(vid: Int, pid: Int): UsbDeviceMode = when {
            isEdlMode(vid, pid) -> UsbDeviceMode.EDL
            isFastbootMode(vid, pid) -> UsbDeviceMode.FASTBOOT
            else -> UsbDeviceMode.ADB
        }
    }
}