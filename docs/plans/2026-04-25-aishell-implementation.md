# AIShell Implementation Plan (v3)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build an AI-driven terminal for Android ARM64 where users describe tasks in natural language, and the AI executes commands in real-time with streaming output. Pure Android + Rust dual-language architecture — no backend server.

**Architecture:** Kotlin (JVM) + Rust (native) dual-language. Kotlin handles UI/Compose, DI, Room, AI orchestration. Rust handles PTY, ANSI parsing, proot bridge, USB stack, ADB/Fastboot/EDL protocols, SSH/SFTP, SMB2/3, BLE, serial drivers, Mass Storage, MTP — all via JNI bridge.

**Tech Stack:** Kotlin 2.1.0, Compose + Material3, Hilt, Ktor Client + SSE 3.0.1, Room 2.6.1 (WAL), kotlinx.serialization 1.7.3, Rust 1.82, cargo-ndk NDK 27, btleplug, russh

**Key v3 Decisions:**
- `sealed class ToolParams` replaces `Map<String, Any>` — 46+ subtypes, compile-time safety
- `Channel<AgentEvent>` replaces `Flow` — per-conversation backpressure
- Ktor Client + SSE replaces OkHttp+Retrofit — ~400KB vs ~700KB, pure coroutines
- Rust PTY + Canvas replaces LazyColumn — differential rendering, zero-JNI-per-frame
- Rust native protocol stacks replace Java libraries (sshj/jcifs) — full control, no GC pressure
- Room WAL mode — concurrent read/write

---

## Phase 1: Project Skeleton & Core Domain

### Task 1: Gradle Multi-Module Project Setup

**Files:**
- Create: `settings.gradle.kts` — multi-module includes
- Create: `build.gradle.kts` (root) — Kotlin 2.1.0, Compose BOM, Hilt, Ktor, Room
- Create: `app/build.gradle.kts` — Android Application, arm64-v8a only, minSdk 26
- Create: `core/domain/build.gradle.kts` — pure Kotlin library (zero Android deps)
- Create: `core/data/build.gradle.kts` — Room + DataStore + EncryptedFile
- Create: `core/engine/build.gradle.kts` — Agent engine
- Create: `core/ai/build.gradle.kts` — Ktor Client + SSE
- Create: `core/terminal/build.gradle.kts` — terminal Kotlin layer
- Create: `core/executor/build.gradle.kts` — command executors
- Create: `core/vfs/build.gradle.kts` — VFS + providers
- Create: `core/platform/build.gradle.kts` — Shizuku + USB + proot
- Create: `core/security/build.gradle.kts` — SecureKeyStore + CommandSanitizer
- Create: `core/automation/build.gradle.kts` — Cron/Event/Daemon
- Create: `feature/terminal/build.gradle.kts`
- Create: `feature/sessions/build.gradle.kts`
- Create: `feature/files/build.gradle.kts`
- Create: `feature/devices/build.gradle.kts`
- Create: `feature/settings/build.gradle.kts`
- Create: `gradle.properties` — android.useAndroidX=true, kotlin.code.style=official
- Create: `gradle/libs.versions.toml` — version catalog

**Step 1: Create version catalog**

```toml
# gradle/libs.versions.toml
[versions]
kotlin = "2.1.0"
compose-bom = "2024.12.01"
hilt = "2.53.1"
ktor = "3.0.1"
room = "2.6.1"
serialization = "1.7.3"
navigation = "2.8.5"
datastore = "1.1.1"
shizuku = "13.1.5"

[libraries]
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
ktor-client-core = { group = "io.ktor", name = "ktor-client-core", version.ref = "ktor" }
ktor-client-okhttp = { group = "io.ktor", name = "ktor-client-okhttp", version.ref = "ktor" }
ktor-client-sse = { group = "io.ktor", name = "ktor-client-sse", version.ref = "ktor" }
ktor-client-content-negotiation = { group = "io.ktor", name = "ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-json = { group = "io.ktor", name = "ktor-serialization-kotlinx-json", version.ref = "ktor" }
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serialization" }
navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigation" }
datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
shizuku-api = { group = "dev.rikka.shizuku", name = "api", version.ref = "shizuku" }
shizuku-provider = { group = "dev.rikka.shizuku", name = "provider", version.ref = "shizuku" }
```

**Step 2: Create settings.gradle.kts**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "AIShell"

include(":app")
include(":core:domain", ":core:data", ":core:engine", ":core:ai")
include(":core:terminal", ":core:executor", ":core:vfs")
include(":core:platform", ":core:security", ":core:automation")
include(":feature:terminal", ":feature:sessions", ":feature:files")
include(":feature:devices", ":feature:settings")
```

**Step 3: Create app/build.gradle.kts**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.aishell"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.aishell"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    buildFeatures { compose = true }
    compileOptions { isCoreLibraryDesugaringEnabled = true }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:engine"))
    implementation(project(":core:ai"))
    implementation(project(":core:terminal"))
    implementation(project(":core:executor"))
    implementation(project(":core:vfs"))
    implementation(project(":core:platform"))
    implementation(project(":core:security"))
    implementation(project(":core:automation"))
    implementation(project(":feature:terminal"))
    implementation(project(":feature:sessions"))
    implementation(project(":feature:files"))
    implementation(project(":feature:devices"))
    implementation(project(":feature:settings"))
    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.navigation.compose)
    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
```

**Step 4: Create core/domain/build.gradle.kts**

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.serialization.json)
    // Zero Android dependencies — pure Kotlin
}
```

**Step 5: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle/ app/ core/ feature/
git commit -m "feat: v3 multi-module Gradle project skeleton (arm64-v8a only)"
```

---

### Task 2: Core Domain Models — Entities & Result

**Module:** `:core:domain` (pure Kotlin, zero Android deps)

**Files:**
- Create: `core/domain/src/main/kotlin/com/aishell/domain/entity/Conversation.kt`
- Create: `core/domain/src/main/kotlin/com/aishell/domain/entity/Message.kt`
- Create: `core/domain/src/main/kotlin/com/aishell/domain/entity/ToolCallRecord.kt`
- Create: `core/domain/src/main/kotlin/com/aishell/domain/model/Result.kt`
- Create: `core/domain/src/main/kotlin/com/aishell/domain/model/DomainError.kt`

**Step 1: Create entities**

```kotlin
// entity/Conversation.kt
package com.aishell.domain.entity

import kotlinx.serialization.Serializable

@Serializable
data class Conversation(
    val id: Long = 0,
    val title: String,
    val providerId: String = "openai",
    val modelId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
```

```kotlin
// entity/Message.kt
package com.aishell.domain.entity

import kotlinx.serialization.Serializable

enum class MessageRole { USER, ASSISTANT, SYSTEM, TOOL }

@Serializable
data class Message(
    val id: Long = 0,
    val conversationId: Long,
    val role: MessageRole,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
```

```kotlin
// entity/ToolCallRecord.kt
package com.aishell.domain.entity

import kotlinx.serialization.Serializable

enum class RiskLevel { READ_ONLY, MODIFY, DESTRUCTIVE }
enum class ToolCallStatus { PENDING, CONFIRMED, EXECUTING, SUCCESS, FAILED, CANCELLED }

@Serializable
data class ToolCallRecord(
    val id: Long = 0,
    val messageId: Long,
    val toolName: String,
    val params: String,      // JSON string from AI
    val result: String? = null,
    val status: ToolCallStatus = ToolCallStatus.PENDING,
    val riskLevel: RiskLevel = RiskLevel.READ_ONLY
)
```

**Step 2: Create Result type**

```kotlin
// model/Result.kt
package com.aishell.domain.model

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
```

```kotlin
// model/DomainError.kt
package com.aishell.domain.model

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

**Step 3: Commit**

```bash
git add core/domain/
git commit -m "feat: core domain entities (Conversation, Message, ToolCallRecord) + Result type"
```

---

### Task 3: Core Domain — Tool Interface & ToolParams (sealed class)

**Module:** `:core:domain`

**Files:**
- Create: `core/domain/src/main/kotlin/com/aishell/domain/tool/Tool.kt`
- Create: `core/domain/src/main/kotlin/com/aishell/domain/tool/ToolParams.kt`
- Create: `core/domain/src/main/kotlin/com/aishell/domain/tool/ToolEvent.kt`
- Create: `core/domain/src/main/kotlin/com/aishell/domain/tool/ToolDescriptor.kt`
- Create: `core/domain/src/main/kotlin/com/aishell/domain/tool/ToolParamParser.kt`

**Step 1: Create Tool interface**

```kotlin
// tool/Tool.kt
package com.aishell.domain.tool

import kotlinx.coroutines.flow.Flow

interface Tool {
    val name: String
    val riskLevel: RiskLevel
    val descriptor: ToolDescriptor

    suspend fun execute(params: ToolParams): Flow<ToolEvent>
}

enum class RiskLevel { READ_ONLY, MODIFY, DESTRUCTIVE }
```

```kotlin
// tool/ToolDescriptor.kt
package com.aishell.domain.tool

