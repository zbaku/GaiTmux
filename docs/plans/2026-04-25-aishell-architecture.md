# AIShell 架构设计 v3 — 高性能本地优先终端

## 设计目标

| 目标 | 策略 |
|---|---|
| 集成度高 | Kotlin + Rust 双语言统一架构，JNI 零拷贝桥接 |
| 性能高 | Rust 驱动终端核心（PTY/解析/渲染数据），Canvas 直接绘制 |
| 流畅 | 60fps 终端渲染，Compose 状态差分更新，背压控制 |
| 高并发 | Channel 多生产者模型，Per-Session 事件流，协程调度隔离 |

## 核心架构决策

### 1. 纯 Android 架构 — 无后端服务器

```
┌──────────────────────────────────────────────────────┐
│                   AIShell (Android)                  │
│                                                      │
│  ┌──────────┐  ┌──────────┐  ┌───────────────────┐  │
│  │ Compose  │  │  Agent   │  │  Rust Native      │  │
│  │   UI     │  │  Engine  │  │  ┌─PTY Manager    │  │
│  │          │  │          │  │  ├─Terminal Parser │  │
│  │          │  │          │  │  ├─proot Bridge    │  │
│  │          │  │          │  │  └─libusb Stack    │  │
│  └──────────┘  └──────────┘  └───────────────────┘  │
│       │             │               │                │
│  ┌────▼─────────────▼───────────────▼──────────────┐ │
│  │              Kotlin Coroutines + Flow            │ │
│  │         (统一并发原语，全链路流式)                │ │
│  └────────────────────┬────────────────────────────┘ │
│                       │                              │
│  ┌────────────────────▼────────────────────────────┐ │
│  │             Room + DataStore + EncryptedFile     │ │
│  └─────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────┘
          │                              │
          ▼                              ▼
  ┌───────────────┐          ┌───────────────────┐
  │  AI Cloud API │          │  proot Ubuntu     │
  │  (OpenAI/     │          │  (本地 Linux 环境) │
  │   DeepSeek/   │          └───────────────────┘
  │   智谱/...    │
  └───────────────┘
```

**"后端" = AI 云端 API + proot 本地服务**。AIShell 自身不需要任何服务器。

### 2. Kotlin + Rust 双语言架构

| 层 | 语言 | 职责 |
|---|---|---|
| UI / 业务逻辑 / AI 流式处理 | **Kotlin** | Compose UI, Agent Engine, AI Provider, Tools |
| 终端核心 / 高吞吐 I/O | **Rust** | PTY 管理, Terminal 解析, ANSI 渲染数据生成 |
| 系统集成 | **C/Rust** | proot 桥接, libusb 协议栈, Shizuku IPC |

**Rust 的价值：**
- PTY 进程管理：fork/exec/wait 信号处理，零 GC 压力
- Terminal Parser：ANSI escape sequence 解析，每秒万行输出无卡顿
- 渲染数据生成：将解析结果转为结构化 Cell 数据，Kotlin 侧零解析开销
- proot 桥接：直接与 proot C 库交互，无需中间进程
- USB 协议栈：libusb 绑定，直接操控 USB 设备

### 3. 终端渲染流水线

```
PTY Process                Rust Native                  Kotlin / Compose
    │                          │                            │
    │── stdout bytes ────────→ │                            │
    │                          │── ANSI Parser ──→          │
    │                          │   (状态机解析)              │
    │                          │── Grid Update ──→          │
    │                          │   (Cell[row][col])         │
    │                          │── JNI Callback ──→         │
    │                          │   (零拷贝 DirectBuffer)     │
    │                          │                     ┌──────▼──────┐
    │                          │                     │ Canvas 渲染  │
    │                          │                     │ (60fps 差分) │
    │                          │                     └─────────────┘
```

**关键性能优化：**
- **零拷贝传输**：Rust 写入 `DirectByteBuffer`，Kotlin 侧直接读取，无序列化
- **差分渲染**：Rust 侧计算 dirty cells，Compose Canvas 只重绘变化区域
- **背压控制**：PTY 输出速度 > 渲染速度时，Rust 侧做行合并（tail -f 效果）

---

## ARM64 Android 平台约束

AIShell 运行在 **ARM64 (aarch64) Android 设备**上。这不只是目标架构的选择，而是一系列能力边界的决定因素。所有架构决策必须在这个约束下验证。

### 硬件/OS 特性

```
┌─ ARM64 Android 设备 ──────────────────────────────────────┐
│                                                            │
│  SoC: Qualcomm Snapdragon / MediaTek / Exynos (ARMv8-A)   │
│  内核: Linux 4.14 ~ 6.1 (厂商定制)                        │
│  运行时: Android 8.0 (API 26) ~ Android 15 (API 35)      │
│  沙箱: SELinux Enforcing, seccomp-bpf 过滤系统调用        │
│  存储: eMMC/UFS, /sdcard = FUSE 模拟 FAT                  │
│  网络: WiFi / 移动数据, 无以太网                           │
│  USB: OTG (device mode 为主, host mode 需适配器)          │
│  CPU: 4~8 核, big.LITTLE, 无超线程                         │
│  内存: 4~16 GB, 低内存杀后台 (LMK)                        │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

### 关键限制与对策

| 限制 | 影响 | 对策 |
|---|---|---|
| **SELinux Enforcing** | 无法访问其他 app 私有目录 | Shizuku 提权 or proot 沙箱内操作 |
| **seccomp-bpf** | 限制系统调用（如 `ptrace` 在某些版本受限） | proot 使用 `seccomp` 过滤绕行；AIShell 的 Rust PTY 改用 `clone()` 而非 `fork()` 在部分场景 |
| **无 `ptrace`** (Android ≥10) | proot 依赖 ptrace 拦截系统调用 | 使用 proot-seccomp 变体，或内置修补版 proot |
| **低内存杀后台** (LMK) | 后台进程随时被杀 | 前台 Service + `trimMemory` 主动释放 |
| **/sdcard FUSE** | 性能差，不支持符号链接 | proot Ubuntu 内操作用 `/home`，`/sdcard` 仅做数据交换 |
| **没有 GNU libc** | Android 用 bionic，与 Linux glibc 不兼容 | proot Ubuntu 自带 glibc；Rust 编译用 `aarch64-linux-android` target |
| **ADB 二进制缺失** | 系统不提供 adb 可执行文件 | 内置 arm64 adb binary (from Android SDK) |
| **USB host 需 OTG** | 大部分手机默认 device mode | 检测 USB host 能力，不可用时降级为 ADB 方案 |
| **热管理** | 长时间编译导致降频 | 后台任务限流 + 温度监控 |

### ABI 与构建配置

**只支持 arm64-v8a**，不兼容 armeabi-v7a（32位已无意义）。

```kotlin
// app/build.gradle.kts
android {
    namespace = "com.aishell"
    compileSdk = 36
    minSdk = 26           // Android 8.0
    targetSdk = 36

    defaultConfig {
        ndk {
            abiFilters += "arm64-v8a"  // 仅 ARM64
        }
    }

    // Rust 交叉编译配置
    // 开发机: cargo-ndk -t arm64-v8a
    // Termux 本机构建: cargo build --target aarch64-linux-android
}
```

```toml
# Cargo.toml — Rust workspace
[workspace]
members = ["native/terminal-core", "native/proot-bridge", "native/usb-stack"]

[workspace.dependencies]
jni = "0.21"
libc = "0.2"
nix = "0.29"           # ARM64 Linux syscalls

# 编译目标
# 交叉编译 (开发机): aarch64-linux-android
# 本机编译 (Termux): aarch64-unknown-linux-gnu
```

```toml
# .cargo/config.toml
[target.aarch64-linux-android]
linker = "aarch64-linux-android33-clang"  # NDK 27 clang

[target.aarch64-unknown-linux-gnu]
linker = "clang"  # Termux 本机
```

### Rust Native 编译策略

两种构建路径：

```
路径 1: 开发机交叉编译 (推荐 CI/CD)
────────────────────────────────────
macOS/Linux x86_64
    │ cargo-ndk -t arm64-v8a build --release
    ▼
libaishell_terminal.so  (arm64-v8a)
libaishell_proot.so     (arm64-v8a)
libaishell_usb.so       (arm64-v8a)
    │
    ▼ 放入 app/src/main/jniLibs/arm64-v8a/

路径 2: Termux 本机编译 (设备上开发)
────────────────────────────────────
Termux on ARM64 Android
    │ pkg install rust cmake android-ndk
    │ cargo build --target aarch64-unknown-linux-gnu --release
    ▼
libaishell_terminal.so  (arm64)
    │
    ▼ 直接 adb push 或 cp 到 jniLibs/
```

### proot ARM64 特殊处理

```rust
// proot-bridge/src/arm64_compat.rs
//
// ARM64 Android 上的 proot 有特殊限制：
// 1. Android ≥10 限制 ptrace → 需要 seccomp 模式
// 2. ARM64 syscall 编号与 x86_64 不同
// 3. 部分 ARM64 特有 syscall (如 membarrier) 需透传

/// 检测当前设备是否支持 ptrace-based proot
pub fn detect_proot_mode() -> ProotMode {
    // 尝试 ptrace
    if can_ptrace() {
        ProotMode::Ptrace  // 最快，完整兼容
    } else if can_seccomp() {
        ProotMode::Seccomp // Android ≥10 fallback
    } else {
        ProotMode::SymlinkOnly  // 最终降级：仅符号链接模拟
    }
}

pub enum ProotMode {
    /// 标准 proot：ptrace 拦截系统调用
    /// Android < 10 或开启了开发者选项
    Ptrace,

    /// seccomp 模式：用 seccomp-bpf 过滤 + SIGSYS 信号处理
    /// 性能更好但兼容性受限
    Seccomp,

    /// 降级模式：仅做路径翻译和符号链接模拟
    /// 无 uid 映射，无法运行需要 root 的操作
    SymlinkOnly,
}

fn can_ptrace() -> bool {
    use nix::sys::ptrace;
    // fork 一个子进程，尝试 attach
    match unsafe { fork() } {
        Ok(ForkResult::Child) => {
            // 子进程：等待被 trace
            ptrace::traceme().is_ok()
        }
        Ok(ForkResult::Parent { child }) => {
            let result = ptrace::attach(child).is_ok();
            kill(child, Signal::SIGKILL);
            result
        }
        Err(_) => false,
    }
}

fn can_seccomp() -> bool {
    // 检查 PR_SET_NO_NEW_PRIVS + seccomp 是否可用
    nix::unistd::prctl(
        PrctlOption::PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0
    ).is_ok()
}
```

### 内置 ADB Binary

Android 系统不提供 adb 可执行文件。AIShell 需要内置 arm64 adb：

```
app/src/main/assets/bin/
└── adb              # arm64 adb binary (来自 Android SDK platform-tools)
                       # ~8MB, stripped
                       # 用于: adb connect, adb shell, adb push/pull, adb install
```

```kotlin
class AdbBinaryManager(@ApplicationContext private val context: Context) {
    private val adbFile: File
        get() = File(context.filesDir, "bin/adb")

    suspend fun ensureExtracted(): File {
        if (adbFile.exists() && adbFile.canExecute()) return adbFile

        // 从 assets 解压到 filesDir
        context.assets.open("bin/adb").use { input ->
            FileOutputStream(adbFile).use { output ->
                input.copyTo(output)
            }
        }
        adbFile.setExecutable(true)
        return adbFile
    }

    fun getAdbPath(): String = adbFile.absolutePath
}
```

### ADB Wire Protocol — 自研协议栈

除了内置 adb binary（用于 WiFi ADB 和高级操作），AIShell 还实现了纯 Rust 的 ADB 线协议，用于 USB 直连场景和 AI 工具直接调用，无需启动外部 adb 进程。

**双模式架构：**

```
┌────────────────────────────────────────────────────────┐
│              ADB 访问层                                  │
│                                                        │
│  ┌──────────────────┐  ┌────────────────────────────┐  │
│  │  adb binary      │  │  ADB Wire Protocol (Rust)  │  │
│  │  (WiFi/高级操作)  │  │  (USB 直连/AI 工具调用)    │  │
│  │                  │  │  无需外部进程               │  │
│  └──────────────────┘  └────────────────────────────┘  │
└────────────────────────────────────────────────────────┘
```

**ADB 协议消息格式：**

```rust
/// ADB 消息头 — 24 字节固定长度
/// 所有字段 little-endian
#[repr(C, packed)]
struct AdbMessage {
    command:    u32,   // 命令标识 (CNXN, AUTH, OPEN, OKAY, WRTE, CLSE, SYNC)
    arg0:       u32,   // 参数 1 (local-id / auth-type)
    arg1:       u32,   // 参数 2 (remote-id / max-data)
    data_length: u32,  // data 校验和
    data_crc32: u32,   // data 的 CRC32
    magic:      u32,   // command ^ 0xFFFFFFFF (校验)
}
// 紧跟: data (0..data_length 字节)

// 命令常量
const A_CNXN:  u32 = 0x4e584e43;  // "CNXN" — 连接
const A_AUTH:  u32 = 0x48545541;  // "AUTH" — 认证
const A_OPEN:  u32 = 0x4e45504f;  // "OPEN" — 打开流
const A_OKAY:  u32 = 0x59414b4f;  // "OKAY" — 确认
const A_WRTE:  u32 = 0x45545257;  // "WRTE" — 写数据
const A_CLSE:  u32 = 0x45534c43;  // "CLSE" — 关闭流
const A_SYNC:  u32 = 0x4e594353;  // "SYNC" — 同步 (adb sync 协议)
```

**Rust ADB 协议核心：**

```rust
/// ADB 协议客户端 — 纯 Rust 实现
pub struct AdbClient {
    transport: Box<dyn AdbTransport>,  // USB 或 TCP
    max_data: u32,
    local_id: AtomicU32,
    rsa_key: RsaKeyPair,
}

/// 传输层抽象 — USB 和 TCP 统一接口
pub trait AdbTransport: Send + Sync {
    fn connect(&mut self) -> Result<()>;
    fn read_message(&mut self) -> Result<AdbMessage>;
    fn write_message(&mut self, msg: &AdbMessage, data: &[u8]) -> Result<()>;
    fn close(&mut self);
}

/// USB 传输层 — 通过 libusb bulk endpoint
pub struct UsbAdbTransport {
    handle: UsbDeviceHandle,
    ep_in: u8,      // Bulk IN (通常 0x81)
    ep_out: u8,     // Bulk OUT (通常 0x01)
    timeout_ms: u32,
}

impl AdbTransport for UsbAdbTransport {
    fn connect(&mut self) -> Result<()> {
        // ADB 接口: class=0xFF, subclass=0x42, protocol=0x01
        self.handle.claim_interface(0)?;
        Ok(())
    }

    fn read_message(&mut self) -> Result<AdbMessage> {
        let mut header = [0u8; 24];
        self.handle.read_bulk(self.ep_in, &mut header, self.timeout_ms)?;
        let msg = AdbMessage::from_bytes(&header)?;
        if msg.data_length > 0 {
            let mut data = vec![0u8; msg.data_length as usize];
            self.handle.read_bulk(self.ep_in, &mut data, self.timeout_ms)?;
            // 验证 CRC32
            let crc = crc32(&data);
            if crc != msg.data_crc32 { return Err(AdbError::CrcMismatch); }
            msg.data = data;
        }
        Ok(msg)
    }

    fn write_message(&mut self, msg: &AdbMessage, data: &[u8]) -> Result<()> {
        let header = msg.to_bytes(data);
        self.handle.write_bulk(self.ep_out, &header, self.timeout_ms)?;
        if !data.is_empty() {
            self.handle.write_bulk(self.ep_out, data, self.timeout_ms)?;
        }
        Ok(())
    }
}

/// TCP 传输层 — WiFi ADB
pub struct TcpAdbTransport {
    stream: TcpStream,
}

impl AdbClient {
    /// 连接设备 — 完整握手 + RSA 认证
    pub fn connect(&mut self) -> Result<AdbDeviceInfo> {
        // 1. 发送 CNXN
        let system_identity = format!("host::{}\x00", "aishell-protocol-1");
        self.send_message(A_CNXN, 0x01000000, self.max_data, system_identity.as_bytes())?;

        // 2. 接收响应 — 可能是 AUTH 或 CNXN
        loop {
            let msg = self.read_message()?;
            match msg.command {
                A_AUTH => {
                    // 3. RSA 签名认证
                    let auth_type = msg.arg0;
                    match auth_type {
                        1 => {
                            // AUTH TOKEN — 对 token 签名
                            let signature = self.rsa_key.sign(&msg.data)?;
                            self.send_message(A_AUTH, 2, 0, &signature)?;
                        }
                        2 => {
                            // AUTH RSA PUBLIC KEY — 发送公钥
                            let pub_key = self.rsa_key.public_key_bytes();
                            self.send_message(A_AUTH, 3, 0, &pub_key)?;
                        }
                        _ => return Err(AdbError::UnknownAuthType(auth_type)),
                    }
                }
                A_CNXN => {
                    // 认证成功！
                    return Ok(AdbDeviceInfo {
                        system_identity: String::from_utf8_lossy(&msg.data).to_string(),
                        max_data: msg.arg1,
                    });
                }
                _ => return Err(AdbError::UnexpectedMessage(msg.command)),
            }
        }
    }

    /// 打开 shell 流
    pub fn shell(&mut self, command: &str) -> Result<AdbStream> {
        let local_id = self.local_id.fetch_add(1, Ordering::Relaxed);
        let payload = format!("shell:{}\x00", command);
        self.send_message(A_OPEN, local_id, 0, payload.as_bytes())?;

        // 等待 OKAY
        let msg = self.read_message()?;
        if msg.command != A_OKAY {
            return Err(AdbError::UnexpectedMessage(msg.command));
        }

        Ok(AdbStream {
            local_id,
            remote_id: msg.arg0,
            client: self,
        })
    }

    /// 推送文件 (adb sync 协议)
    pub fn push(&mut self, local_path: &str, remote_path: &str, mode: u32) -> Result<()> {
        let mut stream = self.open_sync()?;
        // SYNC 协议: SEND → DATA → DONE → OKAY
        let send_cmd = format!("SEND\x00{}", remote_path);
        // ... sync push 实现
        Ok(())
    }

    /// 拉取文件
    pub fn pull(&mut self, remote_path: &str, local_path: &str) -> Result<()> {
        let mut stream = self.open_sync()?;
        // SYNC 协议: RECV → DATA → DONE
        // ... sync pull 实现
        Ok(())
    }

    /// 安装 APK
    pub fn install(&mut self, apk_path: &str) -> Result<String> {
        // 1. 推送 APK 到 /data/local/tmp/
        let remote = "/data/local/tmp/aishell_install.apk";
        self.push(apk_path, remote, 0x644)?;

        // 2. pm install
        let mut stream = self.shell(&format!("pm install -r -g {}", remote))?;
        let output = stream.read_all()?;

        // 3. 清理
        self.shell(&format!("rm {}", remote))?;

        Ok(output)
    }

    /// 列出已连接设备
    pub fn list_devices(&mut self) -> Result<Vec<AdbDevice>> {
        let mut stream = self.shell("devices -l")?;
        let output = stream.read_all()?;
        Ok(parse_device_list(&output))
    }
}

/// ADB 流 — 对应一个打开的交互通道
pub struct AdbStream<'a> {
    local_id: u32,
    remote_id: u32,
    client: &'a mut AdbClient,
}

impl<'a> AdbStream<'a> {
    /// 读取流数据
    pub fn read(&mut self) -> Result<Vec<u8>> {
        let msg = self.client.read_message()?;
        match msg.command {
            A_WRTE => {
                // 确认收到
                self.client.send_message(A_OKAY, self.local_id, self.remote_id, &[])?;
                Ok(msg.data)
            }
            A_CLSE => Err(AdbError::StreamClosed),
            _ => Err(AdbError::UnexpectedMessage(msg.command)),
        }
    }

    /// 写入流数据
    pub fn write(&mut self, data: &[u8]) -> Result<()> {
        self.client.send_message(A_WRTE, self.local_id, self.remote_id, data)?;
        // 等待 OKAY
        let msg = self.client.read_message()?;
        if msg.command != A_OKAY {
            return Err(AdbError::UnexpectedMessage(msg.command));
        }
        Ok(())
    }

    /// 读取全部输出直到流关闭
    pub fn read_all(&mut self) -> Result<String> {
        let mut output = Vec::new();
        loop {
            match self.read() {
                Ok(data) => output.extend_from_slice(&data),
                Err(AdbError::StreamClosed) => break,
                Err(e) => return Err(e),
            }
        }
        Ok(String::from_utf8_lossy(&output).to_string())
    }

    /// 关闭流
    pub fn close(&mut self) -> Result<()> {
        self.client.send_message(A_CLSE, self.local_id, self.remote_id, &[])?;
        Ok(())
    }
}

#[derive(Debug)]
pub struct AdbDeviceInfo {
    pub system_identity: String,
    pub max_data: u32,
}

#[derive(Debug)]
pub enum AdbError {
    CrcMismatch,
    UnknownAuthType(u32),
    UnexpectedMessage(u32),
    StreamClosed,
    UsbError(String),
    IoError(String),
    AuthFailed,
}
```

**Kotlin ADB 管理器（统一双模式）：**

```kotlin
@Singleton
class AdbManager @Inject constructor(
    private val adbBinary: AdbBinaryManager,
    private val usbRouter: UsbRouter
) {
    /** USB 直连 — 使用 Rust ADB Wire Protocol */
    suspend fun connectUsb(device: UsbDevice): Result<AdbDevice> {
        val transport = usbRouter.openAdbTransport(device)
        return nativeAdbConnect(transport)
    }

    /** WiFi 连接 — 使用 adb binary */
    suspend fun connectWifi(address: String): Result<AdbDevice> {
        val adb = adbBinary.ensureExtracted()
        val result = commandExecutor.execute("$adb connect $address", timeout = 10)
        return if (result.exitCode == 0) Result.success(AdbDevice(address, "wifi"))
               else Result.failure(AdbException("连接失败: ${result.stderr}"))
    }

    /** 统一 shell 接口 — 自动选择传输方式 */
    suspend fun shell(target: String?, command: String): Flow<ToolEvent> {
        val device = resolveDevice(target)
        return when (device.transport) {
            Transport.USB -> nativeAdbShell(device, command)     // Rust Wire Protocol
            Transport.WIFI -> binaryAdbShell(device, command)    // adb binary
        }
    }

    // JNI native 方法
    private external fun nativeAdbConnect(transport: Any): Result<AdbDevice>
    private external fun nativeAdbShell(device: AdbDevice, command: String): Flow<ToolEvent>
}
```

### 内置 proot Binary

同理，proot 也需要内置：

```
app/src/main/assets/bin/
├── adb              # arm64 adb
└── proot            # arm64 proot (修补版，支持 seccomp)
                       # ~1.5MB, stripped
                       # 来源: proot-carel + seccomp 补丁 编译
```

```kotlin
class ProotBinaryManager(@ApplicationContext private val context: Context) {
    private val prootFile: File
        get() = File(context.filesDir, "bin/proot")

    suspend fun ensureExtracted(): File {
        if (prootFile.exists() && prootFile.canExecute()) return prootFile

        context.assets.open("bin/proot").use { input ->
            FileOutputStream(prootFile).use { output ->
                input.copyTo(output)
            }
        }
        prootFile.setExecutable(true)
        return prootFile
    }
}
```

### USB 分层架构

AIShell 的 USB 访问采用**三层递进**策略：优先使用 Android 标准 USB 授权（无需 root），仅在高级场景下才需要 libusb + Shizuku。

```
┌───────────────────────────────────────────────────────────┐
│                  USB 访问三层架构                          │
│                                                           │
│  Layer 1: UsbManager API (默认，无需 root/Shizuku)       │
│  ├── 设备发现: UsbManager.getDeviceList()                │
│  ├── 用户授权: UsbManager.requestPermission() 弹窗       │
│  ├── 传输: bulkTransfer / interruptTransfer / controlTr  │
│  ├── 覆盖场景: 串口、存储、自定义 HID、打印机、调试器   │
│  └── 限制: 无 isochronous 传输，无法访问被内核驱动的设备 │
│                                                           │
│  Layer 2: libusb via UsbManager fd (高级，无需 root)     │
│  ├── 原理: UsbManager.openDevice() → 获取 fd → 传给     │
│  │         libusb_wrap_sysfs() → libusb 用 fd 操作设备   │
│  ├── 覆盖场景: 需要精确控制传输时序的协议               │
│  └── 限制: 仍受 Android USB 授权限制，但更灵活的传输控制 │
│                                                           │
│  Layer 3: libusb direct usbfs (需要 Shizuku/root)        │
│  ├── 原理: 直接访问 /dev/bus/usb/ 下设备节点            │
│  ├── 覆盖场景: isochronous (音频/视频)，绕过内核驱动    │
│  └── 限制: 需要 Shizuku 或 root 权限                    │
│                                                           │
│  路由策略: Layer 1 可用 → 用 Layer 1                     │
│           Layer 1 不够 → 用 Layer 2 (同一 fd)            │
│           Layer 2 不够 → 用 Layer 3 (需 Shizuku)         │
└───────────────────────────────────────────────────────────┘
```

#### Layer 1: UsbManager API (默认)

Android 标准流程，用户授权弹窗，无需任何特权：

```kotlin
@Singleton
class UsbManagerLayer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    /// 已授权的设备连接
    private val connections = ConcurrentHashMap<String, UsbDeviceConnection>()

    /// USB 设备附加/分离广播
    private val _deviceEvents = Channel<UsbDeviceEvent>(capacity = Channel.BUFFERED)
    val deviceEvents: ReceiveChannel<UsbDeviceEvent> = _deviceEvents

    // ── 设备发现 ──

    fun listDevices(): List<UsbDeviceInfo> {
        return usbManager.deviceList.values.map { device ->
            UsbDeviceInfo(
                name = device.deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                deviceClass = device.deviceClass,
                interfaces = device.interfaceCount,
                hasPermission = usbManager.hasPermission(device),
                // 将 UsbDevice 传递给授权/打开方法
                device = device
            )
        }
    }

    // ── 用户授权 ──

    /**
     * 请求 USB 设备访问权限
     * 弹出系统授权对话框，用户点击"允许"后获得访问权
     *
     * 关键：用户可勾选"默认使用此设备"→ 后续自动授权
     */
    fun requestPermission(device: UsbDevice, pendingIntent: PendingIntent) {
        usbManager.requestPermission(device, pendingIntent)
    }

    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    // ── 打开连接 ──

    /**
     * 打开 USB 设备连接
     * 前提: hasPermission() == true
     * 返回: 文件描述符 fd (可传给 Layer 2 libusb)
     */
    fun openDevice(device: UsbDevice): UsbDeviceConnection? {
        if (!usbManager.hasPermission(device)) return null
        val connection = usbManager.openDevice(device) ?: return null
        connections[device.deviceName] = connection
        return connection
    }

    // ── 数据传输 ──

    fun bulkTransfer(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        data: ByteArray,
        timeout: Int = 5000
    ): Int {
        return connection.bulkTransfer(endpoint, data, data.size, timeout)
    }

    fun controlTransfer(
        connection: UsbDeviceConnection,
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        data: ByteArray,
        timeout: Int = 5000
    ): Int {
        return connection.controlTransfer(requestType, request, value, index, data, data.size, timeout)
    }

    // ── 关闭 ──

    fun closeDevice(deviceName: String) {
        connections.remove(deviceName)?.close()
    }
}

data class UsbDeviceInfo(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val deviceClass: Int,
    val interfaces: Int,
    val hasPermission: Boolean,
    val device: UsbDevice  // 内部使用
)

sealed class UsbDeviceEvent {
    data class Attached(val device: UsbDeviceInfo) : UsbDeviceEvent()
    data class Detached(val deviceName: String) : UsbDeviceEvent()
    data class PermissionResult(val deviceName: String, val granted: Boolean) : UsbDeviceEvent()
}
```

USB 授权 UI 流程：

```
用户连接 USB 设备
    ↓
AIShell 收到 ACTION_USB_DEVICE_ATTACHED 广播
    ↓
┌──────────────────────────────────────┐
│  USB 设备已连接                       │
│                                      │
│  📱 FTDI Serial Converter           │
│     VID: 0x0403  PID: 0x6001       │
│     接口: 1 (CDC Serial)            │
│                                      │
│  [允许访问]  [忽略]                  │
│                                      │
│  ☐ 默认允许此设备                    │  ← 勾选后不再弹窗
└──────────────────────────────────────┘
    ↓ 用户点击"允许"
获得 UsbDeviceConnection → 可执行 bulk/control/interrupt 传输
```

#### Layer 2: libusb via UsbManager fd (高级)

当 Layer 1 的 `UsbDeviceConnection` API 粒度不够时（如精确时序控制、自定义传输调度），将 UsbManager 打开的 fd 传给 libusb：

```rust
// usb-stack/src/layer2_fd.rs
//
// 原理：Android UsbManager.openDevice() 返回一个文件描述符
//        将这个 fd 传给 libusb，让 libusb 在这个 fd 上做 I/O
//        不需要 usbfs 直接访问权限，不需要 root/Shizuku

use std::os::unix::io::RawFd;

pub struct UsbDeviceFromFd {
    handle: libusb::DeviceHandle,
}

impl UsBDeviceFromFd {
    /// 从 Android UsbManager 打开的 fd 创建 libusb handle
    /// fd 由 Kotlin 侧通过 JNI 传入
    pub fn from_fd(fd: RawFd) -> Result<Self> {
        let context = libusb::Context::new()?;

        // libusb_wrap_sysfs — 用已有的 fd 而非自己打开设备
        let handle = unsafe {
            context.wrap_sysfs_device(fd)?
        };

        Ok(Self { handle })
    }

    /// 在已授权的 fd 上执行传输
    /// 不需要额外的权限检查 — Android 授权已覆盖
    pub fn bulk_transfer(
        &mut self,
        endpoint: u8,
        data: &[u8],
        timeout_ms: u32,
    ) -> Result<usize> {
        self.handle.write_bulk(endpoint, data, timeout_ms)
    }

    pub fn bulk_read(
        &mut self,
        endpoint: u8,
        buf: &mut [u8],
        timeout_ms: u32,
    ) -> Result<usize> {
        self.handle.read_bulk(endpoint, buf, timeout_ms)
    }

    pub fn control_transfer(
        &mut self,
        request_type: u8,
        request: u8,
        value: u16,
        index: u16,
        data: &[u8],
        timeout_ms: u32,
    ) -> Result<usize> {
        self.handle.write_control(request_type, request, value, index, data, timeout_ms)
    }

    pub fn claim_interface(&mut self, interface: u8) -> Result<()> {
        self.handle.claim_interface(interface)
    }

    pub fn release_interface(&mut self, interface: u8) -> Result<()> {
        self.handle.release_interface(interface)
    }
}
```

JNI 桥接：

```kotlin
// Kotlin 侧：打开设备 → 获取 fd → 传给 Rust
class UsbAdvancedLayer(private val usbManagerLayer: UsbManagerLayer) {

    /**
     * 使用 libusb 进行高级 USB 操作
     * fd 来自 UsbManager（已授权），不需要额外权限
     */
    fun openWithLibusb(device: UsbDevice): Long {
        val connection = usbManagerLayer.openDevice(device) ?: return -1
        val fd = connection.fileDescriptor  // 关键：获取已授权的 fd
        return UsbStackJni.nativeOpenFromFd(fd)  // 传给 Rust libusb
    }

    fun bulkWrite(handle: Long, endpoint: Int, data: ByteArray, timeout: Int): Int {
        return UsbStackJni.nativeBulkWrite(handle, endpoint, data, timeout)
    }

    fun bulkRead(handle: Long, endpoint: Int, size: Int, timeout: Int): ByteArray {
        return UsbStackJni.nativeBulkRead(handle, endpoint, size, timeout)
    }

    fun closeHandle(handle: Long) {
        UsbStackJni.nativeClose(handle)
    }
}
```

#### Layer 3: libusb direct usbfs (需 Shizuku)

仅当 Layer 1/2 都无法满足时使用：

| 场景 | Layer 1 | Layer 2 | Layer 3 |
|---|---|---|---|
| 串口通信 (FTDI/CP2102/CH340) | ✓ | - | - |
| USB 存储 (大容量传输) | ✓ | - | - |
| HID 设备 (键盘/鼠标/游戏手柄) | ✓ | - | - |
| 打印机 | ✓ | - | - |
| JTAG/SWD 调试探针 | ✓ (基本) | ✓ (精确时序) | - |
| 自定义协议 (精确时序控制) | - | ✓ | - |
| **音频/视频 isochronous 传输** | ✗ | ✗ | ✓ |
| **绕过内核驱动独占** | ✗ | ✗ | ✓ |
| **多设备并发访问** | ✓ | ✓ | ✓ (更强) |

```rust
// usb-stack/src/layer3_direct.rs
// 仅在 Shizuku 可用时使用

