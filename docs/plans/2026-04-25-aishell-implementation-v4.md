# AIShell Implementation Plan v4

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build an AI-driven terminal for Android ARM64 where users describe tasks in natural language, and the AI executes commands in real-time with streaming output. Pure Android + Rust dual-language architecture — no backend server.

**Architecture:** Kotlin (JVM) + Rust (native) dual-language. Kotlin handles UI/Compose, DI, Room, AI orchestration. Rust handles PTY, ANSI parsing, proot bridge, USB stack, ADB/Fastboot/EDL protocols, SSH/SFTP, SMB2/3, BLE, serial drivers, Mass Storage, MTP — all via JNI bridge.

**Tech Stack:** Kotlin 2.1.0, Compose + Material3, Hilt, Ktor Client + SSE 3.0.1, Room 2.6.1 (WAL), kotlinx.serialization 1.7.3, Rust 1.82, cargo-ndk NDK 27

---

## Phase Overview

| Phase | Tasks | Description | Estimated Size |
|-------|-------|-------------|----------------|
| 1 | 1-4 | 项目骨架 + 核心领域模型 | 中 |
| 2 | 5 | 数据层 + Room WAL | 中 |
| 3 | 6-7 | Rust 原生层 (PTY/JNI/USB) | 大 |
| 4 | 8 | AI Provider (Ktor + SSE) | 中 |
| 5 | 9 | Agent Engine (Channel) | 大 |
| 6 | 10 | 终端 UI (Canvas 渲染) | 大 |
| 7 | 11-13 | Tool 实现 (Shell/VFS/USB) | 大 |
| 8 | 14-17 | 功能 UI 界面 + 导航 | 大 |
| 9 | 18 | System Prompt + 集成 | 中 |
| 10 | 19-24 | Shell 智能化功能 ✨新增 | 大 |
| 11 | 25 | proot Ubuntu 环境 | 中 |

**总计：25 个任务，11 个阶段**

---

## Phase 1: Project Skeleton & Core Domain

### Task 1: Gradle Multi-Module Project Setup

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts` (root)
- Create: `gradle/libs.versions.toml`
- Create: `app/build.gradle.kts`
- Create: `core/*/build.gradle.kts` (domain, data, engine, ai, terminal, executor, vfs, platform, security, automation)
- Create: `feature/*/build.gradle.kts` (terminal, sessions, files, devices, settings)

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

[plugins]
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp = { id = "com.google.devtools.ksp", version = "2.1.0-1.0.29" }
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
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.navigation.compose)
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
}
```

**Step 5: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle/ app/ core/ feature/
git commit -m "feat: v4 multi-module Gradle project skeleton (arm64-v8a only)"
```

---

### Task 2: Core Domain Models — Entities & Result

**Module:** `:core:domain`

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
    val params: String,
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

    inline fun onSuccess(action: (T) -> Unit): Result<T> {
        if (this is Success) action(data)
        return this
    }

    inline fun onFailure(action: (DomainError) -> Unit): Result<T> {
        if (this is Failure) action(error)
        return this
    }
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
```

**Step 3: Commit**

```bash
git add core/domain/src/
git commit -m "feat(core): add domain entities (Conversation, Message, ToolCallRecord) and Result type"
```

---

### Task 3: Core Domain — Tool Interface & ToolParams

**Module:** `:core:domain`

**Files:**
- Create: `core/domain/src/main/kotlin/com/aishell/domain/tool/Tool.kt`
- Create: `core/domain/src/main/kotlin/com/aishell/domain/tool/ToolParams.kt`
- Create: `core/domain/src/main/kotlin/com/aishell/domain/tool/ToolResult.kt`

**Step 1: Create Tool interface**

```kotlin
// tool/Tool.kt
package com.aishell.domain.tool

interface Tool {
    val name: String
    val description: String
    val riskLevel: RiskLevel

    suspend fun execute(params: ToolParams): ToolResult
    fun validateParams(params: ToolParams): Boolean
}

enum class RiskLevel {
    READ_ONLY,      // 安全，自动执行
    MODIFY,         // 修改操作，需确认
    DESTRUCTIVE     // 危险操作，需二次确认
}
```

**Step 2: Create sealed ToolParams**

```kotlin
// tool/ToolParams.kt
package com.aishell.domain.tool

import kotlinx.serialization.Serializable

@Serializable
sealed class ToolParams {
    // Shell 执行
    @Serializable
    data class ShellExec(
        val command: String,
        val timeout: Long = 30000,
        val workingDir: String? = null
    ) : ToolParams()

    // 文件操作
    @Serializable
    data class FileRead(val path: String) : ToolParams()

    @Serializable
    data class FileWrite(val path: String, val content: String) : ToolParams()

    @Serializable
    data class FileDelete(val path: String, val recursive: Boolean = false) : ToolParams()

    // ADB 操作
    @Serializable
    data class AdbExec(
        val command: String,
        val deviceId: String? = null
    ) : ToolParams()

    @Serializable
    data class AdbPush(
        val localPath: String,
        val remotePath: String,
        val deviceId: String? = null
    ) : ToolParams()

    // Fastboot 操作
    @Serializable
    data class FastbootFlash(
        val partition: String,
        val imagePath: String
    ) : ToolParams()

    // SSH 操作
    @Serializable
    data class SshExec(
        val host: String,
        val command: String,
        val username: String,
        val port: Int = 22
    ) : ToolParams()

    // 更多参数类型...
}
```

**Step 3: Create ToolResult**

```kotlin
// tool/ToolResult.kt
package com.aishell.domain.tool

import kotlinx.serialization.Serializable

@Serializable
data class ToolResult(
    val success: Boolean,
    val output: String,
    val error: String? = null,
    val exitCode: Int = 0,
    val duration: Long = 0,
    val requiresConfirmation: Boolean = false
) {
    companion object {
        fun success(output: String, duration: Long = 0) = ToolResult(
            success = true,
            output = output,
            duration = duration
        )

        fun failure(error: String, exitCode: Int = 1) = ToolResult(
            success = false,
            output = "",
            error = error,
            exitCode = exitCode
        )
    }
}
```

**Step 4: Commit**

```bash
git add core/domain/src/main/kotlin/com/aishell/domain/tool/
git commit -m "feat(core): add Tool interface and sealed ToolParams with 46+ subtypes"
```

---

### Task 4: Core Domain — Repository & Service Interfaces

**Module:** `:core:domain`

**Files:**
- Create: `core/domain/src/main/kotlin/com/aishell/domain/repository/ConversationRepository.kt`
- Create: `core/domain/src/main/kotlin/com/aishell/domain/repository/MessageRepository.kt`
- Create: `core/domain/src/main/kotlin/com/aishell/domain/repository/ToolCallRepository.kt`
- Create: `core/domain/src/main/kotlin/com/aishell/domain/service/AiProvider.kt`

**Step 1: Create repository interfaces**

```kotlin
// repository/ConversationRepository.kt
package com.aishell.domain.repository

import com.aishell.domain.entity.Conversation
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    suspend fun getAll(): Flow<List<Conversation>>
    suspend fun getById(id: Long): Conversation?
    suspend fun insert(conversation: Conversation): Long
    suspend fun update(conversation: Conversation)
    suspend fun delete(id: Long)
    suspend fun search(query: String): Flow<List<Conversation>>
}
```

```kotlin
// repository/MessageRepository.kt
package com.aishell.domain.repository

import com.aishell.domain.entity.Message
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    suspend fun getByConversation(conversationId: Long): Flow<List<Message>>
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
    suspend fun getByMessage(messageId: Long): Flow<List<ToolCallRecord>>
    suspend fun insert(record: ToolCallRecord): Long
    suspend fun updateStatus(id: Long, status: ToolCallRecord.Status, result: String?)
}
```

**Step 2: Create AiProvider interface**

```kotlin
// service/AiProvider.kt
package com.aishell.domain.service

import com.aishell.domain.entity.Message
import com.aishell.domain.tool.ToolParams
import kotlinx.coroutines.flow.Flow

data class AiChunk(
    val content: String = "",
    val toolName: String? = null,
    val toolCallId: String? = null,
    val toolParams: ToolParams? = null,
    val isDone: Boolean = false,
    val error: String? = null
)

data class AiConfig(
    val providerId: String,
    val modelId: String,
    val apiKey: String,
    val baseUrl: String,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096
)

interface AiProvider {
    val providerId: String
    val displayName: String

    suspend fun chatStream(
        config: AiConfig,
        messages: List<Message>,
        tools: List<ToolSpec>
    ): Flow<AiChunk>
}

data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>
)
```

**Step 3: Commit**

```bash
git add core/domain/src/main/kotlin/com/aishell/domain/repository/
git add core/domain/src/main/kotlin/com/aishell/domain/service/
git commit -m "feat(core): add repository and AiProvider interfaces"
```

---

## Phase 2: Data Layer + Room WAL

### Task 5: Room Database — WAL Mode + DAOs

**Module:** `:core:data`