import kotlinx.serialization.Serializable

@Serializable
data class ToolDescriptor(
    val name: String,
    val description: String,
    val parameters: String  // JSON Schema for AI function calling
)
```

```kotlin
// tool/ToolEvent.kt
package com.aishell.domain.tool

sealed class ToolEvent {
    data class Output(val text: String) : ToolEvent()
    data class Error(val message: String) : ToolEvent()
    data object Completed : ToolEvent()
}
```

**Step 2: Create sealed class ToolParams — 46+ subtypes**

```kotlin
// tool/ToolParams.kt
package com.aishell.domain.tool

import kotlinx.serialization.Serializable

@Serializable
sealed class ToolParams {
    // === Shell / Process ===
    @Serializable data class ShellExec(val command: String, val workDir: String? = null, val timeout: Int? = null, val env: Map<String, String>? = null) : ToolParams()
    @Serializable data class ProcessList(val filter: String? = null) : ToolParams()
    @Serializable data class ProcessSignal(val pid: Int, val signal: Int = 15) : ToolParams()
    @Serializable data class BackgroundTask(val action: String, val command: String? = null, val taskId: String? = null) : ToolParams()

    // === File (via VFS) ===
    @Serializable data class FileList(val path: String) : ToolParams()
    @Serializable data class FileRead(val path: String, val offset: Long = 0, val limit: Int = 10000) : ToolParams()
    @Serializable data class FileWrite(val path: String, val content: String, val append: Boolean = false) : ToolParams()
    @Serializable data class FileMove(val source: String, val destination: String) : ToolParams()
    @Serializable data class FileDelete(val path: String, val recursive: Boolean = false) : ToolParams()
    @Serializable data class FileStat(val path: String) : ToolParams()
    @Serializable data class FileSearch(val path: String, val pattern: String, val maxDepth: Int = 10) : ToolParams()
    @Serializable data class FileChecksum(val path: String, val algorithm: String = "sha256") : ToolParams()

    // === ADB ===
    @Serializable data class AdbConnect(val target: String, val disconnect: Boolean = false) : ToolParams()
    @Serializable data class AdbCommand(val target: String?, val command: String, val timeout: Int? = null) : ToolParams()
    @Serializable data class AdbPush(val target: String?, val local: String, val remote: String) : ToolParams()
    @Serializable data class AdbPull(val target: String?, val remote: String, val local: String) : ToolParams()
    @Serializable data class AdbInstall(val target: String?, val apkPath: String) : ToolParams()

    // === USB ===
    @Serializable data class UsbList(val vendorId: Int? = null, val productId: Int? = null) : ToolParams()
    @Serializable data class UsbAction(val deviceId: String, val action: String, val endpoint: Int? = null) : ToolParams()
    @Serializable data class UsbTransfer(val deviceId: String, val endpoint: Int, val data: ByteArray, val timeout: Int = 5000) : ToolParams()

    // === Recovery / Fastboot / EDL ===
    @Serializable data class RecoverySideload(val packagePath: String) : ToolParams()
    @Serializable data class RecoveryLog(val output: String = "/tmp/recovery.log") : ToolParams()
    @Serializable data class FastbootFlash(val partition: String, val imagePath: String) : ToolParams()
    @Serializable data class FastbootBoot(val imagePath: String) : ToolParams()
    @Serializable data class FastbootUnlock(val confirm: Boolean = false) : ToolParams()
    @Serializable data class FastbootInfo(val query: String = "all") : ToolParams()
    @Serializable data class FastbootErase(val partition: String) : ToolParams()
    @Serializable data class FastbootSetSlot(val slot: String) : ToolParams()
    @Serializable data class EdlFlash(val partition: String, val imagePath: String, val programmer: String? = null) : ToolParams()
    @Serializable data class EdlBackup(val partition: String, val outputPath: String) : ToolParams()
    @Serializable data class EdlGpt(val action: String = "read") : ToolParams()
    @Serializable data class DeviceModeDetect(val timeout: Int = 5000) : ToolParams()
    @Serializable data class DeviceModeSwitch(val targetMode: String) : ToolParams()

    // === Bluetooth ===
    @Serializable data class BleScan(val durationMs: Long = 10000, val filterUuids: List<String>? = null) : ToolParams()
    @Serializable data class ClassicScan(val durationMs: Long = 12000) : ToolParams()
    @Serializable data class BleConnect(val address: String) : ToolParams()
    @Serializable data class BleRead(val address: String, val characteristicUuid: String) : ToolParams()
    @Serializable data class BleWrite(val address: String, val characteristicUuid: String, val data: String, val withResponse: Boolean = true) : ToolParams()
    @Serializable data class BleNotify(val address: String, val characteristicUuid: String, val enable: Boolean) : ToolParams()
    @Serializable data class SppConnect(val address: String) : ToolParams()
    @Serializable data class SppSend(val address: String, val data: String) : ToolParams()
    @Serializable data class SppReceive(val address: String) : ToolParams()

    // === tmux ===
    @Serializable data class TmuxAction(val action: String, val sessionName: String? = null, val windowName: String? = null) : ToolParams()

    // === Daemon ===
    @Serializable data class DaemonAction(val action: String, val name: String? = null, val command: String? = null) : ToolParams()

    // === System Integration ===
    @Serializable data class ClipboardAction(val action: String, val text: String? = null) : ToolParams()
    @Serializable data class AppLaunch(val packageName: String? = null, val url: String? = null) : ToolParams()
    @Serializable data class ShareAction(val text: String) : ToolParams()
    @Serializable data class NotifyAction(val title: String, val message: String) : ToolParams()
    @Serializable data class Screenshot(val outputPath: String? = null) : ToolParams()
    @Serializable data class InputAction(val action: String, val x: Int? = null, val y: Int? = null, val text: String? = null) : ToolParams()
    @Serializable data class PackageAction(val action: String, val packageName: String) : ToolParams()
    @Serializable data class NetAction(val action: String, val target: String? = null) : ToolParams()
    @Serializable data class BackupAction(val action: String, val packageName: String? = null, val outputPath: String? = null) : ToolParams()
    @Serializable data class SettingsAction(val namespace: String, val key: String, val value: String? = null) : ToolParams()

    // === Plugin / MCP Extension ===
    @Serializable data class Generic(val toolName: String, val paramsJson: String) : ToolParams()
}
```

**Step 3: Create ToolParamParser**

```kotlin
// tool/ToolParamParser.kt
package com.aishell.domain.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

object ToolParamParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(toolName: String, argumentsJson: String): ToolParams {
        return when (toolName) {
            "shell_exec" -> json.decodeFromString<ToolParams.ShellExec>(argumentsJson)
            "file_list" -> json.decodeFromString<ToolParams.FileList>(argumentsJson)
            "file_read" -> json.decodeFromString<ToolParams.FileRead>(argumentsJson)
            "file_write" -> json.decodeFromString<ToolParams.FileWrite>(argumentsJson)
            "file_move" -> json.decodeFromString<ToolParams.FileMove>(argumentsJson)
            "file_delete" -> json.decodeFromString<ToolParams.FileDelete>(argumentsJson)
            "file_stat" -> json.decodeFromString<ToolParams.FileStat>(argumentsJson)
            "file_search" -> json.decodeFromString<ToolParams.FileSearch>(argumentsJson)
            "file_checksum" -> json.decodeFromString<ToolParams.FileChecksum>(argumentsJson)
            "adb_connect" -> json.decodeFromString<ToolParams.AdbConnect>(argumentsJson)
            "adb_command" -> json.decodeFromString<ToolParams.AdbCommand>(argumentsJson)
            "adb_push" -> json.decodeFromString<ToolParams.AdbPush>(argumentsJson)
            "adb_pull" -> json.decodeFromString<ToolParams.AdbPull>(argumentsJson)
            "adb_install" -> json.decodeFromString<ToolParams.AdbInstall>(argumentsJson)
            "usb_list" -> json.decodeFromString<ToolParams.UsbList>(argumentsJson)
            "usb_action" -> json.decodeFromString<ToolParams.UsbAction>(argumentsJson)
            "usb_transfer" -> json.decodeFromString<ToolParams.UsbTransfer>(argumentsJson)
            "recovery_sideload" -> json.decodeFromString<ToolParams.RecoverySideload>(argumentsJson)
            "recovery_log" -> json.decodeFromString<ToolParams.RecoveryLog>(argumentsJson)
            "fastboot_flash" -> json.decodeFromString<ToolParams.FastbootFlash>(argumentsJson)
            "fastboot_boot" -> json.decodeFromString<ToolParams.FastbootBoot>(argumentsJson)
            "fastboot_unlock" -> json.decodeFromString<ToolParams.FastbootUnlock>(argumentsJson)
            "fastboot_info" -> json.decodeFromString<ToolParams.FastbootInfo>(argumentsJson)
            "fastboot_erase" -> json.decodeFromString<ToolParams.FastbootErase>(argumentsJson)
            "fastboot_set_slot" -> json.decodeFromString<ToolParams.FastbootSetSlot>(argumentsJson)
            "edl_flash" -> json.decodeFromString<ToolParams.EdlFlash>(argumentsJson)
            "edl_backup" -> json.decodeFromString<ToolParams.EdlBackup>(argumentsJson)
            "edl_gpt" -> json.decodeFromString<ToolParams.EdlGpt>(argumentsJson)
            "device_mode" -> json.decodeFromString<ToolParams.DeviceModeDetect>(argumentsJson)
            "device_switch" -> json.decodeFromString<ToolParams.DeviceModeSwitch>(argumentsJson)
            "ble_scan" -> json.decodeFromString<ToolParams.BleScan>(argumentsJson)
            "classic_scan" -> json.decodeFromString<ToolParams.ClassicScan>(argumentsJson)
            "ble_connect" -> json.decodeFromString<ToolParams.BleConnect>(argumentsJson)
            "ble_read" -> json.decodeFromString<ToolParams.BleRead>(argumentsJson)
            "ble_write" -> json.decodeFromString<ToolParams.BleWrite>(argumentsJson)
            "ble_notify" -> json.decodeFromString<ToolParams.BleNotify>(argumentsJson)
            "spp_connect" -> json.decodeFromString<ToolParams.SppConnect>(argumentsJson)
            "spp_send" -> json.decodeFromString<ToolParams.SppSend>(argumentsJson)
            "spp_receive" -> json.decodeFromString<ToolParams.SppReceive>(argumentsJson)
            "tmux" -> json.decodeFromString<ToolParams.TmuxAction>(argumentsJson)
            "daemon" -> json.decodeFromString<ToolParams.DaemonAction>(argumentsJson)
            "clipboard" -> json.decodeFromString<ToolParams.ClipboardAction>(argumentsJson)
            "app_launch" -> json.decodeFromString<ToolParams.AppLaunch>(argumentsJson)
            "share" -> json.decodeFromString<ToolParams.ShareAction>(argumentsJson)
            "notify" -> json.decodeFromString<ToolParams.NotifyAction>(argumentsJson)
            "screenshot" -> json.decodeFromString<ToolParams.Screenshot>(argumentsJson)
            "input" -> json.decodeFromString<ToolParams.InputAction>(argumentsJson)
            "package" -> json.decodeFromString<ToolParams.PackageAction>(argumentsJson)
            "net" -> json.decodeFromString<ToolParams.NetAction>(argumentsJson)
            "backup" -> json.decodeFromString<ToolParams.BackupAction>(argumentsJson)
            "settings" -> json.decodeFromString<ToolParams.SettingsAction>(argumentsJson)
            else -> ToolParams.Generic(toolName, argumentsJson)
        }
    }
}
```

**Step 4: Commit**

```bash
git add core/domain/src/main/kotlin/com/aishell/domain/tool/
git commit -m "feat: Tool interface + sealed class ToolParams (46 subtypes) + ToolParamParser"
```

---

### Task 4: Core Domain — Repository & Service Interfaces

**Module:** `:core:domain`

**Files:**
- Create: `core/domain/src/main/kotlin/com/aishell/domain/repository/ConversationRepository.kt`
- Create: `core/domain/src/main/kotlin/com/aishell/domain/repository/MessageRepository.kt`
- Create: `core/domain/src/main/kotlin/com/aishell/domain/repository/ToolCallRepository.kt`
- Create: `core/domain/src/main/kotlin/com/aishell/domain/service/AiProvider.kt`
- Create: `core/domain/src/main/kotlin/com/aishell/domain/service/CommandExecutor.kt`
- Create: `core/domain/src/main/kotlin/com/aishell/domain/service/FileProvider.kt`

**Step 1: Create repository interfaces**

```kotlin
// repository/ConversationRepository.kt
package com.aishell.domain.repository

import com.aishell.domain.entity.Conversation
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    fun getAll(): Flow<List<Conversation>>
    suspend fun getById(id: Long): Conversation?
    suspend fun insert(conversation: Conversation): Long
    suspend fun update(conversation: Conversation)
    suspend fun delete(id: Long)
}
```

```kotlin
// repository/MessageRepository.kt
package com.aishell.domain.repository

import com.aishell.domain.entity.Message
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    fun getByConversation(conversationId: Long): Flow<List<Message>>
    suspend fun insert(message: Message): Long
    suspend fun deleteByConversation(conversationId: Long)
}
```

```kotlin
// repository/ToolCallRepository.kt
package com.aishell.domain.repository

import com.aishell.domain.entity.ToolCallRecord
import kotlinx.coroutines.flow.Flow

interface ToolCallRepository {
    fun getByMessage(messageId: Long): Flow<List<ToolCallRecord>>
    suspend fun insert(toolCall: ToolCallRecord): Long
    suspend fun update(toolCall: ToolCallRecord)
}
```

**Step 2: Create service interfaces**

```kotlin
// service/AiProvider.kt
package com.aishell.domain.service

import com.aishell.domain.entity.Message
import com.aishell.domain.tool.ToolDescriptor
import kotlinx.coroutines.flow.Flow

data class AiChunk(val text: String?, val toolCalls: List<ToolCallChunk>?)
data class ToolCallChunk(val id: String, val name: String, val arguments: String)

interface AiProvider {
    val id: String
    val displayName: String
    suspend fun chatStream(messages: List<Message>, tools: List<ToolDescriptor>): Flow<AiChunk>
}
```

```kotlin
// service/CommandExecutor.kt
package com.aishell.domain.service

import kotlinx.coroutines.flow.Flow

data class CommandOutput(val text: String, val isStderr: Boolean = false)
data class CommandResult(val exitCode: Int, val output: String)

interface CommandExecutor {
    val id: String
    suspend fun execute(command: String, timeout: Int? = null): Flow<CommandOutput>
    suspend fun executeSync(command: String, timeout: Int? = null): CommandResult
}
```

```kotlin
// service/FileProvider.kt
package com.aishell.domain.service

import java.io.InputStream
import java.io.OutputStream

interface FileProvider {
    val scheme: String   // "file", "sftp", "smb", "webdav"
    val displayName: String
    suspend fun list(path: String): List<FileEntry>
    suspend fun read(path: String): InputStream
    suspend fun write(path: String): OutputStream
    suspend fun delete(path: String): Boolean
    suspend fun move(src: String, dst: String): Boolean
    suspend fun mkdir(path: String): Boolean
    suspend fun exists(path: String): Boolean
    suspend fun stat(path: String): FileEntry?
}

data class FileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long,
    val permissions: String? = null
)
```

**Step 3: Commit**

```bash
git add core/domain/src/main/kotlin/com/aishell/domain/repository/ core/domain/src/main/kotlin/com/aishell/domain/service/
git commit -m "feat: repository interfaces + AiProvider/CommandExecutor/FileProvider service interfaces"
```

---

## Phase 2: Data Layer + Room WAL

### Task 5: Room Database — WAL Mode + DAOs

**Module:** `:core:data`

**Files:**
- Create: `core/data/src/main/kotlin/com/aishell/data/local/AppDatabase.kt`
- Create: `core/data/src/main/kotlin/com/aishell/data/local/dao/ConversationDao.kt`
- Create: `core/data/src/main/kotlin/com/aishell/data/local/dao/MessageDao.kt`
- Create: `core/data/src/main/kotlin/com/aishell/data/local/dao/ToolCallDao.kt`
- Create: `core/data/src/main/kotlin/com/aishell/data/local/dao/ProviderConfigDao.kt`
- Create: `core/data/src/main/kotlin/com/aishell/data/local/entity/RoomEntities.kt`
- Create: `core/data/src/main/kotlin/com/aishell/data/repository/ConversationRepositoryImpl.kt`
- Create: `core/data/src/main/kotlin/com/aishell/data/repository/MessageRepositoryImpl.kt`
- Create: `core/data/src/main/kotlin/com/aishell/data/repository/ToolCallRepositoryImpl.kt`
- Create: `core/data/src/main/kotlin/com/aishell/data/di/DataModule.kt`

**Step 1: Create Room entities with @Entity/@ForeignKey**

```kotlin
// local/entity/RoomEntities.kt
package com.aishell.data.local.entity

import androidx.room.*
import com.aishell.domain.entity.*

@Entity(tableName = "Conversation")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val providerId: String = "openai",
    val modelId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "Message",
    foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "ToolCallRecord",
    foreignKeys = [ForeignKey(entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)]
)
data class ToolCallRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val toolName: String,
    val params: String,
    val result: String? = null,
    val status: String = "PENDING",
    val riskLevel: String = "READ_ONLY"
)

@Entity(tableName = "ProviderConfig")
data class ProviderConfigEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val baseUrl: String,
    val apiKeyEncrypted: String? = null,
    val defaultModel: String = "",
    val protocol: String = "OPENAI_COMPATIBLE",
    val isCustom: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