pub struct DirectUsbAccess {
    context: libusb::Context,
}

impl DirectUsbAccess {
    /// 需要 Shizuku 或 root 才能直接访问 /dev/bus/usb/
    pub fn new_with_shizuku() -> Result<Self> {
        // 验证权限
        if !std::path::Path::new("/dev/bus/usb").exists() {
            return Err(Error::NoUsbfsAccess);
        }

        // 测试能否读取设备节点
        let test_access = std::fs::read_dir("/dev/bus/usb");
        if test_access.is_err() {
            return Err(Error::PermissionDenied(
                "需要 Shizuku 或 root 才能直接访问 USB 设备节点"
            ));
        }

        let context = libusb::Context::new()?;
        Ok(Self { context })
    }

    /// 直接打开设备 — 绕过 Android UsbManager
    /// 可执行 isochronous 传输
    pub fn open_device(&self, vendor_id: u16, product_id: u16) -> Result<DeviceHandle> {
        for device in self.context.devices()?.iter() {
            let desc = device.device_descriptor()?;
            if desc.vendor_id() == vendor_id && desc.product_id() == product_id {
                return device.open()
                    .map_err(Error::LibUsb);
            }
        }
        Err(Error::DeviceNotFound)
    }

    /// isochronous 传输 — UsbManager API 不支持
    pub fn isochronous_transfer(
        &self,
        handle: &DeviceHandle,
        endpoint: u8,
        packets: &[IsoPacket],
        timeout_ms: u32,
    ) -> Result<Vec<IsoPacketResult>> {
        // libusb isochronous API
        ...
    }
}
```

#### USB 路由决策

```kotlin
@Singleton
class UsbRouter @Inject constructor(
    private val usbManagerLayer: UsbManagerLayer,
    private val usbAdvancedLayer: UsbAdvancedLayer,
    private val shizukuStatus: ShizukuStatusProvider
) {
    /**
     * 根据设备能力和需求自动选择 USB 访问层
     */
    fun resolveLayer(device: UsbDevice, requirement: UsbRequirement): UsbAccessLayer {
        // Layer 1 足够？
        if (requirement == UsbRequirement.STANDARD) {
            return UsbAccessLayer.MANAGER_API
        }

        // Layer 2: 需要精确控制，但 UsbManager 授权即可
        if (requirement == UsbRequirement.PRECISE_TIMING) {
            return UsbAccessLayer.LIBUSB_VIA_FD
        }

        // Layer 3: isochronous 或绕过内核驱动
        if (requirement == UsbRequirement.ISOCHRONOUS ||
            requirement == UsbRequirement.BYPASS_KERNEL) {
            return if (shizukuStatus.isAvailable()) {
                UsbAccessLayer.LIBUSB_DIRECT
            } else {
                UsbAccessLayer.UNAVAILABLE  // 需要用户启用 Shizuku
            }
        }

        return UsbAccessLayer.MANAGER_API
    }
}

enum class UsbRequirement {
    STANDARD,          // 普通串口/存储/HID → Layer 1
    PRECISE_TIMING,    // JTAG/SWD 精确时序 → Layer 2
    ISOCHRONOUS,       // 音频/视频流 → Layer 3
    BYPASS_KERNEL      // 绕过内核驱动独占 → Layer 3
}

enum class UsbAccessLayer {
    MANAGER_API,       // Layer 1: UsbManager (标准授权弹窗)
    LIBUSB_VIA_FD,     // Layer 2: libusb + UsbManager fd (无需 root)
    LIBUSB_DIRECT,     // Layer 3: libusb direct usbfs (需 Shizuku)
    UNAVAILABLE        // 无权限
}
```

### USB 外设驱动 — 串口/存储/MTP

AIShell 的 USB 驱动层为常见外设提供 Rust 原生协议实现，AI 可直接通过工具操作这些设备。

```
┌──────────────────────────────────────────────────────────────┐
│                 USB 外设驱动层                                 │
│                                                              │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────────┐  │
│  │ Serial 驱动   │ │ Mass Storage │ │ MTP/PTP 驱动        │  │
│  │ ┌──────────┐ │ │ 驱动         │ │                      │  │
│  │ │ CDC-ACM  │ │ │ BBB/CBW/    │ │ 对象存储模型         │  │
│  │ │ (内置)   │ │ │ CSW 协议     │ │ 命令/响应/数据       │  │
│  │ ├──────────┤ │ │              │ │ 三个端点             │  │
│  │ │ FTDI     │ │ │ SCSI 命令    │ │                      │  │
│  │ │ (D2XX)   │ │ │ READ/WRITE   │ │ ┌────────────────┐  │  │
│  │ ├──────────┤ │ │              │ │ │ OpenSession     │  │  │
│  │ │ CH340    │ │ │ 分区: FAT/   │ │ │ GetObjectInfo   │  │  │
│  │ │ ( Vendor)│ │ │ exFAT/NTFS  │ │ │ GetObject       │  │  │
│  │ ├──────────┤ │ │              │ │ │ SendObject      │  │  │
│  │ │ CP2102   │ │ │ Layer 1:    │ │ │ DeleteObject    │  │  │
│  │ │ ( Vendor)│ │ │ UsbManager  │ │ └────────────────┘  │  │
│  │ └──────────┘ │ │              │ │                      │  │
│  │              │ │ Layer 2:    │ │ Layer 1:             │  │
│  │ 波特率/流控  │ │ libusb-via- │ │ UsbManager API      │  │
│  │ DTR/RTS/CTS │ │ fd (大文件)  │ │                      │  │
│  │              │ │              │ │                      │  │
│  │ Layer 1:    │ └──────────────┘ └──────────────────────┘  │
│  │ UsbManager  │                                            │
│  └──────────────┘                                            │
└──────────────────────────────────────────────────────────────┘
```

#### USB 串口驱动 (Serial)

```rust
/// 串口驱动统一接口
pub trait SerialDriver: Send + Sync {
    /// 打开串口
    fn open(&mut self, handle: UsbDeviceHandle, interface: u8) -> Result<()>;
    /// 设置波特率
    fn set_baud_rate(&mut self, baud: u32) -> Result<()>;
    /// 设置数据格式
    fn set_line_coding(&mut self, coding: LineCoding) -> Result<()>;
    /// 设置控制信号 (DTR/RTS)
    fn set_control_signals(&mut self, dtr: bool, rts: bool) -> Result<()>;
    /// 读取 CTS/DSR/CD 状态
    fn get_status(&mut self) -> Result<SerialStatus>;
    /// 读取数据
    fn read(&mut self, buf: &mut [u8], timeout_ms: u32) -> Result<usize>;
    /// 写入数据
    fn write(&mut self, data: &[u8], timeout_ms: u32) -> Result<usize>;
    /// 关闭
    fn close(&mut self) -> Result<()>;
}

#[derive(Clone, Copy)]
pub struct LineCoding {
    pub baud_rate: u32,
    pub stop_bits: StopBits,      // 1, 1.5, 2
    pub parity: Parity,           // None, Odd, Even, Mark, Space
    pub data_bits: u8,            // 5, 6, 7, 8, 16
}

#[derive(Clone, Copy)]
pub struct SerialStatus {
    pub cts: bool,   // Clear To Send
    pub dsr: bool,   // Data Set Ready
    pub cd: bool,    // Carrier Detect
    pub ri: bool,    // Ring Indicator
}

/// CDC-ACM 驱动 — USB 标准串口类 (Arduino/ESP32/STM32)
pub struct CdcAcmDriver {
    handle: Option<UsbDeviceHandle>,
    ep_in: u8,         // Bulk IN
    ep_out: u8,        // Bulk OUT
    ep_int: u8,        // Interrupt IN (状态通知)
    interface: u8,
}

impl SerialDriver for CdcAcmDriver {
    fn open(&mut self, mut handle: UsbDeviceHandle, interface: u8) -> Result<()> {
        handle.claim_interface(interface)?;
        // 找到 Bulk IN/OUT 和 Interrupt IN 端点
        // CDC-ACM: class=0x02, subclass=0x02
        self.ep_in = find_endpoint(&handle, USB_DIR_IN, USB_ENDPOINT_XFER_BULK)?;
        self.ep_out = find_endpoint(&handle, USB_DIR_OUT, USB_ENDPOINT_XFER_BULK)?;
        self.ep_int = find_endpoint(&handle, USB_DIR_IN, USB_ENDPOINT_XFER_INT)?;
        self.handle = Some(handle);
        Ok(())
    }

    fn set_baud_rate(&mut self, baud: u32) -> Result<()> {
        let coding = LineCoding {
            baud_rate: baud,
            stop_bits: StopBits::One,
            parity: Parity::None,
            data_bits: 8,
        };
        self.set_line_coding(coding)
    }

    fn set_line_coding(&mut self, coding: LineCoding) -> Result<()> {
        let handle = self.handle.as_mut().ok_or(SerialError::NotOpen)?;
        // CDC-ACM SET_LINE_CODING 请求
        // bmRequestType: 0x21 (Host-to-Device, Class, Interface)
        // bRequest: 0x20 (SET_LINE_CODING)
        let mut data = [0u8; 7];
        LittleEndian::write_u32(&mut data[0..4], coding.baud_rate);
        data[4] = coding.stop_bits as u8;
        data[5] = coding.parity as u8;
        data[6] = coding.data_bits;
        handle.control_transfer(0x21, 0x20, 0, self.interface as u16, &data, 5000)?;
        Ok(())
    }

    fn set_control_signals(&mut self, dtr: bool, rts: bool) -> Result<()> {
        let handle = self.handle.as_mut().ok_or(SerialError::NotOpen)?;
        // CDC-ACM SET_CONTROL_LINE_STATE
        let value = (dtr as u16) | ((rts as u16) << 1);
        handle.control_transfer(0x21, 0x22, value, self.interface as u16, &[], 5000)?;
        Ok(())
    }

    fn read(&mut self, buf: &mut [u8], timeout_ms: u32) -> Result<usize> {
        let handle = self.handle.as_mut().ok_or(SerialError::NotOpen)?;
        handle.read_bulk(self.ep_in, buf, timeout_ms)
            .map_err(|e| SerialError::UsbError(e.to_string()))
    }

    fn write(&mut self, data: &[u8], timeout_ms: u32) -> Result<usize> {
        let handle = self.handle.as_mut().ok_or(SerialError::NotOpen)?;
        handle.write_bulk(self.ep_out, data, timeout_ms)
            .map_err(|e| SerialError::UsbError(e.to_string()))
    }

    fn close(&mut self) -> Result<()> {
        if let Some(handle) = self.handle.take() {
            handle.release_interface(self.interface)?;
        }
        Ok(())
    }
}

/// FTDI 驱动 — FT232R/FT232H/FT2232 等
/// 使用 Vendor-specific 命令，不是 CDC-ACM
pub struct FtdiDriver {
    handle: Option<UsbDeviceHandle>,
    ep_in: u8,
    ep_out: u8,
    interface: u8,
    chip_type: FtdiChip,   // FT232R, FT232H, FT2232, FT4232
}

impl SerialDriver for FtdiDriver {
    fn set_baud_rate(&mut self, baud: u32) -> Result<()> {
        let handle = self.handle.as_mut().ok_or(SerialError::NotOpen)?;
        // FTDI 波特率计算: 30000000 / (baud * divisor)
        // FT232R: 使用分数分频器
        let (divisor, frac) = ftdi_calc_baud_divisor(baud, self.chip_type)?;
        handle.control_transfer(
            0x40,           // Vendor, Host-to-Device
            0x03,           // FTDI_SIO_SET_BAUD_RATE
            (divisor as u16) | (frac as u16),
            self.interface as u16,
            &[], 5000
        )?;
        Ok(())
    }

    fn set_line_coding(&mut self, coding: LineCoding) -> Result<()> {
        let handle = self.handle.as_mut().ok_or(SerialError::NotOpen)?;
        self.set_baud_rate(coding.baud_rate)?;
        // FTDI 设置数据格式: FTDI_SIO_SET_DATA
        let value = (coding.data_bits as u16)    // 7/8
                  | (coding.stop_bits as u16) << 11  // 1/2
                  | (coding.parity as u16) << 8;     // none/odd/even
        handle.control_transfer(0x40, 0x04, value, self.interface as u16, &[], 5000)?;
        Ok(())
    }

    fn set_control_signals(&mut self, dtr: bool, rts: bool) -> Result<()> {
        let handle = self.handle.as_mut().ok_or(SerialError::NotOpen)?;
        // FTDI_SIO_MODEM_CTRL
        let value = if dtr { 0x0101 } else { 0x0100 }
                  | if rts { 0x0202 } else { 0x0200 };
        handle.control_transfer(0x40, 0x01, value, 0, &[], 5000)?;
        Ok(())
    }
    // ... open, read, write, close 同 CDC-ACM
}

/// CH340 驱动 — 南京沁恒 CH340/CH341
/// 中国市场最常见的 USB 转串口芯片
pub struct Ch340Driver {
    handle: Option<UsbDeviceHandle>,
    ep_in: u8,
    ep_out: u8,
}

impl SerialDriver for Ch340Driver {
    fn set_baud_rate(&mut self, baud: u32) -> Result<()> {
        let handle = self.handle.as_mut().ok_or(SerialError::NotOpen)?;
        // CH340 波特率: 写入分频因子
        let (divisor, prescaler) = ch340_calc_baud_divisor(baud)?;
        handle.control_transfer(0x40, 0x01, 0x1312, 0, &[], 5000)?; // 特殊初始化序列
        let factor = ((prescaler as u16) << 8) | (divisor as u16);
        handle.control_transfer(0x40, 0x01, factor, 0, &[], 5000)?;
        Ok(())
    }

    fn set_control_signals(&mut self, dtr: bool, rts: bool) -> Result<()> {
        let handle = self.handle.as_mut().ok_or(SerialError::NotOpen)?;
        let value = if dtr { 0x01 } else { 0x00 }
                  | if rts { 0x02 } else { 0x00 };
        handle.control_transfer(0x40, 0xA4, value as u16, 0, &[], 5000)?;
        Ok(())
    }
    // ... open, read, write, close 同 CDC-ACM
}

/// CP2102 驱动 — Silicon Labs CP210x 系列
pub struct Cp2102Driver {
    handle: Option<UsbDeviceHandle>,
    ep_in: u8,
    ep_out: u8,
    interface: u8,
}

impl SerialDriver for Cp2102Driver {
    fn set_baud_rate(&mut self, baud: u32) -> Result<()> {
        let handle = self.handle.as_mut().ok_or(SerialError::NotOpen)?;
        // CP210x SET_BAUDRATE: 直接发送 32-bit 波特率值
        let mut data = [0u8; 4];
        LittleEndian::write_u32(&mut data, baud);
        handle.control_transfer(0x41, 0x1E, 0, 0, &data, 5000)?;
        Ok(())
    }

    fn set_line_coding(&mut self, coding: LineCoding) -> Result<()> {
        let handle = self.handle.as_mut().ok_or(SerialError::NotOpen)?;
        self.set_baud_rate(coding.baud_rate)?;
        // CP210x SET_LINE_CTL
        let value = (coding.data_bits as u16)
                  | (coding.stop_bits as u16) << 2
                  | (coding.parity as u16) << 8;
        handle.control_transfer(0x41, 0x03, value, 0, &[], 5000)?;
        Ok(())
    }
    // ... open, read, write, close 同 CDC-ACM
}

/// 串口驱动工厂 — 自动识别芯片类型
pub fn create_serial_driver(device: &UsbDeviceInfo) -> Box<dyn SerialDriver> {
    match (device.vendor_id, device.product_id) {
        // FTDI: VID 0x0403
        (0x0403, _) => Box::new(FtdiDriver::new()),
        // CH340: VID 0x1A86
        (0x1A86, _) => Box::new(Ch340Driver::new()),
        // CP210x: VID 0x10C4
        (0x10C4, _) => Box::new(Cp2102Driver::new()),
        // CDC-ACM: class=0x02
        _ if device.device_class == 0x02 => Box::new(CdcAcmDriver::new()),
        // 默认尝试 CDC-ACM
        _ => Box::new(CdcAcmDriver::new()),
    }
}
```

**Kotlin 串口管理器：**

```kotlin
@Singleton
class SerialPortManager @Inject constructor(
    private val usbRouter: UsbRouter
) {
    /** 打开串口 */
    suspend fun open(deviceAddress: String, config: SerialConfig): Result<SerialConnection> {
        val usbDevice = resolveUsbDevice(deviceAddress)
        val fd = usbRouter.openDevice(usbDevice)  // Layer 1: UsbManager 授权
        return nativeSerialOpen(fd, config)
    }

    /** 列出可用串口设备 */
    suspend fun listDevices(): List<SerialDeviceInfo> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return usbManager.deviceList.values
            .filter { isSerialDevice(it) }
            .map { SerialDeviceInfo(it.deviceName, it.vendorId, it.productId, detectChipType(it)) }
    }

    private fun isSerialDevice(device: UsbDevice): Boolean {
        // CDC-ACM: class=0x02, subclass=0x02
        // FTDI: VID=0x0403
        // CH340: VID=0x1A86
        // CP210x: VID=0x10C4
        return device.vendorId == 0x0403
            || device.vendorId == 0x1A86
            || device.vendorId == 0x10C4
            || device.interfaceCount > 0 && device.getInterface(0).interfaceClass == 0x02
    }

    private external fun nativeSerialOpen(fd: Int, config: SerialConfig): Result<SerialConnection>
}

data class SerialConfig(
    val baudRate: Int = 115200,
    val dataBits: Int = 8,
    val stopBits: Int = 1,
    val parity: Int = 0,       // 0=None, 1=Odd, 2=Even
    val flowControl: Int = 0   // 0=None, 1=Hardware(RTS/CTS), 2=Software(XON/XOFF)
)

data class SerialDeviceInfo(
    val address: String,
    val vendorId: Int,
    val productId: Int,
    val chipType: String        // "CDC-ACM", "FTDI", "CH340", "CP2102"
)
```

#### USB Mass Storage 驱动

U 盘读写需要实现 Bulk-Only Transport (BOT) 协议和 SCSI 命令。

```rust
/// USB Mass Storage — Bulk-Only Transport
pub struct MassStorageDriver {
    handle: UsbDeviceHandle,
    ep_in: u8,         // Bulk IN
    ep_out: u8,        // Bulk OUT
    tag: AtomicU32,    // 命令标签 (递增)
    block_size: u32,   // 通常 512
    capacity: u64,     // 总扇区数
}

impl MassStorageDriver {
    /// SCSI 命令包装 — CBW (Command Block Wrapper)
    fn send_cbw(&mut self, cb: &[u8], data_length: u32, direction: u8) -> Result<()> {
        let tag = self.tag.fetch_add(1, Ordering::Relaxed);
        let mut cbw = [0u8; 31];
        // CBW 签名: 0x43425355 ("USBC")
        LittleEndian::write_u32(&mut cbw[0..4], 0x43425355);
        LittleEndian::write_u32(&mut cbw[4..8], tag);
        LittleEndian::write_u32(&mut cbw[8..12], data_length);
        cbw[12] = direction;  // 0x80=IN, 0x00=OUT
        cbw[14] = cb.len() as u8;
        cbw[15..15 + cb.len()].copy_from_slice(cb);
        self.handle.write_bulk(self.ep_out, &cbw, 5000)?;
        Ok(())
    }

    /// 读取 CSW (Command Status Wrapper)
    fn read_csw(&mut self) -> Result<(u32, u8)> {
        let mut csw = [0u8; 13];
        self.handle.read_bulk(self.ep_in, &mut csw, 5000)?;
        // CSW 签名: 0x53425355 ("USBS")
        if LittleEndian::read_u32(&csw[0..4]) != 0x53425355 {
            return Err(MassStorageError::InvalidCsw);
        }
        let tag = LittleEndian::read_u32(&csw[4..8]);
        let status = csw[12];  // 0=Good, 1=Failed, 2=Phase Error
        Ok((tag, status))
    }

    /// SCSI TEST UNIT READY — 检查设备就绪
    pub fn test_unit_ready(&mut self) -> Result<bool> {
        let cb = [0x00, 0, 0, 0, 0, 0];  // SCSI TEST UNIT READY
        self.send_cbw(&cb, 0, 0)?;
        let (_, status) = self.read_csw()?;
        Ok(status == 0)
    }

    /// SCSI READ CAPACITY — 获取容量
    pub fn read_capacity(&mut self) -> Result<(u64, u32)> {
        let cb = [0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0];  // SCSI READ CAPACITY(10)
        self.send_cbw(&cb, 8, 0x80)?;
        let mut data = [0u8; 8];
        self.handle.read_bulk(self.ep_in, &mut data, 5000)?;
        let (_, status) = self.read_csw()?;
        if status != 0 { return Err(MassStorageError::ScsiError(status)); }
        let last_lba = BigEndian::read_u32(&data[0..4]) as u64;
        let block_size = BigEndian::read_u32(&data[4..8]);
        self.block_size = block_size;
        self.capacity = last_lba + 1;
        Ok((self.capacity, block_size))
    }

    /// SCSI READ(10) — 读取扇区
    pub fn read_blocks(&mut self, lba: u64, count: u16, buf: &mut [u8]) -> Result<()> {
        let mut cb = [0u8; 10];
        cb[0] = 0x28;   // SCSI READ(10)
        cb[2..6].copy_from_slice(&(lba as u32).to_be_bytes());
        cb[7..9].copy_from_slice(&count.to_be_bytes());
        let data_length = count as u32 * self.block_size;
        self.send_cbw(&cb, data_length, 0x80)?;
        self.handle.read_bulk(self.ep_in, buf, 30000)?;
        let (_, status) = self.read_csw()?;
        if status != 0 { return Err(MassStorageError::ScsiError(status)); }
        Ok(())
    }

    /// SCSI WRITE(10) — 写入扇区
    pub fn write_blocks(&mut self, lba: u64, count: u16, data: &[u8]) -> Result<()> {
        let mut cb = [0u8; 10];
        cb[0] = 0x2A;   // SCSI WRITE(10)
        cb[2..6].copy_from_slice(&(lba as u32).to_be_bytes());
        cb[7..9].copy_from_slice(&count.to_be_bytes());
        let data_length = count as u32 * self.block_size;
        self.send_cbw(&cb, data_length, 0x00)?;
        self.handle.write_bulk(self.ep_out, data, 30000)?;
        let (_, status) = self.read_csw()?;
        if status != 0 { return Err(MassStorageError::ScsiError(status)); }
        Ok(())
    }

    /// SCSI REQUEST SENSE — 获取错误详情
    pub fn request_sense(&mut self) -> Result<SenseData> {
        let cb = [0x03, 0, 0, 0, 18, 0];  // REQUEST SENSE
        self.send_cbw(&cb, 18, 0x80)?;
        let mut data = [0u8; 18];
        self.handle.read_bulk(self.ep_in, &mut data, 5000)?;
        let (_, _) = self.read_csw()?;
        Ok(SenseData {
            sense_key: data[2] & 0x0F,
            asc: data[12],
            ascq: data[13],
        })
    }
}

pub struct SenseData {
    pub sense_key: u8,  // 0=No Sense, 2=Not Ready, 3=Medium Error, 5=Illegal Request
    pub asc: u8,        // Additional Sense Code
    pub ascq: u8,       // Additional Sense Code Qualifier
}

#[derive(Debug)]
pub enum MassStorageError {
    InvalidCsw,
    ScsiError(u8),
    UsbError(String),
    NotReady,
}
```

**文件系统挂载（FAT32 只读）：**

```rust
/// FAT32 最小只读解析 — 从 MassStorage 读取文件
pub struct Fat32Reader {
    storage: MassStorageDriver,
    bytes_per_sector: u16,
    sectors_per_cluster: u8,
    root_dir_cluster: u32,
    fat_start_lba: u32,
    data_start_lba: u32,
}

impl Fat32Reader {
    pub fn new(mut storage: MassStorageDriver) -> Result<Self> {
        // 读取 MBR + BPB (BIOS Parameter Block)
        let mut boot = [0u8; 512];
        storage.read_blocks(0, 1, &mut boot)?;
        let bytes_per_sector = LittleEndian::read_u16(&boot[11..13]);
        let sectors_per_cluster = boot[13];
        let reserved_sectors = LittleEndian::read_u16(&boot[14..16]);
        let fat_count = boot[16];
        let root_dir_cluster = LittleEndian::read_u32(&boot[44..48]);

        let fat_start = reserved_sectors as u32;
        let data_start = fat_start + (fat_count as u32 * LittleEndian::read_u32(&boot[36..40]));

        Ok(Self {
            storage, bytes_per_sector, sectors_per_cluster,
            root_dir_cluster, fat_start_lba: fat_start, data_start_lba: data_start,
        })
    }

    /// 列出目录内容
    pub fn list_dir(&mut self, path: &str) -> Result<Vec<FatDirEntry>> {
        let cluster = self.resolve_path(path)?;
        self.read_dir_entries(cluster)
    }

    /// 读取文件
    pub fn read_file(&mut self, path: &str) -> Result<Vec<u8>> {
        let (cluster, size) = self.find_file(path)?;
        let mut buf = Vec::with_capacity(size);
        let mut current = cluster;
        while current < 0x0FFFFFF8 {  // FAT32 EOC marker
            let lba = self.cluster_to_lba(current);
            let sectors = self.sectors_per_cluster as u16;
            let mut block = vec![0u8; self.bytes_per_sector as usize * sectors as usize];
            self.storage.read_blocks(lba, sectors, &mut block)?;
            buf.extend_from_slice(&block);
            current = self.read_fat_entry(current)?;
        }
        buf.truncate(size as usize);
        Ok(buf)
    }
}
```

#### MTP/PTP 驱动

MTP (Media Transfer Protocol) 用于与 Android 设备、相机等传输媒体文件。

```rust
/// MTP/PTP 协议驱动
pub struct MtpDriver {
    handle: UsbDeviceHandle,
    ep_in: u8,         // Bulk IN
    ep_out: u8,        // Bulk OUT
    ep_int: u8,        // Interrupt IN (事件通知)
    session_id: u32,
    transaction_id: u32,
}

/// MTP 容器类型
#[repr(u16)]
enum MtpContainerType {
    Command = 1,
    Data = 2,
    Response = 3,
    Event = 4,
}

/// MTP 容器头 — 命令/响应通用
struct MtpContainer {
    length: u32,       // 容器总长度
    container_type: u16,
    code: u16,         // 操作码/响应码
    transaction_id: u32,
}

impl MtpDriver {
    /// 打开会话
    pub fn open_session(&mut self) -> Result<()> {
        let resp = self.send_command(0x1002, &[1])?;  // OpenSession
        self.session_id = 1;
        Ok(())
    }

    /// 获取设备信息
    pub fn get_device_info(&mut self) -> Result<MtpDeviceInfo> {
        let data = self.send_command_with_data(0x1001)?;  // GetDeviceInfo
        parse_device_info(&data)
    }

    /// 列出存储
    pub fn get_storage_ids(&mut self) -> Result<Vec<u32>> {
        let data = self.send_command_with_data(0x1004)?;  // GetStorageIDs
        parse_array_u32(&data)
    }

    /// 列出对象（目录）
    pub fn get_object_handles(&mut self, storage_id: u32, parent: u32) -> Result<Vec<u32>> {
        let data = self.send_command_with_data(0x1007, &[storage_id, 0, parent])?;
        parse_array_u32(&data)
    }

    /// 获取对象信息
    pub fn get_object_info(&mut self, handle: u32) -> Result<MtpObjectInfo> {
        let data = self.send_command_with_data(0x1008, &[handle])?;
        parse_object_info(&data)
    }

    /// 获取对象数据（下载文件）
    pub fn get_object(&mut self, handle: u32) -> Result<Vec<u8>> {
        self.send_command(0x1009, &[handle])?;  // GetObject
        self.read_data_phase()
    }

    /// 发送对象（上传文件）
    pub fn send_object(&mut self, parent: u32, info: &MtpObjectInfo, data: &[u8]) -> Result<()> {
        self.send_command(0x100D, &[0, parent])?;  // SendObjectInfo
        self.write_data_phase(data)?;
        self.send_command(0x100E, &[])?;  // SendObject
        Ok(())
    }

    /// 删除对象
    pub fn delete_object(&mut self, handle: u32) -> Result<()> {
        self.send_command(0x100B, &[handle])?;  // DeleteObject
        Ok(())
    }

    /// 发送 MTP 命令
    fn send_command(&mut self, code: u16, params: &[u32]) -> Result<MtpResponse> {
        let tid = self.transaction_id;
        self.transaction_id += 1;
        // 构造 Command Container
        let mut container = Vec::new();
        let param_count = params.len() as u32;
        let header_size = 12 + params.len() * 4;
        LittleEndian::write_u32(&mut container[0..4], header_size as u32);  // length
        LittleEndian::write_u16(&mut container[4..6], MtpContainerType::Command as u16);
        LittleEndian::write_u16(&mut container[6..8], code);
        LittleEndian::write_u32(&mut container[8..12], tid);
        for &p in params {
            container.extend_from_slice(&p.to_le_bytes());
        }
        self.handle.write_bulk(self.ep_out, &container, 5000)?;

        // 读取响应
        let mut resp_buf = [0u8; 64];
        self.handle.read_bulk(self.ep_in, &mut resp_buf, 5000)?;
        Ok(MtpResponse {
            code: LittleEndian::read_u16(&resp_buf[6..8]),
            params: parse_response_params(&resp_buf),
        })
    }
}

pub struct MtpObjectInfo {
    pub storage_id: u32,
    pub format: u16,        // 0x3000=关联(文件夹), 0x3001=脚本, 0x3008=HTML, 0x300B=EXIF/JPEG...
    pub protection_status: u16,
    pub size: u64,
    pub filename: String,
    pub created: String,
    pub modified: String,
}

