# proot Ubuntu 设计文档

> Created: 2026-04-25
> Status: Draft

## 概述

AIShell 使用 **proot + Ubuntu rootfs** 方案，为用户提供完整的 Linux 终端环境。

**安装方案**：
- **proot 二进制**：内置到 APK（JNI 库形式，约 500KB）
- **Ubuntu rootfs**：首次运行时下载（约 50MB）

---

## 1. 架构设计

```
┌─────────────────────────────────────────────────────────┐
│                      APK 结构                           │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  app/src/main/jniLibs/arm64-v8a/                        │
│  └── libproot.so         ← proot 二进制（约 500KB）     │
│                                                         │
│  app/src/main/assets/                                   │
│  └── scripts/                                            │
│      └── init.sh         ← 初始化脚本                  │
│                                                         │
│  首次运行时下载到：                                      │
│  /data/data/com.aishell/files/                          │
│  └── ubuntu/             ← Ubuntu rootfs               │
│      ├── bin/                                           │
│      ├── etc/                                           │
│      ├── lib/                                           │
│      ├── usr/                                           │
│      └── var/                                           │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

---

## 2. 安装流程

```
┌─────────────────────────────────────────────────────────┐
│                   首次启动流程                           │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  App 启动                                               │
│      │                                                  │
│      ▼                                                  │
│  检查 rootfs 是否存在                                   │
│      │                                                  │
│      ├── 存在 ──────────────────┐                      │
│      │                          ▼                      │
│      ▼                      环境就绪                   │
│  显示安装界面                                            │
│      │                                                  │
│      ▼                                                  │
│  下载 Ubuntu rootfs                                     │
│  (显示进度条: 0% - 100%)                                │
│      │                                                  │
│      ▼                                                  │
│  解压到 filesDir/ubuntu/                                │
│      │                                                  │
│      ▼                                                  │
│  运行初始化脚本                                          │
│  - apt update                                           │
│  - 安装基础工具                                          │
│      │                                                  │
│      ▼                                                  │
│  安装完成，进入终端                                      │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

---

## 3. 组件设计

### 3.1 ProotManager (Kotlin)

```kotlin
// core/platform/src/main/kotlin/com/aishell/platform/proot/ProotManager.kt
package com.aishell.platform.proot

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProotManager @Inject constructor(
    private val context: Context,
    private val rootfsDownloader: RootfsDownloader
) {
    private val rootfsDir = File(context.filesDir, "ubuntu")
    private val prootPath = "${context.applicationInfo.nativeLibraryDir}/libproot.so"
    
    // 检查环境是否就绪
    suspend fun isReady(): Boolean {
        return File(rootfsDir, "bin/bash").exists()
    }
    
    // 安装环境
    suspend fun install(progress: (Float) -> Unit): Result<Unit> {
        return try {
            // 1. 下载 rootfs
            rootfsDownloader.download(
                url = ROOTFS_URL,
                destDir = rootfsDir,
                progress = progress
            )
            
            // 2. 运行初始化脚本
            runInitScript()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // 执行命令
    fun execute(command: String): Flow<String> = flow {
        val process = Runtime.getRuntime().exec(
            "$prootPath -0 -r ${rootfsDir.absolutePath} " +
            "-b /dev -b /proc -b /sys " +
            "/bin/sh -c '$command'"
        )
        
        process.inputStream.bufferedReader().use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                emit(line!!)
            }
        }
        
        process.waitFor()
    }.flowOn(Dispatchers.IO)
    
    // 运行初始化脚本
    private suspend fun runInitScript() {
        execute("apt update && apt install -y curl wget git").collect()
    }
    
    companion object {
        // Ubuntu ARM64 最小 rootfs 下载地址
        const val ROOTFS_URL = "https://partner-images.canonical.com/core/focal/current/ubuntu-core-20.5-arm64.rootfs.tar.gz"
    }
}
```

### 3.2 RootfsDownloader (Kotlin)

```kotlin
// core/platform/src/main/kotlin/com/aishell/platform/proot/RootfsDownloader.kt
package com.aishell.platform.proot

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.zip.GZIPInputStream
import javax.inject.Inject

class RootfsDownloader @Inject constructor(
    private val client: OkHttpClient
) {
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
            
            // 下载并解压
            body.byteStream().use { input ->
                GZIPInputStream(input).use { gzipInput ->
                    extractTar(gzipInput, destDir) { extracted ->
                        downloadedBytes = extracted
                        if (totalBytes > 0) {
                            progress(downloadedBytes.toFloat() / totalBytes)
                        }
                    }
                }
            }
        }
    }
    
    private fun extractTar(
        input: java.io.InputStream,
        destDir: File,
        onProgress: (Long) -> Unit
    ) {
        // 使用 Apache Commons Compress 或自定义 tar 解析
        // 这里简化实现
        destDir.mkdirs()
        // TODO: 实现 tar 解压逻辑
    }
}
```

### 3.3 proot JNI 封装 (Rust)

