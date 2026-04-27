# VFS + USB Tools 实现细节

> Created: 2026-04-25
> Part of: aishell-implementation-v4 (Task 12-13)

## Task 12: VFS (虚拟文件系统) + 文件工具

### 模块结构

```
core/vfs/src/main/kotlin/com/aishell/vfs/
├── VfsProvider.kt          # VFS 接口
├── LocalProvider.kt        # 本地文件系统
├── SftpProvider.kt         # SSH/SFTP 远程
├── SmbProvider.kt          # SMB/CIFS 共享
├── VfsManager.kt           # 统一管理器
└── model/
    ├── VfsFile.kt          # 文件模型
    └── VfsPath.kt          # 路径模型

core/vfs/src/main/rust/src/
├── vfs/
│   ├── mod.rs
│   ├── sftp.rs             # Russh-based SFTP
│   └── smb.rs              # SMB2/3 (custom FFI)
└── jni/
    └── vfs_jni.rs          # JNI 桥接
```

---

### Step 1: VfsProvider 接口

```kotlin
// vfs/VfsProvider.kt
package com.aishell.vfs

import com.aishell.vfs.model.VfsFile
import kotlinx.coroutines.flow.Flow

enum class VfsScheme {
    FILE,       // 本地文件
    SFTP,       // SSH/SFTP
    SMB,        // SMB/CIFS
    MTP,        // MTP 设备
}

interface VfsProvider {
    val scheme: VfsScheme
    
    // 连接
    suspend fun connect(config: VfsConfig): Result<Unit>
    suspend fun disconnect()
    suspend fun isConnected(): Boolean
    
    // 文件操作
    suspend fun list(path: String): Flow<VfsFile>
    suspend fun read(path: String): Flow<ByteArray>
    suspend fun write(path: String, data: Flow<ByteArray>): Result<Unit>
    suspend fun delete(path: String): Result<Unit>
    suspend fun mkdir(path: String): Result<Unit>
    suspend fun exists(path: String): Boolean
    
    // 文件信息
    suspend fun stat(path: String): VfsFile?
}

data class VfsConfig(
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    val domain: String = "",      // SMB domain
    val shareName: String = "",   // SMB share
    val privateKey: String = "",  // SSH key
)
```

---

### Step 2: VfsFile 模型

```kotlin
// model/VfsFile.kt
package com.aishell.vfs.model

import kotlinx.serialization.Serializable

@Serializable
data class VfsFile(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long,
    val permissions: String = "",
    val mimeType: String? = null,
) {
    val extension: String
        get() = name.substringAfterLast('.', "")

    val isHidden: Boolean
        get() = name.startsWith('.')
}

// 便捷构造函数
fun VfsFile.directory(path: String, name: String) = VfsFile(
    path = path,
    name = name,
    isDirectory = true,
    size = 0,
    modifiedAt = System.currentTimeMillis()
)

fun VfsFile.file(path: String, name: String, size: Long) = VfsFile(
    path = path,
    name = name,
    isDirectory = false,
    size = size,
    modifiedAt = System.currentTimeMillis()
)
```

---

### Step 3: LocalProvider (本地文件系统)

```kotlin
// vfs/LocalProvider.kt
package com.aishell.vfs

import android.content.Context
import com.aishell.vfs.model.VfsFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import javax.inject.Inject

class LocalProvider @Inject constructor(
    private val context: Context
) : VfsProvider {

    override val scheme = VfsScheme.FILE

    override suspend fun connect(config: VfsConfig) = Result.success(Unit)
    override suspend fun disconnect() {}
    override suspend fun isConnected() = true

    override suspend fun list(path: String): Flow<VfsFile> = flow {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) {
            return@flow
        }

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
                data.collect { chunk ->
                    output.write(chunk)
                }
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
            modifiedAt = file.lastModified()
        ) else null
    }

    private fun getPermissions(file: File): String {
        var perms = ""
        perms += if (file.canRead()) "r" else "-"
        perms += if (file.canWrite()) "w" else "-"
        perms += if (file.canExecute()) "x" else "-"
        return perms
    }

    private fun getMimeType(file: File): String? {
        return java.net.URLConnection.guessContentTypeFromName(file.name)
    }
}
```