pub struct MtpDeviceInfo {
    pub manufacturer: String,
    pub model: String,
    pub device_version: String,
    pub serial_number: String,
    pub supported_operations: Vec<u16>,
    pub supported_formats: Vec<u16>,
}
```

### Android 设备刷机模式 — Recovery / Fastboot / EDL

AIShell 面向 Android 终端用户，设备刷机是核心刚需。三种刷机模式覆盖从日常维护到救砖的全场景：

```
┌───────────────────────────────────────────────────────────────┐
│                    刷机模式架构                                 │
│                                                               │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────────────┐  │
│  │  Recovery    │  │  Fastboot    │  │  EDL (Qualcomm)     │  │
│  │  恢复模式    │  │  引导加载器   │  │  紧急下载模式        │  │
│  │             │  │             │  │                     │  │
│  │  ADB 协议   │  │  Fastboot    │  │  Sahara + Firehose  │  │
│  │  (sideload) │  │  协议        │  │  协议               │  │
│  │             │  │             │  │                     │  │
│  │  USB Layer1 │  │  USB Layer2 │  │  USB Layer2/3       │  │
│  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘  │
│         │                │                     │              │
│  ┌──────▼────────────────▼─────────────────────▼──────────┐  │
│  │              FlashDeviceManager (统一管理)              │  │
│  │  ┌─ 设备状态检测 (自动识别当前模式)                      │  │
│  │  ├─ 协议切换 (Recovery ↔ Fastboot ↔ EDL)               │  │
│  │  ├─ 分区表管理 (解析/校验/写入)                         │  │
│  │  └─ 安全校验 (签名验证/防砖)                            │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                               │
│  ┌────────────────────────────────────────────────────────┐  │
│  │              内置 ARM64 Binaries                        │  │
│  │  adb (8MB) │ fastboot (5MB) │ payloads (签名镜像)      │  │
│  └────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────┘
```

#### 1. Recovery 模式

设备进入 Recovery 后通过 ADB 协议通信，USB 层使用 Layer 1（UsbManager API）即可。

**支持的 Recovery 操作：**

| 操作 | 命令/协议 | 说明 |
|---|---|---|
| ADB sideload | `adb sideload <zip>` | OTA/刷机包推送 |
| ADB shell | `adb shell` | Recovery 内 shell（有限命令集） |
| 推送文件 | `adb push <local> <remote>` | 向 Recovery 环境推送文件 |
| 拉取日志 | `adb pull /tmp/recovery.log` | 获取 Recovery 日志 |
| 设备信息 | `adb get-state` → `recovery` | 检测设备当前处于 Recovery |

**Kotlin Recovery 管理：**

```kotlin
@Singleton
class RecoveryManager @Inject constructor(
    private val adbBinary: AdbBinaryManager,
    private val commandExecutor: CommandExecutor,
    private val confirmationGate: ConfirmationGate
) {
    /**
     * 检测连接设备是否处于 Recovery 模式
     */
    suspend fun detectRecoveryDevice(): RecoveryDevice? {
        val adb = adbBinary.ensureExtracted()
        val result = commandExecutor.execute(
            "$adb devices -l",
            timeout = 5
        )
        // 解析 "adb devices" 输出，识别 recovery 状态
        return parseRecoveryDevice(result.stdout)
    }

    /**
     * ADB Sideload — 刷入 OTA/卡刷包
     * ⚠️ 危险操作，需用户确认
     */
    suspend fun sideload(zipPath: VfsPath): Result<SideloadProgress> {
        // 1. 安全检查
        val checksum = vfs.checksum(zipPath, "SHA-256")
        val knownGood = verifyChecksum(checksum)
        if (!knownGood) {
            confirmationGate.requireConfirm(
                conversationId,
                "刷机包校验: 未找到已知校验和，继续刷入可能导致设备异常。确认继续？"
            )
        }

        // 2. 执行 sideload
        val adb = adbBinary.ensureExtracted()
        return commandExecutor.executeStreaming(
            "$adb sideload ${zipPath.value}",
            timeout = 600  // sideload 可能很慢
        ).map { progress ->
            SideloadProgress(
                total = extractTotal(progress),
                transferred = extractTransferred(progress),
                percentage = extractPercentage(progress)
            )
        }
    }

    /**
     * 拉取 Recovery 日志
     */
    suspend fun pullRecoveryLog(outputPath: VfsPath): Result<VfsPath> {
        val adb = adbBinary.ensureExtracted()
        val result = commandExecutor.execute(
            "$adb pull /tmp/recovery.log ${outputPath.value}"
        )
        return if (result.exitCode == 0) Result.success(outputPath)
               else Result.failure(FlashException("拉取日志失败: ${result.stderr}"))
    }
}

data class RecoveryDevice(
    val serial: String,
    val recoveryType: String,     // "stock" | "twrp" | "orangefox"
    val usbState: String          // "device" | "recovery"
)

data class SideloadProgress(
    val total: Long,
    val transferred: Long,
    val percentage: Float
)
```

**Sideload 进度解析：**

ADB sideload 输出格式为 `%` 百分比，Rust 侧解析：

```rust
/// 解析 adb sideload 进度输出
#[no_mangle]
pub extern "C" fn parse_sideload_progress(line: *const c_char) -> SideloadProgress {
    let line = unsafe { CStr::from_ptr(line).to_string_lossy() };
    // adb sideload 输出: "47%" 或 "Sending 'sideload' (12345 KB)" 或 "Transferring: 47%"
    if let Some(pct) = line.trim().strip_suffix('%')
        .and_then(|s| s.parse::<f32>().ok())
    {
        SideloadProgress { percentage: pct, ..Default::default() }
    } else {
        SideloadProgress::default()
    }
}
```

#### 2. Fastboot 模式

Fastboot 是 Android 引导加载器阶段的刷机协议，比 Recovery 更底层。需要内置 arm64 fastboot binary + USB Layer 2（libusb via UsbManager fd，需要精确时序控制）。

**内置 Fastboot Binary：**

```
app/src/main/assets/bin/
├── adb              # arm64 adb (8MB)
├── fastboot         # arm64 fastboot (5MB, 来自 Android SDK platform-tools)
│                       # 用于: flash, boot, erase, oem unlock
└── proot            # arm64 proot (1.5MB)
```

```kotlin
class FastbootBinaryManager(@ApplicationContext private val context: Context) {
    private val fastbootFile: File
        get() = File(context.filesDir, "bin/fastboot")

    suspend fun ensureExtracted(): File {
        if (fastbootFile.exists() && fastbootFile.canExecute()) return fastbootFile
        context.assets.open("bin/fastboot").use { input ->
            FileOutputStream(fastbootFile).use { output ->
                input.copyTo(output)
            }
        }
        fastbootFile.setExecutable(true)
        return fastbootFile
    }
}
```

**Fastboot 协议层次：**

```
┌──────────────────────────────────────┐
│  AIShell Fastboot 管理层             │
│  ┌──────────────────────────────┐    │
│  │  FastbootManager (Kotlin)    │    │
│  │  - 高层操作: flash, boot     │    │
│  │  - 安全校验, 用户确认         │    │
│  └──────────┬───────────────────┘    │
│             │                         │
│  ┌──────────▼───────────────────┐    │
│  │  FastbootProtocol (Rust)     │    │
│  │  - USB bulk transfer         │    │
│  │  - Fastboot 命令编码/解码    │    │
│  │  - 超时重试, 状态机          │    │
│  └──────────┬───────────────────┘    │
│             │                         │
│  ┌──────────▼───────────────────┐    │
│  │  USB Layer 2: libusb via fd  │    │
│  │  - UsbManager 标准授权弹窗    │    │
│  │  - Bulk EP 读写              │    │
│  └──────────────────────────────┘    │
└──────────────────────────────────────┘
```

**支持的 Fastboot 操作：**

| 操作 | 命令 | 风险等级 | 说明 |
|---|---|---|---|
| 设备信息 | `fastboot devices` / `getvar all` | READ_ONLY | 获取设备序列号、分区表等 |
| 刷写分区 | `fastboot flash <partition> <img>` | DESTRUCTIVE | 写入分区镜像 |
| 启动镜像 | `fastboot boot <img>` | MODIFY | 临时启动（不写入闪存） |
| 擦除分区 | `fastboot erase <partition>` | DESTRUCTIVE | 擦除分区数据 |
| 解锁 Bootloader | `fastboot oem unlock` / `flashing unlock` | DESTRUCTIVE | 解锁引导加载器 |
| 锁定 Bootloader | `fastboot oem lock` / `flashing lock` | DESTRUCTIVE | 锁定引导加载器 |
| 重启 | `fastboot reboot` / `recovery` / `bootloader` | MODIFY | 重启到指定模式 |
| 获取解锁状态 | `fastboot oem device-info` | READ_ONLY | 查询 bootloader 锁状态 |
| 设置 Slot | `fastboot set_active <slot>` | MODIFY | A/B 分区切换 |

**Rust Fastboot 协议实现：**

```rust
/// Fastboot 协议核心 — USB bulk 传输
pub struct FastbootProtocol {
    usb_handle: UsbDeviceHandle,
    ep_in: u8,     // Bulk IN endpoint
    ep_out: u8,    // Bulk OUT endpoint
    timeout_ms: u32,
}

impl FastbootProtocol {
    const FB_HEADER_SIZE: usize = 4;
    const FB_MAX_PAYLOAD: usize = 65536;  // 64KB

    /// 发送 Fastboot 命令
    pub fn send_command(&mut self, command: &str) -> Result<FastbootResponse> {
        // Fastboot 协议: [4字节长度(ASCII)] + [payload]
        let cmd_bytes = command.as_bytes();
        if cmd_bytes.len() > Self::FB_MAX_PAYLOAD {
            return Err(FastbootError::CommandTooLong);
        }
        let header = format!("{:04x}", cmd_bytes.len());
        self.usb_write(header.as_bytes())?;
        self.usb_write(cmd_bytes)?;
        self.read_response()
    }

    /// 下载镜像数据到设备
    pub fn download(&mut self, data: &[u8]) -> Result<FastbootResponse> {
        let size_header = format!("download:{:08x}", data.len());
        self.send_command(&size_header)?;

        // 分块传输
        for chunk in data.chunks(Self::FB_MAX_PAYLOAD) {
            self.usb_write(chunk)?;
        }

        self.read_response()
    }

    /// 刷写分区
    pub fn flash(&mut self, partition: &str, image_data: &[u8]) -> Result<FastbootResponse> {
        // 1. 下载镜像到设备 RAM
        self.download(image_data)?;
        // 2. 写入分区
        self.send_command(&format!("flash:{}", partition))
    }

    /// 读取响应 (OKAY / FAIL / DATA / INFO)
    fn read_response(&mut self) -> Result<FastbootResponse> {
        let mut buf = [0u8; 256];
        let n = self.usb_read(&mut buf)?;
        let response_type = &buf[0..4];
        let payload = String::from_utf8_lossy(&buf[4..n]).to_string();

        match response_type {
            b"OKAY" => Ok(FastbootResponse::Okay(payload)),
            b"FAIL" => Err(FastbootError::CommandFailed(payload)),
            b"DATA" => Ok(FastbootResponse::Data(
                u32::from_str_radix(&payload, 16).unwrap_or(0)
            )),
            b"INFO" => Ok(FastbootResponse::Info(payload)),
            _ => Err(FastbootError::InvalidResponse),
        }
    }
}

#[derive(Debug)]
pub enum FastbootResponse {
    Okay(String),       // 命令成功
    Data(u32),          // 设备准备接收 data, 参数为字节数
    Info(String),       // 信息性消息
}

#[derive(Debug)]
pub enum FastbootError {
    CommandTooLong,
    CommandFailed(String),  // FAIL 响应
    InvalidResponse,
    UsbError(String),
    Timeout,
}
```

**Kotlin FastbootManager：**

```kotlin
@Singleton
class FastbootManager @Inject constructor(
    private val fastbootBinary: FastbootBinaryManager,
    private val adbBinary: AdbBinaryManager,
    private val commandExecutor: CommandExecutor,
    private val confirmationGate: ConfirmationGate
) {
    /** 检测 Fastboot 设备 */
    suspend fun detectFastbootDevice(): FastbootDevice? {
        val fb = fastbootBinary.ensureExtracted()
        val result = commandExecutor.execute("$fb devices", timeout = 5)
        // 输出格式: "SERIAL    fastboot"
        return parseFastbootDevice(result.stdout)
    }

    /** 获取设备变量（分区表、序列号等）*/
    suspend fun getVar(serial: String, varName: String): Result<String> {
        val fb = fastbootBinary.ensureExtracted()
        val result = commandExecutor.execute(
            "$fb -s $serial getvar $varName", timeout = 10
        )
        return if (result.exitCode == 0) Result.success(parseGetVar(result.stdout))
               else Result.failure(FlashException("getvar 失败: ${result.stderr}"))
    }

    /** 获取全部设备信息 */
    suspend fun getDeviceInfo(serial: String): Result<FastbootDeviceInfo> {
        val vars = mutableMapOf<String, String>()
        for (key in listOf(
            "product", "serialno", "secure", "unlocked",
            "hw-revision", "boot-slot", "current-slot",
            "slot-count", "partition-type:system",
            "partition-size:system"
        )) {
            getVar(serial, key).onSuccess { vars[key] = it }
        }
        return Result.success(FastbootDeviceInfo(vars))
    }

    /**
     * 刷写分区镜像
     * ⚠️ DESTRUCTIVE — 必须用户确认
     */
    suspend fun flash(
        serial: String,
        partition: String,
        imagePath: VfsPath,
        skipReboot: Boolean = false
    ): Result<Unit> {
        // 1. 安全校验
        val safePartitions = setOf(
            "boot", "init_boot", "vbmeta", "dtbo", "vendor_boot",
            "system", "vendor", "product", "odm", "system_ext"
        )
        val dangerousPartitions = setOf(
            "bootloader", "xbl", "abl", "tz", "rpm", "modem",
            "persist", "frp", "partition"
        )

        if (partition in dangerousPartitions) {
            confirmationGate.requireConfirm(
                conversationId,
                "⚠️ 正在刷写危险分区 '$partition'，操作失误可能导致设备变砖！确认继续？"
            )
        } else if (partition !in safePartitions) {
            confirmationGate.requireConfirm(
                conversationId,
                "分区 '$partition' 不在已知安全列表中，确认继续？"
            )
        }

        // 2. 校验镜像文件
        val imageSize = vfs.stat(imagePath).size
        if (imageSize > MAX_FLASH_SIZE) {
            return Result.failure(FlashException("镜像文件过大: ${imageSize / 1024 / 1024}MB"))
        }

        // 3. 执行刷写
        val fb = fastbootBinary.ensureExtracted()
        val result = commandExecutor.executeStreaming(
            "$fb -s $serial flash $partition ${imagePath.value}",
            timeout = 300
        )

        // 4. 可选自动重启
        if (!skipReboot && result.isSuccess()) {
            commandExecutor.execute("$fb -s $serial reboot", timeout = 30)
        }

        return if (result.exitCode == 0) Result.success(Unit)
               else Result.failure(FlashException("刷写失败: ${result.stderr}"))
    }

    /**
     * 临时启动镜像（不写入闪存）
     * 安全 — 不修改设备存储
     */
    suspend fun boot(serial: String, imagePath: VfsPath): Result<Unit> {
        val fb = fastbootBinary.ensureExtracted()
        val result = commandExecutor.execute(
            "$fb -s $serial boot ${imagePath.value}", timeout = 60
        )
        return if (result.exitCode == 0) Result.success(Unit)
               else Result.failure(FlashException("boot 失败: ${result.stderr}"))
    }

    /** 解锁 Bootloader */
    suspend fun unlockBootloader(serial: String): Result<Unit> {
        confirmationGate.requireConfirm(
            conversationId,
            "⚠️ 解锁 Bootloader 将清除所有用户数据并使保修失效！确认解锁？"
        )
        val fb = fastbootBinary.ensureExtracted()
        // 先尝试新式命令，回退到旧式
        var result = commandExecutor.execute(
            "$fb -s $serial flashing unlock", timeout = 30
        )
        if (result.exitCode != 0) {
            result = commandExecutor.execute(
                "$fb -s $serial oem unlock", timeout = 30
            )
        }
        return if (result.exitCode == 0) Result.success(Unit)
               else Result.failure(FlashException("解锁失败: ${result.stderr}"))
    }

    /** A/B 分区切换 */
    suspend fun setActiveSlot(serial: String, slot: String): Result<Unit> {
        require(slot in listOf("a", "b")) { "Invalid slot: $slot" }
        val fb = fastbootBinary.ensureExtracted()
        val result = commandExecutor.execute(
            "$fb -s $serial set_active $slot", timeout = 10
        )
        return if (result.exitCode == 0) Result.success(Unit)
               else Result.failure(FlashException("切换 slot 失败"))
    }

    /** 重启到指定模式 */
    suspend fun reboot(serial: String, target: RebootTarget): Result<Unit> {
        val fb = fastbootBinary.ensureExtracted()
        val cmd = when (target) {
            RebootTarget.SYSTEM -> "reboot"
            RebootTarget.RECOVERY -> "reboot recovery"
            RebootTarget.BOOTLOADER -> "reboot bootloader"
            RebootTarget.FASTBOOTD -> "reboot fastboot"
            RebootTarget.EDL -> "reboot edl"           // Qualcomm EDL
        }
        val result = commandExecutor.execute("$fb -s $serial $cmd", timeout = 15)
        return if (result.exitCode == 0) Result.success(Unit)
               else Result.failure(FlashException("重启失败"))
    }

    companion object {
        private const val MAX_FLASH_SIZE = 4L * 1024 * 1024 * 1024  // 4GB
    }
}

enum class RebootTarget { SYSTEM, RECOVERY, BOOTLOADER, FASTBOOTD, EDL }

data class FastbootDevice(
    val serial: String,
    val product: String,
    val unlocked: Boolean,
    val slotCount: Int,
    val currentSlot: String
)

data class FastbootDeviceInfo(
    val vars: Map<String, String>
)
```

#### 3. EDL (Emergency Download Mode) — Qualcomm QFIL

EDL 是高通芯片的紧急下载模式（又称 Qualcomm 9008 模式），是最后的救砖手段。设备变砖后唯一可用的刷机方式。

**EDL 触发方式：**

| 触发方式 | 条件 | 说明 |
|---|---|---|
| `fastboot reboot edl` | 设备在 Fastboot | 最常用 |
| Vol- + USB 插入 | 设备关机 | 硬件按键组合 |
| `adb reboot edl` | 设备在系统/Recovery | 需要调试权限 |
| 自动进入 | Bootloader 损坏 | 设备自动进入 9008 |

**EDL 协议栈：**

```
┌─────────────────────────────────────────────────┐
│  EDL 协议栈 (Qualcomm)                          │
│                                                 │
│  ┌─────────────────────────────────────────┐    │
│  │  QFIL (高层) — 分区刷写/备份/擦除        │    │
│  │  - firehose: <program> / <read> / <erase>│    │
│  │  - 单文件/多文件刷入                      │    │
│  └──────────────┬──────────────────────────┘    │
│                 │                                │
│  ┌──────────────▼──────────────────────────┐    │
│  │  Firehose 协议 (XML 命令)                │    │
│  │  - 签名/非签名 programmer 加载            │    │
│  │  - XML over USB bulk transfer            │    │
│  │  - <configure> → <program> → <patch>     │    │
│  └──────────────┬──────────────────────────┘    │
│                 │                                │
│  ┌──────────────▼──────────────────────────┐    │
│  │  Sahara 协议 (握手/加载 programmer)       │    │
│  │  - Hello → Hello Resp → Mode → Data     │    │
│  │  - 加载 firehose programmer (MBN 文件)   │    │
│  └──────────────┬──────────────────────────┘    │
│                 │                                │
│  ┌──────────────▼──────────────────────────┐    │
│  │  USB Layer 2/3                           │    │
│  │  - VID: 0x05C6  PID: 0x9008             │    │
│  │  - Bulk EP: IN=0x81, OUT=0x01           │    │
│  │  - 签名设备需要 Layer 3 (绕过内核驱动)    │    │
│  └─────────────────────────────────────────┘    │
└─────────────────────────────────────────────────┘
```

**Sahara 协议 (Rust)：**

```rust
/// Sahara 协议 — EDL 握手与 programmer 加载
pub struct SaharaProtocol {
    usb: UsbDeviceHandle,
    ep_in: u8,
    ep_out: u8,
}

// Sahara 命令 ID
const SAHARA_HELLO: u32 = 0x01;
const SAHARA_HELLO_RESP: u32 = 0x02;
const SAHARA_READ_DATA: u32 = 0x03;
const SAHARA_END_IMAGE_TRANSFER: u32 = 0x04;
const SAHARA_DONE: u32 = 0x05;
const SAHARA_DONE_RESP: u32 = 0x06;
const SAHARA_RESET: u32 = 0x07;
const SAHARA_MODE_COMMAND: u32 = 0x0B;

/// Sahara 模式
#[repr(u32)]
pub enum SaharaMode {
    ImageTransfer = 0x01,      // 加载 programmer
    CommandMode = 0x02,        // 命令模式
    MemoryDebug = 0x03,        // 内存调试
}

impl SaharaProtocol {
    /// 与设备握手，加载 firehose programmer
    pub fn handshake_and_load(
        &mut self,
        programmer: &[u8],     // firehose programmer MBN 数据
    ) -> Result<()> {
        // 1. 接收 Hello
        let hello = self.receive_command()?;
        if hello.command_id != SAHARA_HELLO {
            return Err(SaharaError::UnexpectedCommand);
        }

        // 2. 回复 Hello Resp — 选择 Image Transfer 模式
        let resp = SaharaPacket {
            command_id: SAHARA_HELLO_RESP,
            ..Self::build_hello_resp(SaharaMode::ImageTransfer, programmer.len() as u32)
        };
        self.send_packet(&resp)?;

        // 3. 循环发送 programmer 数据
        let mut offset: u32 = 0;
        loop {
            let cmd = self.receive_command()?;
            match cmd.command_id {
                SAHARA_READ_DATA => {
                    // 设备请求数据: [offset, length]
                    let read_offset = cmd.data[0];
                    let read_length = cmd.data[1] as usize;
                    let end = (read_offset as usize + read_length).min(programmer.len());
                    self.usb_bulk_write(&programmer[read_offset as usize..end])?;
                    offset = end as u32;
                }
                SAHARA_END_IMAGE_TRANSFER => {
                    // 传输完成
                    let done = SaharaPacket {
                        command_id: SAHARA_DONE,
                        ..Default::default()
                    };
                    self.send_packet(&done)?;
                    break;
                }
                _ => return Err(SaharaError::UnexpectedCommand),
            }
        }

        // 4. 等待 Done Resp
        let done_resp = self.receive_command()?;
        if done_resp.command_id != SAHARA_DONE_RESP {
            return Err(SaharaError::HandshakeFailed);
        }

        Ok(())
    }
}

#[derive(Debug)]
pub enum SaharaError {
    UnexpectedCommand,
    HandshakeFailed,
    UsbError(String),
    Timeout,
}
```

**Firehose 协议 (Rust)：**

```rust
/// Firehose 协议 — XML 命令式分区操作
pub struct FirehoseProtocol {
    usb: UsbDeviceHandle,
    ep_in: u8,
    ep_out: u8,
    max_payload_size: u32,  // 设备支持的最大传输大小
}

impl FirehoseProtocol {
    /// 配置 Firehose 参数
    pub fn configure(&mut self, storage_type: StorageType) -> Result<FirehoseConfig> {
        let xml = format!(
            r#"<?xml version="1.0" ?>
<data>
  <configure MemoryName="{storage}" Verbose="0"
            MaxPayloadSizeToTargetInBytes="1048576"
            MaxPayloadSizeToTargetInBytesSupported="1048576"/>
</data>"#,
            storage = match storage_type {
                StorageType::UFS => "ufs",
                StorageType::EMMC => "emmc",
                StorageType::NAND => "nand",
            }
        );
        self.send_xml(&xml)?;
        let resp = self.read_response()?;
        parse_configure_response(resp)
    }

    /// 刷写分区
    pub fn program(
        &mut self,
        partition: &str,
        start_sector: u64,
        image_data: &[u8],
        on_progress: impl Fn(f32),
    ) -> Result<()> {
        let sector_size = 4096u64;  // UFS 扇区大小
        let num_sectors = (image_data.len() as u64 + sector_size - 1) / sector_size;

        // 1. 发送 program 命令
        let cmd_xml = format!(
            r#"<?xml version="1.0" ?>
<data>
  <program SECTOR_SIZE_IN_BYTES="{sector_size}"
           num_partition_sectors="{num_sectors}"
           physical_partition_number="0"
           start_sector="{start_sector}"
           filename="{partition}"/>
</data>"#,
            sector_size = sector_size,
            num_sectors = num_sectors,
            start_sector = start_sector,
            partition = partition,
        );
        self.send_xml(&cmd_xml)?;

        // 2. 传输镜像数据（分块）
        let chunk_size = self.max_payload_size as usize;
        for (i, chunk) in image_data.chunks(chunk_size).enumerate() {
            self.usb_bulk_write(chunk)?;
            on_progress((i as f32 * chunk_size as f32 / image_data.len() as f32).min(1.0));
        }

        // 3. 读取响应
        let resp = self.read_response()?;
        if resp.contains("value=\"ACK\"") {
            Ok(())
        } else {
            Err(FirehoseError::ProgramFailed(resp))
        }
    }

    /// 读取分区（备份）
    pub fn read_partition(
        &mut self,
        partition: &str,
        start_sector: u64,
        num_sectors: u64,
        on_progress: impl Fn(f32),
    ) -> Result<Vec<u8>> {
        let sector_size = 4096u64;
        let total_bytes = num_sectors * sector_size;
        let mut buffer = Vec::with_capacity(total_bytes as usize);

        let cmd_xml = format!(
            r#"<?xml version="1.0" ?>
<data>
  <read SECTOR_SIZE_IN_BYTES="{sector_size}"
        num_partition_sectors="{num_sectors}"
        physical_partition_number="0"
        start_sector="{start_sector}"/>
</data>"#,
        );
        self.send_xml(&cmd_xml)?;

        let chunk_size = self.max_payload_size as usize;
        while buffer.len() < total_bytes as usize {
            let remaining = total_bytes as usize - buffer.len();
            let to_read = chunk_size.min(remaining);
            let mut chunk = vec![0u8; to_read];
            self.usb_bulk_read(&mut chunk)?;
            buffer.extend_from_slice(&chunk);
            on_progress(buffer.len() as f32 / total_bytes as f32);
        }

        Ok(buffer)
    }

    /// 擦除分区
    pub fn erase(
        &mut self,
        start_sector: u64,
        num_sectors: u64,
    ) -> Result<()> {
        let cmd_xml = format!(
            r#"<?xml version="1.0" ?>
<data>
  <erase SECTOR_SIZE_IN_BYTES="4096"
         num_partition_sectors="{num_sectors}"
         physical_partition_number="0"
         start_sector="{start_sector}"/>
</data>"#,
        );
        self.send_xml(&cmd_xml)?;
        let resp = self.read_response()?;
        if resp.contains("value=\"ACK\"") { Ok(()) }
        else { Err(FirehoseError::EraseFailed(resp)) }
    }

    /// 获取分区表 (GPT)
    pub fn get_gpt(&mut self) -> Result<GptTable> {
        // 读取 LBA 0-33 (GPT 表头 + 分区条目)
        let raw = self.read_partition("gpt", 0, 34, |_| {})?;
        GptTable::parse(&raw)
    }

    fn send_xml(&mut self, xml: &str) -> Result<()> {
        self.usb_bulk_write(xml.as_bytes())
    }

    fn read_response(&mut self) -> Result<String> {
        let mut buf = vec![0u8; 4096];
        let n = self.usb_bulk_read(&mut buf)?;
        Ok(String::from_utf8_lossy(&buf[..n]).to_string())
    }
}

#[derive(Debug)]
pub enum StorageType { UFS, EMMC, NAND }

#[derive(Debug)]
pub enum FirehoseError {
    ProgramFailed(String),
    EraseFailed(String),
    ConfigureFailed(String),
    UsbError(String),
    Timeout,
    InvalidXml(String),
}

/// GPT 分区表解析
pub struct GptTable {
    pub partitions: Vec<GptPartition>,
}

pub struct GptPartition {
    pub name: String,
    pub start_lba: u64,
    pub end_lba: u64,
    pub attributes: u64,
    pub type_guid: [u8; 16],
}

impl GptTable {
    pub fn parse(raw: &[u8]) -> Result<Self> {
        // 跳过 Protective MBR (LBA 0), 从 GPT Header (LBA 1) 开始
        let header = &raw[512..];
        let partition_count = u32::from_le_bytes(header[80..84].try_into()?);
        let entry_size = u32::from_le_bytes(header[84..88].try_into()?);
        let partition_entries_start = u64::from_le_bytes(header[72..80].try_into()?);

        let mut partitions = Vec::new();
        let entries_offset = partition_entries_start as usize * 512;
        for i in 0..partition_count as usize {
            let offset = entries_offset + i * entry_size as usize;
            if offset + entry_size as usize > raw.len() { break; }
            let entry = &raw[offset..offset + entry_size as usize];

            // 分区名: UTF-16LE at offset 56
            let name_bytes = &entry[56..128];
            let name = String::from_utf16le_lossy(name_bytes)
                .trim_end_matches('\0').to_string();

            partitions.push(GptPartition {
                type_guid: entry[0..16].try_into()?,
                start_lba: u64::from_le_bytes(entry[32..40].try_into()?),
                end_lba: u64::from_le_bytes(entry[40..48].try_into()?),
                attributes: u64::from_le_bytes(entry[48..56].try_into()?),
                name,
            });
        }

        Ok(Self { partitions })
    }
}
```

**Kotlin EDL 管理：**

```kotlin
@Singleton
class EdlManager @Inject constructor(
    private val confirmationGate: ConfirmationGate,
    private val usbRouter: UsbRouter
) {
    companion object {
        // Qualcomm 9008 设备标识
        private const val EDL_VENDOR_ID = 0x05C6
        private const val EDL_PRODUCT_ID = 0x9008
    }

    /** 检测 EDL 设备 (VID: 05C6, PID: 9008) */
    suspend fun detectEdlDevice(): EdlDevice? {
        // 通过 UsbManager 枚举 USB 设备
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = usbManager.deviceList.values.find {
            it.vendorId == EDL_VENDOR_ID && it.productId == EDL_PRODUCT_ID
        }
        return device?.let {
            EdlDevice(
                deviceName = it.deviceName,
                vendorId = it.vendorId,
                productId = it.productId,
                interfaces = it.interfaceCount
            )
        }
    }

    /**
     * EDL 全流程刷机
     * 1. Sahara 握手 → 加载 firehose programmer
     * 2. Firehose 配置 → 获取分区表
     * 3. 刷写分区
     */
    suspend fun flashEdl(
        programmerData: ByteArray,     // firehose programmer (MBN)
        flashImages: List<FlashImage>,  // 待刷分区列表
        storageType: StorageType = StorageType.UFS
    ): Result<EdlFlashResult> {
        // ⚠️ 最高风险操作 — 必须确认
        confirmationGate.requireConfirm(
            conversationId,
            "⚠️ EDL 刷机是最低层操作，任何错误都可能导致设备永久变砖！" +
            "即将刷写 ${flashImages.size} 个分区。确认继续？"
        )

        // 1. 获取 USB 设备 (需要 Layer 2 或 Layer 3)
        val usbDevice = detectEdlDevice()
            ?: return Result.failure(FlashException("未检测到 EDL 设备 (9008)"))

        val layer = usbRouter.resolveLayer(
            usbDevice,
            UsbRequirement.PRECISE_TIMING  // EDL 需要精确时序 → Layer 2
        )

        // 2. 通过 JNI 调用 Rust EDL 协议栈
        return nativeEdlFlash(
            usbDevice = usbDevice,
            programmer = programmerData,
            flashImages = flashImages.map {
                NativeFlashImage(it.partition, it.startSector, it.data)
            }.toTypedArray(),
            storageType = storageType,
            usbLayer = layer
        )
    }

    /**
     * 读取分区备份
     */
    suspend fun backupPartition(
        programmerData: ByteArray,
        partition: String,
        startSector: ULong,
        numSectors: ULong,
        storageType: StorageType = StorageType.UFS
    ): Result<ByteArray> {
        val usbDevice = detectEdlDevice()
            ?: return Result.failure(FlashException("未检测到 EDL 设备"))
        return nativeEdlRead(
            usbDevice = usbDevice,
            programmer = programmerData,
            partition = partition,
            startSector = startSector,
            numSectors = numSectors,
            storageType = storageType
        )
    }

    /** 获取分区表 (GPT) */
    suspend fun readGpt(
        programmerData: ByteArray,
        storageType: StorageType = StorageType.UFS
    ): Result<GptTable> {
        val usbDevice = detectEdlDevice()
            ?: return Result.failure(FlashException("未检测到 EDL 设备"))
        return nativeReadGpt(usbDevice, programmerData, storageType)
    }

    // ── JNI native 方法 ──

    private external fun nativeEdlFlash(
        usbDevice: EdlDevice,
        programmer: ByteArray,
        flashImages: Array<NativeFlashImage>,
        storageType: StorageType,
        usbLayer: UsbAccessLayer
    ): Result<EdlFlashResult>

    private external fun nativeEdlRead(
        usbDevice: EdlDevice,
        programmer: ByteArray,
        partition: String,
        startSector: ULong,
        numSectors: ULong,
        storageType: StorageType
    ): Result<ByteArray>

    private external fun nativeReadGpt(
        usbDevice: EdlDevice,
        programmer: ByteArray,
        storageType: StorageType
    ): Result<GptTable>
}

data class EdlDevice(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val interfaces: Int
)

data class FlashImage(
    val partition: String,
    val startSector: ULong,
    val data: ByteArray
)

data class NativeFlashImage(
    val partition: String,
    val startSector: ULong,
    val data: ByteArray
)

data class EdlFlashResult(
    val partitionsFlashed: List<String>,
    val totalBytes: ULong,
    val durationMs: ULong
)

data class GptTable(
    val partitions: List<GptPartition>
)

data class GptPartition(
    val name: String,
    val startLba: ULong,
    val endLba: ULong,
    val sizeBytes: ULong,
    val typeGuid: String,
    val attributes: ULong
)