```rust
// core/terminal/src/rust/src/proot/mod.rs
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jstring};
use std::process::Command;

/// proot 执行器
pub struct ProotExecutor {
    proot_path: String,
    rootfs_path: String,
}

impl ProotExecutor {
    pub fn new(proot_path: String, rootfs_path: String) -> Self {
        Self { proot_path, rootfs_path }
    }

    pub fn execute(&self, command: &str) -> Result<String, String> {
        let output = Command::new(&self.proot_path)
            .arg("-0")                          // 假装 root
            .arg("-r").arg(&self.rootfs_path)   // rootfs 路径
            .arg("-b").arg("/dev")              // 绑定设备
            .arg("-b").arg("/proc")             // 绑定 proc
            .arg("-b").arg("/sys")              // 绑定 sys
            .arg("/bin/sh")
            .arg("-c")
            .arg(command)
            .output()
            .map_err(|e| e.to_string())?;

        if output.status.success() {
            Ok(String::from_utf8_lossy(&output.stdout).to_string())
        } else {
            Err(String::from_utf8_lossy(&output.stderr).to_string())
        }
    }
}

// JNI 接口
#[no_mangle]
pub extern "system" fn Java_com_aishell_platform_proot_ProotNative_createExecutor(
    mut env: JNIEnv,
    _class: JClass,
    proot_path: JString,
    rootfs_path: JString,
) -> jlong {
    let proot: String = env.get_string(&proot_path).unwrap().into();
    let rootfs: String = env.get_string(&rootfs_path).unwrap().into();

    let executor = Box::new(ProotExecutor::new(proot, rootfs));
    Box::into_raw(executor) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_aishell_platform_proot_ProotNative_destroyExecutor(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe { Box::from_raw(handle as *mut ProotExecutor) };
    }
}

#[no_mangle]
pub extern "system" fn Java_com_aishell_platform_proot_ProotNative_execute(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    command: JString,
) -> jstring {
    let executor = unsafe { &mut *(handle as *mut ProotExecutor) };
    let cmd: String = env.get_string(&command).unwrap().into();

    match executor.execute(&cmd) {
        Ok(output) => env.new_string(output).unwrap().into_raw(),
        Err(e) => {
            env.throw_new("java/io/IOException", &e).unwrap();
            std::ptr::null_mut()
        }
    }
}
```

---

## 4. UI 设计

### 4.1 安装界面

```kotlin
// feature/terminal/src/main/kotlin/com/aishell/feature/terminal/InstallScreen.kt
@Composable
fun InstallScreen(
    viewModel: InstallViewModel = hiltViewModel(),
    onInstallComplete: () -> Unit
) {
    val progress by viewModel.progress.collectAsState()
    val status by viewModel.status.collectAsState()
    val error by viewModel.error.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Logo
        Icon(
            imageVector = Icons.Default.Terminal,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 标题
        Text(
            text = "AIShell",
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = "AI 终端助手",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(48.dp))

        // 进度条
        if (progress < 1f) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 状态文本
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium
            )

            // 百分比
            Text(
                text = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // 错误处理
        error?.let { msg ->
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "安装失败: $msg",
                color = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = { viewModel.retry() }) {
                Text("重试")
            }
        }
    }

    // 安装完成回调
    LaunchedEffect(progress) {
        if (progress >= 1f) {
            onInstallComplete()
        }
    }
}
```

---

## 5. 下载源配置

### 5.1 官方 Ubuntu rootfs

```kotlin
object RootfsUrls {
    // Ubuntu 官方 ARM64 rootfs
    const val UBUNTU_FOCAL = 
        "https://partner-images.canonical.com/core/focal/current/" +
        "ubuntu-core-20.5-arm64.rootfs.tar.gz"
    
    // 备用镜像（国内加速）
    const val UBUNTU_FOCAL_MIRROR_TUNA =
        "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cloud-images/" +
        "ubuntu-core/20.04/ubuntu-core-20.04-core-arm64.tar.gz"
    
    // 最小化 rootfs（自定义构建，约 20MB）
    const val MINIMAL =
        "https://github.com/aishell/rootfs/releases/download/v1.0/" +
        "ubuntu-minimal-arm64.tar.gz"
}
```

### 5.2 多源下载策略

```kotlin
class RootfsDownloader {
    private val mirrors = listOf(
        RootfsUrls.MINIMAL,           // 优先：最小化版本
        RootfsUrls.UBUNTU_FOCAL_MIRROR_TUNA,  // 国内镜像
        RootfsUrls.UBUNTU_FOCAL       // 官方源
    )
    
    suspend fun downloadWithMirrors(
        destDir: File,
        progress: (Float) -> Unit
    ): Result<Unit> {
        for (url in mirrors) {
            try {
                return download(url, destDir, progress)
            } catch (e: Exception) {
                // 尝试下一个镜像
                continue
            }
        }
        return Result.failure(Exception("所有下载源都失败了"))
    }
}
```

---

## 6. APK 体积估算

| 组件 | 大小 | 说明 |
|------|------|------|
| libproot.so | ~500KB | JNI 库 |
| libaishell-native.so | ~1MB | 其他 Rust 代码 |
| Kotlin 代码 | ~2MB | 编译后 |
| 资源文件 | ~500KB | 图标、脚本等 |
| **APK 总计** | **~10MB** | 不含 rootfs |
| rootfs (首次下载) | ~50MB | 运行时下载 |

---

## 7. 文件结构

```
core/platform/src/main/kotlin/com/aishell/platform/proot/
├── ProotManager.kt          # proot 管理器
├── RootfsDownloader.kt      # rootfs 下载器
├── ProotExecutor.kt         # 命令执行器
└── ProotNative.kt           # JNI 声明

core/platform/src/main/rust/src/proot/
├── mod.rs                   # 模块入口
├── executor.rs              # Rust 执行器
└── jni.rs                   # JNI 接口

feature/terminal/src/main/kotlin/com/aishell/feature/install/
├── InstallScreen.kt         # 安装界面
└── InstallViewModel.kt      # 安装逻辑
```

---

## 变更历史

| 日期 | 版本 | 变更 |
|------|------|------|
| 2026-04-25 | v1.0 | 初始设计：proot 内置 + rootfs 下载 |