---

### Step 4: SftpProvider (SSH/SFTP)

```kotlin
// vfs/SftpProvider.kt
package com.aishell.vfs

import com.aishell.vfs.model.VfsFile
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

    override suspend fun list(path: String): Flow<VfsFile> = flow {
        if (handle == 0L) throw Exception("未连接")

        val json = SftpNative.listDir(handle, path)
        // 解析 JSON 并 emit VfsFile
    }.flowOn(Dispatchers.IO)

    override suspend fun read(path: String): Flow<ByteArray> = flow {
        if (handle == 0L) throw Exception("未连接")

        SftpNative.readFile(handle, path) { data ->
            emit(data)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun write(path: String, data: Flow<ByteArray>): Result<Unit> {
        if (handle == 0L) return Result.failure(Exception("未连接"))

        return try {
            SftpNative.startWrite(handle, path)
            data.collect { chunk ->
                SftpNative.writeChunk(handle, chunk)
            }
            SftpNative.endWrite(handle)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun delete(path: String): Result<Unit> {
        if (handle == 0L) return Result.failure(Exception("未连接"))
        return try {
            SftpNative.delete(handle, path)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun mkdir(path: String): Result<Unit> {
        if (handle == 0L) return Result.failure(Exception("未连接"))
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
        // 解析 JSON 返回 VfsFile
        return null // TODO
    }
}

// JNI 声明
object SftpNative {
    init {
        System.loadLibrary("aishell-native")
    }

    external fun connect(host: String, port: Int, username: String, password: String, privateKey: String): Long
    external fun disconnect(handle: Long)
    external fun listDir(handle: Long, path: String): String
    external fun readFile(handle: Long, path: String, callback: (ByteArray) -> Unit)
    external fun startWrite(handle: Long, path: String)
    external fun writeChunk(handle: Long, data: ByteArray)
    external fun endWrite(handle: Long)
    external fun delete(handle: Long, path: String)
    external fun mkdir(handle: Long, path: String)
    external fun exists(handle: Long, path: String): Boolean
    external fun stat(handle: Long, path: String): String
}
```

---

### Step 5: Rust SFTP 实现

```rust
// src/vfs/sftp.rs
use russh::*;
use russh_sftp::*;
use std::sync::Arc;
use tokio::runtime::Runtime;

pub struct SftpClient {
    session: Handle<Client>,
    sftp: Sftp,
    rt: Runtime,
}

impl SftpClient {
    pub async fn connect(
        host: &str,
        port: u16,
        username: &str,
        password: Option<&str>,
        private_key: Option<&str>,
    ) -> Result<Self, String> {
        let rt = Runtime::new().map_err(|e| e.to_string())?;

        let config = russh::client::Config::default();
        let mut session = rt.block_on(async {
            russh::client::connect(
                Arc::new(config),
                host,
                port,
                username,
                russh::client::Authenticate::Password(password.unwrap_or("").to_string()),
            ).await
        }).map_err(|e| e.to_string())?;

        let sftp = rt.block_on(async {
            russh_sftp::client::Sftp::from_session(&mut session).await
        }).map_err(|e| e.to_string())?;

        Ok(Self { session, sftp, rt })
    }

    pub fn list_dir(&self, path: &str) -> Result<Vec<FileStat>, String> {
        self.rt.block_on(async {
            self.sftp.readdir(path).await
        }).map_err(|e| e.to_string())
    }

    pub fn read_file(&self, path: &str) -> Result<Vec<u8>, String> {
        self.rt.block_on(async {
            use async_compression::tokio::bufread::ZlibDecoder;
            let mut file = self.sftp.open(path).await.map_err(|e| e.to_string())?;
            let mut contents = Vec::new();
            file.read_to_end(&mut contents).await.map_err(|e| e.to_string())?;
            Ok(contents)
        })
    }

    pub fn write_file(&self, path: &str, data: &[u8]) -> Result<(), String> {
        self.rt.block_on(async {
            let mut file = self.sftp.create(path).await.map_err(|e| e.to_string())?;
            file.write_all(data).await.map_err(|e| e.to_string())
        })
    }

    pub fn disconnect(&mut self) {
        self.rt.block_on(async {
            let _ = self.session.disconnect(None, "", None).await;
        });
    }
}
```