**Files:**
- Create: `core/data/src/main/kotlin/com/aishell/data/local/AiShellDatabase.kt`
- Create: `core/data/src/main/kotlin/com/aishell/data/local/dao/ConversationDao.kt`
- Create: `core/data/src/main/kotlin/com/aishell/data/local/dao/MessageDao.kt`
- Create: `core/data/src/main/kotlin/com/aishell/data/local/dao/ToolCallDao.kt`
- Create: `core/data/src/main/kotlin/com/aishell/data/local/entity/ConversationEntity.kt`
- Create: `core/data/src/main/kotlin/com/aishell/data/repository/ConversationRepositoryImpl.kt`

**Step 1: Create entities**

```kotlin
// local/entity/ConversationEntity.kt
package com.aishell.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.aishell.domain.entity.Conversation

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val providerId: String,
    val modelId: String,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toDomain() = Conversation(id, title, providerId, modelId, createdAt, updatedAt)

    companion object {
        fun fromDomain(domain: Conversation) = ConversationEntity(
            domain.id, domain.title, domain.providerId,
            domain.modelId, domain.createdAt, domain.updatedAt
        )
    }
}
```

**Step 2: Create DAOs**

```kotlin
// local/dao/ConversationDao.kt
package com.aishell.data.local.dao

import androidx.room.*
import androidx.room.Dao
import com.aishell.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: Long): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ConversationEntity): Long

    @Update
    suspend fun update(entity: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM conversations WHERE title LIKE :query ORDER BY updatedAt DESC")
    fun search(query: String): Flow<List<ConversationEntity>>
}
```

**Step 3: Create Database with WAL**

```kotlin
// local/AiShellDatabase.kt
package com.aishell.data.local

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aishell.data.local.dao.ConversationDao
import com.aishell.data.local.dao.MessageDao
import com.aishell.data.local.dao.ToolCallDao
import com.aishell.data.local.entity.ConversationEntity
import com.aishell.data.local.entity.MessageEntity
import com.aishell.data.local.entity.ToolCallEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ToolCallEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AiShellDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun toolCallDao(): ToolCallDao

    companion object {
        fun create(context: Context): AiShellDatabase {
            return Room.databaseBuilder(
                context,
                AiShellDatabase::class.java,
                "aishell.db"
            )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // WAL mode optimizations
                        db.execSQL("PRAGMA synchronous=NORMAL")
                        db.execSQL("PRAGMA cache_size=-64000") // 64MB
                    }
                })
                .build()
        }
    }
}
```

**Step 4: Create repository implementation**

```kotlin
// repository/ConversationRepositoryImpl.kt
package com.aishell.data.repository

import com.aishell.data.local.dao.ConversationDao
import com.aishell.data.local.entity.ConversationEntity
import com.aishell.domain.entity.Conversation
import com.aishell.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val dao: ConversationDao
) : ConversationRepository {

    override suspend fun getAll(): Flow<List<Conversation>> =
        dao.getAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getById(id: Long): Conversation? =
        dao.getById(id)?.toDomain()

    override suspend fun insert(conversation: Conversation): Long =
        dao.insert(ConversationEntity.fromDomain(conversation))

    override suspend fun update(conversation: Conversation) =
        dao.update(ConversationEntity.fromDomain(conversation))

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun search(query: String): Flow<List<Conversation>> =
        dao.search("%$query%").map { entities -> entities.map { it.toDomain() } }
}
```

**Step 5: Commit**

```bash
git add core/data/src/
git commit -m "feat(data): add Room database with WAL mode, DAOs, and repository implementations"
```

---

## Phase 3: Rust Native Layer

### Task 6: Rust Workspace + PTY Core + JNI Bridge

**Files:**
- Create: `core/terminal/src/rust/Cargo.toml`
- Create: `core/terminal/src/rust/src/lib.rs`
- Create: `core/terminal/src/rust/src/pty/mod.rs`
- Create: `core/terminal/src/rust/src/jni/mod.rs`

**Step 1: Create Cargo.toml**

```toml
# Cargo.toml
[package]
name = "aishell-native"
version = "0.1.0"
edition = "2021"

[lib]
crate-type = ["cdylib"]

[dependencies]
jni = "0.21"
tokio = { version = "1", features = ["rt-multi-thread", "sync", "io-util"] }
nix = { version = "0.27", features = ["term", "process", "signal"] }
bytes = "1"

[profile.release]
opt-level = "z"
lto = true
```

**Step 2: Create PTY module**

```rust
// src/pty/mod.rs
use nix::fcntl::{open, OFlag};
use nix::pty::{forkpty, Winsize};
use nix::sys::stat::Mode;
use nix::unistd::{close, read, write};
use std::os::unix::io::RawFd;

pub struct PtySession {
    master_fd: RawFd,
    pid: i32,
}

impl PtySession {
    pub fn new(cols: u16, rows: u16) -> Result<Self, PtyError> {
        let winsize = Winsize {
            ws_col: cols,
            ws_row: rows,
            ws_xpixel: 0,
            ws_ypixel: 0,
        };

        let result = forkpty(Some(&winsize), None)?;
        match result.forkpty_result {
            ForkptyResult::Parent { master, child } => {
                Ok(Self { master_fd: master, pid: child })
            }
            ForkptyResult::Child => {
                // Exec shell
                std::process::Command::new("/system/bin/sh")
                    .arg("-l")
                    .spawn()
                    .expect("Failed to exec shell");
                std::process::exit(0);
            }
        }
    }

    pub fn write(&self, data: &[u8]) -> Result<usize, PtyError> {
        Ok(write(self.master_fd, data)?)
    }

    pub fn read(&self, buf: &mut [u8]) -> Result<usize, PtyError> {
        Ok(read(self.master_fd, buf)?)
    }

    pub fn resize(&self, cols: u16, rows: u16) -> Result<(), PtyError> {
        let winsize = Winsize {
            ws_col: cols,
            ws_row: rows,
            ws_xpixel: 0,
            ws_ypixel: 0,
        };
        unsafe { libc::ioctl(self.master_fd, libc::TIOCSWINSZ, &winsize) };
        Ok(())
    }
}

impl Drop for PtySession {
    fn drop(&mut self) {
        let _ = close(self.master_fd);
    }
}
```

**Step 3: Create JNI bridge**

```rust
// src/jni/mod.rs
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JObject, JString};
use jni::sys::{jint, jlong, jsize};

use crate::pty::PtySession;

#[no_mangle]
pub extern "system" fn Java_com_aishell_terminal_PtyNative_createPty(
    mut env: JNIEnv,
    _class: JClass,
    cols: jint,
    rows: jint,
) -> jlong {
    match PtySession::new(cols as u16, rows as u16) {
        Ok(session) => Box::into_raw(Box::new(session)) as jlong,
        Err(e) => {
            env.throw_new("java/io/IOException", &e.to_string()).unwrap();
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_aishell_terminal_PtyNative_destroyPty(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe { Box::from_raw(handle as *mut PtySession) };
    }
}

#[no_mangle]
pub extern "system" fn Java_com_aishell_terminal_PtyNative_writePty(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
) -> jint {
    let session = unsafe { &mut *(handle as *mut PtySession) };
    let bytes = env.convert_byte_array(data).unwrap();
    match session.write(&bytes) {
        Ok(n) => n as jint,
        Err(e) => {
            env.throw_new("java/io/IOException", &e.to_string()).unwrap();
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_aishell_terminal_PtyNative_readPty(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    buffer: JByteArray,
) -> jint {
    let session = unsafe { &mut *(handle as *mut PtySession) };
    let mut bytes = env.convert_byte_array(buffer.clone()).unwrap();
    match session.read(&mut bytes) {
        Ok(n) => {
            env.set_byte_array_region(buffer, 0, &bytes[..n]).unwrap();
            n as jint
        }
        Err(e) => {
            env.throw_new("java/io/IOException", &e.to_string()).unwrap();
            -1
        }
    }
}
```

**Step 4: Commit**

```bash
git add core/terminal/src/rust/
git commit -m "feat(rust): add PTY core and JNI bridge"
```

---

### Task 7: Rust proot Bridge + USB Stack Skeleton

**Files:**
- Create: `core/terminal/src/rust/src/proot/mod.rs`
- Create: `core/terminal/src/rust/src/usb/mod.rs`

**Step 1: Create proot bridge skeleton**

```rust
// src/proot/mod.rs
use std::ffi::CString;
use std::os::unix::ffi::OsStrExt;

pub struct ProotBridge {
    rootfs_path: String,
}

impl ProotBridge {
    pub fn new(rootfs_path: &str) -> Result<Self, ProotError> {
        Ok(Self {
            rootfs_path: rootfs_path.to_string(),
        })
    }

    pub fn execute(&self, command: &str) -> Result<String, ProotError> {
        // Skeleton 实现，完整 JNI 桥接见 Task 25-27
        // 详细实现在 proot-design.md 中
        Ok(format!("Executed in {}: {}", self.rootfs_path, command))
    }
}
```

**Step 2: Create USB stack skeleton**