```

**Step 2: Create DAOs**

```kotlin
// local/dao/ConversationDao.kt
@Dao
interface ConversationDao {
    @Query("SELECT * FROM Conversation ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM Conversation WHERE id = :id")
    suspend fun getById(id: Long): ConversationEntity?
    @Insert suspend fun insert(conv: ConversationEntity): Long
    @Update suspend fun update(conv: ConversationEntity)
    @Query("DELETE FROM Conversation WHERE id = :id")
    suspend fun delete(id: Long)
}
```

(Similar for MessageDao, ToolCallDao, ProviderConfigDao — standard Room DAOs)

**Step 3: Create AppDatabase with WAL mode**

```kotlin
// local/AppDatabase.kt
@Database(
    entities = [ConversationEntity::class, MessageEntity::class, ToolCallRecordEntity::class, ProviderConfigEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun toolCallDao(): ToolCallDao
    abstract fun providerConfigDao(): ProviderConfigDao
}

// In Hilt module:
@Provides @Singleton
fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
    Room.databaseBuilder(context, AppDatabase::class.java, "aishell.db")
        .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)  // WAL mode — concurrent read/write
        .build()
```

**Step 4: Create repository implementations + Hilt DataModule**

**Step 5: Commit**

```bash
git add core/data/
git commit -m "feat: Room database (WAL mode) + DAOs + repository implementations"
```

---

## Phase 3: Rust Native Layer

### Task 6: Rust Workspace + PTY Core + JNI Bridge

**Files:**
- Create: `Cargo.toml` (workspace root)
- Create: `native/terminal-core/Cargo.toml`
- Create: `native/terminal-core/src/pty.rs` — PTY fork/exec + poll
- Create: `native/terminal-core/src/parser.rs` — ANSI escape sequence parser
- Create: `native/terminal-core/src/grid.rs` — Terminal Cell Grid
- Create: `native/terminal-core/src/jni.rs` — JNI bridge
- Create: `native/terminal-core/build.rs` — cargo-ndk config
- Create: `native/proot-bridge/Cargo.toml`
- Create: `native/usb-stack/Cargo.toml`
- Create: `cargo-ndk-config.toml`

**Step 1: Create Rust workspace**

```toml
# Cargo.toml
[workspace]
members = [
    "native/terminal-core",
    "native/proot-bridge",
    "native/usb-stack",
]
resolver = "2"

[workspace.dependencies]
jni = { version = "0.21", features = ["invocation"] }
tokio = { version = "1", features = ["full"] }
thiserror = "2"
```

```toml
# native/terminal-core/Cargo.toml
[package]
name = "aishell-terminal-core"
version = "0.1.0"
edition = "2021"

[lib]
crate-type = ["cdylib"]

[dependencies]
jni = { workspace = true }
tokio = { workspace = true }
thiserror = { workspace = true }
nix = { version = "0.29", features = ["term", "process", "poll", "fs"] }
vte = "0.13"
```

**Step 2: Implement PTY (fork/exec + poll)**

```rust
// src/pty.rs — key structures
pub struct PtyProcess {
    master_fd: RawFd,
    pid: Pid,
    cols: u16,
    rows: u16,
}

impl PtyProcess {
    pub fn spawn(shell: &str, args: &[&str], cols: u16, rows: u16, env: &[(&str, &str)]) -> Result<Self> { ... }
    pub fn read(&self, buf: &mut [u8]) -> Result<usize> { ... }
    pub fn write(&self, data: &[u8]) -> Result<usize> { ... }
    pub fn resize(&self, cols: u16, rows: u16) -> Result<()> { ... }
    pub fn is_alive(&self) -> bool { ... }
    pub fn wait(&self) -> Result<ExitStatus> { ... }
}
```

**Step 3: Implement ANSI parser (vte-based)**

```rust
// src/parser.rs
pub struct AnsiParser {
    parser: vte::Parser,
    grid: Grid,
}

impl AnsiParser {
    pub fn new(cols: u16, rows: u16) -> Self { ... }
    pub fn process(&mut self, data: &[u8]) -> Vec<GridChange> { ... }
    pub fn grid(&self) -> &Grid { ... }
}
```

**Step 4: Implement JNI bridge**

```rust
// src/jni.rs — JNI exports for PTY + Parser
// Java_com_aishell_terminal_TerminalJni_nativeCreatePty
// Java_com_aishell_terminal_TerminalJni_nativeReadPty
// Java_com_aishell_terminal_TerminalJni_nativeWritePty
// Java_com_aishell_terminal_TerminalJni_nativeResizePty
// Java_com_aishell_terminal_TerminalJni_nativeParseAnsi
// Java_com_aishell_terminal_TerminalJni_nativeGetGrid
```

**Step 5: Build with cargo-ndk**

```bash
cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release
```

**Step 6: Commit**

```bash
git add Cargo.toml native/ cargo-ndk-config.toml
git commit -m "feat: Rust workspace + PTY core + ANSI parser + JNI bridge"
```

---

### Task 7: Rust proot Bridge + USB Stack Skeleton

**Files:**
- Create: `native/proot-bridge/src/bridge.rs` — proot C FFI + persistent helper process
- Create: `native/proot-bridge/src/jni.rs`
- Create: `native/usb-stack/src/device.rs` — USB device enumeration
- Create: `native/usb-stack/src/transfer.rs` — USB bulk/interrupt transfer
- Create: `native/usb-stack/src/adb.rs` — ADB Wire Protocol skeleton
- Create: `native/usb-stack/src/fastboot.rs` — FastbootProtocol skeleton
- Create: `native/usb-stack/src/serial.rs` — Serial driver trait + CDC-ACM
- Create: `native/usb-stack/src/mass_storage.rs` — BOT/SCSI skeleton
- Create: `native/usb-stack/src/mtp.rs` — MTP skeleton
- Create: `native/usb-stack/src/jni.rs`
- Create: `app/src/main/assets/bin/` — placeholder for arm64 binaries

**Step 1: Implement proot bridge**

```rust
// native/proot-bridge/src/bridge.rs
pub struct ProotBridge {
    helper_pid: Option<Pid>,
    rootfs_path: PathBuf,
}

impl ProotBridge {
    pub fn new(rootfs_path: &Path) -> Self { ... }
    pub fn start_helper(&mut self) -> Result<()> { ... }  // fork+exec proot helper
    pub fn execute(&self, command: &str, cwd: &str, env: &[(&str, &str)]) -> Result<CommandOutput> { ... }
    pub fn is_alive(&self) -> bool { ... }
    pub fn shutdown(&mut self) -> Result<()> { ... }
}
```

**Step 2: USB stack skeleton — device enum + ADB wire protocol + Fastboot**

```rust
// native/usb-stack/src/adb.rs
pub struct AdbClient { transport: AdbTransport }

enum AdbTransport { Usb(UsbDevice), Tcp(TcpStream) }

impl AdbClient {
    pub async fn connect_usb(device: UsbDevice) -> Result<Self> { ... }
    pub async fn connect_tcp(addr: &str) -> Result<Self> { ... }
    pub async fn authenticate(&mut self, rsa_key: &RsaKey) -> Result<()> { ... }
    pub async fn shell(&mut self, command: &str) -> Result<AdbStream> { ... }
    pub async fn push(&mut self, local: &Path, remote: &str) -> Result<()> { ... }
    pub async fn pull(&mut self, remote: &str, local: &Path) -> Result<()> { ... }
}

// ADB message: 24-byte header (command, arg0, arg1, data_length, data_crc32, magic)
pub struct AdbMessage {
    pub command: u32,
    pub arg0: u32,
    pub arg1: u32,
    pub data: Vec<u8>,
}
```

**Step 3: Serial driver trait + CDC-ACM**

```rust
// native/usb-stack/src/serial.rs
pub trait SerialDriver {
    fn open(&mut self, device: &UsbDevice) -> Result<()>;
    fn set_baud_rate(&mut self, baud: u32) -> Result<()>;
    fn read(&mut self, buf: &mut [u8]) -> Result<usize>;
    fn write(&mut self, data: &[u8]) -> Result<usize>;
    fn close(&mut self) -> Result<()>;
}

pub struct CdcAcmDriver { /* ... */ }
impl SerialDriver for CdcAcmDriver { /* ... */ }