enum class StorageType { UFS, EMMC, NAND }
```

#### 4. 统一刷机管理器 — FlashDeviceManager

三种模式统一管理，自动检测设备状态，选择正确的协议：

```kotlin
@Singleton
class FlashDeviceManager @Inject constructor(
    private val recoveryManager: RecoveryManager,
    private val fastbootManager: FastbootManager,
    private val edlManager: EdlManager,
    private val adbBinary: AdbBinaryManager,
    private val fastbootBinary: FastbootBinaryManager
) {
    /**
     * 自动检测连接设备的当前模式
     * 优先级: EDL > Fastboot > Recovery > ADB
     */
    suspend fun detectDeviceMode(): DeviceMode {
        // 1. 检测 EDL (VID:05C6 PID:9008)
        edlManager.detectEdlDevice()?.let {
            return DeviceMode.EDL(it)
        }

        // 2. 检测 Fastboot
        fastbootManager.detectFastbootDevice()?.let {
            return DeviceMode.FASTBOOT(it)
        }

        // 3. 检测 Recovery
        recoveryManager.detectRecoveryDevice()?.let {
            return DeviceMode.RECOVERY(it)
        }

        // 4. 检测正常 ADB
        val adb = adbBinary.ensureExtracted()
        val result = commandExecutor.execute("$adb devices -l", timeout = 5)
        val adbDevice = parseAdbDevice(result.stdout)
        return if (adbDevice != null) DeviceMode.ADB(adbDevice)
               else DeviceMode.NONE
    }

    /**
     * 切换设备模式
     * 支持方向:
     *   ADB → Recovery:      adb reboot recovery
     *   ADB → Bootloader:    adb reboot bootloader
     *   ADB → EDL:           adb reboot edl (Qualcomm)
     *   Recovery → ADB:      adb reboot
     *   Recovery → Bootloader: adb reboot bootloader
     *   Fastboot → Recovery: fastboot reboot recovery
     *   Fastboot → EDL:      fastboot reboot edl
     *   Fastboot → System:   fastboot reboot
     *   EDL → (只能刷机后重启)
     */
    suspend fun switchMode(
        from: DeviceMode,
        to: DeviceMode.Target
    ): Result<Unit> = when {
        // ADB → Recovery / Bootloader / EDL
        from is DeviceMode.ADB && to == DeviceMode.Target.RECOVERY ->
            adbReboot("recovery", from.device.serial)
        from is DeviceMode.ADB && to == DeviceMode.Target.BOOTLOADER ->
            adbReboot("bootloader", from.device.serial)
        from is DeviceMode.ADB && to == DeviceMode.Target.EDL ->
            adbReboot("edl", from.device.serial)

        // Fastboot → Recovery / System / EDL
        from is DeviceMode.FASTBOOT && to == DeviceMode.Target.RECOVERY ->
            fastbootManager.reboot(from.device.serial, RebootTarget.RECOVERY)
        from is DeviceMode.FASTBOOT && to == DeviceMode.Target.SYSTEM ->
            fastbootManager.reboot(from.device.serial, RebootTarget.SYSTEM)
        from is DeviceMode.FASTBOOT && to == DeviceMode.Target.EDL ->
            fastbootManager.reboot(from.device.serial, RebootTarget.EDL)

        // Recovery → System / Bootloader
        from is DeviceMode.RECOVERY && to == DeviceMode.Target.SYSTEM ->
            adbReboot("", from.device.serial)
        from is DeviceMode.RECOVERY && to == DeviceMode.Target.BOOTLOADER ->
            adbReboot("bootloader", from.device.serial)

        else -> Result.failure(FlashException("不支持的模式切换: $from → $to"))
    }

    private suspend fun adbReboot(target: String, serial: String): Result<Unit> {
        val adb = adbBinary.ensureExtracted()
        val cmd = if (target.isEmpty()) "$adb -s $serial reboot"
                  else "$adb -s $serial reboot $target"
        val result = commandExecutor.execute(cmd, timeout = 15)
        return if (result.exitCode == 0) Result.success(Unit)
               else Result.failure(FlashException("重启失败"))
    }
}

/**
 * 设备模式 — sealed class 确保类型安全
 */
sealed class DeviceMode {
    data class ADB(val device: AdbDevice) : DeviceMode()
    data class RECOVERY(val device: RecoveryDevice) : DeviceMode()
    data class FASTBOOT(val device: FastbootDevice) : DeviceMode()
    data class EDL(val device: EdlDevice) : DeviceMode()
    object NONE : DeviceMode()

    enum class Target { SYSTEM, RECOVERY, BOOTLOADER, EDL, FASTBOOTD }
}
```

#### 5. 刷机安全策略

刷机操作是最高风险功能，必须多层保护：

```kotlin
@Singleton
class FlashSafetyGuard @Inject constructor(
    private val confirmationGate: ConfirmationGate
) {
    /** 分区风险等级 */
    fun partitionRiskLevel(partition: String): RiskLevel = when (partition) {
        // 🔴 砖机级 — 错误写入几乎必然变砖
        "bootloader", "xbl", "xbl_config", "abl", "tz", "rpm",
        "hyp", "sec", "devcfg", "cmnlib", "cmnlib64", "keymaster",
        "keystore", "frp", "persist", "modemst1", "modemst2",
        "partition", "rawrpm", "rawmodem" -> RiskLevel.BRICK

        // 🟡 高危 — 可能变砖但可恢复
        "boot", "init_boot", "vbmeta", "vbmeta_system", "dtbo",
        "vendor_boot", "recovery", "modem", "bluetooth" -> RiskLevel.HIGH

        // 🟢 安全 — 刷错可轻松恢复
        "system", "vendor", "product", "odm", "system_ext",
        "userdata", "cache" -> RiskLevel.SAFE

        else -> RiskLevel.UNKNOWN  // 未知分区按高危处理
    }

    /** 刷机前检查清单 */
    suspend fun preFlashCheck(params: FlashCheckParams): Result<FlashCheckResult> {
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()

        // 1. 电量检查 (仅 ADB/Recovery 模式可检查)
        if (params.batteryLevel != null && params.batteryLevel < 20) {
            warnings.add("设备电量 ${params.batteryLevel}%，建议充电至 50% 以上再刷机")
        }

        // 2. 分区风险检查
        params.partitions.forEach { partition ->
            when (partitionRiskLevel(partition)) {
                RiskLevel.BRICK -> errors.add("分区 '$partition' 为砖机级风险，禁止直接刷写！需专家确认")
                RiskLevel.HIGH -> warnings.add("分区 '$partition' 为高风险，确认你有正确的镜像文件")
                RiskLevel.UNKNOWN -> warnings.add("分区 '$partition' 不在已知列表中，谨慎操作")
                RiskLevel.SAFE -> { /* OK */ }
            }
        }

        // 3. 镜像文件校验
        params.images.forEach { image ->
            if (image.size == 0L) {
                errors.add("镜像文件 ${image.path} 为空")
            }
            if (image.expectedChecksum != null && image.actualChecksum != null) {
                if (image.expectedChecksum != image.actualChecksum) {
                    errors.add("镜像 ${image.path} 校验和不匹配！文件可能已损坏")
                }
            }
        }

        // 4. USB 连接稳定性
        if (params.usbConnectionType == "wireless") {
            warnings.add("无线 ADB 连接不稳定，建议使用 USB 直连")
        }

        // 5. 确认要求
        val requireConfirmLevel = when {
            errors.isNotEmpty() -> ConfirmLevel.BLOCKED
            warnings.any { it.contains("砖机级") } -> ConfirmLevel.EXPERT
            warnings.isNotEmpty() -> ConfirmLevel.WARNING
            else -> ConfirmLevel.NONE
        }

        return Result.success(FlashCheckResult(warnings, errors, requireConfirmLevel))
    }
}

enum class RiskLevel { SAFE, HIGH, BRICK, UNKNOWN }
enum class ConfirmLevel { NONE, WARNING, EXPERT, BLOCKED }

data class FlashCheckParams(
    val partitions: List<String>,
    val images: List<FlashImageCheck>,
    val batteryLevel: Int? = null,
    val usbConnectionType: String = "usb"
)

data class FlashImageCheck(
    val path: String,
    val size: Long,
    val expectedChecksum: String? = null,
    val actualChecksum: String? = null
)

data class FlashCheckResult(
    val warnings: List<String>,
    val errors: List<String>,
    val confirmLevel: ConfirmLevel
)
```

#### 6. 内置 Binaries 扩展

```
app/src/main/assets/bin/
├── adb              # arm64 adb (8MB) — Recovery + 日常 ADB
├── fastboot         # arm64 fastboot (5MB) — Fastboot 模式刷机
└── proot            # arm64 proot (1.5MB) — Ubuntu 子系统

app/src/main/assets/edl/
├── programmers/
│   ├── sahara_generic.mbn        # 通用 Firehose programmer (~300KB)
│   ├── prog_emmc_firehose_8998.mbn  # Snapdragon 835
│   ├── prog_emmc_firehose_Sdm660.mbn # Snapdragon 660
│   └── prog_ufs_firehose_sm8150.mbn  # Snapdragon 855 (UFS)
└── scripts/
    └── flash_all.xml              # 示例全刷脚本
```

```kotlin
class EdlBinaryManager(@ApplicationContext private val context: Context) {
    private val edlDir: File
        get() = File(context.filesDir, "edl")

    /** 获取指定芯片的 firehose programmer */
    suspend fun getProgrammer(chipset: String): Result<File> {
        val programmer = File(edlDir, "programmers/${chipset}.mbn")
        if (programmer.exists()) return Result.success(programmer)

        // 从 assets 解压
        val assetPath = "edl/programmers/${chipset}.mbn"
        return try {
            programmer.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(programmer).use { output ->
                    input.copyTo(output)
                }
            }
            Result.success(programmer)
        } catch (e: Exception) {
            Result.failure(FlashException("未找到 $chipset 的 programmer，请手动提供"))
        }
    }

    /** 列出内置 programmer 支持的芯片 */
    fun listSupportedChipsets(): List<String> {
        return context.assets.list("edl/programmers")
            ?.map { it.removeSuffix(".mbn") }
            ?: emptyList()
    }
}
```

#### 7. AI 工具接口扩展 — 刷机相关 ToolParams

```kotlin
sealed class ToolParams {
    // ... 已有子类 ...

    // ── Recovery 操作 ──

    /** ADB Sideload 刷入 */
    data class RecoverySideload(
        val zipPath: String,
        val target: String? = null      // 设备 serial，null 则自动选择
    ) : ToolParams()

    /** Recovery 日志拉取 */
    data class RecoveryLog(
        val target: String? = null
    ) : ToolParams()

    // ── Fastboot 操作 ──

    /** Fastboot 刷写分区 */
    data class FastbootFlash(
        val partition: String,          // 分区名: boot, system, vendor...
        val imagePath: String,          // 镜像文件路径
        val target: String? = null,     // 设备 serial
        val skipReboot: Boolean = false // 刷完不重启
    ) : ToolParams()

    /** Fastboot 临时启动 */
    data class FastbootBoot(
        val imagePath: String,
        val target: String? = null
    ) : ToolParams()

    /** Fastboot 解锁 */
    data class FastbootUnlock(
        val target: String? = null
    ) : ToolParams()

    /** Fastboot 设备信息 */
    data class FastbootInfo(
        val target: String? = null
    ) : ToolParams()

    /** Fastboot 分区擦除 */
    data class FastbootErase(
        val partition: String,
        val target: String? = null
    ) : ToolParams()

    /** Fastboot Slot 切换 */
    data class FastbootSetSlot(
        val slot: String,               // "a" | "b"
        val target: String? = null
    ) : ToolParams()

    // ── EDL 操作 ──

    /** EDL 刷机 */
    data class EdlFlash(
        val programmerPath: String,     // firehose programmer MBN 文件
        val flashImages: List<FlashImageParam>,
        val storageType: String = "ufs", // "ufs" | "emmc" | "nand"
        val target: String? = null
    ) : ToolParams()

    /** EDL 分区备份 */
    data class EdlBackup(
        val programmerPath: String,
        val partition: String,
        val outputPath: String,
        val storageType: String = "ufs",
        val target: String? = null
    ) : ToolParams()

    /** EDL 读取分区表 */
    data class EdlGpt(
        val programmerPath: String,
        val storageType: String = "ufs",
        val target: String? = null
    ) : ToolParams()

    /** 设备模式检测 */
    data class DeviceModeDetect(
        val target: String? = null
    ) : ToolParams()

    /** 设备模式切换 */
    data class DeviceModeSwitch(
        val target: String? = null,
        val to: String                   // "system"|"recovery"|"bootloader"|"edl"|"fastbootd"
    ) : ToolParams()

    data class FlashImageParam(
        val partition: String,
        val startSector: Long = 0,      // 0 = 自动查找
        val imagePath: String
    )
}
```

**工具注册表扩展：**

| Tool Name | ToolParams | Risk Level | 说明 |
|---|---|---|---|
| `recovery_sideload` | `RecoverySideload` | DESTRUCTIVE | ADB sideload 刷入 |
| `recovery_log` | `RecoveryLog` | READ_ONLY | 拉取 Recovery 日志 |
| `fastboot_flash` | `FastbootFlash` | DESTRUCTIVE | 刷写分区镜像 |
| `fastboot_boot` | `FastbootBoot` | MODIFY | 临时启动镜像 |
| `fastboot_unlock` | `FastbootUnlock` | DESTRUCTIVE | 解锁 Bootloader |
| `fastboot_info` | `FastbootInfo` | READ_ONLY | 获取设备信息 |
| `fastboot_erase` | `FastbootErase` | DESTRUCTIVE | 擦除分区 |
| `fastboot_set_slot` | `FastbootSetSlot` | MODIFY | A/B Slot 切换 |
| `edl_flash` | `EdlFlash` | DESTRUCTIVE | EDL 全流程刷机 |
| `edl_backup` | `EdlBackup` | READ_ONLY | EDL 分区备份 |
| `edl_gpt` | `EdlGpt` | READ_ONLY | 读取 GPT 分区表 |
| `device_mode` | `DeviceModeDetect` | READ_ONLY | 检测设备当前模式 |
| `device_switch` | `DeviceModeSwitch` | MODIFY | 切换设备模式 |

#### 8. Rust Native 层扩展 — EDL JNI Bridge

```rust
// src/edl_jni.rs

/// JNI: EDL 刷机入口
#[no_mangle]
pub extern "C" fn Java_com_aishell_native_EdlManager_nativeEdlFlash(
    mut env: JNIEnv,
    _class: JClass,
    usb_fd: jint,           // UsbManager 文件描述符
    programmer: JByteArray, // firehose programmer
    flash_images: JObjectArray,
    storage_type: jint,     // 0=UFS, 1=EMMC, 2=NAND
) -> jobject {
    let programmer_data = convert_java_bytearray(&mut env, &programmer);
    let images = convert_flash_images(&mut env, &flash_images);

    // 1. 初始化 USB
    let usb = match UsbDeviceHandle::from_fd(usb_fd) {
        Ok(h) => h,
        Err(e) => return create_error_result(&mut env, &format!("USB 打开失败: {}", e)),
    };

    // 2. Sahara 握手
    let mut sahara = SaharaProtocol::new(usb);
    if let Err(e) = sahara.handshake_and_load(&programmer_data) {
        return create_error_result(&mut env, &format!("Sahara 握手失败: {}", e));
    }

    // 3. Firehose 配置
    let mut firehose = FirehoseProtocol::new(sahara.into_usb_handle());
    let config = match firehose.configure(StorageType::from(storage_type)) {
        Ok(c) => c,
        Err(e) => return create_error_result(&mut env, &format!("Firehose 配置失败: {}", e)),
    };

    // 4. 逐分区刷写
    let mut flashed = Vec::new();
    let mut total_bytes: u64 = 0;
    let start = std::time::Instant::now();

    for image in &images {
        let sector = if image.start_sector > 0 {
            image.start_sector
        } else {
            // 自动查找分区起始扇区
            match find_partition_sector(&mut firehose, &image.partition) {
                Ok(s) => s,
                Err(e) => return create_error_result(&mut env,
                    &format!("分区 {} 未找到: {}", image.partition, e)),
            }
        };

        if let Err(e) = firehose.program(&image.partition, sector, &image.data, |p| {
            // 进度回调 → 通过 JNI 回调 Kotlin
            send_progress_callback(&mut env, p);
        }) {
            return create_error_result(&mut env,
                &format!("刷写 {} 失败: {}", image.partition, e));
        }

        flashed.push(image.partition.clone());
        total_bytes += image.data.len() as u64;
    }

    // 5. 返回结果
    create_flash_result(&mut env, &flashed, total_bytes, start.elapsed().as_millis() as u64)
}

/// JNI: EDL 分区备份
#[no_mangle]
pub extern "C" fn Java_com_aishell_native_EdlManager_nativeEdlRead(
    mut env: JNIEnv,
    _class: JClass,
    usb_fd: jint,
    programmer: JByteArray,
    partition: JString,
    start_sector: jlong,
    num_sectors: jlong,
    storage_type: jint,
) -> jbyteArray {
    // ... 类似流程: Sahara → Firehose → read_partition ...
}

/// JNI: 读取 GPT 分区表
#[no_mangle]
pub extern "C" fn Java_com_aishell_native_EdlManager_nativeReadGpt(
    mut env: JNIEnv,
    _class: JClass,
    usb_fd: jint,
    programmer: JByteArray,
    storage_type: jint,
) -> jobject {
    // ... Sahara → Firehose → get_gpt → 返回 GptTable JNI 对象 ...
}
```

### 低内存设备适配

```kotlin
// 内存压力响应
@HiltAndroidApp
class AIShellApp : Application(), ComponentCallbacks2 {

    @Inject lateinit var prootManager: ProotManager
    @Inject lateinit var sessionManager: TmuxSessionManager

    override fun onTrimMemory(level: Int) {
        when (level) {
            TRIM_MEMORY_RUNNING_LOW -> {
                // 可用内存低 — 减少 scrollback 缓冲
                TerminalConfig.scrollbackLines = 500  // 从 5000 降级
            }
            TRIM_MEMORY_UI_HIDDEN -> {
                // UI 不可见 — 释放渲染资源
                TerminalRenderer.releaseCaches()
            }
            TRIM_MEMORY_MODERATE -> {
                // 中等压力 — 停止 proot helper 进程
                prootManager.stopHelper()
            }
            TRIM_MEMORY_COMPLETE -> {
                // 严重压力 — detach 所有 tmux 会话，只保留前台 Service
                sessionManager.detachAllSessions()
            }
        }
    }
}
```

### ARM64 Ubuntu Rootfs

```
Rootfs 来源:
  https://cloud-images.ubuntu.com/releases/24.04/release/
  ubuntu-24.04-server-cloudimg-arm64-root.tar.xz  (~60MB 压缩)

为什么选 Ubuntu 24.04 LTS ARM64:
  - ARM64 原生支持，无需 QEMU 模拟（零性能损耗）
  - apt 仓库完整支持 arm64 架构
  - LTS 长期支持到 2029
  - 与 Debian arm64 包兼容

解压后大小: ~800MB (基础) → 1.2GB (含预装包)
存储位置: /data/data/com.aishell/files/ubuntu-root/

重要: 不要用 x86_64 rootfs + QEMU 模拟！
  - QEMU 用户态模拟在 Android 上极慢（10-50x 性能损失）
  - ARM64 设备直接运行 ARM64 rootfs = 原生性能
  - proot 仅做系统调用翻译（ptrace/seccomp），不模拟指令
```

### Termux 兼容与共存

AIShell 与 Termux 可能共存于同一设备。设计原则：**不冲突，可互补**。

```
┌─ Termux (独立进程) ────────────────┐  ┌─ AIShell (独立进程) ──────┐
│  /data/data/com.termux/           │  │  /data/data/com.aishell/  │
│  files/usr/                       │  │  files/ubuntu-root/       │
│  ├── bin/python → pkg install     │  │  ├── bin/python → apt     │
│  ├── lib/ (bionic)               │  │  ├── lib/ (glibc)         │
│  └── home/                       │  │  └── home/                │
│                                   │  │                           │
│  包管理: pkg (apt 修改版)         │  │  包管理: apt (标准 Ubuntu) │
│  libc: Bionic                    │  │  libc: glibc              │
└───────────────────────────────────┘  └───────────────────────────┘

不冲突:
  - 不同的 /data/data 包名 → 文件系统完全隔离
  - 不同的 Unix 用户 → 进程隔离
  - 不同的端口范围 → 网络不冲突

可互补:
  - AIShell 可调用 Termux 的命令: /data/data/com.termux/files/usr/bin/python
  - Termux 的 $PREFIX 不影响 AIShell
  - 共享 /sdcard 存储空间
```

---

## Gradle 多模块结构

```
aishell/
├── app/                          # Android Application 壳
│   └── src/main/
│       ├── AIShellApp.kt
│       └── di/
│
├── core/
│   ├── domain/                   # 纯 Kotlin 领域层（零依赖）
│   │   └── src/main/kotlin/      # Entity + 接口
│   │
│   ├── data/                     # 数据层
│   │   └── src/main/kotlin/      # Room + DataStore + EncryptedFile
│   │
│   ├── engine/                   # Agent 引擎
│   │   └── src/main/kotlin/      # AgentEngine + CommandRouter + ConfirmationGate
│   │
│   ├── ai/                       # AI Provider
│   │   └── src/main/kotlin/      # OpenAI/Claude/MiniMax/Ollama + SSE
│   │
│   ├── terminal/                 # 终端核心（Kotlin 侧）
│   │   └── src/main/kotlin/      # TerminalRenderer + SessionManager + JNI Bridge
│   │
│   ├── executor/                 # 命令执行器
│   │   └── src/main/kotlin/      # Shell/Proot/Shizuku/Adb 执行器
│   │
│   ├── vfs/                      # 虚拟文件系统
│   │   └── src/main/kotlin/      # Local/SFTP/SMB/WebDAV
│   │
│   ├── automation/               # 自动化引擎
│   │   └── src/main/kotlin/      # Cron/Event/Autonomous/Daemon
│   │
│   ├── security/                 # 安全模块
│   │   └── src/main/kotlin/      # SecureKeyStore + CommandSanitizer
│   │
│   └── platform/                 # 平台集成
│       └── src/main/kotlin/      # Shizuku + proot 管理 + USB 管理
│
├── feature/
│   ├── terminal/                 # 终端页面（Compose Screen + ViewModel）
│   ├── sessions/                 # 会话列表
│   ├── files/                    # 文件管理器
│   ├── devices/                  # 设备管理
│   ├── automation-ui/            # 自动化 UI
│   └── settings/                 # 设置
│
├── native/                       # Rust/C 原生代码
│   ├── terminal-core/            # PTY + ANSI Parser + Grid (Rust)
│   │   ├── Cargo.toml
│   │   └── src/
│   │       ├── pty.rs            # PTY 进程管理
│   │       ├── parser.rs         # ANSI escape sequence 解析
│   │       ├── grid.rs           # Terminal Cell Grid
│   │       └── jni.rs            # JNI 桥接
│   │
│   ├── proot-bridge/             # proot C 库桥接
│   │   ├── Cargo.toml
│   │   └── src/
│   │       └── bridge.rs
│   │
│   └── usb-stack/                # libusb 协议栈
│       ├── Cargo.toml
│       └── src/
│           ├── device.rs         # USB 设备枚举
│           ├── transfer.rs       # USB 传输
│           └── jni.rs
│
├── build.gradle.kts
├── settings.gradle.kts
└── Cargo.toml                    # Rust workspace
```

### 模块依赖图（v3）

```
                    ┌─────────┐
                    │   app   │
                    └────┬────┘
                         │
        ┌────────────────┼────────────────┐
        │                │                │
  ┌─────▼─────┐   ┌─────▼─────┐   ┌──────▼─────┐
  │feature:   │   │feature:   │   │feature:*   │
  │terminal   │   │files      │   │(others)    │
  └─────┬─────┘   └─────┬─────┘   └──────┬─────┘
        │                │                │
  ┌─────▼──────┐   ┌────▼────┐           │
  │core:       │   │core:    │           │
  │terminal    │   │vfs      │           │
  └─────┬──────┘   └────┬────┘           │
        │               │                │
        └───────┬───────┴────────────────┘
                │
         ┌──────▼──────┐
         │core:engine  │
         └──────┬──────┘
                │
    ┌───────────┼──────────────┐
    │           │              │
┌───▼───┐ ┌────▼────┐  ┌──────▼─────┐
│core:ai│ │core:    │  │core:       │
│       │ │executor │  │automation  │
└───┬───┘ └────┬────┘  └──────┬─────┘
    │           │              │
    └───────────┼──────────────┘
                │
    ┌───────────┼──────────────┐
    │           │              │
┌───▼───┐ ┌────▼────┐  ┌──────▼─────┐
│core:  │ │core:    │  │core:      │
│data   │ │security │  │platform   │
└───┬───┘ └────┬────┘  └──────┬─────┘
    │           │              │
    └───────────┼──────────────┘
                │
          ┌─────▼─────┐
          │core:domain│  ← 零依赖
          └───────────┘
```

### 新增/变更模块说明

| 模块 | 变更 | 说明 |
|---|---|---|
| `core:terminal` | **新增** | 终端渲染核心：JNI Bridge、Canvas 渲染器、PTY Session 管理 |
| `native/terminal-core` | **新增** | Rust 实现：PTY fork/exec、ANSI 解析、Grid 维护 |
| `native/proot-bridge` | **新增** | Rust 对 proot C 库的 FFI 桥接 |
| `native/usb-stack` | **新增** | Rust libusb 绑定 + JNI |
| `core:tools` | **移除** | Tool 实现分散到各自模块（ShellExec→executor, FileTools→vfs, Adb→platform） |
| `backend/` | **移除** | 不需要 Spring Boot 后端 |

---

## :native:terminal-core — Rust 终端核心

### PTY 管理

```rust
// pty.rs — PTY 进程生命周期

pub struct PtySession {
    master_fd: RawFd,
    child_pid: pid_t,
    grid: Grid,            // Terminal Cell 缓冲区
    parser: AnsiParser,    // ANSI 解析状态机
    scrollback: VecDeque<Line>,
}

impl PtySession {
    /// fork + exec 创建子进程，返回 master fd
    pub fn spawn(
        command: &str,
        args: &[&str],
        env: &HashMap<String, String>,
        cols: u16,
        rows: u16,
    ) -> Result<Self> { ... }

    /// 非阻塞读 master fd → ANSI 解析 → Grid 更新
    /// 返回 dirty region（差分数据）
    pub fn read_output(&mut self) -> Result<DirtyRegion> { ... }

    /// 写入 master fd（用户输入）
    pub fn write_input(&mut self, data: &[u8]) -> Result<()> { ... }

    /// 窗口大小变更
    pub fn resize(&mut self, cols: u16, rows: u16) -> Result<()> { ... }

    /// 发送信号 (SIGINT, SIGTERM, SIGHUP, ...)
    pub fn send_signal(&self, sig: Signal) -> Result<()> { ... }

    /// 检查子进程是否存活
    pub fn is_alive(&self) -> bool { ... }
}

/// 差分区域 — 只传递变化的部分
#[repr(C)]
pub struct DirtyRegion {
    pub start_row: u16,
    pub end_row: u16,
    pub cells: *const CellData,  // DirectByteBuffer 中的数据
    pub len: u32,
}

#[repr(C)]
pub struct CellData {
    pub char: u32,           // Unicode codepoint
    pub fg_color: u32,       // ARGB
    pub bg_color: u32,       // ARGB
    pub flags: u8,           // BOLD|ITALIC|UNDERLINE|BLINK
}
```

### ANSI Parser

```rust
// parser.rs — 状态机解析 ANSI escape sequences

pub struct AnsiParser {
    state: ParserState,
    params: Vec<u16,
    intermediate: Vec<u8>,
    // 当前光标状态
    cursor_row: u16,
    cursor_col: u16,
    fg: u32,
    bg: u32,
    flags: u8,
}

enum ParserState {
    Ground,           // 普通字符
    Escape,           // ESC
    CsiEntry,         // ESC [
    CsiParam,         // 收集参数
    CsiIntermediate,  // 中间字节
    OscString,        // OSC 序列
    DcsEntry,         // DCS 序列
    SosPmApcString,   // SOS/PM/APC
}

impl AnsiParser {
    /// 输入字节流 → 执行 Grid 操作
    /// 性能目标：10MB/s 吞吐量（≈10万行/秒）
    pub fn parse(&mut self, input: &[u8], grid: &mut Grid) -> Vec<GridOp> {
        // 状态机逐字节处理
        // 支持：SGR (颜色), CUP (光标), ED (擦除), EL (行擦除),
        //       SU/SD (滚动), DECSET/DECRST, OSC 标题, etc.
    }
}

enum GridOp {
    PutChar { row: u16, col: u16, ch: char, fg: u32, bg: u32, flags: u8 },
    MoveCursor { row: u16, col: u16 },
    ClearScreen { from: u16, to: u16 },
    ScrollUp { lines: u16 },
    SetTitle { text: String },
}
```

### JNI Bridge

```rust
// jni.rs — Kotlin ↔ Rust 桥接

#[no_mangle]
pub extern "C" fn Java_com_aishell_terminal_TerminalJni_nativeSpawn(
    mut env: JNIEnv,
    _class: JClass,
    command: JString,
    env_map: JObject,
    cols: jint,
    rows: jint,
) -> jlong {
    // 返回 PtySession 指针作为 handle
    let session = PtySession::spawn(...)?;
    Box::into_raw(Box::new(session)) as jlong
}

#[no_mangle]
pub extern "C" fn Java_com_aishell_terminal_TerminalJni_nativeReadOutput(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    buffer: JObject,  // DirectByteBuffer
) -> jint {
    let session = &mut *(handle as *mut PtySession);
    let dirty = session.read_output()?;

    // 零拷贝写入 DirectByteBuffer
    let buf = env.get_direct_buffer_address(&buffer)?;
    // 写入 DirtyRegion header + CellData[]
    ...
}

#[no_mangle]
pub extern "C" fn Java_com_aishell_terminal_TerminalJni_nativeWriteInput(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
) {
    let session = &mut *(handle as *mut PtySession);
    let input = env.convert_byte_array(data)?;
    session.write_input(&input)?;
}

#[no_mangle]
pub extern "C" fn Java_com_aishell_terminal_TerminalJni_nativeResize(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    cols: jint,
    rows: jint,
) {
    let session = &mut *(handle as *mut PtySession);
    session.resize(cols as u16, rows as u16)?;
}

#[no_mangle]
pub extern "C" fn Java_com_aishell_terminal_TerminalJni_nativeDestroy(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let session = Box::from_raw(handle as *mut PtySession);
    session.send_signal(Signal::SIGHUP);
    drop(session);
}
```

---

## :core:terminal — Kotlin 终端层

### TerminalJni — Rust 桥接

```kotlin
object TerminalJni {
    init {
        System.loadLibrary("aishell_terminal")
    }

    // JNI native 方法 — 对应 Rust jni.rs
    private external fun nativeSpawn(
        command: String, env: Map<String, String>, cols: Int, rows: Int
    ): Long

    private external fun nativeReadOutput(handle: Long, buffer: ByteBuffer): Int
    private external fun nativeWriteInput(handle: Long, data: ByteArray)
    private external fun nativeResize(handle: Long, cols: Int, rows: Int)
    private external fun nativeDestroy(handle: Long)

    // Kotlin 侧封装
    fun spawn(command: String, env: Map<String, String>, cols: Int, rows: Int): Long
    fun readOutput(handle: Long, buffer: ByteBuffer): Int
    fun writeInput(handle: Long, data: ByteArray)
    fun resize(handle: Long, cols: Int, rows: Int)
    fun destroy(handle: Long)
}
```

### TerminalSession — PTY 会话管理

```kotlin
class TerminalSession(
    private val handle: Long,
    private val scope: CoroutineScope,
    private val bufferSize: Int = 256 * 1024  // 256KB DirectBuffer
) {
    private val buffer = ByteBuffer.allocateDirect(bufferSize)
    private val _output = Channel<ScreenUpdate>(capacity = Channel.UNLIMITED)
    val output: ReceiveChannel<ScreenUpdate> = _output

    private val readJob = scope.launch(Dispatchers.IO) {
        while (isActive) {
            val len = TerminalJni.readOutput(handle, buffer)
            if (len > 0) {
                buffer.rewind()
                buffer.limit(len)
                val update = decodeScreenUpdate(buffer)
                _output.send(update)
                buffer.clear()
            } else {
                delay(8) // ~120fps 轮询，比信号驱动更简单可靠
            }
        }
    }

    fun writeInput(data: ByteArray) {
        TerminalJni.writeInput(handle, data)
    }

    fun resize(cols: Int, rows: Int) {
        TerminalJni.resize(handle, cols, rows)
    }

    fun destroy() {
        readJob.cancel()
        TerminalJni.destroy(handle)
    }

    private fun decodeScreenUpdate(buffer: ByteBuffer): ScreenUpdate {
        // 解析 DirtyRegion header + CellData[]
        val startRow = buffer.short.toInt()
        val endRow = buffer.short.toInt()
        val cellCount = buffer.int
        val cells = ArrayList<CellData>(cellCount)
        repeat(cellCount) {
            cells.add(CellData(
                char = buffer.int.toChar(),
                fgColor = buffer.int,
                bgColor = buffer.int,
                flags = buffer.get()
            ))
        }
        return ScreenUpdate(startRow, endRow, cells)
    }
}