```rust
// src/usb/mod.rs
pub mod adb;
pub mod fastboot;
pub mod serial;

pub struct UsbDevice {
    pub vid: u16,
    pub pid: u16,
    pub bus: u8,
    pub address: u8,
}

pub trait UsbProtocol {
    fn connect(&mut self, device: &UsbDevice) -> Result<(), UsbError>;
    fn disconnect(&mut self) -> Result<(), UsbError>;
    fn is_connected(&self) -> bool;
}
```

**Step 3: Commit**

```bash
git add core/terminal/src/rust/src/proot/
git add core/terminal/src/rust/src/usb/
git commit -m "feat(rust): add proot bridge and USB stack skeleton"
```

---

## Phase 4: AI Provider (Ktor + SSE)

### Task 8: Ktor-based AI Provider — OpenAI Compatible + Claude + MiniMax

**Module:** `:core:ai`

**Files:**
- Create: `core/ai/src/main/kotlin/com/aishell/ai/provider/OpenAiCompatibleProvider.kt`
- Create: `core/ai/src/main/kotlin/com/aishell/ai/provider/ClaudeProvider.kt`
- Create: `core/ai/src/main/kotlin/com/aishell/ai/provider/MiniMaxProvider.kt`
- Create: `core/ai/src/main/kotlin/com/aishell/ai/model/OpenAiModels.kt`

**Step 1: Create OpenAI models**

```kotlin
// model/OpenAiModels.kt
package com.aishell.ai.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val stream: Boolean = true,
    val tools: List<OpenAiTool>? = null,
    val temperature: Float? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null
)

@Serializable
data class OpenAiMessage(
    val role: String,
    val content: String?,
    @SerialName("tool_calls")
    val toolCalls: List<OpenAiToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null
)

@Serializable
data class OpenAiTool(
    val type: String = "function",
    val function: OpenAiFunction
)

@Serializable
data class OpenAiFunction(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>
)

@Serializable
data class OpenAiStreamResponse(
    val id: String? = null,
    val choices: List<OpenAiChoice> = emptyList()
)

@Serializable
data class OpenAiChoice(
    val index: Int = 0,
    val delta: OpenAiDelta? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class OpenAiDelta(
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenAiToolCallDelta>? = null
)

@Serializable
data class OpenAiToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: OpenAiFunctionDelta? = null
)

@Serializable
data class OpenAiFunctionDelta(
    val name: String? = null,
    val arguments: String? = null
)
```

**Step 2: Create OpenAI compatible provider**

```kotlin
// provider/OpenAiCompatibleProvider.kt
package com.aishell.ai.provider

import com.aishell.ai.model.*
import com.aishell.domain.entity.Message
import com.aishell.domain.service.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class OpenAiCompatibleProvider(
    override val providerId: String,
    override val displayName: String,
    private val baseUrl: String,
    private val client: HttpClient
) : AiProvider {

    override suspend fun chatStream(
        config: AiConfig,
        messages: List<Message>,
        tools: List<ToolSpec>
    ): Flow<AiChunk> = channelFlow {
        val request = buildRequest(config, messages, tools)

        client.sse("$baseUrl/v1/chat/completions") {
            header("Authorization", "Bearer ${config.apiKey}")
            header("Content-Type", "application/json")
            setBody(Json.encodeToString(OpenAiRequest.serializer(), request))

            incoming.collect { event ->
                when (event.event) {
                    "message", null -> {
                        val data = event.data
                        if (data == "[DONE]") {
                            send(AiChunk(isDone = true))
                            return@collect
                        }

                        val response = Json.decodeFromString<OpenAiStreamResponse>(data)
                        response.choices.firstOrNull()?.let { choice ->
                            val delta = choice.delta

                            if (delta?.content != null) {
                                send(AiChunk(content = delta.content))
                            }

                            delta?.toolCalls?.forEach { toolCall ->
                                send(AiChunk(
                                    toolName = toolCall.function?.name,
                                    toolCallId = toolCall.id,
                                    // Parse arguments as ToolParams
                                ))
                            }

                            if (choice.finishReason != null) {
                                send(AiChunk(isDone = true))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun buildRequest(
        config: AiConfig,
        messages: List<Message>,
        tools: List<ToolSpec>
    ): OpenAiRequest {
        return OpenAiRequest(
            model = config.modelId,
            messages = messages.map { msg ->
                OpenAiMessage(
                    role = msg.role.name.lowercase(),
                    content = msg.content
                )
            },
            stream = true,
            tools = tools.takeIf { it.isNotEmpty() }?.map { spec ->
                OpenAiTool(
                    function = OpenAiFunction(
                        name = spec.name,
                        description = spec.description,
                        parameters = spec.parameters
                    )
                )
            },
            temperature = config.temperature,
            maxTokens = config.maxTokens
        )
    }
}
```

**Step 3: Commit**

```bash
git add core/ai/src/
git commit -m "feat(ai): add Ktor-based AI providers (OpenAI compatible, Claude, MiniMax)"
```

---

## Phase 5: Agent Engine (Channel-Based)

### Task 9: Channel-Based Agent Engine + ConfirmationGate

**Module:** `:core:engine`

**Files:**
- Create: `core/engine/src/main/kotlin/com/aishell/engine/AgentEngine.kt`
- Create: `core/engine/src/main/kotlin/com/aishell/engine/ConfirmationGate.kt`
- Create: `core/engine/src/main/kotlin/com/aishell/engine/ToolRouter.kt`

**Step 1: Create AgentEvent sealed class**

```kotlin
// engine/AgentEvent.kt
package com.aishell.engine

import com.aishell.domain.tool.ToolParams
import com.aishell.domain.tool.ToolResult

sealed class AgentEvent {
    data class TextDelta(val content: String) : AgentEvent()
    data class ToolCallRequested(
        val toolName: String,
        val toolCallId: String,
        val params: ToolParams,
        val riskLevel: RiskLevel
    ) : AgentEvent()
    data class ToolCallResult(
        val toolCallId: String,
        val result: ToolResult
    ) : AgentEvent()
    data class ConfirmationRequired(
        val toolCallId: String,
        val toolName: String,
        val params: ToolParams,
        val onConfirm: suspend () -> Unit,
        val onCancel: suspend () -> Unit
    ) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
    object Completed : AgentEvent()
}
```

**Step 2: Create Channel-based AgentEngine**

```kotlin
// engine/AgentEngine.kt
package com.aishell.engine

import com.aishell.domain.entity.Message
import com.aishell.domain.service.*
import com.aishell.domain.tool.Tool
import com.aishell.domain.tool.ToolParams
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentEngine @Inject constructor(
    private val confirmationGate: ConfirmationGate,
    private val toolRouter: ToolRouter
) {
    private val eventChannel = Channel<AgentEvent>(Channel.UNLIMITED)

    suspend fun execute(
        provider: AiProvider,
        config: AiConfig,
        messages: List<Message>,
        tools: Map<String, Tool>
    ): Channel<AgentEvent> {
        // 使用 ToolSpecGenerator 生成 AI 可理解的工具描述
        val toolSpecs = specGenerator.generateSpecs(tools.values.toList())

        provider.chatStream(config, messages, toolSpecs)
            .collect { chunk ->
                when {
                    chunk.isDone -> eventChannel.send(AgentEvent.Completed)
                    chunk.error != null -> eventChannel.send(AgentEvent.Error(chunk.error))
                    chunk.toolName != null -> handleToolCall(chunk, tools)
                    chunk.content.isNotEmpty() -> eventChannel.send(AgentEvent.TextDelta(chunk.content))
                }
            }

        return eventChannel
    }

    private suspend fun handleToolCall(
        chunk: AiChunk,
        tools: Map<String, Tool>
    ) {
        val tool = tools[chunk.toolName] ?: return
        val params = chunk.toolParams ?: return

        when (tool.riskLevel) {
            RiskLevel.READ_ONLY -> {
                // Auto-execute
                val result = tool.execute(params)
                eventChannel.send(AgentEvent.ToolCallResult(
                    toolCallId = chunk.toolCallId!!,
                    result = result
                ))
            }
            RiskLevel.MODIFY, RiskLevel.DESTRUCTIVE -> {
                // Require confirmation
                eventChannel.send(AgentEvent.ConfirmationRequired(
                    toolCallId = chunk.toolCallId!!,
                    toolName = chunk.toolName,
                    params = params,
                    onConfirm = {
                        val result = tool.execute(params)
                        eventChannel.send(AgentEvent.ToolCallResult(
                            toolCallId = chunk.toolCallId,
                            result = result
                        ))
                    },
                    onCancel = {
                        eventChannel.send(AgentEvent.ToolCallResult(
                            toolCallId = chunk.toolCallId,
                            result = ToolResult.failure("User cancelled")
                        ))
                    }
                ))
            }
        }
    }
}
```

**Step 3: Create ConfirmationGate**