pub fn create_serial_driver(device: &UsbDevice) -> Result<Box<dyn SerialDriver>> {
    match device.vendor_id {
        0x0403 => Ok(Box::new(FtdiDriver::new())),
        0x1A86 => Ok(Box::new(Ch340Driver::new())),
        0x10C4 => Ok(Box::new(Cp2102Driver::new())),
        _ if device.interface_class == 0x02 => Ok(Box::new(CdcAcmDriver::new())),
        _ => Err(Error::UnsupportedDevice),
    }
}
```

**Step 4: Mass Storage + MTP skeletons (empty trait + struct definitions)**

**Step 5: JNI bridge for USB stack**

**Step 6: Commit**

```bash
git add native/proot-bridge/ native/usb-stack/ app/src/main/assets/
git commit -m "feat: Rust proot bridge + USB stack (ADB/Fastboot/serial/MassStorage/MTP) skeletons"
```

---

## Phase 4: AI Provider (Ktor + SSE)

### Task 8: Ktor-based AI Provider — OpenAI Compatible + Claude + MiniMax

**Module:** `:core:ai`

**Files:**
- Create: `core/ai/src/main/kotlin/com/aishell/ai/OpenAiCompatibleProvider.kt`
- Create: `core/ai/src/main/kotlin/com/aishell/ai/ClaudeProvider.kt`
- Create: `core/ai/src/main/kotlin/com/aishell/ai/MiniMaxProvider.kt`
- Create: `core/ai/src/main/kotlin/com/aishell/ai/OllamaProvider.kt`
- Create: `core/ai/src/main/kotlin/com/aishell/ai/ProviderFactory.kt`
- Create: `core/ai/src/main/kotlin/com/aishell/ai/di/AiModule.kt`

**Step 1: Implement OpenAI Compatible Provider (Ktor + SSE)**

```kotlin
class OpenAiCompatibleProvider(
    private val client: HttpClient,
    private val config: ProviderConfig,
) : AiProvider {
    override val id = config.id
    override val displayName = config.displayName

    override suspend fun chatStream(messages: List<Message>, tools: List<ToolDescriptor>): Flow<AiChunk> = channelFlow {
        val request = buildRequest(messages, tools)
        client.preparePost("${config.baseUrl}/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${config.apiKey}")
            setBody(request)
        }.execute { httpResponse ->
            val channel: ByteReadChannel = httpResponse.body()
            val sseParser = SseParser()
            val buffer = ByteArray(4096)

            while (!channel.isClosedForRead) {
                val bytesRead = channel.readAvailable(buffer)
                if (bytesRead <= 0) continue
                val chunk = String(buffer, 0, bytesRead)
                for (event in sseParser.parse(chunk)) {
                    val parsed = parseSseEvent(event)
                    if (parsed != null) send(parsed)
                }
            }
        }
    }
}
```

**Step 2: Implement Claude Provider (messages API)**

```kotlin
class ClaudeProvider(
    private val client: HttpClient,
    private val config: ProviderConfig,
) : AiProvider {
    // Anthropic messages API — different format (content blocks, tool_use blocks)
    // POST /v1/messages with x-api-key header
    override suspend fun chatStream(messages: List<Message>, tools: List<ToolDescriptor>): Flow<AiChunk> = channelFlow { ... }
}
```

**Step 3: Implement MiniMax Provider (OpenAI-compatible format)**

```kotlin
class MiniMaxProvider(
    private val client: HttpClient,
    private val config: ProviderConfig,
) : AiProvider {
    // MiniMax uses OpenAI-compatible chat/completions format
    override suspend fun chatStream(messages: List<Message>, tools: List<ToolDescriptor>): Flow<AiChunk> = channelFlow { ... }
}
```

**Step 4: Create ProviderFactory + Hilt module**

```kotlin
class ProviderFactory @Inject constructor(private val client: HttpClient) {
    fun create(config: ProviderConfig): AiProvider = when (config.protocol) {
        Protocol.OPENAI_COMPATIBLE -> OpenAiCompatibleProvider(client, config)
        Protocol.ANTHROPIC -> ClaudeProvider(client, config)
        Protocol.MINIMAX -> MiniMaxProvider(client, config)
        Protocol.OLLAMA -> OllamaProvider(client, config)
    }
}
```

**Step 5: Commit**

```bash
git add core/ai/
git commit -m "feat: Ktor+SSE AI providers (OpenAI/Claude/MiniMax/Ollama) + ProviderFactory"
```

---

## Phase 5: Agent Engine (Channel-Based)

### Task 9: Channel-Based Agent Engine + ConfirmationGate

**Module:** `:core:engine`

**Files:**
- Create: `core/engine/src/main/kotlin/com/aishell/engine/AgentEngine.kt`
- Create: `core/engine/src/main/kotlin/com/aishell/engine/AgentEvent.kt`
- Create: `core/engine/src/main/kotlin/com/aishell/engine/ConfirmationGate.kt`
- Create: `core/engine/src/main/kotlin/com/aishell/engine/ToolExecutor.kt`
- Create: `core/engine/src/main/kotlin/com/aishell/engine/CommandRouter.kt`
- Create: `core/engine/src/main/kotlin/com/aishell/engine/di/EngineModule.kt`

**Step 1: Create AgentEvent**

```kotlin
sealed class AgentEvent {
    data class TextDelta(val text: String) : AgentEvent()
    data class ToolCallStarted(val toolName: String, val toolCallId: String, val arguments: String) : AgentEvent()
    data class ToolCallCompleted(val toolName: String, val result: String?, val error: String?) : AgentEvent()
    data class ConfirmationRequired(val toolName: String, val riskLevel: RiskLevel, val params: ToolParams) : AgentEvent()
    data class ToolOutput(val text: String) : AgentEvent()
    data object Completed : AgentEvent()
    data class Error(val message: String) : AgentEvent()
}
```

**Step 2: Implement Channel-based AgentEngine**

```kotlin
@Singleton
class AgentEngine @Inject constructor(
    private val providerFactory: ProviderFactory,
    private val toolExecutor: ToolExecutor,
    private val messageRepository: MessageRepository,
    private val toolCallRepository: ToolCallRepository,
) {
    private val channels = ConcurrentHashMap<Long, Channel<AgentEvent>>()

    fun getChannel(conversationId: Long): Channel<AgentEvent> =
        channels.getOrPut(conversationId) { Channel(Channel.BUFFERED) }

    suspend fun sendMessage(conversationId: Long, userContent: String) {
        val channel = getChannel(conversationId)

        // Save user message
        messageRepository.insert(Message(conversationId = conversationId, role = MessageRole.USER, content = userContent))

        // Get history + provider
        val history = messageRepository.getByConversationSync(conversationId)
        val provider = providerFactory.create(/* current config */)
        val tools = toolExecutor.getToolDescriptors()

        // Stream AI response
        val toolCalls = mutableListOf<Pair<String, String>>()  // (toolCallId, arguments)
        var currentText = StringBuilder()

        provider.chatStream(history, tools).collect { chunk ->
            chunk.text?.let { text ->
                currentText.append(text)
                channel.send(AgentEvent.TextDelta(text))
            }
            chunk.toolCalls?.forEach { tc ->
                toolCalls.add(tc.id to tc.arguments)
                channel.send(AgentEvent.ToolCallStarted(tc.name, tc.id, tc.arguments))
            }
        }

        // Execute tool calls
        for ((toolCallId, arguments) in toolCalls) {
            val toolName = /* extract from arguments */
            val params = ToolParamParser.parse(toolName, arguments)

            // Check risk level → confirmation gate
            val tool = toolExecutor.getTool(toolName)!!
            if (tool.riskLevel != RiskLevel.READ_ONLY) {
                channel.send(AgentEvent.ConfirmationRequired(toolName, tool.riskLevel, params))
                // Wait for user confirmation via ConfirmationGate
                val confirmed = confirmationGate.awaitConfirmation(conversationId, toolCallId)
                if (!confirmed) {
                    channel.send(AgentEvent.ToolCallCompleted(toolName, null, "User cancelled"))
                    continue
                }
            }

            // Execute tool
            channel.send(AgentEvent.ToolOutput("Executing $toolName..."))
            toolExecutor.execute(toolName, params).collect { event ->
                when (event) {
                    is ToolEvent.Output -> channel.send(AgentEvent.ToolOutput(event.text))
                    is ToolEvent.Error -> channel.send(AgentEvent.ToolOutput("Error: ${event.message}"))
                    is ToolEvent.Completed -> {}
                }
            }
            channel.send(AgentEvent.ToolCallCompleted(toolName, "Done", null))
        }

        channel.send(AgentEvent.Completed)
    }
}
```

**Step 3: Implement ConfirmationGate — per-conversationId**

```kotlin
@Singleton
class ConfirmationGate {
    private val pending = ConcurrentHashMap<Long, ConcurrentHashMap<String, CompletableDeferred<Boolean>>>()

    suspend fun awaitConfirmation(conversationId: Long, toolCallId: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pending.getOrPut(conversationId) { ConcurrentHashMap() }[toolCallId] = deferred
        return deferred.await()
    }

    fun confirm(conversationId: Long, toolCallId: String) {
        pending[conversationId]?.remove(toolCallId)?.complete(true)
    }