---

### Step 6: VfsManager (统一管理器)

```kotlin
// vfs/VfsManager.kt
package com.aishell.vfs

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VfsManager @Inject constructor(
    private val localProvider: LocalProvider,
    // 其他 provider 通过工厂创建
) {
    private val providers = mutableMapOf<VfsScheme, VfsProvider>()

    init {
        providers[VfsScheme.FILE] = localProvider
    }

    fun getProvider(scheme: VfsScheme): VfsProvider? = providers[scheme]

    suspend fun getOrCreateProvider(config: VfsConfig): VfsProvider? {
        return when (config.scheme) {
            VfsScheme.FILE -> localProvider
            VfsScheme.SFTP -> {
                providers.getOrPut(VfsScheme.SFTP) {
                    SftpProvider().apply { connect(config) }
                }
            }
            VfsScheme.SMB -> {
                providers.getOrPut(VfsScheme.SMB) {
                    SmbProvider().apply { connect(config) }
                }
            }
            else -> null
        }
    }

    // 根据路径自动选择 provider
    fun parsePath(path: String): Pair<VfsProvider?, String> {
        return when {
            path.startsWith("sftp://") -> {
                val p = path.removePrefix("sftp://")
                providers[VfsScheme.SFTP] to p
            }
            path.startsWith("smb://") -> {
                val p = path.removePrefix("smb://")
                providers[VfsScheme.SMB] to p
            }
            else -> localProvider to path
        }
    }
}
```

---

## Task 13: USB/ADB/Fastboot Tools

### 模块结构

```
core/platform/src/main/kotlin/com/aishell/platform/usb/
├── UsbManager.kt           # USB 设备管理
├── AdbTool.kt              # ADB 工具
├── FastbootTool.kt         # Fastboot 工具
├── EdlTool.kt              # EDL 模式
└── model/
    ├── UsbDevice.kt        # 设备模型
    └── FlashResult.kt      # 刷机结果

core/platform/src/main/rust/src/usb/
├── mod.rs
├── adb.rs                  # ADB 协议
├── fastboot.rs             # Fastboot 协议
├── edl.rs                  # EDL 模式
└── libusb.rs               # libusb 绑定
```

---

### Step 1: UsbDevice 模型

```kotlin
// usb/model/UsbDevice.kt
package com.aishell.platform.usb.model

enum class UsbDeviceState {
    DISCONNECTED,
    CONNECTED,
    AUTHORIZED,
    UNAUTHORIZED,
    UNKNOWN
}

enum class UsbDeviceMode {
    ADB,        // Android Debug Bridge
    FASTBOOT,   // Fastboot 模式
    EDL,        // Emergency Download Mode
    MTP,        // Media Transfer Protocol
    SERIAL,     // 串口模式
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
    // 常见设备 VID/PID
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

        // 判断是否为 EDL 模式
        fun isEdlMode(vid: Int, pid: Int): Boolean =
            vid == QUALCOMM_VID && pid == QUALCOMM_EDL_PID

        // 判断是否为 Fastboot 模式
        fun isFastbootMode(vid: Int, pid: Int): Boolean =
            pid == QUALCOMM_FASTBOOT_PID ||
            pid == MEDIATEK_PRELOADER_PID
    }
}
```

---

### Step 2: AdbTool