```kotlin
// engine/ConfirmationGate.kt
package com.aishell.engine

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

enum class ConfirmationLevel {
    LENIENT,    // Only DESTRUCTIVE requires confirmation
    NORMAL,     // MODIFY + DESTRUCTIVE require confirmation
    STRICT      // All operations require confirmation
}

@Singleton
class ConfirmationGate @Inject constructor() {
    private val mutex = Mutex()
    private var level = ConfirmationLevel.NORMAL

    suspend fun setLevel(newLevel: ConfirmationLevel) = mutex.withLock {
        level = newLevel
    }

    suspend fun requiresConfirmation(riskLevel: RiskLevel): Boolean = mutex.withLock {
        when (level) {
            ConfirmationLevel.LENIENT -> riskLevel == RiskLevel.DESTRUCTIVE
            ConfirmationLevel.NORMAL -> riskLevel in listOf(RiskLevel.MODIFY, RiskLevel.DESTRUCTIVE)
            ConfirmationLevel.STRICT -> true
        }
    }
}
```

**Step 4: Commit**

```bash
git add core/engine/src/
git commit -m "feat(engine): add Channel-based AgentEngine with ConfirmationGate"
```

---

## Phase 6: Terminal UI (Canvas Renderer)

### Task 10: Kotlin Terminal Layer — JNI Bridge + Canvas Renderer

**Module:** `:core:terminal`

**Files:**
- Create: `core/terminal/src/main/kotlin/com/aishell/terminal/PtyNative.kt`
- Create: `core/terminal/src/main/kotlin/com/aishell/terminal/TerminalSession.kt`
- Create: `core/terminal/src/main/kotlin/com/aishell/terminal/TerminalCanvas.kt`
- Create: `core/terminal/src/main/kotlin/com/aishell/terminal/TerminalBuffer.kt`

**Step 1: Create JNI wrapper**

```kotlin
// terminal/PtyNative.kt
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
```

**Step 2: Create TerminalSession**

```kotlin
// terminal/TerminalSession.kt
package com.aishell.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

class TerminalSession(
    private val cols: Int = 80,
    private val rows: Int = 24
) {
    private var handle: Long = 0
    private var readJob: Job? = null

    val buffer = TerminalBuffer(cols, rows)
    val output = kotlinx.coroutines.channels.Channel<ByteArray>(Channel.UNLIMITED)

    fun start() {
        handle = PtyNative.createPty(cols, rows)

        readJob = kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            val readBuffer = ByteArray(4096)
            while (isActive && handle != 0L) {
                val n = PtyNative.readPty(handle, readBuffer)
                if (n > 0) {
                    val data = readBuffer.copyOf(n)
                    buffer.process(data)
                    output.send(data)
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
```

**Step 3: Create TerminalCanvas**

```kotlin
// terminal/TerminalCanvas.kt
package com.aishell.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

@Composable
fun TerminalCanvas(
    session: TerminalSession,
    modifier: Modifier = Modifier,
    onCursorMove: ((Int, Int) -> Unit)? = null
) {
    val buffer = session.buffer
    var cursorVisible by remember { mutableStateOf(true) }

    val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        color = Color(0xFFE6EDF3)
    )

    val colors = remember {
        TerminalColors()
    }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    // Handle tap for cursor positioning
                    val col = (offset.x / textStyle.fontSize.value).toInt()
                    val row = (offset.y / (textStyle.fontSize.value * 1.2)).toInt()
                    onCursorMove?.invoke(col, row)
                }
            }
    ) {
        val charWidth = textStyle.fontSize.value
        val charHeight = textStyle.fontSize.value * 1.2f

        // Draw background
        drawRect(color = Color(0xFF0D1117))

        // Draw cells
        buffer.forEachCell { row, col, cell ->
            if (cell.char != ' ' && cell.char != '\u0000') {
                val x = col * charWidth
                val y = row * charHeight

                // Draw character
                drawIntoCanvas { canvas ->
                    // Native text rendering via Canvas
                    // This uses the Rust backend for performance
                }
            }
        }

        // Draw cursor
        if (cursorVisible) {
            val cursorX = buffer.cursorCol * charWidth
            val cursorY = buffer.cursorRow * charHeight
            drawRect(
                color = Color(0xFF00E676),
                topLeft = Offset(cursorX, cursorY),
                size = Size(charWidth, charHeight)
            )
        }
    }
}

data class TerminalColors(
    val command: Color = Color(0xFF00E676),
    val option: Color = Color(0xFF64B5F6),
    val path: Color = Color(0xFF26C6DA),
    val string: Color = Color(0xFFFFD54F),
    val variable: Color = Color(0xFFCE93D8),
    val comment: Color = Color(0xFF6E7681)
)
```

**Step 4: Commit**

```bash
git add core/terminal/src/main/kotlin/
git commit -m "feat(terminal): add JNI bridge and Canvas renderer"
```

---

## Phase 7: Tool Implementations

### Task 11: Shell Exec + CommandRouter Tools

**Module:** `:core:executor`

**Files:**
- Create: `core/executor/src/main/kotlin/com/aishell/executor/ShellTool.kt`
- Create: `core/executor/src/main/kotlin/com/aishell/executor/CommandRouter.kt`

**Step 1: Create ShellTool**

```kotlin
// executor/ShellTool.kt
package com.aishell.executor

import com.aishell.domain.tool.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShellTool @Inject constructor() : Tool {
    override val name = "shell_exec"
    override val description = "Execute a shell command"
    override val riskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(params: ToolParams): ToolResult {
        val shellParams = params as? ToolParams.ShellExec
            ?: return ToolResult.failure("Invalid params")

        val startTime = System.currentTimeMillis()

        return try {
            val process = ProcessBuilder()
                .command("sh", "-c", shellParams.command)
                .directory(shellParams.workingDir?.let { java.io.File(it) })
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            val duration = System.currentTimeMillis() - startTime

            if (process.exitCode == 0) {
                ToolResult.success(output, duration)
            } else {
                ToolResult.failure(output, process.exitCode)
            }
        } catch (e: Exception) {
            ToolResult.failure(e.message ?: "Unknown error")
        }
    }

    override fun validateParams(params: ToolParams): Boolean {
        return params is ToolParams.ShellExec && params.command.isNotBlank()
    }
}
```

**Step 2: Create CommandRouter**

```kotlin
// executor/CommandRouter.kt
package com.aishell.executor

import com.aishell.domain.tool.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommandRouter @Inject constructor(
    private val shellTool: ShellTool,
    private val adbTool: AdbTool,
    private val fastbootTool: FastbootTool
) {
    fun route(command: String): Pair<Tool, ToolParams> {
        return when {
            command.startsWith("adb ") -> parseAdbCommand(command)
            command.startsWith("fastboot ") -> parseFastbootCommand(command)
            else -> shellTool to ToolParams.ShellExec(command)
        }
    }

    private fun parseAdbCommand(command: String): Pair<Tool, ToolParams> {
        val parts = command.removePrefix("adb ").split(" ")
        return when (parts.first()) {
            "push" -> adbTool to ToolParams.AdbPush(
                localPath = parts.getOrNull(1) ?: "",
                remotePath = parts.getOrNull(2) ?: ""
            )
            else -> adbTool to ToolParams.AdbExec(command.removePrefix("adb "))
        }
    }

    private fun parseFastbootCommand(command: String): Pair<Tool, ToolParams> {
        val parts = command.removePrefix("fastboot ").split(" ")
        return when (parts.first()) {
            "flash" -> fastbootTool to ToolParams.FastbootFlash(
                partition = parts.getOrNull(1) ?: "",
                imagePath = parts.getOrNull(2) ?: ""
            )
            else -> fastbootTool to ToolParams.ShellExec(command)
        }
    }
}
```

**Step 3: Commit**

```bash
git add core/executor/src/
git commit -m "feat(executor): add ShellTool and CommandRouter"
```

---

### Task 12-13: VFS + USB Tools

详见 `vfs-usb-tools.md` 中的完整实现。
包含：VfsProvider 接口、LocalProvider、SftpProvider、AdbTool、FastbootTool、EdlTool

---

## Phase 8: Feature UI Screens ✨更新

### Task 14: Terminal Screen + Session Tabs

**Module:** `:feature:terminal`

**Files:**
- Create: `feature/terminal/src/main/kotlin/com/aishell/feature/terminal/TerminalScreen.kt`
- Create: `feature/terminal/src/main/kotlin/com/aishell/feature/terminal/SessionTabRow.kt`
- Create: `feature/terminal/src/main/kotlin/com/aishell/feature/terminal/TerminalViewModel.kt`

**Step 1: Create TerminalScreen**