    fun reject(conversationId: Long, toolCallId: String) {
        pending[conversationId]?.remove(toolCallId)?.complete(false)
    }
}
```

**Step 4: Implement CommandRouter — first-word routing**

```kotlin
@Singleton
class CommandRouter @Inject constructor(
    private val shellExecutor: ShellExecutor,
    private val prootExecutor: ProotExecutor,
    private val shizukuExecutor: ShizukuExecutor,
    private val adbExecutor: AdbExecutor,
) : CommandExecutor {
    override val id = "command_router"

    override suspend fun execute(command: String, timeout: Int?): Flow<CommandOutput> {
        return when {
            command.startsWith("adb ") -> adbExecutor.execute(command, timeout)
            command.startsWith("pm ") || command.startsWith("am ") || command.startsWith("input ") ->
                if (shizukuExecutor.isAvailable()) shizukuExecutor.execute(command, timeout)
                else shellExecutor.execute(command, timeout)
            else -> shellExecutor.execute(command, timeout)  // default: Android shell
        }
    }

    fun executeInProot(command: String, timeout: Int?): Flow<CommandOutput> =
        prootExecutor.execute(command, timeout)
}
```

**Step 5: Commit**

```bash
git add core/engine/
git commit -m "feat: Channel-based Agent Engine + ConfirmationGate + CommandRouter"
```

---

## Phase 6: Terminal UI (Rust PTY + Canvas)

### Task 10: Kotlin Terminal Layer — JNI Bridge + Canvas Renderer

**Module:** `:core:terminal` + `:feature:terminal`

**Files:**
- Create: `core/terminal/src/main/kotlin/com/aishell/terminal/TerminalJni.kt` — Rust JNI bridge
- Create: `core/terminal/src/main/kotlin/com/aishell/terminal/TerminalSession.kt` — PTY session
- Create: `core/terminal/src/main/kotlin/com/aishell/terminal/TerminalRenderer.kt` — Canvas diff renderer
- Create: `core/terminal/src/main/kotlin/com/aishell/terminal/TerminalView.kt` — Compose component
- Create: `feature/terminal/src/main/kotlin/com/aishell/feature/terminal/TerminalScreen.kt`
- Create: `feature/terminal/src/main/kotlin/com/aishell/feature/terminal/TerminalViewModel.kt`

**Step 1: TerminalJni — Rust bridge**

```kotlin
object TerminalJni {
    init { System.loadLibrary("aishell_terminal_core") }

    external fun nativeCreatePty(shell: String, cols: Int, rows: Int): Long
    external fun nativeReadPty(handle: Long): ByteArray?
    external fun nativeWritePty(handle: Long, data: ByteArray)
    external fun nativeResizePty(handle: Long, cols: Int, rows: Int)
    external fun nativeIsAlive(handle: Long): Boolean
    external fun nativeClosePty(handle: Long)
    external fun nativeParseAnsi(handle: Long, data: ByteArray): Long  // returns grid snapshot handle
    external fun nativeGetGridChanges(handle: Long): GridChangeBatch
    external fun nativeFreeGrid(handle: Long)
}
```

**Step 2: TerminalSession — PTY session lifecycle**

```kotlin
class TerminalSession(
    private val shell: String = "/system/bin/sh",
    private var cols: Int = 80,
    private var rows: Int = 24,
) {
    private var handle: Long = 0

    fun start() {
        handle = TerminalJni.nativeCreatePty(shell, cols, rows)
    }

    fun readOutput(): ByteArray? = TerminalJni.nativeReadPty(handle)

    fun writeInput(data: ByteArray) = TerminalJni.nativeWritePty(handle, data)

    fun resize(cols: Int, rows: Int) {
        this.cols = cols
        this.rows = rows
        if (handle != 0L) TerminalJni.nativeResizePty(handle, cols, rows)
    }

    fun close() {
        if (handle != 0L) {
            TerminalJni.nativeClosePty(handle)
            handle = 0
        }
    }
}
```

**Step 3: TerminalRenderer — Canvas differential rendering**

```kotlin
class TerminalRenderer {
    private val paint = TextPaint().apply {
        typeface = Typeface.MONOSPACE
        textSize = 14f * Resources.getSystem().displayMetrics.density
    }