```kotlin
// usb/AdbTool.kt
package com.aishell.platform.usb

import com.aishell.domain.tool.*
import com.aishell.platform.usb.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AdbTool @Inject constructor(
    private val adbManager: AdbManager
) : Tool {

    override val name = "adb_exec"
    override val description = "Execute ADB commands on connected Android devices"
    override val riskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(params: ToolParams): ToolResult {
        val adbParams = params as? ToolParams.AdbExec
            ?: return ToolResult.failure("Invalid params for adb_exec")

        val startTime = System.currentTimeMillis()

        return try {
            val device = adbManager.getDevice(adbParams.deviceId)
                ?: return ToolResult.failure("Device not found: ${adbParams.deviceId}")

            val output = adbManager.executeCommand(device, adbParams.command)
            val duration = System.currentTimeMillis() - startTime

            ToolResult.success(output, duration)
        } catch (e: Exception) {
            ToolResult.failure(e.message ?: "ADB execution failed")
        }
    }

    override fun validateParams(params: ToolParams): Boolean {
        return params is ToolParams.AdbExec && params.command.isNotBlank()
    }
}

@Singleton
class AdbManager @Inject constructor() {
    private val devices = mutableMapOf<String, UsbDevice>()
    private var nativeHandle: Long = 0

    // 扫描设备
    suspend fun scanDevices(): List<UsbDevice> {
        val json = AdbNative.scanDevices(nativeHandle)
        // 解析 JSON 并更新 devices
        return devices.values.toList()
    }

    // 获取设备
    fun getDevice(deviceId: String?): UsbDevice? {
        return if (deviceId == null) devices.values.firstOrNull()
        else devices[deviceId]
    }

    // 执行命令
    suspend fun executeCommand(device: UsbDevice, command: String): String {
        return AdbNative.execute(nativeHandle, device.deviceId, command)
    }

    // 推送文件
    suspend fun pushFile(device: UsbDevice, localPath: String, remotePath: String): String {
        return AdbNative.push(nativeHandle, device.deviceId, localPath, remotePath)
    }

    // 拉取文件
    suspend fun pullFile(device: UsbDevice, remotePath: String, localPath: String): String {
        return AdbNative.pull(nativeHandle, device.deviceId, remotePath, localPath)
    }

    // 安装 APK
    suspend fun installApk(device: UsbDevice, apkPath: String): String {
        return AdbNative.install(nativeHandle, device.deviceId, apkPath)
    }

    // 刷入分区
    suspend fun flashPartition(device: UsbDevice, partition: String, imagePath: String): String {
        return executeCommand(device, "dd if=$imagePath of=/dev/block/by-name/$partition")
    }

    // 重启到指定模式
    suspend fun reboot(device: UsbDevice, mode: String): String {
        return AdbNative.reboot(nativeHandle, device.deviceId, mode)
    }
}

// JNI 声明
object AdbNative {
    init {
        System.loadLibrary("aishell-native")
    }

    external fun init(): Long
    external fun scanDevices(handle: Long): String
    external fun execute(handle: Long, deviceId: String, command: String): String
    external fun push(handle: Long, deviceId: String, local: String, remote: String): String
    external fun pull(handle: Long, deviceId: String, remote: String, local: String): String
    external fun install(handle: Long, deviceId: String, apk: String): String
    external fun reboot(handle: Long, deviceId: String, mode: String): String
}
```

---

### Step 3: FastbootTool