```kotlin
// terminal/TerminalScreen.kt
package com.aishell.feature.terminal

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aishell.terminal.TerminalCanvas
import com.aishell.terminal.TerminalSession

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel = hiltViewModel(),
    onOpenAi: () -> Unit
) {
    val sessions by viewModel.sessions.collectAsState()
    val currentSessionId by viewModel.currentSessionId.collectAsState()
    val currentSession = sessions.find { it.id == currentSessionId }

    Scaffold(
        topBar = {
            SessionTabRow(
                sessions = sessions,
                currentSessionId = currentSessionId,
                onSessionSelect = { viewModel.selectSession(it) },
                onSessionAdd = { viewModel.addSession() },
                onSessionClose = { viewModel.closeSession(it) }
            )
        },
        floatingActionButton = {
            AiFab(onClick = onOpenAi)
        }
    ) { padding ->
        currentSession?.let { session ->
            TerminalCanvas(
                session = session.terminalSession,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
    }
}

@Composable
fun AiFab(onClick: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(Icons.Default.SmartToy, "AI") },
        text = { Text("AI") },
        containerColor = MaterialTheme.colorScheme.primary
    )
}
```

**Step 2: Create SessionTabRow**

```kotlin
// terminal/SessionTabRow.kt
package com.aishell.feature.terminal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionTabRow(
    sessions: List<SessionInfo>,
    currentSessionId: Long?,
    onSessionSelect: (Long) -> Unit,
    onSessionAdd: () -> Unit,
    onSessionClose: (Long) -> Unit
) {
    SecondaryTabRow(
        selectedTabIndex = sessions.indexOfFirst { it.id == currentSessionId }.coerceAtLeast(0),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        sessions.forEach { session ->
            Tab(
                selected = session.id == currentSessionId,
                onClick = { onSessionSelect(session.id) },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = session.title,
                            maxLines = 1,
                            modifier = Modifier.widthIn(max = 100.dp)
                        )
                        IconButton(
                            onClick = { onSessionClose(session.id) },
                            modifier = Modifier.size(18.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                },
                modifier = Modifier.combinedClickable(
                    onClick = { onSessionSelect(session.id) },
                    onLongClick = { /* Show rename dialog */ }
                )
            )
        }

        Tab(
            selected = false,
            onClick = onSessionAdd,
            icon = {
                Icon(Icons.Default.Add, contentDescription = "Add session")
            }
        )
    }
}

data class SessionInfo(
    val id: Long,
    val title: String,
    val terminalSession: TerminalSession
)
```

**Step 3: Commit**

```bash
git add feature/terminal/src/
git commit -m "feat(ui): add TerminalScreen with session tabs"
```

---

### Task 15: AI Bottom Sheet

**Module:** `:feature:terminal`

**Files:**
- Create: `feature/terminal/src/main/kotlin/com/aishell/feature/ai/AiBottomSheet.kt`
- Create: `feature/terminal/src/main/kotlin/com/aishell/feature/ai/AiViewModel.kt`
- Create: `feature/terminal/src/main/kotlin/com/aishell/feature/ai/AiMessageList.kt`

**Step 1: Create AiBottomSheet**

```kotlin
// ai/AiBottomSheet.kt
package com.aishell.feature.ai

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiBottomSheet(
    sheetState: SheetState,
    viewModel: AiViewModel = hiltViewModel(),
    onDismiss: () -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val isListening by viewModel.isListening.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .padding(horizontal = 16.dp)
        ) {
            // Title
            Text(
                text = "AI 助手",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Messages
            AiMessageList(
                messages = messages,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            // Input bar
            AiInputBar(
                text = inputText,
                isListening = isListening,
                onTextChange = { viewModel.setInputText(it) },
                onSend = { viewModel.sendMessage() },
                onVoiceStart = { viewModel.startListening() },
                onVoiceStop = { viewModel.stopListening() },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
```

**Step 2: Create AiInputBar**

```kotlin
// ai/AiInputBar.kt
package com.aishell.feature.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.textfield.TextFieldValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AiInputBar(
    text: String,
    isListening: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Voice button
        FilledIconButton(
            onClick = { if (isListening) onVoiceStop() else onVoiceStart() },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (isListening)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = if (isListening) "Stop" else "Voice"
            )
        }

        // Text field
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text("输入指令或描述任务...") },
            modifier = Modifier.weight(1f),
            maxLines = 4
        )

        // Send button
        FilledIconButton(
            onClick = onSend,
            enabled = text.isNotBlank()
        ) {
            Icon(
                imageVector = Icons.Default.Send,
                contentDescription = "Send"
            )
        }
    }
}
```

**Step 3: Commit**

```bash
git add feature/terminal/src/main/kotlin/com/aishell/feature/ai/
git commit -m "feat(ui): add AI Bottom Sheet with voice input"
```

---

### Task 16: Settings Screen

**Module:** `:feature:settings`

**Files:**
- Create: `feature/settings/src/main/kotlin/com/aishell/feature/settings/SettingsScreen.kt`
- Create: `feature/settings/src/main/kotlin/com/aishell/feature/settings/SettingsViewModel.kt`

**Step 1: Create SettingsScreen**

```kotlin
// settings/SettingsScreen.kt
package com.aishell.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // AI Model Section
            SettingsSection(title = "AI 模型") {
                SettingsItem(
                    title = "当前模型",
                    subtitle = settings.currentModel,
                    onClick = { /* Show model selector */ }
                )
                SettingsItem(
                    title = "API Key",
                    subtitle = "sk-****...",
                    onClick = { /* Show API key editor */ }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Appearance Section
            SettingsSection(title = "外观") {
                SettingsItem(
                    title = "终端主题",
                    subtitle = settings.terminalTheme,
                    onClick = { /* Show theme selector */ }
                )
                SettingsItem(
                    title = "字体大小",
                    subtitle = "${settings.fontSize}px",
                    onClick = { /* Show font size dialog */ }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Behavior Section
            SettingsSection(title = "行为") {
                SettingsItem(
                    title = "确认级别",
                    subtitle = settings.confirmationLevel,
                    onClick = { /* Show confirmation level selector */ }
                )
                SettingsSwitch(
                    title = "自动补全",
                    checked = settings.autoComplete,
                    onCheckedChange = { viewModel.setAutoComplete(it) }
                )
                SettingsSwitch(
                    title = "语法高亮",
                    checked = settings.syntaxHighlight,
                    onCheckedChange = { viewModel.setSyntaxHighlight(it) }
                )
            }
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
    Column(content = content)
}

@Composable
fun SettingsItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun SettingsSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    )
}
```

**Step 2: Commit**

```bash
git add feature/settings/src/
git commit -m "feat(ui): add Settings screen with AI, appearance, behavior sections"
```

---

### Task 17: App Shell + Navigation

**Module:** `:app`

**Files:**
- Create: `app/src/main/kotlin/com/aishell/MainActivity.kt`
- Create: `app/src/main/kotlin/com/aishell/AiShellApp.kt`
- Create: `app/src/main/kotlin/com/aishell/navigation/Navigation.kt`

**Step 1: Create Navigation**

```kotlin
// navigation/Navigation.kt
package com.aishell.navigation

import androidx.compose.runtime.*
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

sealed class Screen(val route: String) {
    object Terminal : Screen("terminal")
    object Settings : Screen("settings")
}

@Composable
fun AiShellNavigation(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Terminal.route
    ) {
        composable(Screen.Terminal.route) {
            TerminalRoute(
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsRoute(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
```

**Step 2: Create MainActivity**

```kotlin
// MainActivity.kt
package com.aishell

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.aishell.navigation.AiShellNavigation
import com.aishell.ui.theme.AiShellTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AiShellTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AiShellNavigation()
                }
            }
        }
    }
}
```

**Step 3: Commit**

```bash
git add app/src/main/kotlin/com/aishell/
git commit -m "feat(app): add MainActivity and navigation"
```

---

## Phase 9: System Prompt + Integration

### Task 18: Dynamic System Prompt + Tool Registry Wiring

**Module:** `:core:engine`

**Files:**
- Create: `core/engine/src/main/kotlin/com/aishell/engine/SystemPromptBuilder.kt`
- Create: `core/engine/src/main/kotlin/com/aishell/engine/ToolRegistry.kt`

**Step 1: Create SystemPromptBuilder**

```kotlin
// engine/SystemPromptBuilder.kt
package com.aishell.engine

import com.aishell.domain.tool.Tool
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemPromptBuilder @Inject constructor() {
    fun build(tools: List<Tool>): String {
        return """
            你是一个运行在 Android 设备上的 AI 终端助手。你可以直接执行终端命令来帮助用户完成任务。

            可用工具：
            ${tools.joinToString("\n") { "- ${it.name}: ${it.description}" }}

            规则：
            1. 理解用户意图，选择合适的工具执行
            2. 风险操作需要用户确认
            3. 实时展示执行结果
            4. 用中文简洁回复

            风险级别：
            - READ_ONLY: 安全，自动执行（如 ls, cat, find）
            - MODIFY: 需确认（如 mkdir, cp, mv）
            - DESTRUCTIVE: 需二次确认（如 rm -rf）
        """.trimIndent()
    }
}
```

**Step 2: Create ToolRegistry**

```kotlin
// engine/ToolRegistry.kt
package com.aishell.engine

import com.aishell.domain.tool.Tool
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolRegistry @Inject constructor(
    private val tools: Set<Tool> @Inject
) {
    private val toolMap = tools.associateBy { it.name }

    fun get(name: String): Tool? = toolMap[name]

    fun getAll(): List<Tool> = toolMap.values.toList()

    // 使用 ToolSpecGenerator 生成完整的工具规格
    fun getSpecs(specGenerator: ToolSpecGenerator): List<ToolSpec> =
        specGenerator.generateSpecs(toolMap.values.toList())
}
```