    fun render(canvas: Canvas, grid: GridChangeBatch, width: Int, height: Int) {
        // Iterate only changed cells from Rust grid
        for (change in grid.changes) {
            val x = change.col * cellWidth
            val y = change.row * cellHeight
            // Clear cell
            canvas.drawRect(x, y, x + cellWidth, y + cellHeight, bgPaint(change.bgColor))
            // Draw character
            canvas.drawText(change.char.toString(), x, y + baseline, fgPaint(change.fgColor))
        }
    }
}
```

**Step 4: TerminalView — Compose component wrapping Canvas**

```kotlin
@Composable
fun TerminalView(
    session: TerminalSession,
    modifier: Modifier = Modifier,
) {
    val renderer = remember { TerminalRenderer() }
    val gridState = remember { mutableStateOf<GridChangeBatch?>(null) }

    // Read loop — poll PTY + parse ANSI on background thread
    LaunchedEffect(session) {
        while (isActive) {
            val output = session.readOutput()
            if (output != null) {
                val grid = TerminalJni.nativeParseAnsi(session.handle, output)
                gridState.value = TerminalJni.nativeGetGridChanges(grid)
                TerminalJni.nativeFreeGrid(grid)
            }
            delay(16) // ~60fps
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        gridState.value?.let { renderer.render(this, it, size.width.toInt(), size.height.toInt()) }
    }
}
```

**Step 5: Commit**

```bash
git add core/terminal/ feature/terminal/
git commit -m "feat: Kotlin terminal layer — JNI bridge + Canvas renderer + Compose TerminalView"
```

---

## Phase 7: Tool Implementations

### Task 11: Shell Exec + CommandRouter Tools

**Module:** `:core:executor`

**Files:**
- Create: `core/executor/src/main/kotlin/com/aishell/executor/ShellExecutor.kt`
- Create: `core/executor/src/main/kotlin/com/aishell/executor/ProotExecutor.kt`
- Create: `core/executor/src/main/kotlin/com/aishell/executor/ShizukuExecutor.kt`
- Create: `core/executor/src/main/kotlin/com/aishell/executor/AdbExecutor.kt`
- Create: `core/engine/src/main/kotlin/com/aishell/engine/ShellExecTool.kt`

**Step 1: Implement ShellExecutor (Android shell)**

```kotlin
class ShellExecutor : CommandExecutor {
    override val id = "shell"
    override suspend fun execute(command: String, timeout: Int?): Flow<CommandOutput> = channelFlow {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        // Stream stdout + stderr via process.inputStream / process.errorStream
        // Send CommandOutput chunks via channel
        process.waitFor()
        close()
    }
}
```

**Step 2: Implement ProotExecutor (JNI → Rust proot-bridge)**

```kotlin
class ProotExecutor : CommandExecutor {
    override val id = "proot"
    // Calls Rust ProotBridge via JNI
    private external fun nativeProotExec(rootfs: String, command: String, cwd: String, env: Array<String>): Int
    override suspend fun execute(command: String, timeout: Int?): Flow<CommandOutput> { ... }
}
```

**Step 3: Implement ShizukuExecutor**

```kotlin
class ShizukuExecutor : CommandExecutor {
    override val id = "shizuku"
    fun isAvailable(): Boolean = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    override suspend fun execute(command: String, timeout: Int?): Flow<CommandOutput> {
        // Use Shizuku.newProcess(arrayOf("sh", "-c", command))
    }
}
```

**Step 4: Implement ShellExecTool**

```kotlin
class ShellExecTool(
    private val commandRouter: CommandRouter,
    private val prootManager: ProotManager,
) : Tool {
    override val name = "shell_exec"
    override val riskLevel = RiskLevel.MODIFY
    override val descriptor = ToolDescriptor("shell_exec", "Execute shell command", SHELL_EXEC_SCHEMA)

    override suspend fun execute(params: ToolParams): Flow<ToolEvent> = flow {
        val p = params as ToolParams.ShellExec
        val isProotCommand = prootManager.isAvailable() && shouldRouteToProot(p.command)

        val output = if (isProotCommand)
            commandRouter.executeInProot(p.command, p.timeout)
        else
            commandRouter.execute(p.command, p.timeout)

        output.collect { chunk ->
            emit(ToolEvent.Output(chunk.text))
        }
        emit(ToolEvent.Completed)
    }

    private fun shouldRouteToProot(command: String): Boolean {
        val prootCommands = setOf("apt", "python3", "pip", "git", "node", "npm", "ssh", "curl", "wget")
        return prootCommands.any { command.startsWith(it) }
    }
}
```

**Step 5: Commit**

```bash
git add core/executor/ core/engine/src/main/kotlin/com/aishell/engine/ShellExecTool.kt
git commit -m "feat: command executors (Shell/Proot/Shizuku/ADB) + ShellExecTool with CommandRouter"
```

---

### Task 12: VFS + File Tools + SFTP/SMB Providers (Rust JNI)

**Module:** `:core:vfs`

**Files:**
- Create: `core/vfs/src/main/kotlin/com/aishell/vfs/VirtualFileSystem.kt`
- Create: `core/vfs/src/main/kotlin/com/aishell/vfs/LocalProvider.kt`
- Create: `core/vfs/src/main/kotlin/com/aishell/vfs/SftpProvider.kt` — JNI to Rust SSH/SFTP
- Create: `core/vfs/src/main/kotlin/com/aishell/vfs/SmbProvider.kt` — JNI to Rust SMB2/3
- Create: `core/vfs/src/main/kotlin/com/aishell/vfs/WebDavProvider.kt`
- Create: `core/vfs/src/main/kotlin/com/aishell/vfs/VfsFileTools.kt` — file_list/read/write/move/delete/stat/search/checksum

**Step 1: VirtualFileSystem**

```kotlin
@Singleton
class VirtualFileSystem @Inject constructor() {
    private val providers = ConcurrentHashMap<String, FileProvider>()

    fun register(provider: FileProvider) { providers[provider.scheme] = provider }

    fun resolveProvider(path: String): FileProvider {
        val scheme = path.substringBefore("://", "file")
        return providers[scheme] ?: throw IllegalArgumentException("No provider for scheme: $scheme")
    }
}
```

**Step 2: SftpProvider (Rust JNI)**

```kotlin
class SftpProvider(...) : FileProvider {
    override val scheme = "sftp"
    private var handle: Long = 0

    private suspend fun ensureConnected() {
        if (handle == 0L) handle = nativeSftpConnect(host, port, username, password, privateKey)
    }

    override suspend fun list(path: String): List<FileEntry> { ensureConnected(); return nativeSftpList(handle, path) }
    override suspend fun read(path: String): InputStream { ensureConnected(); return nativeSftpRead(handle, path) }
    // ...

    private external fun nativeSftpConnect(host: String, port: Int, user: String, pass: String?, key: String?): Long
    private external fun nativeSftpList(handle: Long, path: String): List<FileEntry>
    private external fun nativeSftpDisconnect(handle: Long)
}
```

**Step 3: SmbProvider (Rust JNI)** — similar pattern with native SMB2/3 calls

**Step 4: VfsFileTools — 8 file tools using VFS**

```kotlin
class VfsFileListTool(private val vfs: VirtualFileSystem) : Tool {
    override val name = "file_list"
    override val riskLevel = RiskLevel.READ_ONLY
    override suspend fun execute(params: ToolParams): Flow<ToolEvent> = flow {
        val p = params as ToolParams.FileList
        val provider = vfs.resolveProvider(p.path)
        val entries = provider.list(p.path)
        val output = entries.joinToString("\n") { e ->
            val type = if (e.isDirectory) "DIR" else "FILE"
            "$type ${e.permissions ?: ""} ${e.name} (${e.size} bytes)"
        }
        emit(ToolEvent.Output(output))
        emit(ToolEvent.Completed)
    }
}
// Similar for VfsFileReadTool, VfsFileWriteTool, VfsFileMoveTool, VfsFileDeleteTool, VfsFileStatTool, VfsFileSearchTool, VfsFileChecksumTool
```

**Step 5: Commit**

```bash
git add core/vfs/
git commit -m "feat: VFS + Local/SFTP(Rust JNI)/SMB(Rust JNI)/WebDAV providers + 8 file tools"
```

---

### Task 13: USB/ADB/Fastboot/EDL + Bluetooth Tools

**Module:** `:core:platform`

**Files:**
- Create: `core/platform/src/main/kotlin/com/aishell/platform/usb/UsbManager.kt`
- Create: `core/platform/src/main/kotlin/com/aishell/platform/usb/UsbRouter.kt`
- Create: `core/platform/src/main/kotlin/com/aishell/platform/adb/AdbManager.kt` — dual mode (binary + wire)
- Create: `core/platform/src/main/kotlin/com/aishell/platform/flash/FlashDeviceManager.kt`
- Create: `core/platform/src/main/kotlin/com/aishell/platform/flash/FlashSafetyGuard.kt`
- Create: `core/platform/src/main/kotlin/com/aishell/platform/bluetooth/BluetoothManager.kt`
- Create: `core/platform/src/main/kotlin/com/aishell/platform/shizuku/ShizukuManager.kt`
- Create: `core/engine/src/main/kotlin/com/aishell/engine/UsbTools.kt`
- Create: `core/engine/src/main/kotlin/com/aishell/engine/AdbTools.kt`
- Create: `core/engine/src/main/kotlin/com/aishell/engine/FlashTools.kt`
- Create: `core/engine/src/main/kotlin/com/aishell/engine/BluetoothTools.kt`

**Step 1: UsbManager + UsbRouter (three-layer routing)**

```kotlin
class UsbRouter(
    private val usbManagerLayer: UsbManagerLayer,
    private val usbAdvancedLayer: UsbAdvancedLayer,
    private val shizukuStatus: ShizukuStatusProvider,
) {
    fun resolveLayer(device: UsbDevice, requirement: UsbRequirement): UsbAccessLayer { ... }
}
```

**Step 2: AdbManager — dual mode**

```kotlin
class AdbManager(
    private val binaryManager: AdbBinaryManager,
    private val wireClient: AdbWireClient,  // Rust JNI
) {
    suspend fun shell(target: String?, command: String): Flow<CommandOutput> {
        return if (target != null && isUsbDevice(target))
            wireClient.shell(command)   // Rust ADB Wire Protocol (USB)
        else
            binaryManager.execute("adb ${target?.let { "-s $it" } ?: ""} shell $command")  // Binary (TCP)
    }
}
```

**Step 3: FlashDeviceManager + FlashSafetyGuard**

```kotlin
class FlashDeviceManager(private val adbManager: AdbManager, private val fastbootClient: FastbootClient, private val edlClient: EdlClient) {
    suspend fun detectMode(): DeviceMode { ... }  // EDL > Fastboot > Recovery > ADB
}

class FlashSafetyGuard {
    private val RISK_LEVELS = mapOf(
        "boot" to PartitionRisk.HIGH,
        "system" to PartitionRisk.HIGH,
        "bootloader" to PartitionRisk.BRICK,
        "xbl" to PartitionRisk.BRICK,
        "modem" to PartitionRisk.BRICK,
        "vendor" to PartitionRisk.HIGH,
        "userdata" to PartitionRisk.SAFE,
        "cache" to PartitionRisk.SAFE,
    )
    fun check(partition: String): FlashCheckResult { ... }
}
```

**Step 4: BluetoothManager (BLE JNI + Classic Android API)**

```kotlin
class BluetoothManager(private val context: Context) {
    // BLE: Rust btleplug JNI
    suspend fun bleScan(durationMs: Long, filterUuids: List<String>?): List<BleDeviceInfo>
    suspend fun bleConnect(address: String): BleGattConnection
    suspend fun bleRead(conn: BleGattConnection, uuid: String): ByteArray
    suspend fun bleWrite(conn: BleGattConnection, uuid: String, data: ByteArray, withResponse: Boolean)
    fun bleSubscribe(conn: BleGattConnection, uuid: String): Flow<ByteArray>

    // Classic SPP: Android BluetoothSocket
    suspend fun sppConnect(address: String): SppConnection
    fun sppReadStream(conn: SppConnection): Flow<ByteArray>
    suspend fun sppWrite(conn: SppConnection, data: ByteArray)

    private external fun nativeBleScan(durationMs: Long, filterUuids: Array<String>): Array<BleDeviceInfo>
    private external fun nativeBleConnect(address: String): Long
    // ...
}
```

**Step 5: Create tool implementations (22 tools)**

- UsbTools: UsbListTool, UsbActionTool, UsbTransferTool
- AdbTools: AdbConnectTool, AdbCommandTool, AdbPushTool, AdbPullTool, AdbInstallTool
- FlashTools: RecoverySideloadTool, RecoveryLogTool, FastbootFlashTool, FastbootBootTool, FastbootUnlockTool, FastbootInfoTool, FastbootEraseTool, FastbootSetSlotTool, EdlFlashTool, EdlBackupTool, EdlGptTool, DeviceModeDetectTool, DeviceModeSwitchTool
- BluetoothTools: BleScanTool, ClassicScanTool, BleConnectTool, BleReadTool, BleWriteTool, BleNotifyTool, SppConnectTool, SppSendTool, SppReceiveTool

**Step 6: Commit**

```bash
git add core/platform/ core/engine/src/main/kotlin/com/aishell/engine/UsbTools.kt core/engine/src/main/kotlin/com/aishell/engine/AdbTools.kt core/engine/src/main/kotlin/com/aishell/engine/FlashTools.kt core/engine/src/main/kotlin/com/aishell/engine/BluetoothTools.kt
git commit -m "feat: USB/ADB/Fastboot/EDL/Bluetooth platform managers + 22 tool implementations"
```

---

## Phase 8: Feature UI Screens

### Task 14: App Shell + Navigation + Bottom Bar

**Module:** `:app` + `:feature:*`

**Files:**
- Create: `app/src/main/kotlin/com/aishell/AIShellApp.kt` — @HiltAndroidApp
- Create: `app/src/main/kotlin/com/aishell/MainActivity.kt` — @AndroidEntryPoint
- Create: `app/src/main/kotlin/com/aishell/ui/AppNavigation.kt` — 5-tab bottom nav
- Create: `app/src/main/kotlin/com/aishell/ui/di/AppModule.kt`

**Step 1: 5-tab bottom navigation**

```
[终端] [文件] [设备] [会话] [设置]
```

**Step 2: Commit**

```bash
git add app/src/
git commit -m "feat: app shell + 5-tab bottom navigation (终端/文件/设备/会话/设置)"
```

---

### Task 15: Settings Screen — Provider Config + Shizuku + proot + Bluetooth

**Module:** `:feature:settings`

**Files:**
- Create: `feature/settings/src/main/kotlin/com/aishell/feature/settings/SettingsScreen.kt`
- Create: `feature/settings/src/main/kotlin/com/aishell/feature/settings/SettingsViewModel.kt`
- Create: `core/security/src/main/kotlin/com/aishell/security/SecureKeyStore.kt` — EncryptedFile API key storage
- Create: `core/data/src/main/kotlin/com/aishell/data/preferences/UserPreferences.kt` — DataStore

**Step 1: Implement SecureKeyStore**

```kotlin
@Singleton
class SecureKeyStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val EncryptedSharedPreferences.create(masterKey, "aishell_keys", ...) ...

    fun getApiKey(providerId: String): String? = prefs.getString("key_$providerId", null)
    fun setApiKey(providerId: String, key: String) = prefs.edit().putString("key_$providerId", key).apply()
}
```

**Step 2: Settings screen with sections:**
- AI Provider (OpenAI/Claude/MiniMax/Ollama + API key + model)
- Shizuku status + connect
- proot Ubuntu (install/manage rootfs)
- Bluetooth status
- Terminal theme

**Step 3: Commit**

```bash
git add feature/settings/ core/security/
git commit -m "feat: settings screen + SecureKeyStore + Shizuku/proot/Bluetooth status"
```

---

### Task 16: Sessions + Devices + Files Screens

**Modules:** `:feature:sessions`, `:feature:devices`, `:feature:files`

**Files:**
- Create: `feature/sessions/` — conversation list + create/delete
- Create: `feature/devices/` — ADB device management + mode detection + flash UI
- Create: `feature/files/` — file manager with VFS providers + mount dialog

**Step 1: Sessions screen — list of conversations, swipe to delete**

**Step 2: Devices screen — connected devices, mode badges, flash operations**

**Step 3: Files screen — VFS-based file browser with SFTP/SMB mount**

**Step 4: Commit**

```bash
git add feature/sessions/ feature/devices/ feature/files/
git commit -m "feat: sessions + devices + files feature screens"
```

---

## Phase 9: System Prompt + Integration

### Task 17: Dynamic System Prompt + Tool Registry Wiring

**Module:** `:core:engine`

**Files:**
- Create: `core/engine/src/main/kotlin/com/aishell/engine/SystemPromptBuilder.kt`
- Create: `core/engine/src/main/kotlin/com/aishell/engine/ToolRegistry.kt`
- Create: `core/engine/src/main/kotlin/com/aishell/engine/di/EngineModule.kt` — register all 46 tools

**Step 1: SystemPromptBuilder**

```kotlin
class SystemPromptBuilder(
    private val shizukuStatusProvider: ShizukuStatusProvider,
    private val prootManager: ProotManager,
    private val bluetoothManager: BluetoothManager,
) {
    fun build(cwd: String): String {
        val shizukuStatus = shizukuStatusProvider.getStatus()
        val deviceMode = detectDeviceMode()
        val bluetoothAvailable = bluetoothManager.isBluetoothAvailable()

        return """
你是 AIShell，Android ARM64 终端上的 AI 助手。

${shizukuSection(shizukuStatus)}

${deviceModeSection(deviceMode)}

${bluetoothSection(bluetoothAvailable)}

可用执行环境:
1. Ubuntu (proot) — apt/python3/git/node/curl/wget/ssh
2. Android Shell — 基础命令 + ${if (shizukuStatus == ShizukuStatus.AVAILABLE) "系统命令" else "受限"}
3. 设备模式切换: device_mode (检测), device_switch (切换到 recovery/fastboot/edl)
4. 文件系统: file_list/file_read/file_write (支持 sftp:// smb:// webdav:// 路径)
5. USB 设备: usb_list/usb_action/usb_transfer (串口/存储/MTP)
6. 蓝牙: ble_scan/ble_connect/spp_connect (BLE GATT/SPP 串口)

当前工作目录: $cwd
"""
    }
}
```

**Step 2: ToolRegistry — register all tools**

```kotlin
@Singleton
class ToolRegistry @Inject constructor() {
    private val tools = ConcurrentHashMap<String, Tool>()