data class ScreenUpdate(
    val startRow: Int,
    val endRow: Int,
    val cells: List<CellData>
)

data class CellData(
    val char: Char,
    val fgColor: Int,   // ARGB
    val bgColor: Int,
    val flags: Byte      // BOLD=1, ITALIC=2, UNDERLINE=4, BLINK=8
)
```

### TerminalRenderer — Canvas 渲染

```kotlin
class TerminalRenderer(
    private val paint: TextPaint,
    private val charWidth: Float,
    private val charHeight: Float
) {
    // 行缓冲 — 只保留当前可见行
    private var rows: Array<CellRow> = emptyArray()

    fun applyUpdate(update: ScreenUpdate) {
        // 差分更新：只修改 dirty 行
        for (row in update.startRow..update.endRow) {
            if (row < rows.size) {
                rows[row].applyCells(update.cells)
            }
        }
    }

    /**
     * Canvas 绘制 — Compose Canvas 调用
     * 只重绘 dirty 行（Rust 侧已计算差分）
     */
    fun draw(canvas: Canvas, dirtyRows: IntSet) {
        for (row in dirtyRows) {
            if (row >= rows.size) continue
            val cellRow = rows[row]
            val y = row * charHeight

            // 先绘制背景
            for ((col, cell) in cellRow.cells.withIndex()) {
                if (cell.bgColor != 0xFF000000L.toInt()) {
                    paint.color = cell.bgColor
                    canvas.drawRect(
                        col * charWidth, y,
                        (col + 1) * charWidth, y + charHeight,
                        paint
                    )
                }
            }

            // 再绘制前景文字
            for ((col, cell) in cellRow.cells.withIndex()) {
                if (cell.char != ' ' && cell.char != '\u0000') {
                    paint.color = cell.fgColor
                    paint.isFakeBoldText = (cell.flags and 1) != 0
                    canvas.drawText(
                        cell.char.toString(),
                        col * charWidth,
                        y + paint.textSize,
                        paint
                    )
                }
            }
        }
    }
}

class CellRow(val width: Int) {
    val cells: Array<CellData> = Array(width) {
        CellData(' ', 0xFF00FF88.toInt(), 0xFF1A1A2E.toInt(), 0)
    }