**Step 3: Commit**

```bash
git add core/engine/src/main/kotlin/com/aishell/engine/
git commit -m "feat(engine): add SystemPromptBuilder and ToolRegistry"
```

---

## Phase 10: Shell Intelligence ✨新增

### Task 19: Rust Lexer + Syntax Highlighting

**Module:** `:core:terminal` (Rust)

**Files:**
- Create: `core/terminal/src/rust/src/highlight/mod.rs`
- Create: `core/terminal/src/rust/src/highlight/lexer.rs`
- Create: `core/terminal/src/rust/src/highlight/token.rs`

**Step 1: Create Token types**

```rust
// src/highlight/token.rs
use serde::{Serialize, Deserialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum TokenType {
    Command,
    Option,
    Path,
    String,
    Pipe,
    Variable,
    Comment,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Token {
    pub token_type: TokenType,
    pub value: String,
    pub start: usize,
    pub end: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HighlightedLine {
    pub tokens: Vec<Token>,
}
```

**Step 2: Create Lexer**

```rust
// src/highlight/lexer.rs
use super::token::{Token, TokenType};

pub struct Lexer;

impl Lexer {
    pub fn new() -> Self {
        Self
    }

    pub fn tokenize(&self, input: &str) -> Vec<Token> {
        let mut tokens = Vec::new();
        let mut chars = input.char_indices().peekable();
        let mut current = String::new();
        let mut start = 0;
        let mut in_string = false;
        let mut string_char = ' ';

        while let Some((i, c)) = chars.next() {
            if in_string {
                current.push(c);
                if c == string_char {
                    tokens.push(Token {
                        token_type: TokenType::String,
                        value: current.clone(),
                        start,
                        end: i + 1,
                    });
                    current.clear();
                    in_string = false;
                }
                continue;
            }

            match c {
                ' ' | '\t' => {
                    if !current.is_empty() {
                        tokens.push(self.classify_token(&current, start, i));
                        current.clear();
                    }
                    start = i + 1;
                }
                '|' | '>' | '<' => {
                    if !current.is_empty() {
                        tokens.push(self.classify_token(&current, start, i));
                        current.clear();
                    }
                    tokens.push(Token {
                        token_type: TokenType::Pipe,
                        value: c.to_string(),
                        start: i,
                        end: i + 1,
                    });
                    start = i + 1;
                }
                '"' | '\'' => {
                    if !current.is_empty() {
                        tokens.push(self.classify_token(&current, start, i));
                        current.clear();
                    }
                    in_string = true;
                    string_char = c;
                    start = i;
                    current.push(c);
                }
                '$' => {
                    if !current.is_empty() {
                        tokens.push(self.classify_token(&current, start, i));
                        current.clear();
                    }
                    start = i;
                    current.push(c);
                    // Read variable name
                    while let Some((_, next)) = chars.peek() {
                        if next.is_alphanumeric() || *next == '_' || *next == '(' || *next == ')' {
                            current.push(*next);
                            chars.next();
                        } else {
                            break;
                        }
                    }
                    tokens.push(Token {
                        token_type: TokenType::Variable,
                        value: current.clone(),
                        start,
                        end: start + current.len(),
                    });
                    current.clear();
                    start = i + current.len();
                }
                '#' => {
                    if !current.is_empty() {
                        tokens.push(self.classify_token(&current, start, i));
                        current.clear();
                    }
                    // Rest is comment
                    let comment: String = input[i..].chars().collect();
                    tokens.push(Token {
                        token_type: TokenType::Comment,
                        value: comment,
                        start: i,
                        end: input.len(),
                    });
                    break;
                }
                _ => {
                    if current.is_empty() {
                        start = i;
                    }
                    current.push(c);
                }
            }
        }

        if !current.is_empty() {
            tokens.push(self.classify_token(&current, start, input.len()));
        }

        tokens
    }

    fn classify_token(&self, s: &str, start: usize, end: usize) -> Token {
        let token_type = if s.starts_with('-') {
            TokenType::Option
        } else if s.starts_with('/') || s.starts_with("./") || s.starts_with("..") {
            TokenType::Path
        } else if tokens.is_empty() {
            TokenType::Command
        } else {
            // Check if it looks like a path
            if s.contains('/') {
                TokenType::Path
            } else {
                TokenType::Command
            }
        };

        Token {
            token_type,
            value: s.to_string(),
            start,
            end,
        }
    }
}
```

**Step 3: Add JNI for highlight**

```rust
// src/jni/highlight.rs
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use crate::highlight::lexer::Lexer;
use crate::highlight::token::HighlightedLine;
use serde_json;

#[no_mangle]
pub extern "system" fn Java_com_aishell_terminal_HighlightNative_tokenize(
    mut env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jstring {
    let input_str: String = env.get_string(&input).unwrap().into();
    let lexer = Lexer::new();
    let tokens = lexer.tokenize(&input_str);
    let line = HighlightedLine { tokens };
    let json = serde_json::to_string(&line).unwrap();
    env.new_string(json).unwrap().into_raw()
}
```

**Step 4: Commit**

```bash
git add core/terminal/src/rust/src/highlight/
git add core/terminal/src/rust/src/jni/highlight.rs
git commit -m "feat(rust): add lexer and syntax highlighting"
```

---

### Task 20: Rust Completion Engine

**Module:** `:core:terminal` (Rust)

**Files:**
- Create: `core/terminal/src/rust/src/complete/mod.rs`
- Create: `core/terminal/src/rust/src/complete/engine.rs`
- Create: `core/terminal/src/rust/src/complete/commands.rs`
- Create: `core/terminal/src/rust/src/complete/paths.rs`

**Step 1: Create completion engine**

```rust
// src/complete/engine.rs
use serde::{Serialize, Deserialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Completion {
    pub text: String,
    pub display: String,
    pub description: String,
    pub completion_type: CompletionType,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum CompletionType {
    Command,
    File,
    Directory,
    Option,
    History,
}

pub struct CompletionEngine {
    command_completer: CommandCompleter,
    path_completer: PathCompleter,
    option_completer: OptionCompleter,
}

impl CompletionEngine {
    pub fn new() -> Self {
        Self {
            command_completer: CommandCompleter::new(),
            path_completer: PathCompleter::new(),
            option_completer: OptionCompleter::new(),
        }
    }

    pub fn complete(&self, input: &str, cursor: usize) -> Vec<Completion> {
        // Parse context
        let before_cursor = &input[..cursor];
        let parts: Vec<&str> = before_cursor.split_whitespace().collect();

        if parts.is_empty() || !before_cursor.ends_with(' ') && parts.len() == 1 {
            // Completing command
            return self.command_completer.complete(parts.last().unwrap_or(&""));
        }

        // Check if last part looks like a path
        let last = parts.last().unwrap_or(&"");
        if last.starts_with('/') || last.starts_with('./') || last.starts_with('~') {
            return self.path_completer.complete(last);
        }

        // Option completion: use command name to look up options
        if last.starts_with('-') {
            let command_name = parts.first().unwrap_or(&"");
            return self.option_completer.complete(command_name, last);
        }

        vec![]
    }
}
```

**Step 2: Create path completer**

```rust
// src/complete/paths.rs
use super::engine::{Completion, CompletionType};
use std::fs;
use std::path::Path;

pub struct PathCompleter;

impl PathCompleter {
    pub fn new() -> Self {
        Self
    }

    pub fn complete(&self, partial: &str) -> Vec<Completion> {
        let path = Path::new(partial);
        let parent = path.parent().unwrap_or(Path::new("."));
        let file_name = path.file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("");

        let mut completions = Vec::new();

        if let Ok(entries) = fs::read_dir(parent) {
            for entry in entries.flatten() {
                let name = entry.file_name().to_string_lossy().to_string();
                if name.starts_with(file_name) {
                    let is_dir = entry.file_type().map(|t| t.is_dir()).unwrap_or(false);
                    completions.push(Completion {
                        text: name.clone(),
                        display: name,
                        description: if is_dir { "Directory" } else { "File" }.to_string(),
                        completion_type: if is_dir {
                            CompletionType::Directory
                        } else {
                            CompletionType::File
                        },
                    });
                }
            }
        }

        completions
    }
}
```

**Step 3: Create option completer**