    fun register(tool: Tool) { tools[tool.name] = tool }
    fun get(name: String): Tool? = tools[name]
    fun getAll(): List<Tool> = tools.values.toList()
    fun getDescriptors(): List<ToolDescriptor> = tools.values.map { it.descriptor }
}
```

**Step 3: Wire all tools in EngineModule**

```kotlin
@Module @InstallIn(SingletonComponent::class)
object EngineModule {
    @Provides @IntoSet fun provideShellExecTool(...) = ShellExecTool(...)
    @Provides @IntoSet fun provideFileListTool(...) = VfsFileListTool(...)
    // ... 46 tools
}
```

**Step 4: Commit**

```bash
git add core/engine/src/main/kotlin/com/aishell/engine/SystemPromptBuilder.kt core/engine/src/main/kotlin/com/aishell/engine/ToolRegistry.kt core/engine/src/main/kotlin/com/aishell/engine/di/
git commit -m "feat: dynamic SystemPrompt + ToolRegistry wiring (46 tools)"
```

---

## Phase 10: proot Ubuntu Setup

### Task 18: proot Ubuntu Rootfs + Init Scripts

**Files:**
- Create: `app/src/main/assets/ubuntu/` — rootfs tar.gz (downloaded on first launch)
- Create: `app/src/main/assets/scripts/init.sh` — Ubuntu initialization
- Create: `core/platform/src/main/kotlin/com/aishell/platform/proot/ProotManager.kt`
- Create: `core/platform/src/main/kotlin/com/aishell/platform/proot/UbuntuInstaller.kt`

**Step 1: UbuntuInstaller — download + extract rootfs**

```kotlin
class UbuntuInstaller(@ApplicationContext private val context: Context) {
    private val rootfsDir = File(context.filesDir, "ubuntu")

    suspend fun isInstalled(): Boolean = File(rootfsDir, "bin/bash").exists()

    suspend fun install(progress: (Float) -> Unit) {
        // Download ARM64 Ubuntu rootfs from mirror
        // Extract to context.filesDir/ubuntu/
        // Run init.sh (apt update, install packages)
    }

    suspend fun uninstall() { rootfsDir.deleteRecursively() }

    fun getRootfsPath(): String = rootfsDir.absolutePath
}
```

**Step 2: ProotManager — start/stop proot helper**

```kotlin
class ProotManager(private val installer: UbuntuInstaller) {
    private var bridgeHandle: Long = 0

    suspend fun isAvailable(): Boolean = installer.isInstalled()
    suspend fun start() { bridgeHandle = nativeProotStart(installer.getRootfsPath()) }
    fun execute(command: String): Flow<CommandOutput> = /* JNI call */ ...

    private external fun nativeProotStart(rootfs: String): Long
    private external fun nativeProotStop(handle: Long)
}
```

**Step 3: Commit**

```bash
git add app/src/main/assets/ core/platform/src/main/kotlin/com/aishell/platform/proot/
git commit -m "feat: proot Ubuntu installer + ProotManager + init scripts"
```

---

## Implementation Order Summary

| Phase | Tasks | Description | Dependencies |
|---|---|---|---|
| **1** | 1-4 | Project skeleton + Core Domain (entities, ToolParams, interfaces) | None |
| **2** | 5 | Room Database (WAL) + Repository implementations | Phase 1 |
| **3** | 6-7 | Rust Native Layer (PTY, ANSI parser, proot bridge, USB stack) | Phase 1 |
| **4** | 8 | AI Providers (Ktor + SSE: OpenAI/Claude/MiniMax/Ollama) | Phase 1 |
| **5** | 9 | Agent Engine (Channel-based + ConfirmationGate + CommandRouter) | Phases 1,2,4 |
| **6** | 10 | Terminal UI (Rust PTY + Canvas renderer + Compose) | Phase 3 |
| **7** | 11-13 | Tool Implementations (Shell, VFS, USB/ADB/Flash/BLE) | Phases 1,3,5 |
| **8** | 14-16 | Feature UI Screens (Navigation, Settings, Sessions, Devices, Files) | Phase 5 |
| **9** | 17 | System Prompt + Tool Registry Wiring | Phases 5,7 |
| **10** | 18 | proot Ubuntu Setup | Phases 3,7 |

**Parallelizable groups:**
- **Group A** (no deps): Tasks 1-4 (project skeleton + domain)
- **Group B** (after Group A): Tasks 5, 6-7, 8 (data layer, Rust, AI — all parallel)
- **Group C** (after Group B): Tasks 9, 10 (engine + terminal UI — can parallel)
- **Group D** (after Group C): Tasks 11-13 (tools — can parallel)
- **Group E** (after Group D): Tasks 14-17 (UI + wiring)
- **Group F** (after Group E): Task 18 (proot)

**Total: 18 tasks across 10 phases**