    fun applyCells(newCells: List<CellData>) {
        newCells.forEachIndexed { idx, cell ->
            if (idx < width) cells[idx] = cell
        }
    }
}
```

### TerminalView — Compose 终端组件

```kotlin
@Composable
fun TerminalView(
    session: TerminalSession,
    modifier: Modifier = Modifier
) {
    val renderer = remember { TerminalRenderer(...) }
    val dirtyRows = remember { mutableStateOf(IntSet()) }

    // 消费 Rust 输出 → 更新渲染数据
    LaunchedEffect(session) {
        for (update in session.output) {
            renderer.applyUpdate(update)
            dirtyRows.value = dirtyRows.value + (update.startRow..update.endRow)
        }
    }

    // Canvas 绘制
    Canvas(modifier = modifier.fillMaxSize()) {
        renderer.draw(drawContext.canvas, dirtyRows.value)
        dirtyRows.value = IntSet()  // 清除 dirty 标记
    }

    // 输入处理
    var inputText by remember { mutableStateOf("") }
    TextField(
        value = inputText,
        onValueChange = { new ->
            // 直接发送到 PTY，不等回车
            val bytes = (new.drop(inputText.length)).toByteArray()
            session.writeInput(bytes)
            inputText = new
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        onKeyEvent { event ->
            // 处理 Ctrl+C, Ctrl+D, Tab, 方向键等
            handleTerminalKey(event, session)
        }
    )
}
```

---

## proot Ubuntu — 完整 Linux 子系统

### 设计目标

在 Android 上提供**完整 Linux 发行版环境**，无需 root。AI 执行 `apt install`、`python3`、`git`、`node` 等命令时，自动路由到 proot Ubuntu。用户在终端中也能手动进入 Ubuntu shell。

### 三层执行环境

```
┌──────────────────────────────────────────────────────┐
│  Layer 3: Shizuku (ADB/root)                        │
│  ├── 文件系统完全访问 (/sdcard, /data)              │
│  ├── 系统命令 (pm install, am start)                │
│  ├── 应用管理 (卸载、禁用、授权)                    │
│  ├── 输入模拟 (input tap/swipe/text)                │
│  └── 设置修改 (settings put)                        │
│  权限: uid=2000 (shell) 或 uid=0 (root)             │
├──────────────────────────────────────────────────────┤
│  Layer 2: proot Ubuntu                              │
│  ├── 完整 Linux 工具链 (apt/dpkg)                   │
│  ├── 开发语言 (python3, node, go, rust, gcc)        │
│  ├── 网络工具 (curl, wget, ssh, rsync)              │
│  ├── 版本控制 (git)                                 │
│  ├── 数据库 (sqlite3, redis-cli, psql)              │
│  └── 用户可 apt install 任意包                      │
│  权限: proot 虚拟 root (uid 映射)                   │
├──────────────────────────────────────────────────────┤
│  Layer 1: Android App 沙箱                          │
│  ├── 基本文件访问 (app 私有目录 + SAF)              │
│  ├── Android 系统工具 (toybox)                      │
│  └── 网络 (HTTP)                                    │
│  权限: app UID                                      │
└──────────────────────────────────────────────────────┘
```

### 架构

```
┌─ :core:platform ──────────────────────────────────────────────┐
│                                                                │
│  ProotManager (Kotlin)                                        │
│  ├── installStatus: StateFlow<InstallStatus>                  │
│  ├── ensureInstalled()                                        │
│  ├── execute(command, workDir, env) → Flow<CommandEvent>      │
│  └── execInteractive(cols, rows) → PtySession                 │
│       │                                                        │
│       ▼ JNI                                                    │
│  native/proot-bridge (Rust)                                   │
│  ├── proot_spawn() — 启动 proot 进程                          │
│  ├── proot_exec() — 在已有 proot 中执行命令                   │
│  ├── proot_pty() — 交互式 proot PTY                           │
│  └── 直接调用 proot C 库 (libproot.so)                        │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

### Rust 侧 — proot Bridge

```rust
// proot-bridge/src/bridge.rs

use std::ffi::CString;
use std::os::unix::ffi::OsStrExt;

pub struct ProotBridge {
    rootfs_path: PathBuf,
    proot_binary: PathBuf,
    /// 持久化的 proot helper 进程（用于快速命令执行）
    helper_process: Option<HelperProcess>,
}

/// 辅助进程：常驻 proot 内部的 bash，通过 stdin/stdout pipe 通信
/// 避免每次执行命令都要重新启动 proot（冷启动 ~200ms）
struct HelperProcess {
    stdin: File,
    stdout: File,
    pid: pid_t,
}

impl ProotBridge {
    pub fn new(rootfs_path: &Path, proot_binary: &Path) -> Self {
        Self {
            rootfs_path: rootfs_path.to_path_buf(),
            proot_binary: proot_binary.to_path_buf(),
            helper_process: None,
        }
    }

    /// 构建 proot 命令参数
    fn build_proot_args(&self, command: &str, work_dir: Option<&str>) -> Vec<CString> {
        let mut args = vec![
            CString::new(self.proot_binary.to_str().unwrap()).unwrap(),
            // 基础参数
            CString::new("--link2symlink").unwrap(),  // 符号链接支持
            CString::new("-0").unwrap(),               // 模拟 root (uid 0)
            // rootfs
            CString::new("-r").unwrap(),
            CString::new(self.rootfs_path.to_str().unwrap()).unwrap(),
            // 绑定挂载
            CString::new("-b").unwrap(), CString::new("/dev").unwrap(),
            CString::new("-b").unwrap(), CString::new("/proc").unwrap(),
            CString::new("-b").unwrap(), CString::new("/sys").unwrap(),
            // /sdcard 绑定（共享存储访问）
            CString::new("-b").unwrap(), CString::new("/sdcard").unwrap(),
            CString::new("-b").unwrap(), CString::new("/storage").unwrap(),
            // 网络相关
            CString::new("-b").unwrap(), CString::new("/etc/resolv.conf").unwrap(),
        ];

        // 工作目录
        if let Some(dir) = work_dir {
            args.push(CString::new("-w").unwrap());
            args.push(CString::new(dir).unwrap());
        } else {
            args.push(CString::new("-w").unwrap());
            args.push(CString::new("/home").unwrap());
        }

        // 执行命令
        args.push(CString::new("/usr/bin/env").unwrap());
        args.push(CString::new("-i").unwrap());

        // 环境变量
        let env_vars = [
            "HOME=/home",
            "PATH=/usr/local/bin:/usr/bin:/usr/sbin:/bin:/sbin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "ANDROID_ROOT=/system",
            "ANDROID_DATA=/data",
        ];
        for var in &env_vars {
            args.push(CString::new(*var).unwrap());
        }

        args.push(CString::new("sh").unwrap());
        args.push(CString::new("-c").unwrap());
        args.push(CString::new(command).unwrap());

        args
    }

    /// 执行单次命令（非交互）
    pub fn exec_command(
        &mut self,
        command: &str,
        work_dir: Option<&str>,
        env: &HashMap<String, String>,
        timeout_ms: u64,
    ) -> Result<CommandResult> {
        let args = self.build_proot_args(command, work_dir);
        let pid = fork_exec(&args, env)?;

        // 读 stdout/stderr，等 exit
        let result = wait_with_output(pid, timeout_ms)?;
        Ok(result)
    }

    /// 启动交互式 PTY（用于终端）
    /// 返回 master fd，由 PtySession 管理
    pub fn spawn_pty(
        &self,
        cols: u16,
        rows: u16,
        work_dir: Option<&str>,
    ) -> Result<PtySession> {
        let args = self.build_proot_pty_args(work_dir);
        PtySession::spawn_with_args(&args, cols, rows)
    }

    fn build_proot_pty_args(&self, work_dir: Option<&str>) -> Vec<CString> {
        let mut args = vec![
            CString::new(self.proot_binary.to_str().unwrap()).unwrap(),
            CString::new("--link2symlink").unwrap(),
            CString::new("-0").unwrap(),
            CString::new("-r").unwrap(),
            CString::new(self.rootfs_path.to_str().unwrap()).unwrap(),
            CString::new("-b").unwrap(), CString::new("/dev").unwrap(),
            CString::new("-b").unwrap(), CString::new("/proc").unwrap(),
            CString::new("-b").unwrap(), CString::new("/sys").unwrap(),
            CString::new("-b").unwrap(), CString::new("/sdcard").unwrap(),
            CString::new("-b").unwrap(), CString::new("/storage").unwrap(),
            CString::new("-b").unwrap(), CString::new("/etc/resolv.conf").unwrap(),
        ];

        if let Some(dir) = work_dir {
            args.push(CString::new("-w").unwrap());
            args.push(CString::new(dir).unwrap());
        }

        // 启动 bash（交互模式）
        args.push(CString::new("/usr/bin/env").unwrap());
        args.push(CString::new("-i").unwrap());
        args.push(CString::new("HOME=/home").unwrap());
        args.push(CString::new("PATH=/usr/local/bin:/usr/bin:/usr/sbin:/bin:/sbin").unwrap());
        args.push(CString::new("TERM=xterm-256color").unwrap());
        args.push(CString::new("LANG=C.UTF-8").unwrap());
        args.push(CString::new("bash").unwrap());
        args.push(CString::new("--login").unwrap());

        args
    }

    /// 启动常驻 helper 进程（性能优化）
    pub fn start_helper(&mut self) -> Result<()> {
        if self.helper_process.is_some() {
            return Ok(());  // 已启动
        }

        // 启动 proot bash 作为常驻进程
        // 通过 pipe 通信：写入命令 → 读取输出
        // 用特殊标记分隔命令输出
        let (stdin_w, stdin_r) = unix_pipe()?;
        let (stdout_w, stdout_r) = unix_pipe()?;

        let args = self.build_proot_pty_args(None);
        let pid = fork_with_pipes(&args, stdin_r, stdout_w)?;

        self.helper_process = Some(HelperProcess {
            stdin: stdin_w,
            stdout: stdout_r,
            pid,
        });

        Ok(())
    }

    /// 通过 helper 快速执行命令（无需冷启动）
    pub fn exec_via_helper(&mut self, command: &str, timeout_ms: u64) -> Result<CommandResult> {
        let helper = self.helper_process.as_mut()
            .ok_or(Error::HelperNotStarted)?;

        // 写入带标记的命令
        let marker = format!("__AISHELL_EXIT_CODE_{}__", rand::random::<u32>());
        let script = format!(
            "({command}) 2>&1; echo \"{marker}$?\"",
        );
        helper.stdin.write_all(script.as_bytes())?;
        helper.stdin.write_all(b"\n")?;
        helper.stdin.flush()?;

        // 读取输出直到 marker
        let output = read_until_marker(&mut helper.stdout, &marker, timeout_ms)?;
        Ok(output)
    }

    /// 停止 helper
    pub fn stop_helper(&mut self) {
        if let Some(helper) = self.helper_process.take() {
            kill(helper.pid, Signal::SIGTERM);
        }
    }
}

pub struct CommandResult {
    pub stdout: String,
    pub stderr: String,
    pub exit_code: i32,
    pub duration_ms: u64,
}
```

### Kotlin 侧 — ProotManager

```kotlin
@Singleton
class ProotManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _installStatus = MutableStateFlow(InstallStatus.NOT_INSTALLED)
    val installStatus: StateFlow<InstallStatus> = _installStatus.asStateFlow()

    // Rust proot bridge handle
    @Volatile private var bridgeHandle: Long = 0

    private val ubuntuRoot: File
        get() = File(context.filesDir, "ubuntu-root")

    private val prootBinary: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libproot.so")

    enum class InstallStatus {
        NOT_INSTALLED,       // 未安装
        DOWNLOADING,         // 下载中
        EXTRACTING,          // 解压中
        INITIALIZING,        // 初始化中 (apt update)
        READY,               // 可用
        ERROR                // 安装失败
    }

    // ── 安装管理 ──

    suspend fun ensureInstalled() {
        if (_installStatus.value == InstallStatus.READY) return
        if (ubuntuRoot.exists() && File(ubuntuRoot, "bin/bash").exists()) {
            _installStatus.value = InstallStatus.READY
            initBridge()
            return
        }
        installUbuntu()
    }

    private suspend fun installUbuntu() {
        try {
            _installStatus.value = InstallStatus.DOWNLOADING

            // 1. 下载 rootfs
            val rootfsUrl = "https://cloud-images.ubuntu.com/releases/24.04/release/" +
                "ubuntu-24.04-server-cloudimg-arm64-root.tar.xz"
            val archive = File(context.cacheDir, "ubuntu-rootfs.tar.xz")
            downloadFile(rootfsUrl, archive) { progress ->
                // 通知 UI 进度
                _installStatus.value = InstallStatus.DOWNLOADING
            }

            _installStatus.value = InstallStatus.EXTRACTING

            // 2. 解压
            ubuntuRoot.mkdirs()
            extractTarXz(archive, ubuntuRoot)

            archive.delete()

            _installStatus.value = InstallStatus.INITIALIZING

            // 3. 初始化
            initBridge()
            execInUbuntu("apt update && apt install -y python3 git curl wget openssh-client")

            _installStatus.value = InstallStatus.READY
        } catch (e: Exception) {
            _installStatus.value = InstallStatus.ERROR
        }
    }

    private fun initBridge() {
        if (bridgeHandle != 0L) return
        bridgeHandle = ProotBridgeJni.nativeInit(
            ubuntuRoot.absolutePath,
            prootBinary.absolutePath
        )
        ProotBridgeJni.nativeStartHelper(bridgeHandle)
    }

    // ── 命令执行 ──

    /**
     * 在 Ubuntu 中执行命令（非交互）
     * 优先使用 helper 进程（快），fallback 到新进程
     */
    fun execute(
        command: String,
        workDir: String? = null,
        environment: Map<String, String> = emptyMap(),
        timeoutMs: Long = 30_000
    ): Flow<CommandEvent> = channelFlow {
        val startTime = System.currentTimeMillis()

        // 优先通过 helper 执行（避免 proot 冷启动 ~200ms）
        val result = if (bridgeHandle != 0L) {
            ProotBridgeJni.nativeExecViaHelper(
                bridgeHandle, command, timeoutMs
            )
        } else {
            // fallback：启动新 proot 进程
            ProotBridgeJni.nativeExecCommand(
                bridgeHandle, command, workDir, environment, timeoutMs
            )
        }

        // 转换为 CommandEvent 流
        result.stdout.lines().forEach { line ->
            send(CommandEvent.Stdout(line))
        }
        result.stderr.lines().forEach { line ->
            send(CommandEvent.Stderr(line))
        }
        send(CommandEvent.Exit(result.exitCode))
    }

    /**
     * 启动交互式 Ubuntu PTY（终端使用）
     * 返回 PTY session，由 TerminalSession 管理
     */
    fun spawnPty(
        cols: Int,
        rows: Int,
        workDir: String? = null
    ): Long {
        return ProotBridgeJni.nativeSpawnPty(
            bridgeHandle, cols, rows, workDir
        )
    }

    // ── Ubuntu 包管理 ──

    suspend fun installPackages(packages: List<String>): Result<Unit> {
        val cmd = "apt install -y ${packages.joinToString(" ")}"
        val result = execute(cmd, timeoutMs = 120_000).last()
        return if ((result as? CommandEvent.Exit)?.code == 0) {
            Result.success(Unit)
        } else {
            Results.failure(ErrorKind.TOOL_EXECUTION, "安装失败: $cmd")
        }
    }

    suspend fun listInstalledPackages(): Result<List<String>> {
        val output = StringBuilder()
        execute("dpkg --get-selections").collect { event ->
            if (event is CommandEvent.Stdout) output.appendLine(event.line)
        }
        val packages = output.toString().lines()
            .filter { it.contains("\tinstall") }
            .map { it.split("\t")[0].trim() }
        return Result.success(packages)
    }

    // ── Ubuntu 磁盘管理 ──

    fun getRootfsSize(): Long {
        return ubuntuRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    suspend fun cleanCache(): Result<Long> {
        val before = getRootfsSize()
        execute("apt clean && apt autoremove -y").collect {}
        val after = getRootfsSize()
        return Result.success(before - after)
    }

    // ── 生命周期 ──

    fun destroy() {
        if (bridgeHandle != 0L) {
            ProotBridgeJni.nativeStopHelper(bridgeHandle)
            ProotBridgeJni.nativeDestroy(bridgeHandle)
            bridgeHandle = 0L
        }
    }
}
```

### proot Bridge JNI

```kotlin
object ProotBridgeJni {
    init { System.loadLibrary("aishell_proot") }

    // 初始化 bridge（加载 rootfs 路径等）
    private external fun nativeInit(rootfsPath: String, prootBinary: String): Long

    // 启动 helper 进程
    private external fun nativeStartHelper(handle: Long)

    // 通过 helper 执行命令（快路径）
    private external fun nativeExecViaHelper(
        handle: Long, command: String, timeoutMs: Long
    ): CommandResultNative

    // 启动新 proot 进程执行命令（慢路径）
    private external fun nativeExecCommand(
        handle: Long, command: String, workDir: String?,
        env: Map<String, String>, timeoutMs: Long
    ): CommandResultNative

    // 启动交互式 PTY
    private external fun nativeSpawnPty(
        handle: Long, cols: Int, rows: Int, workDir: String?
    ): Long  // pty handle

    // 停止 helper
    private external fun nativeStopHelper(handle: Long)

    // 销毁 bridge
    private external fun nativeDestroy(handle: Long)

    // Native 结果包装
    data class CommandResultNative(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val durationMs: Long
    )
}
```

### Ubuntu rootfs 结构

```
/data/data/com.aishell/files/ubuntu-root/
├── bin/                    # 基础命令 (sh, bash, ls, cp, mv, ...)
├── usr/
│   ├── bin/                # 用户命令
│   │   ├── python3*        # 预装
│   │   ├── git*            # 预装
│   │   ├── curl*           # 预装
│   │   ├── wget*           # 预装
│   │   ├── ssh*            # 预装
│   │   ├── apt*            # 包管理
│   │   ├── dpkg*           # 包管理
│   │   └── ...             # 用户 apt install 的包
│   ├── lib/                # 共享库
│   ├── include/            # 头文件
│   └── local/              # 用户安装的程序
├── lib/                    # 系统共享库
├── etc/
│   ├── resolv.conf         # DNS (bind-mount 宿主)
│   ├── apt/                # apt 源配置
│   └── ssl/                # CA 证书
├── home/                   # 用户目录
│   ├── .bashrc             # 预配置 shell
│   ├── .profile
│   └── projects/           # 用户项目
├── tmp/                    # 临时文件
├── var/
│   ├── cache/apt/          # apt 缓存
│   └── lib/dpkg/           # 包数据库
├── dev/                    # bind-mount 宿主 /dev
├── proc/                   # bind-mount 宿主 /proc
├── sys/                    # bind-mount 宿主 /sys
└── sdcard/                 # bind-mount 宿主 /sdcard (共享存储)
```

### 预装软件

| 类别 | 包 | 说明 |
|---|---|---|
| **构建工具** | `build-essential`, `cmake`, `make` | C/C++ 编译 |
| **语言** | `python3`, `python3-pip` | Python 3 |
| **版本控制** | `git` | Git |
| **网络** | `curl`, `wget`, `openssh-client`, `rsync` | 下载/远程 |
| **数据库** | `sqlite3` | SQLite CLI |
| **文本处理** | `jq`, `vim`, `nano` | JSON 编辑器 |
| **压缩** | `tar`, `unzip`, `xz-utils` | 解压缩 |
| **系统** | `procps`, `htop`, `net-tools` | 进程/网络查看 |

用户可通过 `apt install` 自由安装更多包。

### 网络配置

proot 环境的网络通过 Android 的网络栈透传：

```
┌─ Android Network Stack ────────────────┐
│  WiFi / Mobile Data                    │
│  DNS: /etc/resolv.conf                 │
└──────────────┬─────────────────────────┘
               │ bind-mount
               ▼
┌─ proot Ubuntu ─────────────────────────┐
│  /etc/resolv.conf → 使用宿主 DNS       │
│  网络调用 → 通过 Android 内核路由      │
│  curl/wget/ssh → 正常工作              │
│  apt update → 正常工作                 │
└────────────────────────────────────────┘
```

关键绑定：
- `/etc/resolv.conf` — DNS 解析
- `/dev/` — 网络设备访问
- 不需要额外 VPN/代理配置，继承 Android 网络状态

### 文件系统互通

```
proot Ubuntu 路径           实际 Android 路径
─────────────────────       ─────────────────
/home                       /data/.../ubuntu-root/home
/sdcard                     /sdcard (bind-mount)
/storage                    /storage (bind-mount)
/tmp                        /data/.../ubuntu-root/tmp

互通方式:
1. Ubuntu 内访问 Android 文件: cd /sdcard/Download
2. Android 侧访问 Ubuntu 文件: /data/.../ubuntu-root/home/
3. AI shell_exec 自动路由:
   - 路径以 /sdcard, /storage 开头 → 用绝对路径直接访问
   - 路径以 /home 开头 → 在 proot Ubuntu 中执行
```

### 命令路由 — 与 CommandRouter 集成

```kotlin
class CommandRouter @Inject constructor(
    private val executors: Map<ExecutorCapability, @JvmSuppressWildcards CommandExecutor>,
    private val shizukuStatus: ShizukuStatusProvider,
    private val prootManager: ProotManager
) {
    fun route(command: String): CommandExecutor {
        return when {
            // Layer 2: Ubuntu 专属命令
            needsUbuntu(command) && prootManager.installStatus.value == InstallStatus.READY ->
                executors[ExecutorCapability.PROOT_UBUNTU]!!

            // 需要但未安装 → 先安装
            needsUbuntu(command) && prootManager.installStatus.value != InstallStatus.READY ->
                throw ProotNotReadyException("Ubuntu 环境未就绪，请先完成安装")

            // Layer 3: Shizuku 权限命令
            needsShizuku(command) && shizukuStatus.isAvailable() ->
                executors[ExecutorCapability.SHIZUKU_ELEVATED]!!

            needsShizuku(command) && !shizukuStatus.isAvailable() ->
                throw PermissionDeniedException("此命令需要 Shizuku 权限")

            // Layer 1: Android shell
            else -> executors[ExecutorCapability.ANDROID_SHELL]!!
        }
    }

    /** 命令风险分析 */
    fun assessRisk(toolName: String, rawParams: String): RiskLevel {
        if (toolName != "shell_exec") {
            return toolRegistry.get(toolName)?.descriptor?.riskLevel ?: RiskLevel.MODIFY
        }
        val command = extractCommand(rawParams)
        return when {
            // DESTRUCTIVE — 无论在哪个环境
            command.matches(Regex(".*\\brm\\s+(-rf|-fr).*$")) -> RiskLevel.DESTRUCTIVE
            command.matches(Regex(".*\\brm\\s+-.*\\s+/.*")) -> RiskLevel.DESTRUCTIVE
            command.contains("dd if=") -> RiskLevel.DESTRUCTIVE
            command.contains("mkfs") -> RiskLevel.DESTRUCTIVE
            command.contains("format") -> RiskLevel.DESTRUCTIVE

            // MODIFY
            command.matches(Regex(".*\\b(rm|mv|cp|mkdir|touch|chmod|chown)\\b.*")) -> RiskLevel.MODIFY
            command.matches(Regex(".*\\b(apt install|pip install|npm install)\\b.*")) -> RiskLevel.MODIFY

            // READ_ONLY
            else -> RiskLevel.READ_ONLY
        }
    }

    private fun needsUbuntu(cmd: String): Boolean {
        val ubuntuTools = setOf(
            // 包管理
            "apt", "dpkg", "apt-get",
            // 开发语言
            "python3", "python", "pip3", "pip",
            "node", "npm", "npx",
            "go", "cargo", "rustc",
            "gcc", "g++", "make", "cmake",
            // 网络
            "ssh", "scp", "rsync",
            // 版本控制
            "git",
            // 下载
            "curl", "wget",
            // 数据库
            "sqlite3", "redis-cli", "psql",
            // 其他
            "docker", "jq", "vim", "nano"
        )
        val firstWord = cmd.trimStart().split("\\s+".toRegex()).firstOrNull() ?: ""
        return firstWord in ubuntuTools
    }

    private fun needsShizuku(cmd: String): Boolean {
        val systemCmds = setOf(
            "pm", "am", "input", "settings", "dumpsys",
            "screencap", "cmd", "wm", "svc", "toybox"
        )
        val firstWord = cmd.trimStart().split("\\s+".toRegex()).firstOrNull() ?: ""
        return firstWord in systemCmds
    }
}
```

### System Prompt — 多环境提示

```
你是 AIShell，一个 Android 终端上的 AI 助手。你的核心能力是通过执行终端命令帮助用户完成任务。

可用执行环境:
1. Ubuntu (proot) — 完整 Linux，可用 apt/python3/git/node/go/curl/ssh 等
2. Android Shell — 系统命令，可用 pm/am/input/settings 等
3. Shizuku — 系统级权限（需 Shizuku 已授权），可完全访问文件系统

规则:
1. 需要 Linux 工具时使用 Ubuntu（apt install, python3, git 等）
2. 操作 Android 系统时使用 Android Shell（pm, am, input 等）
3. 需要完全文件访问时使用 Shizuku（/data/data 等受保护目录）
4. 每次只执行一条命令，等待结果后再决定下一步
5. 危险操作必须先告知风险
6. 命令失败时分析原因并尝试修正

当前状态:
- Ubuntu: {ubuntu_status}
- Shizuku: {shizuku_status}
- Ubuntu 工作目录: /home
- Android 工作目录: /sdcard
```

### 安装进度 UI

```
┌──────────────────────────────────────┐
│                                      │
│   >_ AIShell                        │
│                                      │
│   正在初始化 Ubuntu 环境...          │
│                                      │
│   ▓▓▓▓▓▓▓▓▓▓▓▓░░░░░░░░  58%       │
│   下载: 35MB / 60MB                 │
│                                      │
│   预装: python3, git, curl, ssh...  │
│                                      │
└──────────────────────────────────────┘
```

### 磁盘管理 UI（设置中）

```
┌──────────────────────────────────────┐
│ Ubuntu 环境                          │
├──────────────────────────────────────┤
│                                      │
│ 状态: ✓ 已安装                       │
│ 大小: 1.2 GB                        │
│ 已装包: 247 个                       │
│                                      │
│ [清理缓存]  已释放 85MB              │
│ [重新安装]                           │
│ [卸载]                               │
│                                      │
│ 预装包:                              │
│ python3, git, curl, wget, ssh,       │
│ sqlite3, jq, vim, cmake, make       │
│                                      │
│ 最近安装:                            │
│ ffmpeg, imagemagick, redis-server    │
└──────────────────────────────────────┘
```

---

## :core:engine — Agent 引擎（v3 优化）

### 并发模型优化

```kotlin
class AgentEngine @Inject constructor(
    private val providerFactory: AiProviderFactory,
    private val toolRegistry: ToolRegistry,
    private val confirmationGate: ConfirmationGate,
    private val messageRepository: MessageRepository,
    private val toolCallRepository: ToolCallRepository,
    private val commandRouter: CommandRouter,
    private val promptBuilder: PromptBuilder
) {
    // Per-Conversation Mutex — 同一对话串行，不同对话并行
    private val mutexMap = ConcurrentHashMap<Long, Mutex>()

    // Per-Conversation 事件 Channel — 高吞吐，无溢出
    private val eventChannels = ConcurrentHashMap<Long, Channel<AgentEvent>>()

    fun processInput(input: String, conversationId: Long): ReceiveChannel<AgentEvent> {
        val channel = Channel<AgentEvent>(capacity = Channel.BUFFERED)
        eventChannels[conversationId] = channel

        CoroutineScope(Dispatchers.Default).launch {
            val mutex = mutexMap.getOrPut(conversationId) { Mutex() }
            if (!mutex.tryLock()) {
                channel.send(AgentEvent.Error("该对话正在执行中"))
                channel.close()
                return@launch
            }

            try {
                processLoop(input, conversationId, channel)
            } finally {
                mutex.unlock()
                channel.close()
                eventChannels.remove(conversationId)
            }
        }

        return channel
    }

    /**
     * 取消指定对话的执行
     * 通过关闭 Channel 触发协程取消
     */
    fun cancel(conversationId: Long) {
        eventChannels[conversationId]?.close()
    }

    private suspend fun processLoop(
        input: String,
        conversationId: Long,
        channel: Channel<AgentEvent>
    ) {
        // 1. 保存用户消息
        messageRepository.addMessage(conversationId, MessageRole.USER, input)
        channel.send(AgentEvent.UserMessage(input))

        // 2. Agent 循环（最多 20 轮）
        var iteration = 0
        val maxIterations = 20

        while (iteration < maxIterations && !channel.isClosedForSend) {
            iteration++

            val provider = providerFactory.createActive()
            val history = messageRepository.getHistory(conversationId).getOrNull() ?: emptyList()
            val systemPrompt = promptBuilder.build(conversationId)
            val toolDefs = toolRegistry.getDefinitions()

            var textBuffer = StringBuilder()
            val toolCalls = mutableListOf<PendingToolCall>()
            var hasToolCall = false

            // 3. AI 流式响应
            provider.chatStream(
                history.map { it.toChatMessage() },
                toolDefs,
                systemPrompt
            ).collect { chunk ->
                when (chunk) {
                    is AiChunk.TextDelta -> {
                        textBuffer.append(chunk.text)
                        channel.send(AgentEvent.StreamingText(chunk.text))
                    }
                    is AiChunk.ToolCallStart -> {
                        toolCalls.add(PendingToolCall(chunk.id, chunk.name))
                    }
                    is AiChunk.ToolCallDelta -> {
                        toolCalls.find { it.id == chunk.id }?.appendArgs(chunk.argumentsDelta)
                    }
                    is AiChunk.ToolCallEnd -> {
                        hasToolCall = true
                        val call = toolCalls.find { it.id == chunk.id }!!
                        call.finalize(chunk.arguments)

                        // 执行工具（可挂起等待确认）
                        val result = executeTool(call, conversationId, channel)
                        channel.send(result)

                        // 工具结果加入对话
                        messageRepository.addToolResult(
                            conversationId, call.id, call.name,
                            result.getSummary()
                        )
                    }
                    is AiChunk.Done -> { /* 继续循环 */ }
                    is AiChunk.Error -> {
                        channel.send(AgentEvent.Error(chunk.message))
                        if (!chunk.retryable) return
                    }
                }
            }

            // 4. 保存 AI 文本
            if (textBuffer.isNotEmpty()) {
                messageRepository.addMessage(
                    conversationId, MessageRole.ASSISTANT,
                    textBuffer.toString()
                )
            }

            // 5. 无工具调用则结束
            if (!hasToolCall) break
        }
    }

    private suspend fun executeTool(
        call: PendingToolCall,
        conversationId: Long,
        channel: Channel<AgentEvent>
    ): AgentEvent {
        val tool = toolRegistry.get(call.name)
            ?: return AgentEvent.Error("工具未找到: ${call.name}")

        val risk = commandRouter.assessRisk(call.name, call.rawArgs)

        // 确认门控
        if (risk != RiskLevel.READ_ONLY) {
            channel.send(AgentEvent.ConfirmationRequired(call.name, risk, call.rawArgs))
            val confirmed = confirmationGate.request(
                ConfirmationRequest(call.name, risk, call.rawArgs, conversationId.toString())
            )
            if (!confirmed) return AgentEvent.ToolCancelled(call.name)
        }

        // 执行并流式转发
        val startTime = System.currentTimeMillis()
        var output = StringBuilder()
        var exitCode = -1

        tool.execute(call.parsedParams).collect { event ->
            when (event) {
                is ToolEvent.Stdout -> {
                    output.appendLine(event.line)
                    channel.send(AgentEvent.ToolOutput(call.name, event.line))
                }
                is ToolEvent.Stderr -> {
                    output.appendLine("[ERR] ${event.line}")
                    channel.send(AgentEvent.ToolError(call.name, event.line))
                }
                is ToolEvent.Exit -> exitCode = event.code
                is ToolEvent.Progress -> channel.send(AgentEvent.ToolProgress(call.name, event.message))
                is ToolEvent.Output -> channel.send(AgentEvent.ToolResult(call.name, event.content))
                is ToolEvent.Error -> channel.send(AgentEvent.ToolError(call.name, event.message))
            }
        }

        // 记录
        val record = ToolCallRecord(
            id = 0,
            messageId = 0,
            toolName = call.name,
            params = call.params,
            result = JsonString(output.toString()),
            exitCode = exitCode,
            status = if (exitCode == 0) ToolCallStatus.SUCCESS else ToolCallStatus.FAILED,
            riskLevel = risk,
            durationMs = System.currentTimeMillis() - startTime
        )
        toolCallRepository.add(record)

        return AgentEvent.ToolCompleted(call.name, record.durationMs)
    }
}
```

---

## :core:ai — AI Provider（Ktor 替代 Retrofit）

### 为什么换 Ktor

| 对比 | OkHttp + Retrofit | Ktor Client |
|---|---|---|
| Kotlin 原生 | Java 库，Kotlin 包装 | 纯 Kotlin Coroutines |
| SSE 流式 | 需自定义 OkHttp Interceptor | 内置 SSE 支持 |
| 多平台 | Android only | KMP 就绪 |
| 包体积 | ~700KB | ~400KB |
| 协程集成 | Callback → suspend 转换 | 原生 suspend |

```kotlin
class OpenAiCompatibleProvider(
    private val httpClient: HttpClient,
    private val preset: ProviderPreset,
    private val apiKey: String,
    private val model: String
) : AiProvider {

    override val descriptor = ProviderDescriptor(
        id = preset.id,
        displayName = preset.displayName,
        protocol = Protocol.OPENAI_COMPATIBLE,
        defaultModel = preset.defaultModel,
        supportsToolCalling = true,
        supportsStreaming = true
    )

    override fun chatStream(
        messages: List<ChatMessage>,
        tools: List<ToolDescriptor>,
        systemPrompt: String
    ): Flow<AiChunk> = channelFlow {
        val request = buildRequest(messages, tools, systemPrompt)

        httpClient.sse(
            urlString = "${preset.baseUrl}/v1/chat/completions",
            request = request
        ).collect { event ->
            when (event) {
                is ServerSentEvent.Data -> {
                    val chunk = parseSseChunk(event.data)
                    if (chunk != null) send(chunk)
                }
                else -> {}
            }
        }
        send(AiChunk.Done)
    }
}
```

---

## :core:domain — 领域模型

纯 Kotlin 模块，零依赖。所有实体和接口契约。

### 实体

```kotlin
data class Conversation(
    val id: Long,
    val title: String,
    val providerId: String,
    val model: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class Message(
    val id: Long,
    val conversationId: Long,
    val role: MessageRole,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val timestamp: Instant
)

enum class MessageRole { USER, ASSISTANT, SYSTEM, TOOL }

data class ToolCallRecord(
    val id: Long,
    val messageId: Long,
    val toolName: String,
    val params: JsonString,
    val result: JsonString? = null,
    val exitCode: Int? = null,
    val status: ToolCallStatus,
    val riskLevel: RiskLevel,
    val durationMs: Long? = null
)

enum class ToolCallStatus { PENDING, CONFIRMED, EXECUTING, SUCCESS, FAILED, CANCELLED }
enum class RiskLevel { READ_ONLY, MODIFY, DESTRUCTIVE }

@JvmInline value class JsonString(val value: String)
@JvmInline value class VfsPath(val value: String)
```

### 仓储接口

```kotlin
interface ConversationRepository {
    fun observeAll(): Flow<List<Conversation>>
    suspend fun getById(id: Long): Result<Conversation>
    suspend fun create(title: String, providerId: String, model: String): Result<Long>
    suspend fun updateTitle(id: Long, title: String): Result<Unit>
    suspend fun delete(id: Long): Result<Unit>
}

interface MessageRepository {
    fun observeByConversation(conversationId: Long): Flow<List<Message>>
    suspend fun addMessage(conversationId: Long, role: MessageRole, content: String): Result<Long>
    suspend fun addToolResult(conversationId: Long, toolCallId: String, toolName: String, content: String): Result<Long>
    suspend fun getHistory(conversationId: Long): Result<List<Message>>
    suspend fun deleteByConversation(conversationId: Long): Result<Unit>
}

interface ToolCallRepository {
    suspend fun add(record: ToolCallRecord): Result<Long>
    suspend fun updateStatus(id: Long, status: ToolCallStatus): Result<Unit>
    suspend fun updateResult(id: Long, result: JsonString, exitCode: Int?): Result<Unit>
}

interface ProviderRepository {
    fun observeActive(): Flow<ProviderConfig>
    suspend fun setActive(providerId: String): Result<Unit>
    suspend fun saveApiKey(providerId: String, key: String): Result<Unit>
    suspend fun getApiKey(providerId: String): Result<String?>
    suspend fun setModel(providerId: String, model: String): Result<Unit>
    suspend fun getModel(providerId: String): Result<String>
}

interface AutomationRepository {
    fun observeCronJobs(): Flow<List<CronJob>>
    suspend fun saveCronJob(job: CronJob): Result<String>
    suspend fun deleteCronJob(id: String): Result<Unit>
    fun observeRules(): Flow<List<AutomationRule>>
    suspend fun saveRule(rule: AutomationRule): Result<String>
    suspend fun deleteRule(id: String): Result<Unit>
}
```

### Tool 接口

```kotlin
interface Tool {
    val descriptor: ToolDescriptor

    fun execute(params: ToolParams): Flow<ToolEvent>
}

data class ToolDescriptor(
    val name: String,
    val description: String,
    val riskLevel: RiskLevel,
    val parameterSchema: JsonString
)

interface ToolRegistry {
    fun register(tool: Tool)
    fun unregister(name: String)
    fun get(name: String): Tool?
    fun getAll(): List<Tool>
    fun getDefinitions(): List<ToolDescriptor>
}
```

### ToolParams — 强类型参数（sealed class）

每个工具对应一个 data class，不用 `Map<String, Any>`。AI 返回的 JSON 参数由 `ToolParamParser` 解析为对应的 ToolParams 子类。

```kotlin
sealed class ToolParams {

    // ── 终端执行 ──

    /** 核心：执行 shell 命令 */
    data class ShellExec(
        val command: String,
        val workDir: String? = null,
        val timeout: Int = 30,
        val environment: Map<String, String> = emptyMap()
    ) : ToolParams()

    // ── 文件操作 ──

    data class FileList(
        val path: String,
        val showHidden: Boolean = false,
        val recursive: Boolean = false
    ) : ToolParams()

    data class FileRead(
        val path: String,
        val offset: Int = 0,
        val limit: Int = 200,
        val encoding: String = "UTF-8"
    ) : ToolParams()

    data class FileWrite(
        val path: String,
        val content: String,
        val append: Boolean = false,
        val createParents: Boolean = true
    ) : ToolParams()

    data class FileMove(
        val source: String,
        val destination: String,
        val overwrite: Boolean = false
    ) : ToolParams()

    data class FileDelete(
        val path: String,
        val recursive: Boolean = false
    ) : ToolParams()

    data class FileStat(
        val path: String
    ) : ToolParams()

    data class FileSearch(
        val rootPath: String,
        val pattern: String,
        val type: String? = null,       // "file" | "dir" | null(both)
        val maxDepth: Int = 10
    ) : ToolParams()

    data class FileChecksum(
        val path: String,
        val algorithm: String = "SHA-256"  // MD5, SHA-1, SHA-256, SHA-512
    ) : ToolParams()

    // ── ADB 远程设备 ──

    data class AdbConnect(
        val target: String,             // "ip:port" or "usb"
        val action: String = "connect"  // "connect" | "disconnect"
    ) : ToolParams()

    data class AdbCommand(
        val command: String,
        val target: String? = null,     // 目标设备 serial
        val timeout: Int = 30
    ) : ToolParams()

    data class AdbPush(
        val localPath: String,
        val remotePath: String,
        val target: String
    ) : ToolParams()

    data class AdbPull(
        val remotePath: String,
        val localPath: String,
        val target: String
    ) : ToolParams()

    data class AdbInstall(
        val apkPath: String,
        val target: String,
        val reinstall: Boolean = false,
        val grantPermissions: Boolean = true
    ) : ToolParams()

    // ── USB 直连 ──

    data class UsbList(
        val vendorId: Int? = null,
        val productId: Int? = null
    ) : ToolParams()

    data class UsbAction(
        val action: String,             // "open" | "close" | "claim" | "release"
        val deviceAddress: String,
        val interfaceNumber: Int = 0
    ) : ToolParams()

    data class UsbTransfer(
        val deviceAddress: String,
        val endpoint: Int,
        val data: String? = null,       // base64 编码
        val timeout: Int = 5000,
        val direction: String = "out"   // "in" | "out"
    ) : ToolParams()

    // ── 进程/任务管理 ──

    data class ProcessList(
        val filter: String? = null,     // 进程名过滤
        val userId: Int? = null         // 指定用户 ID
    ) : ToolParams()

    data class ProcessSignal(
        val pid: Int,
        val signal: Int = 15            // SIGTERM=15, SIGKILL=9, SIGINT=2
    ) : ToolParams()

    data class BackgroundTask(
        val action: String,             // "start" | "stop" | "list" | "status"
        val command: String? = null,
        val taskId: String? = null,
        val workDir: String? = null
    ) : ToolParams()

    // ── tmux 会话管理 ──

    data class TmuxAction(
        val action: TmuxActionType,
        val sessionId: Long? = null,
        val windowName: String? = null,
        val splitDirection: String? = null, // "horizontal" | "vertical"
        val command: String? = null
    ) : ToolParams()

    enum class TmuxActionType {
        NEW_SESSION, DETACH, ATTACH, KILL_SESSION,
        NEW_WINDOW, KILL_WINDOW, NEXT_WINDOW, PREV_WINDOW,
        SPLIT_PANE, KILL_PANE, SELECT_PANE, RESIZE_PANE,
        LIST_SESSIONS
    }

    // ── 守护进程 ──

    data class DaemonAction(
        val action: String,             // "start" | "stop" | "restart" | "status" | "list"
        val name: String? = null,
        val command: String? = null,
        val restartOnCrash: Boolean = true,
        val restartDelayMs: Long = 5000
    ) : ToolParams()

    // ── 系统集成 ──

    data class ClipboardAction(
        val action: String,             // "read" | "write"
        val text: String? = null
    ) : ToolParams()

    data class AppLaunch(
        val packageName: String,
        val action: String? = null,     // Intent action
        val data: String? = null        // Intent data URI
    ) : ToolParams()

    data class ShareAction(
        val text: String? = null,
        val filePath: String? = null,
        val mimeType: String = "text/plain"
    ) : ToolParams()

    data class NotifyAction(
        val title: String,
        val message: String,
        val channelId: String = "default",
        val action: String = "show"     // "show" | "cancel"
    ) : ToolParams()

    data class Screenshot(
        val path: String? = null,       // 保存路径，null 则临时目录
        val quality: Int = 80
    ) : ToolParams()

    data class InputAction(
        val action: String,             // "tap" | "swipe" | "text" | "key"
        val x: Int? = null,
        val y: Int? = null,
        val text: String? = null,
        val keyCode: Int? = null,
        val dx: Int? = null,            // swipe delta
        val dy: Int? = null,
        val duration: Int = 300
    ) : ToolParams()

    // ── 包管理 ──

    data class PackageAction(
        val action: String,             // "list" | "info" | "install" | "uninstall"
        val packageName: String? = null,
        val apkPath: String? = null,
        val userId: Int? = null
    ) : ToolParams()

    // ── 网络工具 ──

    data class NetAction(
        val action: String,             // "ping" | "dns" | "curl" | "ifconfig" | "ports"
        val host: String? = null,
        val port: Int? = null,
        val path: String? = null,
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
        val timeout: Int = 30
    ) : ToolParams()

    // ── 备份恢复 ──

    data class BackupAction(
        val action: String,             // "create" | "restore" | "list"
        val path: String? = null
    ) : ToolParams()

    // ── 设置读写 ──

    data class SettingsAction(
        val namespace: String,          // "system" | "secure" | "global"
        val action: String,             // "get" | "put" | "list"
        val key: String? = null,
        val value: String? = null
    ) : ToolParams()

    // ── 兜底 ──

    data class Generic(val map: Map<String, String>) : ToolParams()
}
```

### ToolEvent — 工具输出事件

```kotlin
sealed class ToolEvent {
    data class Stdout(val line: String) : ToolEvent()
    data class Stderr(val line: String) : ToolEvent()
    data class Exit(val code: Int) : ToolEvent()
    data class Progress(val message: String) : ToolEvent()
    data class Output(val content: String) : ToolEvent()
    data class Error(val message: String) : ToolEvent()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : ToolEvent()
}
```

### ToolParamParser — AI JSON → 强类型

AI 返回的 tool_call.arguments 是 JSON 字符串，需要解析为对应的 ToolParams 子类：

```kotlin
object ToolParamParser {
    fun parse(toolName: String, arguments: JsonString): ToolParams {
        val json = Json.parseToJsonElement(arguments.value).jsonObject
        return when (toolName) {
            "shell_exec"       -> json.decodeJson<ToolParams.ShellExec>()
            "file_list"        -> json.decodeJson<ToolParams.FileList>()
            "file_read"        -> json.decodeJson<ToolParams.FileRead>()
            "file_write"       -> json.decodeJson<ToolParams.FileWrite>()
            "file_move"        -> json.decodeJson<ToolParams.FileMove>()
            "file_delete"      -> json.decodeJson<ToolParams.FileDelete>()
            "file_stat"        -> json.decodeJson<ToolParams.FileStat>()
            "file_search"      -> json.decodeJson<ToolParams.FileSearch>()
            "file_checksum"    -> json.decodeJson<ToolParams.FileChecksum>()
            "adb_connect"      -> json.decodeJson<ToolParams.AdbConnect>()
            "adb_command"      -> json.decodeJson<ToolParams.AdbCommand>()
            "adb_push"         -> json.decodeJson<ToolParams.AdbPush>()
            "adb_pull"         -> json.decodeJson<ToolParams.AdbPull>()
            "adb_install"      -> json.decodeJson<ToolParams.AdbInstall>()
            "usb_list"         -> json.decodeJson<ToolParams.UsbList>()
            "usb_action"       -> json.decodeJson<ToolParams.UsbAction>()
            "usb_transfer"     -> json.decodeJson<ToolParams.UsbTransfer>()
            "process_list"     -> json.decodeJson<ToolParams.ProcessList>()
            "process_signal"   -> json.decodeJson<ToolParams.ProcessSignal>()
            "background_task"  -> json.decodeJson<ToolParams.BackgroundTask>()
            "tmux"             -> json.decodeJson<ToolParams.TmuxAction>()
            "daemon"           -> json.decodeJson<ToolParams.DaemonAction>()
            "clipboard"        -> json.decodeJson<ToolParams.ClipboardAction>()
            "app_launch"       -> json.decodeJson<ToolParams.AppLaunch>()
            "share"            -> json.decodeJson<ToolParams.ShareAction>()
            "notify"           -> json.decodeJson<ToolParams.NotifyAction>()
            "screenshot"       -> json.decodeJson<ToolParams.Screenshot>()
            "input"            -> json.decodeJson<ToolParams.InputAction>()
            "package"          -> json.decodeJson<ToolParams.PackageAction>()
            "net"              -> json.decodeJson<ToolParams.NetAction>()
            "backup"           -> json.decodeJson<ToolParams.BackupAction>()
            "settings"         -> json.decodeJson<ToolParams.SettingsAction>()
            else               -> ToolParams.Generic(json.mapValues { it.value.toString() })
        }
    }
}
```

### 工具注册表 — 全量定义

| Tool Name | ToolParams | Risk Level | 所在模块 | JSON Schema |
|---|---|---|---|---|
| `shell_exec` | `ShellExec` | 动态(看命令) | executor | `{command: string, work_dir?: string, timeout?: int, env?: map}` |
| `file_list` | `FileList` | READ_ONLY | vfs | `{path: string, show_hidden?: bool, recursive?: bool}` |
| `file_read` | `FileRead` | READ_ONLY | vfs | `{path: string, offset?: int, limit?: int, encoding?: string}` |
| `file_write` | `FileWrite` | MODIFY | vfs | `{path: string, content: string, append?: bool, create_parents?: bool}` |
| `file_move` | `FileMove` | MODIFY | vfs | `{source: string, destination: string, overwrite?: bool}` |
| `file_delete` | `FileDelete` | DESTRUCTIVE | vfs | `{path: string, recursive?: bool}` |
| `file_stat` | `FileStat` | READ_ONLY | vfs | `{path: string}` |
| `file_search` | `FileSearch` | READ_ONLY | vfs | `{root_path: string, pattern: string, type?: string, max_depth?: int}` |
| `file_checksum` | `FileChecksum` | READ_ONLY | vfs | `{path: string, algorithm?: string}` |
| `adb_connect` | `AdbConnect` | READ_ONLY | platform | `{target: string, action?: string}` |
| `adb_command` | `AdbCommand` | 动态 | platform | `{command: string, target?: string, timeout?: int}` |
| `adb_push` | `AdbPush` | MODIFY | platform | `{local_path: string, remote_path: string, target: string}` |
| `adb_pull` | `AdbPull` | READ_ONLY | platform | `{remote_path: string, local_path: string, target: string}` |
| `adb_install` | `AdbInstall` | MODIFY | platform | `{apk_path: string, target: string, reinstall?: bool, grant?: bool}` |
| `usb_list` | `UsbList` | READ_ONLY | platform | `{vendor_id?: int, product_id?: int}` |
| `usb_action` | `UsbAction` | MODIFY | platform | `{action: string, device: string, interface?: int}` |
| `usb_transfer` | `UsbTransfer` | MODIFY | platform | `{device: string, endpoint: int, data?: string, timeout?: int, direction: string}` |
| `process_list` | `ProcessList` | READ_ONLY | executor | `{filter?: string, user_id?: int}` |
| `process_signal` | `ProcessSignal` | DESTRUCTIVE | executor | `{pid: int, signal?: int}` |
| `background_task` | `BackgroundTask` | MODIFY | executor | `{action: string, command?: string, task_id?: string, work_dir?: string}` |
| `tmux` | `TmuxAction` | 动态 | terminal | `{action: enum, session_id?: long, window_name?: string, split_direction?: string, command?: string}` |
| `daemon` | `DaemonAction` | MODIFY | automation | `{action: string, name?: string, command?: string, restart_on_crash?: bool, restart_delay_ms?: long}` |
| `clipboard` | `ClipboardAction` | MODIFY | platform | `{action: string, text?: string}` |
| `app_launch` | `AppLaunch` | READ_ONLY | platform | `{package_name: string, action?: string, data?: string}` |
| `share` | `ShareAction` | READ_ONLY | platform | `{text?: string, file_path?: string, mime_type?: string}` |
| `notify` | `NotifyAction` | MODIFY | platform | `{title: string, message: string, channel_id?: string, action?: string}` |
| `screenshot` | `Screenshot` | READ_ONLY | platform | `{path?: string, quality?: int}` |
| `input` | `InputAction` | MODIFY | platform | `{action: string, x?: int, y?: int, text?: string, key_code?: int, dx?: int, dy?: int, duration?: int}` |
| `package` | `PackageAction` | 动态 | platform | `{action: string, package_name?: string, apk_path?: string, user_id?: int}` |
| `net` | `NetAction` | 动态 | executor | `{action: string, host?: string, port?: int, path?: string, method?: string, headers?: map, body?: string, timeout?: int}` |
| `backup` | `BackupAction` | DESTRUCTIVE | platform | `{action: string, path?: string}` |
| `settings` | `SettingsAction` | MODIFY | platform | `{namespace: string, action: string, key?: string, value?: string}` |

### Plugin / MCP 扩展工具

外部工具（Plugin APK 和 MCP Server）使用 `ToolParams.Generic`：

```kotlin
class PluginTool(
    private val provider: PluginProvider,
    override val descriptor: ToolDescriptor
) : Tool {
    override fun execute(params: ToolParams): Flow<ToolEvent> = channelFlow {
        val genericParams = (params as? ToolParams.Generic)?.map
            ?: params.toGenericMap()
        val result = provider.call(descriptor.name, genericParams)
        send(ToolEvent.Output(result))
    }
}

class McpTool(
    private val connector: McpConnector,
    override val descriptor: ToolDescriptor
) : Tool {
    override fun execute(params: ToolParams): Flow<ToolEvent> = channelFlow {
        val genericParams = (params as? ToolParams.Generic)?.map
            ?: params.toGenericMap()
        val result = connector.callTool(descriptor.name, genericParams)
        send(ToolEvent.Output(result.content))
    }
}
```

### AI Provider 接口

```kotlin
interface AiProvider {
    val descriptor: ProviderDescriptor

    fun chatStream(
        messages: List<ChatMessage>,
        tools: List<ToolDescriptor>,
        systemPrompt: String
    ): Flow<AiChunk>
}

data class ProviderDescriptor(
    val id: String,
    val displayName: String,
    val protocol: Protocol,
    val defaultModel: String,
    val supportsToolCalling: Boolean,
    val supportsStreaming: Boolean
)

enum class Protocol { OPENAI_COMPATIBLE, ANTHROPIC, MINIMAX, OLLAMA }

data class ChatMessage(
    val role: MessageRole,
    val content: String,
    val toolCalls: List<ToolCallRef>? = null,
    val toolCallId: String? = null
)

data class ToolCallRef(
    val id: String,
    val name: String,
    val arguments: JsonString
)

sealed class AiChunk {
    data class TextDelta(val text: String) : AiChunk()
    data class ToolCallStart(val id: String, val name: String) : AiChunk()
    data class ToolCallDelta(val id: String, val argumentsDelta: String) : AiChunk()
    data class ToolCallEnd(val id: String, val name: String, val arguments: JsonString) : AiChunk()
    data object Done : AiChunk()
    data class Error(val message: String, val retryable: Boolean) : AiChunk()
}
```

### 命令执行器接口

```kotlin
interface CommandExecutor {
    val id: String
    val capabilities: Set<ExecutorCapability>

    fun execute(request: CommandRequest): Flow<CommandEvent>
}

enum class ExecutorCapability {
    ANDROID_SHELL, PROOT_UBUNTU, SHIZUKU_ELEVATED, ADB_REMOTE, USB_DIRECT
}

data class CommandRequest(
    val command: String,
    val workDir: String? = null,
    val timeoutMs: Long = 30_000,
    val environment: Map<String, String> = emptyMap()
)

sealed class CommandEvent {
    data class Stdout(val line: String) : CommandEvent()
    data class Stderr(val line: String) : CommandEvent()
    data class Exit(val code: Int) : CommandEvent()
    data class Timeout(val command: String) : CommandEvent()
}
```

### VFS 接口

```kotlin
interface VirtualFileSystem {
    fun resolveProvider(path: String): FileProvider
    fun listMounts(): List<MountInfo>
}

interface FileProvider {
    val scheme: String
    val displayName: String

    suspend fun list(path: VfsPath): Result<List<VfsEntry>>
    suspend fun read(path: VfsPath): Result<InputStream>
    suspend fun write(path: VfsPath): Result<OutputStream>
    suspend fun delete(path: VfsPath): Result<Unit>
    suspend fun move(src: VfsPath, dst: VfsPath): Result<Unit>
    suspend fun mkdir(path: VfsPath): Result<Unit>
    suspend fun exists(path: VfsPath): Result<Boolean>
    suspend fun stat(path: VfsPath): Result<VfsEntry>
}

data class VfsEntry(
    val path: VfsPath,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Instant,
    val permissions: String? = null
)

data class MountInfo(
    val scheme: String,
    val displayName: String,
    val rootPath: VfsPath,
    val isConnected: Boolean
)
```

### SFTP 协议栈 — Rust 原生 SSH/SFTP

AIShell 实现 Rust 原生 SSH/SFTP 协议栈，不依赖外部 sshj 库，确保 ARM64 原生性能和零外部依赖。

**SSH 协议层次：**

```
┌─────────────────────────────────────────────────┐
│  SFTP 子系统 (文件操作)                          │
│  ├── LSTAT/MKDIR/RMDIR/OPEN/CLOSE/READ/WRITE    │
│  ├── RENAME/REMOVE/READDIR/REALPATH             │
│  └── SYMLINK/READLINK/STATFS                    │
├─────────────────────────────────────────────────┤
│  SSH 传输层 (加密通道)                            │
│  ├── 密钥交换: curve25519-sha256, ecdh-sha2-nistp│
│  ├── 加密: aes128-gcm, aes256-gcm, chacha20-poly│
│  ├── MAC: hmac-sha2-256, hmac-sha2-512          │
│  └── 压缩: none, zlib@openssh.com               │
├─────────────────────────────────────────────────┤
│  SSH 认证层                                      │
│  ├── password 认证                               │
│  ├── publickey 认证 (RSA/Ed25519/ECDSA)         │
│  └── keyboard-interactive                       │
├─────────────────────────────────────────────────┤
│  TCP 传输                                        │
│  └── 标准 Socket 或 SOCKS5 代理                  │
└─────────────────────────────────────────────────┘
```

**Rust SSH 核心：**

```rust
/// SSH 客户端 — 纯 Rust 实现
pub struct SshClient {
    transport: SshTransport,
    session_id: Option<Vec<u8>>,
    server_host_key: Option<PublicKey>,
    cipher: Option<Box<dyn Cipher>>,
    mac: Option<Box<dyn Mac>>,
    compressor: Option<Box<dyn Compressor>>,
    sequence_send: u32,
    sequence_recv: u32,
}

/// SSH 传输层 — 处理加密/解密/帧
struct SshTransport {
    stream: TcpStream,
    // 版本交换
    client_version: String,   // "SSH-2.0-AIShell_1.0"
    server_version: String,
}

impl SshClient {
    /// 连接 SSH 服务器
    pub async fn connect(host: &str, port: u16) -> Result<Self> {
        let stream = TcpStream::connect((host, port)).await?;
        let mut transport = SshTransport {
            stream,
            client_version: "SSH-2.0-AIShell_1.0\r\n".to_string(),
            server_version: String::new(),
        };

        // 1. 版本交换
        transport.exchange_version()?;

        // 2. 密钥交换 (KEX)
        //    发送 KEXINIT — 列出支持的算法
        let kex_init = KexInitMsg {
            kex_algorithms: "curve25519-sha256,ecdh-sha2-nistp256".to_string(),
            server_host_key_algorithms: "ssh-ed25519,rsa-sha2-256,ssh-rsa".to_string(),
            encryption_algorithms_client_to_server: "aes128-gcm@openssh.com,aes256-gcm@openssh.com,chacha20-poly1305@openssh.com".to_string(),
            mac_algorithms_client_to_server: "hmac-sha2-256,hmac-sha2-512".to_string(),
            compression_algorithms: "none,zlib@openssh.com".to_string(),
            ..Default::default()
        };
        transport.send_packet(SSH_MSG_KEXINIT, &kex_init.to_bytes())?;

        // 3. Curve25519 密钥交换
        let (ephemeral_secret, ephemeral_public) = generate_curve25519_keypair();
        transport.send_packet(SSH_MSG_KEXDH_INIT, &ephemeral_public)?;

        let reply = transport.recv_packet()?;
        // 解析 KEXDH_REPLY: server_public_host_key + f + signature
        let (host_key, shared_secret, signature) = parse_kex_reply(&reply)?;

        // 4. 派生会话密钥
        let session_id = derive_session_id(&shared_secret, &ephemeral_secret, &host_key);
        let (cipher_c2s, cipher_s2c, mac_c2s, mac_s2c) =
            derive_keys(&shared_secret, &session_id, &kex_init)?;

        // 5. NEWKEYS — 启用加密
        transport.send_packet(SSH_MSG_NEWKEYS, &[])?;
        transport.recv_packet()?; // server NEWKEYS

        Ok(Self {
            transport,
            session_id: Some(session_id),
            server_host_key: Some(host_key),
            cipher: Some(cipher_c2s),
            mac: Some(mac_c2s),
            compressor: None,
            sequence_send: 0,
            sequence_recv: 0,
        })
    }

    /// 密码认证
    pub fn auth_password(&mut self, username: &str, password: &str) -> Result<bool> {
        // SSH_MSG_USERAUTH_REQUEST: password 方法
        self.send_userauth_request(username, "ssh-connection", "password")?;
        // 附加 password 字段
        let reply = self.recv_packet()?;
        Ok(reply.msg_type == SSH_MSG_USERAUTH_SUCCESS)
    }

    /// 公钥认证
    pub fn auth_publickey(&mut self, username: &str, key: &PrivateKey) -> Result<bool> {
        // 1. 询问服务器是否接受此公钥
        // 2. 用私钥签名 session_id + 请求数据
        // 3. 发送签名
        let reply = self.send_signed_userauth(username, key)?;
        Ok(reply == SSH_MSG_USERAUTH_SUCCESS)
    }

    /// 打开 SFTP 子系统
    pub fn sftp(&mut self) -> Result<SftpClient> {
        // 1. 打开 channel (SSH_MSG_CHANNEL_OPEN "session")
        let channel_id = self.open_channel("session")?;

        // 2. 请求 subsystem "sftp"
        self.channel_request(channel_id, "subsystem", Some("sftp"))?;

        // 3. SFTP 初始化
        let sftp_version = self.sftp_init(channel_id)?;

        Ok(SftpClient {
            ssh: self,
            channel_id,
            version: sftp_version,
            request_id: AtomicU32::new(0),
        })
    }
}
```

**SFTP 协议实现：**

```rust
/// SFTP 客户端 — SSH 文件传输协议 (v3-v6)
pub struct SftpClient<'a> {
    ssh: &'a mut SshClient,
    channel_id: u32,
    version: u32,       // 协商的版本 (通常 3)
    request_id: AtomicU32,
}

// SFTP 数据包类型
const SSH_FXP_INIT:      u8 = 1;
const SSH_FXP_VERSION:   u8 = 2;
const SSH_FXP_OPEN:      u8 = 3;
const SSH_FXP_CLOSE:     u8 = 4;
const SSH_FXP_READ:      u8 = 5;
const SSH_FXP_WRITE:     u8 = 6;
const SSH_FXP_LSTAT:     u8 = 7;
const SSH_FXP_FSTAT:     u8 = 8;
const SSH_FXP_SETSTAT:   u8 = 9;
const SSH_FXP_OPENDIR:   u8 = 11;
const SSH_FXP_READDIR:   u8 = 12;
const SSH_FXP_REMOVE:    u8 = 13;
const SSH_FXP_MKDIR:     u8 = 14;
const SSH_FXP_RMDIR:     u8 = 15;
const SSH_FXP_REALPATH:  u8 = 16;
const SSH_FXP_STAT:      u8 = 17;
const SSH_FXP_RENAME:    u8 = 18;
const SSH_FXP_READLINK:  u8 = 19;
const SSH_FXP_SYMLINK:   u8 = 20;

// 文件打开标志
const SSH_FXF_READ:   u32 = 0x00000001;
const SSH_FXF_WRITE:  u32 = 0x00000002;
const SSH_FXF_APPEND: u32 = 0x00000004;
const SSH_FXF_CREAT:  u32 = 0x00000008;
const SSH_FXF_TRUNC:  u32 = 0x00000010;

impl<'a> SftpClient<'a> {
    /// 列出目录
    pub fn read_dir(&mut self, path: &str) -> Result<Vec<SftpDirEntry>> {
        let id = self.request_id.fetch_add(1, Ordering::Relaxed);
        let handle = self.send_request(SSH_FXP_OPENDIR, id, &encode_path(path))?;
        let handle = parse_handle(&handle)?;

        let mut entries = Vec::new();
        loop {
            let data = self.send_request(SSH_FXP_READDIR, id, &handle)?;
            let batch = parse_dir_entries(&data)?;
            if batch.is_empty() { break; }
            entries.extend(batch);
        }

        self.send_request(SSH_FXP_CLOSE, id, &handle)?;
        Ok(entries)
    }

    /// 读取文件
    pub fn read_file(&mut self, path: &str) -> Result<Vec<u8>> {
        let id = self.request_id.fetch_add(1, Ordering::Relaxed);
        let handle = self.send_request(SSH_FXP_OPEN, id,
            &encode_open(path, SSH_FXF_READ))?;
        let handle = parse_handle(&handle)?;

        // 获取文件大小
        let stat = self.send_request(SSH_FXP_FSTAT, id, &handle)?;
        let size = parse_stat(&stat).size;

        // 分块读取 (64KB)
        let mut buf = Vec::with_capacity(size as usize);
        let chunk_size = 65536u32;
        let mut offset: u64 = 0;
        while offset < size {
            let data = self.send_request(SSH_FXP_READ, id,
                &encode_read(&handle, offset, chunk_size))?;
            if data.is_empty() { break; }
            buf.extend_from_slice(&data);
            offset += data.len() as u64;
        }

        self.send_request(SSH_FXP_CLOSE, id, &handle)?;
        Ok(buf)
    }

    /// 写入文件
    pub fn write_file(&mut self, path: &str, data: &[u8]) -> Result<()> {
        let id = self.request_id.fetch_add(1, Ordering::Relaxed);
        let handle = self.send_request(SSH_FXP_OPEN, id,
            &encode_open(path, SSH_FXF_WRITE | SSH_FXF_CREAT | SSH_FXF_TRUNC))?;
        let handle = parse_handle(&handle)?;

        // 分块写入 (32KB)
        let chunk_size = 32768usize;
        for (i, chunk) in data.chunks(chunk_size).enumerate() {
            let offset = (i * chunk_size) as u64;
            self.send_request(SSH_FXP_WRITE, id,
                &encode_write(&handle, offset, chunk))?;
        }

        self.send_request(SSH_FXP_CLOSE, id, &handle)?;
        Ok(())
    }

    /// 创建目录
    pub fn mkdir(&mut self, path: &str) -> Result<()> {
        let id = self.request_id.fetch_add(1, Ordering::Relaxed);
        self.send_request(SSH_FXP_MKDIR, id, &encode_mkdir(path))?;
        Ok(())
    }

    /// 删除文件
    pub fn remove(&mut self, path: &str) -> Result<()> {
        let id = self.request_id.fetch_add(1, Ordering::Relaxed);
        self.send_request(SSH_FXP_REMOVE, id, &encode_path(path))?;
        Ok(())
    }

    /// 重命名
    pub fn rename(&mut self, old_path: &str, new_path: &str) -> Result<()> {
        let id = self.request_id.fetch_add(1, Ordering::Relaxed);
        self.send_request(SSH_FXP_RENAME, id, &encode_rename(old_path, new_path))?;
        Ok(())
    }
}

pub struct SftpDirEntry {
    pub filename: String,
    pub longname: String,
    pub attrs: SftpFileAttributes,
}

pub struct SftpFileAttributes {
    pub size: u64,
    pub uid: u32,
    pub gid: u32,
    pub permissions: u32,
    pub atime: u64,
    pub mtime: u64,
    pub file_type: SftpFileType,
}

pub enum SftpFileType {
    Regular,
    Directory,
    Symlink,
    Special,
    Unknown,
}
```

**Kotlin SFTP Provider（VFS 集成）：**

```kotlin
@Singleton
class SftpProviderFactory @Inject constructor() {
    /** 创建 SFTP 连接 — 通过 JNI 调用 Rust SSH/SFTP */
    suspend fun connect(config: SftpConfig): Result<SftpProvider> {
        return nativeSftpConnect(
            host = config.host,
            port = config.port,
            username = config.username,
            authMethod = config.authMethod,
            password = config.password,
            privateKey = config.privateKey
        )
    }

    private external fun nativeSftpConnect(
        host: String, port: Int, username: String,
        authMethod: Int, password: String?, privateKey: ByteArray?
    ): Result<SftpProvider>
}

data class SftpConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val authMethod: Int = 0,      // 0=password, 1=publickey, 2=keyboard-interactive
    val password: String? = null,
    val privateKey: ByteArray? = null,
    val hostKeyVerifier: Int = 0   // 0=auto-accept, 1=known_hosts, 2=strict
)
```

### SMB/CIFS 协议栈

SMB 用于连接 Windows 共享文件夹和 NAS。Rust 原生实现核心协议，不依赖 jcifs 库。

**SMB 协议层次：**

```
┌─────────────────────────────────────────────────┐
│  SMB2/3 文件操作                                 │
│  ├── Create (打开文件/目录)                       │
│  ├── Read / Write                                │
│  ├── Close / Flush                               │
│  ├── Query Directory (列举目录)                   │
│  ├── SetInfo / QueryInfo                         │
│  └── Ioctl (FSCTL)                               │
├─────────────────────────────────────────────────┤
│  SMB2/3 会话层                                    │
│  ├── Negotiate (协议协商)                         │
│  ├── Session Setup (NTLMv2 / Kerberos 认证)      │
│  └── Tree Connect (连接共享)                      │
├─────────────────────────────────────────────────┤
│  SMB2/3 传输层                                    │
│  ├── 加密: AES-128-CCM / AES-128-GCM (SMB3)     │
│  ├── 签名: HMAC-SHA256 (SMB2) / AES-CMAC (SMB3) │
│  └── Direct TCP (port 445) / NBT (port 139)      │
└─────────────────────────────────────────────────┘
```

**Rust SMB2 核心：**

```rust
/// SMB2 客户端 — 支持 SMB 2.0/2.1/3.0
pub struct Smb2Client {
    transport: SmbTransport,
    session_id: Option<u64>,
    tree_id: Option<u64>,
    message_id: AtomicU64,
    credit_charge: u16,
    dialect: u16,          // 协商的方言版本
    signing_key: Option<[u8; 16]>,
    encryption_key: Option<[u8; 16]>,
}

/// SMB2 消息头 — 64 字节
#[repr(C, packed)]
struct Smb2Header {
    protocol_id: [u8; 4],     // 0xFE, 'S', 'M', 'B'
    structure_size: u16,      // 64
    credit_charge: u16,
    status: u32,
    command: u16,
    credit_request: u16,
    flags: u32,
    next_command: u32,
    message_id: u64,
    reserved: u32,
    tree_id: u32,
    session_id: u64,
    signature: [u8; 16],
}

// SMB2 命令
const SMB2_NEGOTIATE:      u16 = 0x0000;
const SMB2_SESSION_SETUP:  u16 = 0x0001;
const SMB2_LOGOFF:         u16 = 0x0002;
const SMB2_TREE_CONNECT:   u16 = 0x0003;
const SMB2_TREE_DISCONNECT:u16 = 0x0004;
const SMB2_CREATE:         u16 = 0x0005;
const SMB2_CLOSE:          u16 = 0x0006;
const SMB2_FLUSH:          u16 = 0x0007;
const SMB2_READ:           u16 = 0x0008;
const SMB2_WRITE:          u16 = 0x0009;
const SMB2_LOCK:           u16 = 0x000A;
const SMB2_IOCTL:          u16 = 0x000B;
const SMB2_CANCEL:         u16 = 0x000C;
const SMB2_ECHO:           u16 = 0x000D;
const SMB2_QUERY_DIRECTORY:u16 = 0x000E;
const SMB2_CHANGE_NOTIFY:  u16 = 0x000F;
const SMB2_QUERY_INFO:     u16 = 0x0010;
const SMB2_SET_INFO:       u16 = 0x0011;

impl Smb2Client {
    /// 连接 SMB 服务器
    pub async fn connect(host: &str, port: u16) -> Result<Self> {
        let stream = TcpStream::connect((host, port)).await?;
        let transport = SmbTransport::new(stream);

        Ok(Self {
            transport,
            session_id: None,
            tree_id: None,
            message_id: AtomicU64::new(0),
            credit_charge: 1,
            dialect: 0,
            signing_key: None,
            encryption_key: None,
        })
    }

    /// 协议协商
    pub fn negotiate(&mut self) -> Result<u16> {
        let dialects = [
            0x0202,  // SMB 2.0.2
            0x0210,  // SMB 2.1
            0x0300,  // SMB 3.0
            0x0302,  // SMB 3.0.2
        ];
        let resp = self.send_smb2(SMB2_NEGOTIATE, &encode_negotiate(&dialects))?;
        self.dialect = parse_negotiate_response(&resp)?;
        Ok(self.dialect)
    }

    /// NTLMv2 认证
    pub fn session_setup_ntlmv2(
        &mut self,
        username: &str,
        password: &str,
        domain: &str,
    ) -> Result<()> {
        // NTLMv2 三步握手:
        // 1. Type 1 (Negotiate) → 服务器返回 Type 2 (Challenge)
        // 2. 计算 NTLMv2 Response
        // 3. Type 3 (Auth) → 服务器返回成功

        // Type 1
        let type1 = ntlm_negotiate_message(domain)?;
        let resp1 = self.send_smb2_session_setup(type1)?;
        let type2 = parse_session_setup_challenge(&resp1)?;

        // Type 3 — 计算 NTLMv2 响应
        let (nt_proof, session_base_key) = ntlmv2_response(
            password, username, domain, &type2.server_challenge, &type2.target_info
        )?;
        let type3 = ntlm_auth_message(domain, username, &nt_proof, &type2)?;

        let resp2 = self.send_smb2_session_setup(type3)?;
        self.session_id = parse_session_id(&resp2)?;

        // 派生签名/加密密钥 (SMB3)
        if self.dialect >= 0x0300 {
            let preauth_hash = compute_preauth_hash(&resp1, &resp2);
            self.signing_key = Some(derive_signing_key(&session_base_key, &preauth_hash));
            self.encryption_key = Some(derive_encryption_key(&session_base_key, &preauth_hash));
        }

        Ok(())
    }

    /// 连接共享
    pub fn tree_connect(&mut self, share_path: &str) -> Result<u32> {
        // share_path 格式: "\\server\share"
        let resp = self.send_smb2(SMB2_TREE_CONNECT, &encode_tree_connect(share_path))?;
        let tree_id = parse_tree_id(&resp)?;
        self.tree_id = Some(tree_id as u64);
        Ok(tree_id)
    }

    /// 列出目录
    pub fn query_directory(&mut self, file_id: &[u8], pattern: &str) -> Result<Vec<SmbDirEntry>> {
        let resp = self.send_smb2(SMB2_QUERY_DIRECTORY,
            &encode_query_directory(file_id, pattern, 0x01)?  // SMB2_RESTART_SCANS
        )?;
        parse_directory_entries(&resp)
    }

    /// 读取文件
    pub fn read(&mut self, file_id: &[u8], offset: u64, length: u32) -> Result<Vec<u8>> {
        let resp = self.send_smb2(SMB2_READ, &encode_read(file_id, offset, length))?;
        parse_read_data(&resp)
    }

    /// 写入文件
    pub fn write(&mut self, file_id: &[u8], offset: u64, data: &[u8]) -> Result<u32> {
        let resp = self.send_smb2(SMB2_WRITE, &encode_write(file_id, offset, data))?;
        parse_write_count(&resp)
    }

    /// 创建/打开文件
    pub fn create(
        &mut self,
        path: &str,
        access_mask: u32,
        create_disposition: u32,  // FILE_OPEN / FILE_CREATE / FILE_OVERWRITE
    ) -> Result<SmbFileHandle> {
        let resp = self.send_smb2(SMB2_CREATE,
            &encode_create(path, access_mask, create_disposition)?
        )?;
        parse_create_response(&resp)
    }

    /// 关闭文件
    pub fn close(&mut self, file_id: &[u8]) -> Result<()> {
        self.send_smb2(SMB2_CLOSE, &encode_close(file_id))?;
        Ok(())
    }
}

pub struct SmbDirEntry {
    pub filename: String,
    pub file_attributes: u32,
    pub file_size: u64,
    pub is_directory: bool,
    pub created: u64,
    pub last_modified: u64,
}

pub struct SmbFileHandle {
    pub file_id: [u8; 16],
    pub file_size: u64,
    pub access_mask: u32,
}
```

**Kotlin SMB Provider（VFS 集成）：**

```kotlin
@Singleton
class SmbProviderFactory @Inject constructor() {
    /** 创建 SMB 连接 — 通过 JNI 调用 Rust SMB2 */
    suspend fun connect(config: SmbConfig): Result<SmbProvider> {
        return nativeSmb2Connect(
            host = config.host,
            port = config.port,
            share = config.share,
            domain = config.domain,
            username = config.username,
            password = config.password
        )
    }

    private external fun nativeSmb2Connect(
        host: String, port: Int, share: String,
        domain: String, username: String, password: String
    ): Result<SmbProvider>
}

data class SmbConfig(
    val host: String,
    val port: Int = 445,
    val share: String,
    val domain: String = "",
    val username: String = "guest",
    val password: String = ""
)
```

### Bluetooth 协议栈 — BLE / Classic / SPP

AIShell 支持 Bluetooth Low Energy (BLE) 和 Classic Bluetooth，覆盖设备发现、SPP 串口通信、BLE GATT 读写/通知等场景。Rust 原生实现 BLE 底层操作，Kotlin 层通过 Android BluetoothAdapter 处理 Classic Bluetooth。

**Bluetooth 协议层次：**

```
┌──────────────────────────────────────────────────────┐
│  Bluetooth 高级操作                                   │
│  ├── 设备扫描 (BLE Scan / Classic Discovery)          │
│  ├── SPP 串口通信 (RFCOMM)                           │
│  ├── BLE GATT 客户端 (Service/Characteristic 读写)    │
│  ├── BLE 广播 (Advertising)                          │
│  └── 设备配对/绑定 (Pairing/Bonding)                  │
├──────────────────────────────────────────────────────┤
│  BLE GATT 层                                         │
│  ├── Discover Services (by UUID)                     │
│  ├── Discover Characteristics (by UUID)              │
│  ├── Read Characteristic / Write Characteristic      │
│  ├── Enable/Disable Notification + CCCD              │
│  └── Read/Write Descriptor                           │
├──────────────────────────────────────────────────────┤
│  传输层                                              │
│  ├── BLE: ATT/GATT over L2CAP (LE Credit Based)     │
│  ├── Classic: RFCOMM over L2CAP                      │
│  └── HCI: Android BluetoothAdapter / Rust btleplug   │
└──────────────────────────────────────────────────────┘
```

**Rust BLE 核心（:native:terminal-core）：**

```rust
// ============================================================
// crates/bluetooth/src/ble.rs — BLE GATT Client
// ============================================================

use btleplug::api::{Central, Manager as _, Peripheral as _, ScanFilter};
use btleplug::platform::Peripheral;
use std::collections::HashMap;
use uuid::Uuid;

/// BLE GATT 客户端 — 通过 btleplug 实现
pub struct BleGattClient {
    peripherals: HashMap<String, Peripheral>,  // address → peripheral
}

impl BleGattClient {
    /// 扫描 BLE 设备，持续 duration_ms 毫秒
    pub async fn scan(&mut self, duration_ms: u64, filter_uuids: Vec<Uuid>) -> Result<Vec<BleDeviceInfo>> {
        let manager = btleplug::platform::Manager::new().await?;
        let adapter = manager.adapters().await?.into_iter().next()
            .ok_or(BleError::NoAdapter)?;

        adapter.start_scan(ScanFilter {
            services: filter_uuids,
        }).await?;

        tokio::time::sleep(std::time::Duration::from_millis(duration_ms)).await;

        let peripherals = adapter.peripherals().await?;
        let mut devices = Vec::new();

        for p in peripherals {
            let props = p.properties().await?
                .unwrap_or_default();
            let address = p.address().to_string();
            self.peripherals.insert(address.clone(), p);

            devices.push(BleDeviceInfo {
                address: address.clone(),
                name: props.local_name.unwrap_or_default(),
                rssi: props.rssi.unwrap_or(i16::MIN) as i32,
                services: props.services.into_iter()
                    .map(|u| u.to_string()).collect(),
                connectable: props.connectable.unwrap_or(false),
            });
        }

        adapter.stop_scan().await?;
        Ok(devices)
    }

    /// 连接到 BLE 设备
    pub async fn connect(&self, address: &str) -> Result<BleConnection> {
        let peripheral = self.peripherals.get(address)
            .ok_or(BleError::DeviceNotFound(address.to_string()))?;

        peripheral.connect().await?;

        // 发现所有服务和特征
        peripheral.discover_services().await?;

        Ok(BleConnection {
            address: address.to_string(),
            peripheral: peripheral.clone(),
        })
    }
}

/// BLE 连接 — 封装已连接的 peripheral
pub struct BleConnection {
    address: String,
    peripheral: Peripheral,
}

impl BleConnection {
    /// 列出所有 GATT 服务
    pub async fn list_services(&self) -> Result<Vec<GattServiceInfo>> {
        let services = self.peripheral.services();
        Ok(services.into_iter().map(|s| GattServiceInfo {
            uuid: s.uuid.to_string(),
            primary: s.primary,
            characteristics: s.characteristics.into_iter().map(|c| GattCharacteristicInfo {
                uuid: c.uuid.to_string(),
                properties: format_properties(c.properties),
                descriptors: c.descriptors.into_iter()
                    .map(|d| d.uuid.to_string()).collect(),
            }).collect(),
        }).collect())
    }

    /// 读取特征值
    pub async fn read_characteristic(&self, uuid: &str) -> Result<Vec<u8>> {
        let uuid = Uuid::parse_str(uuid)?;
        let characteristic = self.find_characteristic(uuid).await?;
        let data = self.peripheral.read(&characteristic).await?;
        Ok(data)
    }

    /// 写入特征值
    pub async fn write_characteristic(&self, uuid: &str, data: &[u8], with_response: bool) -> Result<()> {
        let uuid = Uuid::parse_str(uuid)?;
        let characteristic = self.find_characteristic(uuid).await?;

        if with_response {
            self.peripheral.write(&characteristic, data, btleplug::api::WriteType::WithResponse).await?;
        } else {
            self.peripheral.write(&characteristic, data, btleplug::api::WriteType::WithoutResponse).await?;
        }
        Ok(())
    }

    /// 订阅通知 — 返回通知数据流
    pub async fn subscribe_notifications(&self, uuid: &str) -> Result<tokio::sync::mpsc::Receiver<Vec<u8>>> {
        let uuid = Uuid::parse_str(uuid)?;
        let characteristic = self.find_characteristic(uuid).await?;

        self.peripheral.subscribe(&characteristic).await?;

        let (tx, rx) = tokio::sync::mpsc::channel(64);

        // 通知回调通过 btleplug notification stream 转发
        let peripheral = self.peripheral.clone();
        let char_uuid = uuid;
        tokio::spawn(async move {
            use futures::StreamExt;
            let mut notification_stream = peripheral.notifications().await
                .unwrap().take_while(|n| n.uuid == char_uuid);

            while let Some(notification) = notification_stream.next().await {
                if tx.send(notification.value).await.is_err() {
                    break;  // receiver dropped
                }
            }
        });

        Ok(rx)
    }

    /// 取消订阅通知
    pub async fn unsubscribe_notifications(&self, uuid: &str) -> Result<()> {
        let uuid = Uuid::parse_str(uuid)?;
        let characteristic = self.find_characteristic(uuid).await?;
        self.peripheral.unsubscribe(&characteristic).await?;
        Ok(())
    }

    /// 断开连接
    pub async fn disconnect(&self) -> Result<()> {
        self.peripheral.disconnect().await?;
        Ok(())
    }

    async fn find_characteristic(&self, uuid: Uuid) -> Result<btleplug::api::Characteristic> {
        for service in self.peripheral.services() {
            for char in &service.characteristics {
                if char.uuid == uuid {
                    return Ok(char.clone());
                }
            }
        }
        Err(BleError::CharacteristicNotFound(uuid.to_string()))
    }
}

// ============================================================
// crates/bluetooth/src/spp.rs — Classic SPP 串口
// ============================================================

/// SPP (Serial Port Profile) — 经典蓝牙串口通信
/// 使用 Android BluetoothSocket 通过 JNI 桥接
pub struct SppClient {
    socket_fd: Option<i32>,       // RFCOMM socket fd (来自 JNI)
    channel: Option<i32>,         // RFCOMM channel
    connected: bool,
}

impl SppClient {
    /// 通过 SDP 查找 SPP 服务的 RFCOMM channel
    pub fn find_spp_channel(socket_fd: i32, target_uuid: &str) -> Result<i32> {
        // 使用 Android BluetoothSocket.lookupUuid 或 SDP 查询
        // 通过 JNI 调用 Android BluetoothDevice.getUuids()
        // SPP UUID: 00001101-0000-1000-8000-00805F9B34FB
        todo!("JNI bridge to Android SDP lookup")
    }

    /// 建立 RFCOMM 连接
    pub async fn connect(&mut self, socket_fd: i32, channel: i32) -> Result<()> {
        self.socket_fd = Some(socket_fd);
        self.channel = Some(channel);
        self.connected = true;
        Ok(())
    }

    /// 读取数据
    pub async fn read(&self, buf: &mut [u8]) -> Result<usize> {
        // 通过 fd read 系统调用
        let fd = self.socket_fd.ok_or(BleError::NotConnected)?;
        let n = tokio::io::unix::AsyncFd::new(fd)?
            .readable().await?;
        // ... read from fd
        todo!("async read from RFCOMM fd")
    }

    /// 写入数据
    pub async fn write(&self, data: &[u8]) -> Result<usize> {
        let fd = self.socket_fd.ok_or(BleError::NotConnected)?;
        // 通过 fd write 系统调用
        todo!("async write to RFCOMM fd")
    }

    /// 断开 SPP 连接
    pub async fn disconnect(&mut self) -> Result<()> {
        if let Some(fd) = self.socket_fd.take() {
            // close(fd)
        }
        self.connected = false;
        Ok(())
    }
}

// ============================================================
// crates/bluetooth/src/types.rs
// ============================================================

#[derive(Debug, Clone)]
pub struct BleDeviceInfo {
    pub address: String,
    pub name: String,
    pub rssi: i32,
    pub services: Vec<String>,
    pub connectable: bool,
}

#[derive(Debug, Clone)]
pub struct GattServiceInfo {
    pub uuid: String,
    pub primary: bool,
    pub characteristics: Vec<GattCharacteristicInfo>,
}

#[derive(Debug, Clone)]
pub struct GattCharacteristicInfo {
    pub uuid: String,
    pub properties: Vec<String>,   // "read", "write", "notify", "indicate"
    pub descriptors: Vec<String>,
}

fn format_properties(props: btleplug::api::CharPropFlags) -> Vec<String> {
    let mut result = Vec::new();
    if props.contains(btleplug::api::CharPropFlags::READ) { result.push("read".into()); }
    if props.contains(btleplug::api::CharPropFlags::WRITE) { result.push("write".into()); }
    if props.contains(btleplug::api::CharPropFlags::WRITE_WITHOUT_RESPONSE) { result.push("write_no_resp".into()); }
    if props.contains(btleplug::api::CharPropFlags::NOTIFY) { result.push("notify".into()); }
    if props.contains(btleplug::api::CharPropFlags::INDICATE) { result.push("indicate".into()); }
    result
}

#[derive(Debug, thiserror::Error)]
pub enum BleError {
    #[error("No Bluetooth adapter found")]
    NoAdapter,
    #[error("Device not found: {0}")]
    DeviceNotFound(String),
    #[error("Characteristic not found: {0}")]
    CharacteristicNotFound(String),
    #[error("Not connected")]
    NotConnected,
    #[error("Scan failed: {0}")]
    ScanFailed(String),
    #[error("Connection failed: {0}")]
    ConnectionFailed(String),
    #[error("GATT error: {0}")]
    GattError(String),
    #[error(transparent)]
    Io(#[from] std::io::Error),
    #[error(transparent)]
    Uuid(#[from] uuid::Error),
}
```

**Kotlin Bluetooth 管理层（:core:bluetooth）：**

```kotlin
// ============================================================
// core/bluetooth/src/main/kotlin/BluetoothManager.kt
// ============================================================

/**
 * Bluetooth 统一管理器 — BLE + Classic + SPP
 *
 * 双层架构:
 *   - BLE: Rust btleplug 原生 GATT 操作 (JNI)
 *   - Classic/SPP: Android BluetoothAdapter API
 *   - 设备发现: Android BluetoothAdapter (统一入口)
 */
class BluetoothManager(
    private val context: Context,
) {
    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)
            ?.adapter

    // ---------- 权限检查 ----------

    fun isBluetoothAvailable(): Boolean =
        bluetoothAdapter?.isEnabled == true

    fun isBleSupported(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    // ---------- 设备扫描 ----------

    /** BLE 设备扫描 (Rust btleplug) */
    suspend fun scanBleDevices(
        durationMs: Long = 10000,
        filterServiceUuids: List<String> = emptyList(),
    ): List<BleDeviceInfo> {
        return nativeBleScan(durationMs, filterServiceUuids)
    }

    /** Classic Bluetooth 设备发现 (Android API) */
    fun scanClassicDevices(): Flow<ClassicDeviceInfo> = callbackFlow {
        val adapter = bluetoothAdapter ?: throw BluetoothException("Bluetooth not available")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ 需要 BLUETOOTH_SCAN 权限
            // 使用 BluetoothLeScanner 或 startDiscovery
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        device?.let {
                            trySend(ClassicDeviceInfo(
                                address = it.address,
                                name = it.name ?: "Unknown",
                                type = when (it.type) {
                                    BluetoothDevice.DEVICE_TYPE_CLASSIC -> DeviceType.CLASSIC
                                    BluetoothDevice.DEVICE_TYPE_LE -> DeviceType.BLE
                                    BluetoothDevice.DEVICE_TYPE_DUAL -> DeviceType.DUAL
                                    else -> DeviceType.UNKNOWN
                                },
                                bondState = when (it.bondState) {
                                    BluetoothDevice.BOND_BONDED -> BondState.BONDED
                                    BluetoothDevice.BOND_BONDING -> BondState.BONDING
                                    else -> BondState.NONE
                                },
                                rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt(),
                            ))
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> close()
                }
            }
        }

        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        })

        adapter.startDiscovery()

        awaitClose {
            adapter.cancelDiscovery()
            context.unregisterReceiver(receiver)
        }
    }

    // ---------- BLE GATT 操作 (Rust JNI) ----------

    suspend fun bleConnect(address: String): BleGattConnection =
        nativeBleConnect(address)

    suspend fun bleListServices(connection: BleGattConnection): List<GattServiceInfo> =
        nativeBleListServices(connection.handle)

    suspend fun bleReadCharacteristic(connection: BleGattConnection, uuid: String): ByteArray =
        nativeBleRead(connection.handle, uuid)

    suspend fun bleWriteCharacteristic(
        connection: BleGattConnection,
        uuid: String,
        data: ByteArray,
        withResponse: Boolean = true,
    ): Unit = nativeBleWrite(connection.handle, uuid, data, withResponse)

    fun bleSubscribeNotifications(
        connection: BleGattConnection,
        uuid: String,
    ): Flow<ByteArray> = nativeBleSubscribe(connection.handle, uuid)

    suspend fun bleUnsubscribeNotifications(
        connection: BleGattConnection,
        uuid: String,
    ) = nativeBleUnsubscribe(connection.handle, uuid)

    suspend fun bleDisconnect(connection: BleGattConnection) =
        nativeBleDisconnect(connection.handle)

    // ---------- SPP 串口 (Android API) ----------

    /** 连接 SPP 设备 — 返回 RFCOMM socket 的 I/O 流 */
    suspend fun sppConnect(
        address: String,
        uuid: UUID = SPP_UUID,
    ): SppConnection {
        val device = bluetoothAdapter?.getRemoteDevice(address)
            ?: throw BluetoothException("Device not found: $address")

        val socket = device.createRfcommSocketToServiceRecord(uuid)
        withContext(Dispatchers.IO) { socket.connect() }

        return SppConnection(
            address = address,
            inputStream = socket.inputStream,
            outputStream = socket.outputStream,
            socket = socket,
        )
    }

    /** SPP 读取数据流 */
    fun sppReadStream(connection: SppConnection): Flow<ByteArray> = callbackFlow {
        val buffer = ByteArray(4096)
        val stream = connection.inputStream

        while (true) {
            val bytesRead = withContext(Dispatchers.IO) { stream.read(buffer) }
            if (bytesRead == -1) break
            send(buffer.copyOf(bytesRead))
        }
        close()
    }.flowOn(Dispatchers.IO)

    /** SPP 写入数据 */
    suspend fun sppWrite(connection: SppConnection, data: ByteArray) {
        withContext(Dispatchers.IO) {
            connection.outputStream.write(data)
            connection.outputStream.flush()
        }
    }

    /** 断开 SPP */
    suspend fun sppDisconnect(connection: SppConnection) {
        withContext(Dispatchers.IO) {
            connection.socket.close()
        }
    }

    // ---------- JNI Bridge ----------

    private external fun nativeBleScan(durationMs: Long, filterUuids: List<String>): List<BleDeviceInfo>
    private external fun nativeBleConnect(address: String): BleGattConnection
    private external fun nativeBleListServices(handle: Long): List<GattServiceInfo>
    private external fun nativeBleRead(handle: Long, uuid: String): ByteArray
    private external fun nativeBleWrite(handle: Long, uuid: String, data: ByteArray, withResponse: Boolean)
    private external fun nativeBleSubscribe(handle: Long, uuid: String): Flow<ByteArray>
    private external fun nativeBleUnsubscribe(handle: Long, uuid: String)
    private external fun nativeBleDisconnect(handle: Long)

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

// ============================================================
// core/bluetooth/src/main/kotlin/BluetoothModels.kt
// ============================================================

data class BleDeviceInfo(
    val address: String,
    val name: String,
    val rssi: Int,
    val services: List<String>,
    val connectable: Boolean,
)

data class ClassicDeviceInfo(
    val address: String,
    val name: String,
    val type: DeviceType,
    val bondState: BondState,
    val rssi: Int,
)

enum class DeviceType { CLASSIC, BLE, DUAL, UNKNOWN }
enum class BondState { NONE, BONDING, BONDED }

data class BleGattConnection(
    val handle: Long,          // Rust BleConnection 指针
    val address: String,
)

data class GattServiceInfo(
    val uuid: String,
    val primary: Boolean,
    val characteristics: List<GattCharacteristicInfo>,
)

data class GattCharacteristicInfo(
    val uuid: String,
    val properties: List<String>,
    val descriptors: List<String>,
)

class SppConnection(
    val address: String,
    val inputStream: InputStream,
    val outputStream: OutputStream,
    val socket: BluetoothSocket,
)

class BluetoothException(message: String) : Exception(message)
```

**Bluetooth ToolParams 扩展：**

```kotlin
// 添加到 sealed class ToolParams
object BleScan : ToolParams()           // 扫描 BLE 设备
object ClassicScan : ToolParams()       // Classic 设备发现
data class BleConnect(val address: String) : ToolParams()
data class BleRead(val address: String, val characteristicUuid: String) : ToolParams()
data class BleWrite(val address: String, val characteristicUuid: String, val data: String) : ToolParams()
data class BleNotify(val address: String, val characteristicUuid: String, val enable: Boolean) : ToolParams()
data class SppConnect(val address: String) : ToolParams()
data class SppSend(val address: String, val data: String) : ToolParams()
data class SppReceive(val address: String) : ToolParams()
```

**Android 权限声明：**

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<!-- Android 12+ -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<!-- 位置 (BLE 扫描在 Android 11 及以下需要) -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />
```

### Result 类型

```kotlin
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Failure(val error: DomainError) : Result<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun getOrNull(): T? = (this as? Success)?.data
    fun getErrorOrNull(): DomainError? = (this as? Failure)?.error

    inline fun <R> map(transform: (T) -> R): Result<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }

    inline fun onSuccess(action: (T) -> Unit): Result<T> { if (this is Success) action(data); return this }
    inline fun onFailure(action: (DomainError) -> Unit): Result<T> { if (this is Failure) action(error); return this }
}