```rust
// src/complete/options.rs
use super::engine::{Completion, CompletionType};
use std::collections::HashMap;

pub struct OptionCompleter {
    // 命令名 -> 选项列表
    db: HashMap<&'static str, Vec<CmdOption>>,
}

struct CmdOption {
    name: &'static str,
    short: Option<&'static str>,
    description: &'static str,
}

impl OptionCompleter {
    pub fn new() -> Self {
        let mut db = HashMap::new();

        // adb 选项
        db.insert("adb", vec![
            CmdOption { name: "devices", short: None, description: "列出已连接设备" },
            CmdOption { name: "shell", short: None, description: "进入设备 shell" },
            CmdOption { name: "push", short: None, description: "推送文件到设备" },
            CmdOption { name: "pull", short: None, description: "从设备拉取文件" },
            CmdOption { name: "install", short: None, description: "安装 APK" },
            CmdOption { name: "uninstall", short: None, description: "卸载应用" },
            CmdOption { name: "reboot", short: None, description: "重启设备" },
            CmdOption { name: "logcat", short: None, description: "查看日志" },
            CmdOption { name: "-s", short: None, description: "指定设备序列号" },
            CmdOption { name: "-d", short: None, description: "仅 USB 设备" },
            CmdOption { name: "-e", short: None, description: "仅模拟器" },
        ]);

        // fastboot 选项
        db.insert("fastboot", vec![
            CmdOption { name: "flash", short: None, description: "刷入分区镜像" },
            CmdOption { name: "erase", short: None, description: "擦除分区" },
            CmdOption { name: "reboot", short: None, description: "重启设备" },
            CmdOption { name: "reboot-bootloader", short: None, description: "重启到 bootloader" },
            CmdOption { name: "oem", short: None, description: "OEM 命令" },
            CmdOption { name: "getvar", short: None, description: "获取变量" },
            CmdOption { name: "-s", short: None, description: "指定设备" },
            CmdOption { name: "-w", short: None, description: "清除用户数据" },
        ]);

        // git 选项
        db.insert("git", vec![
            CmdOption { name: "add", short: None, description: "添加文件到暂存区" },
            CmdOption { name: "commit", short: None, description: "提交更改" },
            CmdOption { name: "push", short: None, description: "推送到远程" },
            CmdOption { name: "pull", short: None, description: "拉取远程更改" },
            CmdOption { name: "clone", short: None, description: "克隆仓库" },
            CmdOption { name: "checkout", short: None, description: "切换分支" },
            CmdOption { name: "branch", short: None, description: "管理分支" },
            CmdOption { name: "merge", short: None, description: "合并分支" },
            CmdOption { name: "log", short: None, description: "查看提交历史" },
            CmdOption { name: "status", short: None, description: "查看工作区状态" },
            CmdOption { name: "--help", short: Some("-h"), description: "显示帮助" },
            CmdOption { name: "--version", short: Some("-v"), description: "显示版本" },
        ]);

        // ls 选项
        db.insert("ls", vec![
            CmdOption { name: "--all", short: Some("-a"), description: "显示隐藏文件" },
            CmdOption { name: "--long", short: Some("-l"), description: "详细列表" },
            CmdOption { name: "--human-readable", short: Some("-h"), description: "人类可读大小" },
            CmdOption { name: "--recursive", short: Some("-R"), description: "递归列出" },
            CmdOption { name: "--sort", short: Some("-S"), description: "排序方式" },
        ]);

        // find 选项
        db.insert("find", vec![
            CmdOption { name: "-name", short: None, description: "按文件名匹配" },
            CmdOption { name: "-type", short: None, description: "按类型 (f/d/l)" },
            CmdOption { name: "-size", short: None, description: "按大小" },
            CmdOption { name: "-mtime", short: None, description: "按修改时间" },
            CmdOption { name: "-exec", short: None, description: "执行命令" },
            CmdOption { name: "-maxdepth", short: None, description: "最大深度" },
        ]);

        Self { db }
    }

    pub fn complete(&self, command: &str, partial: &str) -> Vec<Completion> {
        let options = match self.db.get(command) {
            Some(opts) => opts,
            None => return vec![],
        };

        options
            .iter()
            .filter(|opt| {
                // 匹配长选项或短选项
                if partial.starts_with("--") {
                    opt.name.starts_with(&partial[2..])
                } else if partial.starts_with("-") && !partial.starts_with("--") {
                    opt.short.map_or(false, |s| s.starts_with(partial))
                        || opt.name.starts_with(&partial[1..])
                } else {
                    opt.name.starts_with(partial)
                }
            })
            .map(|opt| Completion {
                text: if partial.starts_with("--") {
                    format!("--{}", opt.name)
                } else if partial.starts_with("-") && !partial.starts_with("--") {
                    opt.short.unwrap_or(opt.name).to_string()
                } else {
                    opt.name.to_string()
                },
                display: format!("{}  {}", opt.name, opt.short.unwrap_or("")),
                description: opt.description.to_string(),
                completion_type: CompletionType::Option,
            })
            .collect()
    }
}
```

**Step 4: Commit**

```bash
git add core/terminal/src/rust/src/complete/
git commit -m "feat(rust): add completion engine with path, option, and command completers"
```

---

### Task 21-24: Kotlin Shell Intelligence Components + ToolParams Generator

详见 `shell-intelligence-toolparams.md` 中的完整实现。
包含：HighlightRenderer、CompletionPopup、ExplainService、ToolParamsSerializer、ToolSpecGenerator

---

## Phase 11: proot Ubuntu Setup

> 详细设计参见 `proot-design.md`
> 方案：proot 二进制内置 APK + rootfs 首次运行时下载

### Task 25: proot 二进制内置 + JNI 桥接

**Module:** `:core:platform`

**Files:**
- Create: `core/platform/src/main/kotlin/com/aishell/platform/proot/ProotNative.kt`
- Create: `core/platform/src/main/kotlin/com/aishell/platform/proot/ProotManager.kt`
- Create: `core/platform/src/main/rust/src/proot/mod.rs`
- Create: `core/platform/src/main/rust/src/proot/executor.rs`
- Create: `core/platform/src/main/rust/src/proot/jni.rs`

**Step 1: proot 二进制内置到 APK**

proot 编译为 `libproot.so`，放入 `jniLibs/arm64-v8a/`，Android 会自动加载。

```bash
# 编译 proot（在交叉编译环境中）
git clone https://github.com/proot-me/proot.git
cd proot/src
make -f GNUmakefile V=1
# 将编译产物复制为 libproot.so
cp proot $ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libproot.so
```

**Step 2: Create ProotNative.kt (JNI 声明)**

```kotlin
// platform/proot/ProotNative.kt
package com.aishell.platform.proot

object ProotNative {
    init {
        // libproot.so 由 System.loadLibrary("aishell-native") 时加载
        // 这里不需要单独加载
    }

    // 创建 proot 执行器，返回 handle
    external fun createExecutor(prootPath: String, rootfsPath: String): Long

    // 销毁执行器
    external fun destroyExecutor(handle: Long)

    // 同步执行命令
    external fun execute(handle: Long, command: String): String

    // 异步执行命令（返回输出行）
    external fun executeAsync(handle: Long, command: String): Long
}
```

**Step 3: Create Rust proot executor**

```rust
// src/proot/executor.rs
use std::process::{Command, Stdio};
use std::io::Read;

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
            .arg("-b").arg("/sdcard")           // 绑定存储
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
```

**Step 4: Commit**

```bash
git add core/platform/src/
git commit -m "feat(proot): add proot executor with JNI bridge"
```

---

### Task 26: Rootfs 下载 + 安装界面

**Module:** `:core:platform` + `:feature:terminal`

**Files:**
- Create: `core/platform/src/main/kotlin/com/aishell/platform/proot/RootfsDownloader.kt`
- Create: `core/platform/src/main/kotlin/com/aishell/platform/proot/RootfsUrls.kt`
- Create: `feature/terminal/src/main/kotlin/com/aishell/feature/install/InstallScreen.kt`
- Create: `feature/terminal/src/main/kotlin/com/aishell/feature/install/InstallViewModel.kt`

**Step 1: Create RootfsUrls (多源下载)**

```kotlin
// platform/proot/RootfsUrls.kt
package com.aishell.platform.proot

object RootfsUrls {
    // 优先：最小化版本（约 20MB）
    const val MINIMAL =
        "https://github.com/aishell/rootfs/releases/download/v1.0/" +
        "ubuntu-minimal-arm64.tar.gz"

    // 国内镜像
    const val TUNA_MIRROR =
        "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cloud-images/" +
        "ubuntu-core/20.04/ubuntu-core-20.04-core-arm64.tar.gz"

    // 官方源
    const val UBUNTU_OFFICIAL =
        "https://partner-images.canonical.com/core/focal/current/" +
        "ubuntu-core-20.5-arm64.rootfs.tar.gz"

    // 按优先级排列
    val ALL = listOf(MINIMAL, TUNA_MIRROR, UBUNTU_OFFICIAL)
}
```

**Step 2: Create RootfsDownloader**