```kotlin
// usb/FastbootTool.kt
package com.aishell.platform.usb

import com.aishell.domain.tool.*
import com.aishell.platform.usb.model.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FastbootTool @Inject constructor(
    private val fastbootManager: FastbootManager
) : Tool {

    override val name = "fastboot_flash"
    override val description = "Flash partitions using Fastboot protocol"
    override val riskLevel = RiskLevel.DESTRUCTIVE  // 刷机是危险操作

    override suspend fun execute(params: ToolParams): ToolResult {
        val fbParams = params as? ToolParams.FastbootFlash
            ?: return ToolResult.failure("Invalid params for fastboot_flash")

        return try {
            val device = fastbootManager.getDevice()
                ?: return ToolResult.failure("No device in fastboot mode")

            when {
                fbParams.partition == "boot" -> fastbootManager.flashBoot(fbParams.imagePath)
                fbParams.partition == "recovery" -> fastbootManager.flashRecovery(fbParams.imagePath)
                fbParams.partition == "system" -> fastbootManager.flashSystem(fbParams.imagePath)
                else -> fastbootManager.flashPartition(fbParams.partition, fbParams.imagePath)
            }

            ToolResult.success("Flashed ${fbParams.partition} successfully")
        } catch (e: Exception) {
            ToolResult.failure(e.message ?: "Fastboot flash failed")
        }
    }

    override fun validateParams(params: ToolParams): Boolean {
        val fbParams = params as? ToolParams.FastbootFlash ?: return false
        return fbParams.partition.isNotBlank() && fbParams.imagePath.isNotBlank()
    }
}

@Singleton
class FastbootManager @Inject constructor() {
    private var nativeHandle: Long = 0
    private var currentDevice: UsbDevice? = null

    suspend fun scanDevices(): List<UsbDevice> {
        val json = FastbootNative.scanDevices(nativeHandle)
        // 解析并返回 Fastboot 设备列表
        return emptyList()
    }

    fun getDevice(): UsbDevice? = currentDevice

    suspend fun flashBoot(imagePath: String): String {
        return FastbootNative.flash(nativeHandle, "boot", imagePath)
    }

    suspend fun flashRecovery(imagePath: String): String {
        return FastbootNative.flash(nativeHandle, "recovery", imagePath)
    }

    suspend fun flashSystem(imagePath: String): String {
        return FastbootNative.flash(nativeHandle, "system", imagePath)
    }

    suspend fun flashPartition(partition: String, imagePath: String): String {
        return FastbootNative.flash(nativeHandle, partition, imagePath)
    }

    suspend fun erasePartition(partition: String): String {
        return FastbootNative.erase(nativeHandle, partition)
    }

    suspend fun reboot(mode: String = "normal"): String {
        return FastbootNative.reboot(nativeHandle, mode)
    }

    suspend fun unlockBootloader(): String {
        return FastbootNative.oem(nativeHandle, "unlock")
    }

    suspend fun getVar(name: String): String {
        return FastbootNative.getVar(nativeHandle, name)
    }
}

object FastbootNative {
    init {
        System.loadLibrary("aishell-native")
    }

    external fun init(): Long
    external fun scanDevices(handle: Long): String
    external fun flash(handle: Long, partition: String, image: String): String
    external fun erase(handle: Long, partition: String): String
    external fun reboot(handle: Long, mode: String): String
    external fun oem(handle: Long, command: String): String
    external fun getVar(handle: Long, name: String): String
}
```

---

### Step 4: Rust Fastboot 实现