data class DomainError(
    val kind: ErrorKind,
    val message: String,
    val cause: Throwable? = null
)

enum class ErrorKind {
    NETWORK, AUTH, RATE_LIMIT, STORAGE, PERMISSION,
    NOT_FOUND, TIMEOUT, VALIDATION, TOOL_EXECUTION,
    PROVIDER, UNKNOWN
}

object Results {
    fun <T> success(data: T): Result<T> = Result.Success(data)
    fun failure(kind: ErrorKind, message: String, cause: Throwable? = null): Result<Nothing> =
        Result.Failure(DomainError(kind, message, cause))
}
```

---

## 数据存储策略

### Room 配置 — WAL 模式高并发

```kotlin
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ToolCallEntity::class,
        ProviderEntity::class,
        CronJobEntity::class,
        AutomationRuleEntity::class
    ],
    version = 1
)
@TypeConverters(InstantConverter::class, JsonStringConverter::class)
abstract class AIShellDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun toolCallDao(): ToolCallDao
    abstract fun providerDao(): ProviderDao
    abstract fun automationDao(): AutomationDao

    companion object {
        fun create(context: Context): AIShellDatabase {
            return Room.databaseBuilder(context, AIShellDatabase::class.java, "aishell.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)  // 并发读写
                .enableMultiInstanceInvalidation()                  // 多进程支持
                .build()
        }
    }
}
```

### 存储分层

| 数据类型 | 存储方式 | 理由 |
|---|---|---|
| 对话/消息/工具记录 | Room (SQLite WAL) | 结构化查询，并发读写 |
| AI Provider 配置 | Room | 需要事务保护 |
| API Key | EncryptedFile (AndroidKeyStore) | 最高安全级别 |
| 用户偏好/终端配置 | DataStore (Proto) | 类型安全，协程友好 |
| 终端 scrollback | 文件 (内存映射) | 大量历史数据，不走 DB |
| 自动化规则 | Room | 需要 Cron 调度查询 |

---

## 并发架构总览

```
┌─────────────────────────────────────────────────────────┐
│                    AIShell 并发模型                      │
│                                                         │
│  ┌─────────────────────────────────────────────────┐   │
│  │  Main Thread (Compose UI)                       │   │
│  │  - TerminalView 渲染 (Canvas draw)              │   │
│  │  - UiState 收集 (StateFlow → Compose)           │   │
│  │  - 用户输入 → TerminalSession.writeInput()       │   │
│  └─────────────────────────────────────────────────┘   │
│                         │                               │
│  ┌──────────────────────▼──────────────────────────┐   │
│  │  Dispatchers.Default (Agent Engine)             │   │
│  │  - AgentEngine.processLoop()                    │   │
│  │  - AI Provider chatStream()                     │   │
│  │  - Tool execute()                               │   │
│  │  - ConfirmationGate (CompletableDeferred)       │   │
│  └─────────────────────────────────────────────────┘   │
│                         │                               │
│  ┌──────────────────────▼──────────────────────────┐   │
│  │  Dispatchers.IO (I/O 密集)                      │   │
│  │  - TerminalSession PTY 读取 (Channel 接收)      │   │
│  │  - Room 数据库读写                              │   │
│  │  - Ktor SSE 网络流                              │   │
│  │  - VFS 文件操作                                 │   │
│  └─────────────────────────────────────────────────┘   │
│                         │                               │
│  ┌──────────────────────▼──────────────────────────┐   │
│  │  Rust Native Thread (per PTY session)           │   │
│  │  - PTY read() → ANSI Parse → Grid Update        │   │
│  │  - libusb 事件循环                              │   │
│  │  - proot IPC                                    │   │
│  └─────────────────────────────────────────────────┘   │
│                                                         │
│  通信原语：                                              │
│  - 同一对话内：Channel<AgentEvent> (高吞吐)             │
│  - 跨对话/模块：SharedFlow<AppEvent> (广播)             │
│  - 确认门控：CompletableDeferred<Boolean>               │
│  - PTY 输出：Channel<ScreenUpdate> (无界)              │
│  - UI 状态：StateFlow<TerminalUiState> (合并更新)       │
└─────────────────────────────────────────────────────────┘
```

---

## tmux 多窗口会话系统

### 设计理念

AIShell 不依赖外部 tmux 二进制，而是用 **Rust 原生实现 tmux 核心语义**：会话(session) → 窗口(window) → 窗格(pane)三级结构，支持 detach/attach、分屏、会话持久化。同时在 proot 环境中也可运行真实 tmux。

### 三级数据模型

```
Session (会话)
 ├── Window 0 (窗口) — 选项卡
 │    ├── Pane 0 (窗格) — PTY 进程 ← AI 对话
 │    └── Pane 1 (窗格) — PTY 进程 ← htop
 ├── Window 1 (窗口)
 │    └── Pane 0 (窗格) — PTY 进程 ← python3
 └── Window 2 (窗口)
      ├── Pane 0 (窗格) — 左半屏
      └── Pane 1 (窗格) — 右半屏