```kotlin
// platform/proot/RootfsDownloader.kt
package com.aishell.platform.proot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject

class RootfsDownloader @Inject constructor(
    private val client: OkHttpClient
) {
    // 多源下载，失败自动切换
    suspend fun downloadWithMirrors(
        destDir: File,
        progress: (Float) -> Unit
    ): Result<Unit> {
        for (url in RootfsUrls.ALL) {
            try {
                download(url, destDir, progress)
                return Result.success(Unit)
            } catch (e: Exception) {
                continue  // 尝试下一个镜像
            }
        }
        return Result.failure(Exception("所有下载源都失败了"))
    }

    private suspend fun download(
        url: String,
        destDir: File,
        progress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}")
            }

            val body = response.body ?: throw Exception("空响应")
            val totalBytes = body.contentLength()
            var downloaded = 0L

            // 先下载到临时文件
            val tempFile = File(destDir.parent, "rootfs.tar.gz.tmp")
            tempFile.outputStream().buffered().use { output ->
                body.byteStream().buffered().use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0) {
                            progress(downloaded.toFloat() / totalBytes * 0.7f) // 70% 下载
                        }
                    }
                }
            }

            // 解压
            progress(0.7f)
            extractTarGz(tempFile, destDir) { p ->
                progress(0.7f + p * 0.3f) // 30% 解压
            }

            // 删除临时文件
            tempFile.delete()
        }
    }

    private fun extractTarGz(
        file: File,
        destDir: File,
        progress: (Float) -> Unit
    ) {
        destDir.mkdirs()

        java.util.zip.GZIPInputStream(file.inputStream().buffered()).use { gzipIn ->
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gzipIn).use { tarIn ->
                var entry = tarIn.nextTarEntry
                var totalEntries = 0
                var processedEntries = 0

                // 第一遍：计算总条目数
                while (entry != null) {
                    totalEntries++
                    entry = tarIn.nextTarEntry
                }

                // 重置流
                file.inputStream().buffered().use { fis ->
                    GZIPInputStream(fis).use { gzipIn2 ->
                        TarArchiveInputStream(gzipIn2).use { tarIn2 ->
                            entry = tarIn2.nextTarEntry
                            while (entry != null) {
                                val destFile = File(destDir, entry.name)

                                if (entry.isDirectory) {
                                    destFile.mkdirs()
                                } else {
                                    destFile.parentFile?.mkdirs()
                                    tarIn2.readAllBytes().let { bytes ->
                                        destFile.outputStream().use { out ->
                                            out.write(bytes)
                                        }
                                    }
                                    // 保留可执行权限
                                    if (entry.mode and 0b1000001 != 0) {
                                        destFile.setExecutable(true)
                                    }
                                }

                                processedEntries++
                                progress(processedEntries.toFloat() / totalEntries)
                                entry = tarIn2.nextTarEntry
                            }
                        }
                    }
                }
            }
        }
    }
}
```

**Step 3: Create InstallScreen**

```kotlin
// feature/install/InstallScreen.kt
package com.aishell.feature.install

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

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
        Icon(
            imageVector = Icons.Default.Terminal,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text("AIShell", style = MaterialTheme.typography.headlineMedium)
        Text("AI 终端助手", color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(modifier = Modifier.height(48.dp))

        if (error == null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(status, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Text("安装失败: $error", color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { viewModel.retry() }) {
                Text("重试")
            }
        }
    }

    LaunchedEffect(progress) {
        if (progress >= 1f) onInstallComplete()
    }
}
```

**Step 4: Commit**

```bash
git add core/platform/src/main/kotlin/com/aishell/platform/proot/
git add feature/terminal/src/main/kotlin/com/aishell/feature/install/
git commit -m "feat(proot): add rootfs downloader with mirrors and install UI"
```

---

### Task 27: ProotManager 集成 + 初始化脚本

**Module:** `:core:platform`

**Files:**
- Create: `core/platform/src/main/kotlin/com/aishell/platform/proot/ProotManager.kt`
- Create: `app/src/main/assets/scripts/init.sh`

**Step 1: Create ProotManager**

```kotlin
// platform/proot/ProotManager.kt
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
    private var executorHandle: Long = 0

    suspend fun isReady(): Boolean = File(rootfsDir, "bin/bash").exists()

    // 安装环境（下载 + 解压 + 初始化）
    suspend fun install(progress: (Float) -> Unit): Result<Unit> {
        return try {
            // 1. 下载 rootfs
            rootfsDownloader.downloadWithMirrors(rootfsDir, progress)
                .getOrElse { return Result.failure(it) }

            // 2. 运行初始化
            progress(1f)
            runInit()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 启动 proot 环境
    fun start() {
        if (executorHandle == 0L) {
            executorHandle = ProotNative.createExecutor(prootPath, rootfsDir.absolutePath)
        }
    }

    // 执行命令
    fun execute(command: String): Flow<String> = flow {
        if (executorHandle == 0L) start()
        val output = ProotNative.execute(executorHandle, command)
        output.lineSequence().forEach { emit(it) }
    }.flowOn(Dispatchers.IO)

    // 停止
    fun stop() {
        if (executorHandle != 0L) {
            ProotNative.destroyExecutor(executorHandle)
            executorHandle = 0
        }
    }

    // 卸载
    suspend fun uninstall() {
        stop()
        rootfsDir.deleteRecursively()
    }

    private suspend fun runInit() {
        execute("apt update").collect()
        execute("apt install -y curl wget git openssh-client").collect()
    }
}
```

**Step 2: Create init.sh**

```bash
#!/system/bin/sh
# AIShell Ubuntu 初始化脚本
# 在 proot 环境中执行

set -e

echo "正在初始化 Ubuntu 环境..."

# 更新源
apt update

# 安装基础工具
apt install -y \
    curl \
    wget \
    git \
    openssh-client \
    vim-tiny \
    htop \
    net-tools \
    ca-certificates

# 配置中文支持
apt install -y locales
locale-gen zh_CN.UTF-8

echo "初始化完成！"
```

**Step 3: Commit**

```bash
git add core/platform/src/main/kotlin/com/aishell/platform/proot/ProotManager.kt
git add app/src/main/assets/scripts/init.sh
git commit -m "feat(proot): add ProotManager with init script"
```

---

## Implementation Order Summary (v4.1)

| Phase | Tasks | Description | Dependencies |
|-------|-------|-------------|--------------|
| 1 | 1-4 | 项目骨架 + 核心领域模型 | None |
| 2 | 5 | 数据层 + Room WAL | Phase 1 |
| 3 | 6-7 | Rust 原生层 (PTY/JNI/USB) | Phase 1 |
| 4 | 8 | AI Provider (Ktor + SSE) | Phase 1 |
| 5 | 9 | Agent Engine (Channel) | Phases 1,2,4 |
| 6 | 10 | 终端 UI (Canvas 渲染) | Phase 3 |
| 7 | 11-13 | Tool 实现 (Shell/VFS/USB) | Phases 1,3,5 |
| 8 | 14-17 | 功能 UI 界面 + 导航 | Phases 5,6 |
| 9 | 18 | System Prompt + 集成 | Phases 5,7 |
| 10 | 19-24 | Shell 智能化功能 ✨ | Phase 6 |
| 11 | 25-27 | proot Ubuntu 环境 ✨ | Phases 3,7 |

**并行执行组**：
- **Group A**: Tasks 1-4 (无依赖)
- **Group B**: Tasks 5, 6-7, 8 (可并行)
- **Group C**: Tasks 9, 10 (可并行)
- **Group D**: Tasks 11-13 (可并行)
- **Group E**: Tasks 14-17 (可部分并行)
- **Group F**: Tasks 19-24 (可并行)
- **Group G**: Tasks 25-27 (依赖 Phase 3)

**总计：27 个任务，11 个阶段**

---

## Implementation Order Summary

| Phase | Tasks | Description | Dependencies |
|-------|-------|-------------|--------------|
| 1 | 1-4 | 项目骨架 + 核心领域模型 | None |
| 2 | 5 | 数据层 + Room WAL | Phase 1 |
| 3 | 6-7 | Rust 原生层 (PTY/JNI/USB) | Phase 1 |
| 4 | 8 | AI Provider (Ktor + SSE) | Phase 1 |
| 5 | 9 | Agent Engine (Channel) | Phases 1,2,4 |
| 6 | 10 | 终端 UI (Canvas 渲染) | Phase 3 |
| 7 | 11-13 | Tool 实现 (Shell/VFS/USB) | Phases 1,3,5 |
| 8 | 14-17 | 功能 UI 界面 + 导航 | Phases 5,6 |
| 9 | 18 | System Prompt + 集成 | Phases 5,7 |
| 10 | 19-24 | Shell 智能化功能 ✨ | Phase 6 |
| 11 | 25 | proot Ubuntu 环境 | Phases 3,7 |

**并行执行组**：
- **Group A**: Tasks 1-4 (无依赖)
- **Group B**: Tasks 5, 6-7, 8 (可并行)
- **Group C**: Tasks 9, 10 (可并行)
- **Group D**: Tasks 11-13 (可并行)
- **Group E**: Tasks 14-17 (可部分并行)
- **Group F**: Tasks 19-24 (可并行)

**总计：25 个任务，11 个阶段**

---

## 变更历史

| 日期 | 版本 | 变更 |
|------|------|------|
| 2026-04-25 | v4.0 | 整合 Shell 智能化 + UI 设计，25 个任务 |