```rust
// src/usb/fastboot.rs
use libusb::*;
use std::time::Duration;

const FASTBOOT_VID: u16 = 0x18D1;
const FASTBOOT_PID: u16 = 0x4EE0;  // varies by device

pub struct FastbootDevice {
    handle: DeviceHandle<GlobalContext>,
    endpoint_out: u8,
    endpoint_in: u8,
}

impl FastbootDevice {
    pub fn open() -> Result<Self, String> {
        let context = GlobalContext::new().map_err(|e| e.to_string())?;

        let mut device_handle = None;
        for device in context.devices().map_err(|e| e.to_string())?.iter() {
            let desc = device.device_descriptor().map_err(|e| e.to_string())?;
            if desc.vendor_id() == FASTBOOT_VID || desc.product_id() == FASTBOOT_PID {
                let handle = device.open().map_err(|e| e.to_string())?;
                handle.claim_interface(0).map_err(|e| e.to_string())?;
                device_handle = Some(handle);
                break;
            }
        }

        let handle = device_handle.ok_or("No fastboot device found")?;

        // Find endpoints
        let config = handle.device().active_config_descriptor().map_err(|e| e.to_string())?;
        let mut endpoint_out = 0u8;
        let mut endpoint_in = 0u8;

        for iface in config.interfaces() {
            for setting in iface.descriptors() {
                for endpoint in setting.endpoint_descriptors() {
                    if endpoint.direction() == Direction::Out {
                        endpoint_out = endpoint.address();
                    } else {
                        endpoint_in = endpoint.address();
                    }
                }
            }
        }

        Ok(Self { handle, endpoint_out, endpoint_in })
    }

    pub fn flash(&self, partition: &str, image_path: &str) -> Result<String, String> {
        // 1. 发送 flash 命令
        self.send_command(&format!("download:{}", image_path))?;

        // 2. 发送镜像数据
        let image_data = std::fs::read(image_path).map_err(|e| e.to_string())?;
        self.send_data(&image_data)?;

        // 3. 执行刷入
        self.send_command(&format!("flash:{}", partition))?;

        Ok(format!("Flashed {} with {}", partition, image_path))
    }

    pub fn erase(&self, partition: &str) -> Result<String, String> {
        self.send_command(&format!("erase:{}", partition))
    }

    pub fn reboot(&self, mode: &str) -> Result<String, String> {
        self.send_command(&format!("reboot-bootloader"))
    }

    fn send_command(&self, command: &str) -> Result<String, String> {
        let mut buf = [0u8; 256];
        let cmd_bytes = command.as_bytes();
        buf[..cmd_bytes.len()].copy_from_slice(cmd_bytes);

        self.handle.write_bulk(
            self.endpoint_out,
            &buf,
            Duration::from_secs(5)
        ).map_err(|e| e.to_string())?;

        // 读取响应
        let mut response = [0u8; 256];
        let len = self.handle.read_bulk(
            self.endpoint_in,
            &mut response,
            Duration::from_secs(5)
        ).map_err(|e| e.to_string())?;

        Ok(String::from_utf8_lossy(&response[..len]).to_string())
    }

    fn send_data(&self, data: &[u8]) -> Result<(), String> {
        const CHUNK_SIZE: usize = 16384;
        for chunk in data.chunks(CHUNK_SIZE) {
            self.handle.write_bulk(
                self.endpoint_out,
                chunk,
                Duration::from_secs(5)
            ).map_err(|e| e.to_string())?;
        }
        Ok(())
    }
}
```

---

### Step 5: EdlTool (紧急下载模式)

```kotlin
// usb/EdlTool.kt
package com.aishell.platform.usb

import com.aishell.domain.tool.*
import com.aishell.platform.usb.model.*
import javax.inject.Inject

class EdlTool @Inject constructor(
    private val edlManager: EdlManager
) : Tool {

    override val name = "edl_flash"
    override val description = "Flash devices in EDL (Emergency Download) mode"
    override val riskLevel = RiskLevel.DESTRUCTIVE

    override suspend fun execute(params: ToolParams): ToolResult {
        val edlParams = params as? ToolParams.EdlFlash
            ?: return ToolResult.failure("Invalid params")

        return try {
            edlManager.connect()
            edlManager.flashPartition(edlParams.partition, edlParams.imagePath)
            edlManager.disconnect()

            ToolResult.success("EDL flash completed")
        } catch (e: Exception) {
            ToolResult.failure(e.message ?: "EDL flash failed")
        }
    }

    override fun validateParams(params: ToolParams) = params is ToolParams.EdlFlash
}

class EdlManager {
    private var handle: Long = 0

    suspend fun connect() {
        handle = EdlNative.connect()
    }

    suspend fun flashPartition(partition: String, imagePath: String) {
        EdlNative.flash(handle, partition, imagePath)
    }

    suspend fun disconnect() {
        EdlNative.disconnect(handle)
        handle = 0
    }
}

object EdlNative {
    init { System.loadLibrary("aishell-native") }

    external fun connect(): Long
    external fun flash(handle: Long, partition: String, image: String)
    external fun disconnect(handle: Long)
}
```

---

### ToolParams 扩展

```kotlin
// 添加到 ToolParams sealed class 中
sealed class ToolParams {
    // ... 已有类型 ...

    // EDL 刷机
    @Serializable
    data class EdlFlash(
        val partition: String,
        val imagePath: String
    ) : ToolParams()
}
```

---

## 变更历史

| 日期 | 版本 | 变更 |
|------|------|------|
| 2026-04-25 | v1.0 | VFS + USB Tools 实现细节 |