```

### Rust 侧 — Session Manager

```rust
// session_manager.rs — tmux 核心语义实现

pub struct SessionManager {
    sessions: HashMap<SessionId, Session>,
    active_session: Option<SessionId>,
}

pub struct Session {
    id: SessionId,
    name: String,
    windows: Vec<Window>,
    active_window: usize,
    created_at: Instant,
    detached: bool,
}

pub struct Window {
    id: WindowId,
    name: String,            // 通常取当前运行命令名
    layout: PaneLayout,      // 窗格布局（分屏结构）
    panes: Vec<Pane>,
    active_pane: usize,
}

pub struct Pane {
    id: PaneId,
    pty: PtySession,         // 每个窗格一个 PTY
    title: String,
    cwd: PathBuf,            // 当前工作目录
    running_cmd: Option<String>,  // 前台进程名
    scrollback: VecDeque<Line>,   // 滚动缓冲
    visible: bool,           // 是否在当前布局中可见
}

/// 窗格布局 — 支持任意分屏
pub enum PaneLayout {
    Single { pane: PaneId },                           // 单窗格
    Horizontal { top: Box<PaneLayout>, bottom: Box<PaneLayout>, ratio: f32 },
    Vertical { left: Box<PaneLayout>, right: Box<PaneLayout>, ratio: f32 },
}

impl SessionManager {
    // ── 会话管理 ──

    pub fn create_session(&mut self, name: &str, cols: u16, rows: u16) -> &Session {
        let session = Session::new(name, cols, rows);
        // 创建默认窗口 + 窗格，启动 PTY
        ...
    }

    pub fn detach_session(&mut self, session_id: SessionId) {
        // 标记 detached，PTY 进程继续运行
        if let Some(s) = self.sessions.get_mut(&session_id) {
            s.detached = true;
        }
    }

    pub fn attach_session(&mut self, session_id: SessionId) -> Result<&Session> {
        // 重新关联，刷新所有窗格尺寸
        let session = self.sessions.get_mut(&session_id)
            .ok_or(Error::SessionNotFound)?;
        session.detached = false;
        session.reflow_panes();  // 重算布局
        Ok(session)
    }

    pub fn kill_session(&mut self, session_id: SessionId) {
        // 关闭所有 PTY，释放资源
        if let Some(mut session) = self.sessions.remove(&session_id) {
            for window in &mut session.windows {
                for pane in &mut window.panes {
                    pane.pty.send_signal(Signal::SIGHUP);
                }
            }
        }
    }

    pub fn list_sessions(&self) -> Vec<SessionInfo> {
        self.sessions.values().map(|s| SessionInfo {
            id: s.id,
            name: s.name.clone(),
            windows: s.windows.len(),
            attached: !s.detached,
            created_at: s.created_at,
        }).collect()
    }

    // ── 窗口管理 ──

    pub fn new_window(&mut self, session_id: SessionId, name: &str) -> WindowId {
        // 在会话中创建新窗口 + 默认窗格
        ...
    }

    pub fn next_window(&mut self, session_id: SessionId) { ... }
    pub fn prev_window(&mut self, session_id: SessionId) { ... }
    pub fn kill_window(&mut self, session_id: SessionId, window_id: WindowId) { ... }

    // ── 窗格管理 ──

    pub fn split_pane_horizontal(
        &mut self, session_id: SessionId, window_id: WindowId, pane_id: PaneId
    ) -> PaneId {
        // 上下分屏：将当前窗格区域一分为二
        // 新建 PTY，继承 cwd
        // 更新 PaneLayout
        ...
    }

    pub fn split_pane_vertical(
        &mut self, session_id: SessionId, window_id: WindowId, pane_id: PaneId
    ) -> PaneId {
        // 左右分屏
        ...
    }

    pub fn kill_pane(&mut self, session_id: SessionId, window_id: WindowId, pane_id: PaneId) {
        // 关闭 PTY，合并布局到相邻窗格
        ...
    }

    pub fn resize_pane(
        &mut self, session_id: SessionId, window_id: WindowId,
        pane_id: PaneId, direction: ResizeDirection, amount: u16
    ) {
        // 调整分屏比例，通知 PTY resize
        ...
    }

    pub fn select_pane(&mut self, session_id: SessionId, window_id: WindowId, pane_id: PaneId) {
        // 切换活跃窗格
        ...
    }

    // ── 读取输出 ──

    pub fn read_active_pane_output(&mut self, session_id: SessionId) -> Result<DirtyRegion> {
        let session = self.sessions.get_mut(&session_id).ok_or(Error::SessionNotFound)?;
        let window = &mut session.windows[session.active_window];
        let pane = &mut window.panes[window.active_pane];
        pane.pty.read_output()
    }

    pub fn read_all_visible_panes(&mut self, session_id: SessionId) -> Vec<(PaneId, DirtyRegion)> {
        // 读取当前窗口所有可见窗格的输出（分屏场景）
        let session = self.sessions.get_mut(&session_id).unwrap();
        let window = &session.windows[session.active_window];
        window.panes.iter()
            .filter(|p| p.visible)
            .filter_map(|p| {
                let mut pane = p;  // &mut
                Some((pane.id, pane.pty.read_output().ok()?))
            })
            .collect()
    }

    // ── 写入输入 ──

    pub fn write_active_pane(&mut self, session_id: SessionId, data: &[u8]) {
        let session = self.sessions.get_mut(&session_id).unwrap();
        let window = &mut session.windows[session.active_window];
        let pane = &mut window.panes[window.active_pane];
        pane.pty.write_input(data);
    }
}

#[derive(Clone)]
pub struct SessionInfo {
    pub id: SessionId,
    pub name: String,
    pub windows: usize,
    pub attached: bool,
    pub created_at: Instant,
}

pub enum ResizeDirection { Up, Down, Left, Right }
```

### JNI Bridge — Session Manager

```rust
// session_jni.rs — SessionManager 的 JNI 暴露

static SESSION_MANAGER: Lazy<Mutex<SessionManager>> = Lazy::new(|| {
    Mutex::new(SessionManager::new())
});

#[no_mangle]
pub extern "C" fn Java_com_aishell_terminal_SessionManagerJni_nativeCreateSession(
    mut env: JNIEnv, _class: JClass, name: JString, cols: jint, rows: jint
) -> jlong {
    let mut mgr = SESSION_MANAGER.lock().unwrap();
    let name_str = env.get_string(&name).unwrap().into();
    let session = mgr.create_session(&name_str, cols as u16, rows as u16);
    session.id as jlong
}

#[no_mangle]
pub extern "C" fn Java_com_aishell_terminal_SessionManagerJni_nativeDetachSession(
    _env: JNIEnv, _class: JClass, session_id: jlong
) {
    let mut mgr = SESSION_MANAGER.lock().unwrap();
    mgr.detach_session(session_id as SessionId);
}

#[no_mangle]
pub extern "C" fn Java_com_aishell_terminal_SessionManagerJni_nativeAttachSession(
    _env: JNIEnv, _class: JClass, session_id: jlong
) {
    let mut mgr = SESSION_MANAGER.lock().unwrap();
    mgr.attach_session(session_id as SessionId).unwrap();
}

#[no_mangle]
pub extern "C" fn Java_com_aishell_terminal_SessionManagerJni_nativeSplitPane(
    _env: JNIEnv, _class: JClass, session_id: jlong, window_id: jlong,
    pane_id: jlong, vertical: jboolean
) -> jlong {
    let mut mgr = SESSION_MANAGER.lock().unwrap();
    let new_pane = if vertical != 0 {
        mgr.split_pane_vertical(session_id as SessionId, window_id as WindowId, pane_id as PaneId)
    } else {
        mgr.split_pane_horizontal(session_id as SessionId, window_id as WindowId, pane_id as PaneId)
    };
    new_pane as jlong
}

#[no_mangle]
pub extern "C" fn Java_com_aishell_terminal_SessionManagerJni_nativeNewWindow(
    _env: JNIEnv, _class: JClass, session_id: jlong, name: JString
) -> jlong {
    let mut mgr = SESSION_MANAGER.lock().unwrap();
    let window = mgr.new_window(session_id as SessionId, &name_str);
    window.id as jlong
}

// ... listSessions, killSession, resizePane, selectPane, etc.
```

### Kotlin 侧 — SessionManager

```kotlin
object SessionManagerJni {
    init { System.loadLibrary("aishell_terminal") }

    private external fun nativeCreateSession(name: String, cols: Int, rows: Int): Long
    private external fun nativeDetachSession(sessionId: Long)
    private external fun nativeAttachSession(sessionId: Long)
    private external fun nativeKillSession(sessionId: Long)
    private external fun nativeNewWindow(sessionId: Long, name: String): Long
    private external fun nativeKillWindow(sessionId: Long, windowId: Long)
    private external fun nativeSplitPane(sessionId: Long, windowId: Long, paneId: Long, vertical: Boolean): Long
    private external fun nativeKillPane(sessionId: Long, windowId: Long, paneId: Long)
    private external fun nativeResizePane(sessionId: Long, windowId: Long, paneId: Long, direction: Int, amount: Int)
    private external fun nativeSelectPane(sessionId: Long, windowId: Long, paneId: Long)
    private external fun nativeNextWindow(sessionId: Long)
    private external fun nativePrevWindow(sessionId: Long)
    private external fun nativeReadActiveOutput(sessionId: Long, buffer: ByteBuffer): Int
    private external fun nativeWriteActiveInput(sessionId: Long, data: ByteArray)
    private external fun nativeListSessions(): ByteArray  // JSON
    private external fun nativeGetLayout(sessionId: Long, windowId: Long): ByteArray  // JSON
}

class TmuxSessionManager(private val scope: CoroutineScope) {

    private val buffer = ByteBuffer.allocateDirect(512 * 1024)
    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions.asStateFlow()

    private val _activeSessionState = MutableStateFlow<SessionState?>(null)
    val activeSessionState: StateFlow<SessionState?> = _activeSessionState.asStateFlow()

    // ── 会话操作 ──

    fun createSession(name: String, cols: Int, rows: Int): Long {
        val id = SessionManagerJni.nativeCreateSession(name, cols, rows)
        refreshSessionList()
        attachSession(id)
        return id
    }

    fun detachSession(sessionId: Long) {
        SessionManagerJni.nativeDetachSession(sessionId)
        refreshSessionList()
    }

    fun attachSession(sessionId: Long) {
        SessionManagerJni.nativeAttachSession(sessionId)
        startPolling(sessionId)
        refreshSessionList()
    }

    fun killSession(sessionId: Long) {
        SessionManagerJni.nativeKillSession(sessionId)
        refreshSessionList()
    }

    // ── 窗口操作 ──

    fun newWindow(sessionId: Long, name: String): Long {
        val windowId = SessionManagerJni.nativeNewWindow(sessionId, name)
        refreshLayout(sessionId)
        return windowId
    }

    fun nextWindow(sessionId: Long) {
        SessionManagerJni.nativeNextWindow(sessionId)
        refreshLayout(sessionId)
    }

    fun prevWindow(sessionId: Long) {
        SessionManagerJni.nativePrevWindow(sessionId)
        refreshLayout(sessionId)
    }

    // ── 窗格操作 ──

    fun splitPaneVertical(sessionId: Long, windowId: Long, paneId: Long): Long {
        val newPane = SessionManagerJni.nativeSplitPane(sessionId, windowId, paneId, true)
        refreshLayout(sessionId)
        return newPane
    }

    fun splitPaneHorizontal(sessionId: Long, windowId: Long, paneId: Long): Long {
        val newPane = SessionManagerJni.nativeSplitPane(sessionId, windowId, paneId, false)
        refreshLayout(sessionId)
        return newPane
    }

    fun selectPane(sessionId: Long, windowId: Long, paneId: Long) {
        SessionManagerJni.nativeSelectPane(sessionId, windowId, paneId)
        refreshLayout(sessionId)
    }

    // ── 轮询读取 ──

    private val pollingJobs = ConcurrentHashMap<Long, Job>()

    private fun startPolling(sessionId: Long) {
        pollingJobs[sessionId]?.cancel()
        pollingJobs[sessionId] = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val len = SessionManagerJni.nativeReadActiveOutput(sessionId, buffer)
                if (len > 0) {
                    buffer.rewind()
                    buffer.limit(len)
                    val update = decodeScreenUpdate(buffer)
                    _activeSessionState.update { old ->
                        old?.copy(screenUpdate = update)
                    }
                    buffer.clear()
                } else {
                    delay(8)
                }
            }
        }
    }

    private fun refreshSessionList() {
        val json = SessionManagerJni.nativeListSessions()
        _sessions.value = json.decodeToList<SessionInfo>()
    }

    private fun refreshLayout(sessionId: Long) {
        val layoutJson = SessionManagerJni.nativeGetLayout(sessionId, -1)
        val layout = json.decodeFromString<PaneLayoutSnapshot>(layoutJson.decodeToString())
        _activeSessionState.update { old ->
            old?.copy(layout = layout)
        }
    }
}

data class SessionInfo(
    val id: Long,
    val name: String,
    val windowCount: Int,
    val attached: Boolean
)

data class SessionState(
    val sessionId: Long,
    val activeWindowId: Long,
    val activePaneId: Long,
    val layout: PaneLayoutSnapshot,
    val screenUpdate: ScreenUpdate?
)

data class PaneLayoutSnapshot(
    val windows: List<WindowSnapshot>,
    val activeWindowIndex: Int
)

data class WindowSnapshot(
    val id: Long,
    val name: String,
    val panes: List<PaneSnapshot>,
    val activePaneIndex: Int,
    val layout: LayoutNode  // 布局树
)

data class PaneSnapshot(
    val id: Long,
    val title: String,
    val cwd: String,
    val runningCmd: String?
)

data class LayoutNode(
    val type: String,         // "single" | "horizontal" | "vertical"
    val paneId: Long? = null, // type=single 时
    val ratio: Float? = null, // 分屏比例
    val children: List<LayoutNode>? = null
)
```

### UI — 终端多窗口界面

```kotlin
@Composable
fun TmuxTerminalView(
    sessionManager: TmuxSessionManager,
    modifier: Modifier = Modifier
) {
    val sessionState by sessionManager.activeSessionState.collectAsState()
    val sessions by sessionManager.sessions.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        // ── 顶部：窗口选项卡栏 ──
        WindowTabBar(
            windows = sessionState?.layout?.windows ?: emptyList(),
            activeWindowIndex = sessionState?.layout?.activeWindowIndex ?: 0,
            onWindowSelect = { index ->
                // 切换窗口：先 prev/next 到目标
                sessionState?.let { s ->
                    val target = s.layout.windows[index]
                    sessionManager.selectPane(s.sessionId, target.id, target.panes[target.activePaneIndex].id)
                }
            },
            onNewWindow = {
                sessionState?.let { s ->
                    sessionManager.newWindow(s.sessionId, "window")
                }
            },
            onCloseWindow = { index ->
                sessionState?.let { s ->
                    val window = s.layout.windows[index]
                    SessionManagerJni.nativeKillWindow(s.sessionId, window.id)
                }
            }
        )

        // ── 中部：终端内容区（支持分屏） ──
        sessionState?.let { state ->
            val activeWindow = state.layout.windows[state.layout.activeWindowIndex]
            PaneLayoutView(
                layoutNode = activeWindow.layoutNode,
                panes = activeWindow.panes,
                activePaneId = state.activePaneId,
                onPaneClick = { paneId ->
                    sessionManager.selectPane(state.sessionId, activeWindow.id, paneId)
                },
                onSplitVertical = { paneId ->
                    sessionManager.splitPaneVertical(state.sessionId, activeWindow.id, paneId)
                },
                onSplitHorizontal = { paneId ->
                    sessionManager.splitPaneHorizontal(state.sessionId, activeWindow.id, paneId)
                },
                sessionManager = sessionManager,
                sessionId = state.sessionId
            )
        }

        // ── 底部：会话栏 ──
        SessionBar(
            sessions = sessions,
            onSessionSelect = { id -> sessionManager.attachSession(id) },
            onNewSession = {
                sessionManager.createSession("session", 80, 24)
            },
            onDetach = { id -> sessionManager.detachSession(id) }
        )
    }
}

@Composable
fun PaneLayoutView(
    layoutNode: LayoutNode,
    panes: List<PaneSnapshot>,
    activePaneId: Long,
    onPaneClick: (Long) -> Unit,
    onSplitVertical: (Long) -> Unit,
    onSplitHorizontal: (Long) -> Unit,
    sessionManager: TmuxSessionManager,
    sessionId: Long
) {
    when (layoutNode.type) {
        "single" -> {
            // 单窗格 — 全屏终端
            val paneId = layoutNode.paneId!!
            val isActive = paneId == activePaneId
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (isActive) Modifier.border(2.dp, Color(0xFF00FF88)) else Modifier)
                    .clickable { onPaneClick(paneId) }
            ) {
                SinglePaneView(sessionManager, sessionId, paneId)
            }
        }
        "horizontal" -> {
            // 上下分屏
            val ratio = layoutNode.ratio ?: 0.5f
            val children = layoutNode.children!!
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(ratio)) {
                    PaneLayoutView(children[0], panes, activePaneId, onPaneClick,
                        onSplitVertical, onSplitHorizontal, sessionManager, sessionId)
                }
                // 分割线 — 可拖拽调整比例
                HorizontalSplitter { delta ->
                    // resize pane
                }
                Box(modifier = Modifier.weight(1f - ratio)) {
                    PaneLayoutView(children[1], panes, activePaneId, onPaneClick,
                        onSplitVertical, onSplitHorizontal, sessionManager, sessionId)
                }
            }
        }
        "vertical" -> {
            // 左右分屏
            val ratio = layoutNode.ratio ?: 0.5f
            val children = layoutNode.children!!
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(ratio)) {
                    PaneLayoutView(children[0], panes, activePaneId, onPaneClick,
                        onSplitVertical, onSplitHorizontal, sessionManager, sessionId)
                }
                VerticalSplitter { delta ->
                    // resize pane
                }
                Box(modifier = Modifier.weight(1f - ratio)) {
                    PaneLayoutView(children[1], panes, activePaneId, onPaneClick,
                        onSplitVertical, onSplitHorizontal, sessionManager, sessionId)
                }
            }
        }
    }
}
```

### 终端界面布局

```
┌──────────────────────────────────────────────────────────┐
│ [1:main] [2:python] [3:git] [+]                    ▼ ▼  │  ← 窗口选项卡
├──────────────────────────────────────────────────────────┤
│                                                          │
│  $ python3 train.py                                      │  ← 上半屏 (Pane 0)
│  Epoch 1/50: loss=0.342                                  │
│  Epoch 2/50: loss=0.287                                  │
│  ...                                                     │
│                                                          │
├─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─┤  ← 可拖拽分割线
│                                                          │
│  $ nvidia-smi                                            │  ← 下半屏 (Pane 1)
│  GPU 0: 67°C  |████████░░| 78%                          │
│  $ watch -n1 nvidia-smi                                  │
│                                                          │
├──────────────────────────────────────────────────────────┤
│ (●) main  (○) deploy  [+] 新会话                 [≡]   │  ← 底部会话栏
└──────────────────────────────────────────────────────────┘
```

### 快捷键映射

| 操作 | 快捷键 | 说明 |
|---|---|---|
| 新建窗口 | `Ctrl+B` → `c` | 兼容 tmux 习惯 |
| 关闭窗口 | `Ctrl+B` → `&` | |
| 下一窗口 | `Ctrl+B` → `n` | |
| 上一窗口 | `Ctrl+B` → `p` | |
| 左右分屏 | `Ctrl+B` → `%` | |
| 上下分屏 | `Ctrl+B` → `"` | |
| 切换窗格 | `Ctrl+B` → 方向键 | |
| 调整大小 | `Ctrl+B` → `Ctrl+方向键` | |
| 关闭窗格 | `Ctrl+B` → `x` | |
| detach | `Ctrl+B` → `d` | 会话后台运行 |
| 窗格全屏 | `Ctrl+B` → `z` | 窗格放大/还原 |
| 滚动模式 | `Ctrl+B` → `[` | 进入 copy mode |
| 会话列表 | `Ctrl+B` → `s` | 弹出会话选择 |

> **前缀键可配置**：默认 `Ctrl+B`，用户可改为 `Ctrl+A`（screen 习惯）或自定义。

### 与 AI Agent 的协作

```
用户: "帮我监控 GPU 使用率，同时在另一个窗口训练模型"
    ↓
AI 执行:
  1. split_pane_horizontal()     → 上下分屏
  2. 上窗格: shell_exec("python3 train.py")
  3. 下窗格: shell_exec("watch -n1 nvidia-smi")
    ↓
终端展示:
  ┌──────────────────────────┐
  │ $ python3 train.py       │  ← AI 自动分屏
  │ Epoch 1/50 ...           │
  ├──────────────────────────┤
  │ $ watch -n1 nvidia-smi   │
  │ GPU 0: 67°C  78%        │
  └──────────────────────────┘
```

AI 工具扩展：

```kotlin
// 新增 ToolParams 子类
sealed class ToolParams {
    // ... 已有参数
    data class TmuxAction(
        val action: TmuxActionType,
        val sessionId: Long? = null,
        val windowName: String? = null,
        val splitDirection: SplitDirection? = null,
        val command: String? = null
    ) : ToolParams()
}

enum class TmuxActionType {
    NEW_SESSION, DETACH, ATTACH, KILL_SESSION,
    NEW_WINDOW, KILL_WINDOW, NEXT_WINDOW, PREV_WINDOW,
    SPLIT_PANE, KILL_PANE, SELECT_PANE, RESIZE_PANE,
    LIST_SESSIONS
}

enum class SplitDirection { HORIZONTAL, VERTICAL }
```

### 会话持久化 — 后台守护

```kotlin
// 后台服务：保持 detached 会话的 PTY 进程存活
class TerminalKeepService : Service() {
    @Inject lateinit var sessionManager: TmuxSessionManager

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 前台通知，防止被系统杀掉
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AIShell")
            .setContentText("${sessionManager.sessions.value.size} 个会话运行中")
            .setSmallIcon(R.drawable.ic_terminal)
            .build()
        startForeground(1, notification)
        return START_STICKY
    }
}
```

detach 时的生命周期：

```
用户 detach 会话
    ↓
1. PTY 进程继续运行（Rust 侧不销毁）
2. 启动 TerminalKeepService（前台通知保活）
3. UI 切回会话列表
4. 用户随时 attach 恢复
    ↓
用户关闭 AIShell（划掉最近任务）
    ↓
TerminalKeepService 仍存活（前台服务优先级高）
PTY 进程继续运行
    ↓
用户重新打开 AIShell → 看到会话列表 → attach
```

### proot 内 tmux 兼容

除了 AIShell 原生的 tmux 语义，用户也可以在 proot Ubuntu 中安装真实 tmux：

```bash
$ apt install tmux
$ tmux new -s dev
```

AIShell 的终端渲染完全兼容：
- Rust ANSI Parser 正确解析 tmux 的 escape sequences
- 分屏渲染由 tmux 内部处理，AIShell 只需渲染 PTY 输出
- 无需特殊适配

---

## 初始化与启动

```kotlin
@HiltAndroidApp
class AIShellApp : Application() {
    @Inject lateinit var shizukuStatus: ShizukuStatusProvider
    @Inject lateinit var toolRegistry: ToolRegistry
    @Inject lateinit var prootManager: ProotManager

    override fun onCreate() {
        super.onCreate()

        // 1. 同步初始化 (< 30ms)
        shizukuStatus.startListening(this)

        // 2. 并行异步初始化
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch { toolRegistry.registerBuiltins() }
        scope.launch { prootManager.ensureInstalled() }

        // 3. 不阻塞 UI — MainActivity 直接显示
    }
}
```

### 启动时序

```
t=0ms     Application.onCreate()
          ├── Hilt DI 初始化
          └── Shizuku 监听启动                    t=5ms

t=5ms     MainActivity.onCreate()
          └── setContent { AppNavigation() }      t=30ms

t=30ms    TerminalScreen 显示
          └── 用户可交互                           t=50ms

t=50ms    后台并行初始化
          ├── Tool 注册                            t=60ms
          ├── proot Ubuntu 检查                    (首次 60s+, 后续 <1s)
          └── Rust native 库加载                   t=70ms
```

---

## 技术栈总览

| 层 | 技术 | 版本 | 说明 |
|---|---|---|---|
| 目标架构 | arm64-v8a | - | 仅 ARM64，不支持 armeabi-v7a |
| 最低 API | Android 8.0 | API 26 | minSdk |
| 语言 | Kotlin + Rust | 2.1.0 + 1.82 | Kotlin 业务 + Rust 性能关键路径 |
| UI | Jetpack Compose + Canvas | BOM 2024.12 | Canvas 自绘终端 |
| DI | Hilt | 2.53.1 | |
| 网络 | Ktor Client + SSE | 3.0.1 | 原生协程 SSE |
| 数据库 | Room (WAL) | 2.6.1 | 并发读写 |
| 偏好 | DataStore Proto | 1.1.1 | |
| 加密 | AndroidKeyStore + EncryptedFile | 1.1.0 | |
| 构建 | AGP + KSP | 8.7.3 + 2.1.0 | |
| Native | JNI + cargo-ndk | NDK 27 | aarch64-linux-android target |
| 序列化 | kotlinx.serialization | 1.7.3 | |
| 终端 | Rust PTY + ANSI Parser | 自研 | ARM64 原生性能 |
| 终端渲染 | Compose Canvas | 自研 | 60fps 差分 |
| proot | 内置 arm64 proot binary | 修补版 | 支持 seccomp 模式 |
| ADB | 内置 arm64 adb binary | 34.0.5 | 来自 Android SDK |
| USB | libusb (Rust 绑定) | 0.9 | 需要 usbfs 或 Shizuku |
| Ubuntu | 24.04 LTS ARM64 rootfs | - | 原生运行，非 QEMU 模拟 |
