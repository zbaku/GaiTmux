# AIShell - Android AI Agent Terminal Design

## Product Overview

AIShell 是一个 Android 上的 AI 终端助手。用户用自然语言描述任务，AI 直接转化为终端命令执行，结果实时流式展示。核心定位：**AI 驱动的终端命令执行器**，而非通用聊天应用。

### Core Flow

```
User: "把 Download 里的图片整理到一个文件夹"
    ↓
AI 直接执行（流式输出）:
  $ find /sdcard/Download -name "*.jpg" | wc -l
  23
  
  $ mkdir -p /sdcard/Download/JPG_整理
  
  ⚠️ 需要确认: mv 23个jpg到 JPG_整理/
  [确认] [取消]
    ↓
确认后:
  $ mv /sdcard/Download/*.jpg /sdcard/Download/JPG_整理/
  完成! 23个文件已移动
```

### Key Design Decisions

1. **AI 处理终端命令为核心** — 不是通用聊天，AI 的主要工作是生成和执行终端命令
2. **仿终端体验** — 黑色背景、绿色命令、实时滚动输出，像真正的终端
3. **流式执行** — AI 生成命令后立即执行，输出实时流式展示
4. **自动链接** — 多步任务自动串联，仅风险操作需确认
5. **预置多家 AI 厂商** — OpenAI 兼容接口（DeepSeek/智谱/通义等）、MiniMax、Kimi、Claude
6. **插件化 + MCP** — 外部 APK 插件 + MCP 工具服务器扩展
7. **会话持久化** — 所有对话自动保存，可随时恢复

### Confirmation Levels

| Risk Level | Behavior | Examples |
|---|---|---|
| READ_ONLY | 自动执行 | ls, cat, find, grep |
| MODIFY | 一次确认 | mkdir, cp, mv, touch |
| DESTRUCTIVE | 确认+二次确认 | rm, rm -rf, format |

Settings 可调：宽松（仅 DESTRUCTIVE）、正常（MODIFY+DESTRUCTIVE）、严格（全部）

---

## AI Provider 预置厂商

### 厂商列表

| 厂商 | Provider ID | Base URL | 兼容协议 | 默认模型 |
|---|---|---|---|---|
| OpenAI | `openai` | `https://api.openai.com` | OpenAI | gpt-4o |
| DeepSeek | `deepseek` | `https://api.deepseek.com` | OpenAI 兼容 | deepseek-chat |
| 智谱 AI | `zhipu` | `https://open.bigmodel.cn/api/paas` | OpenAI 兼容 | glm-4 |
| 通义千问 | `qwen` | `https://dashscope.aliyuncs.com/compatible-mode` | OpenAI 兼容 | qwen-max |
| MiniMax | `minimax` | `https://api.minimax.chat` | 自有协议 | abab6.5s-chat |
| Kimi (Moonshot) | `kimi` | `https://api.moonshot.cn` | OpenAI 兼容 | moonshot-v1-8k |
| Claude | `claude` | `https://api.anthropic.com` | Anthropic | claude-sonnet-4-20250514 |
| Ollama (本地) | `ollama` | `http://localhost:11434` | Ollama | llama3 |

### Provider 架构

```
┌─────────────────────────────────────────┐
│           AiProvider (interface)        │
│           chatStream(): Flow<AiChunk>   │
├─────────────────────────────────────────┤
│  传输层: Ktor Client + SSE (~400KB)     │
│  原生协程 SSE，替代 OkHttp+Retrofit     │
│                                         │
│  ┌───────────────────────────────────┐  │
│  │  OpenAiCompatibleProvider         │  │
│  │  (OpenAI/DeepSeek/智谱/通义/Kimi)  │  │
│  │  统一 SSE 格式，仅 baseUrl 不同    │  │
│  └───────────────────────────────────┘  │
│                                         │
│  ┌─────────────┐  ┌────────────────┐   │
│  │ MiniMax     │  │ Claude         │   │
│  │ (自有协议)   │  │ (Anthropic API)│   │
│  └─────────────┘  └────────────────┘   │
│                                         │
│  ┌─────────────┐                       │
│  │ Ollama      │                       │
│  │ (本地)      │                       │
│  └─────────────┘                       │
└─────────────────────────────────────────┘
```

### OpenAI Compatible Provider

DeepSeek、智谱、通义、Kimi 都兼容 OpenAI API 格式，只需切换 `baseUrl` 和 `apiKey`：

```kotlin
data class ProviderConfig(
    val id: String,           // "deepseek", "zhipu", etc.
    val displayName: String,  // "DeepSeek"
    val baseUrl: String,      // "https://api.deepseek.com"
    val defaultModel: String, // "deepseek-chat"
    val apiKeyHint: String,   // "sk-..."
    val protocol: Protocol    // OPENAI_COMPATIBLE, MINIMAX, ANTHROPIC, OLLAMA
)

enum class Protocol { OPENAI_COMPATIBLE, MINIMAX, ANTHROPIC, OLLAMA }

// 预置配置
val PRESET_PROVIDERS = listOf(
    ProviderConfig("openai", "OpenAI", "https://api.openai.com", "gpt-4o", "sk-...", OPENAI_COMPATIBLE),
    ProviderConfig("deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-chat", "sk-...", OPENAI_COMPATIBLE),
    ProviderConfig("zhipu", "智谱 AI", "https://open.bigmodel.cn/api/paas", "glm-4", "...", OPENAI_COMPATIBLE),
    ProviderConfig("qwen", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode", "qwen-max", "sk-...", OPENAI_COMPATIBLE),
    ProviderConfig("minimax", "MiniMax", "https://api.minimax.chat", "abab6.5s-chat", "...", MINIMAX),
    ProviderConfig("kimi", "Kimi", "https://api.moonshot.cn", "moonshot-v1-8k", "sk-...", OPENAI_COMPATIBLE),
    ProviderConfig("claude", "Claude", "https://api.anthropic.com", "claude-sonnet-4-20250514", "sk-ant-...", ANTHROPIC),
    ProviderConfig("ollama", "Ollama", "http://localhost:11434", "llama3", "", OLLAMA),
)

class OpenAiCompatibleProvider(
    private val client: HttpClient,
    private val config: ProviderConfig,
    private val apiKey: String,
    private val model: String = config.defaultModel
) : AiProvider {
    override val name = config.id

    override fun chatStream(messages: List<Message>, tools: List<ToolDefinition>): Flow<AiChunk> = channelFlow {
        client.preparePost("${config.baseUrl}/v1/chat/completions") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(buildRequestBody(messages, tools))
        }.execute { response ->
            val channel: ByteReadChannel = response.body()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: continue
                if (line.startsWith("data: ")) {
                    val json = line.removePrefix("data: ")
                    if (json == "[DONE]") { trySend(AiChunk.Done); break }
                    val chunk = parseSseChunk(json)
                    chunk?.let { trySend(it) }
                }
            }
        }
    }
}
```

### Claude Provider (Anthropic API)

```kotlin
class ClaudeProvider(
    private val client: HttpClient,
    private val apiKey: String,
    private val model: String = "claude-sonnet-4-20250514"
) : AiProvider {
    override val name = "claude"

    override fun chatStream(messages: List<Message>, tools: List<ToolDefinition>): Flow<AiChunk> = channelFlow {
        client.preparePost("https://api.anthropic.com/v1/messages") {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(buildClaudeRequestBody(messages, tools))
        }.execute { response ->
            val channel: ByteReadChannel = response.body()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: continue
                if (line.startsWith("event: ")) {
                    val eventType = line.removePrefix("event: ")
                    val dataLine = channel.readUTF8Line()?.removePrefix("data: ") ?: continue
                    when (eventType) {
                        "content_block_delta" -> {
                            val delta = parseClaudeDelta(dataLine)
                            delta?.let { trySend(it) }
                        }
                        "message_stop" -> { trySend(AiChunk.Done) }
                    }
                }
            }
        }
    }
}
```

### MiniMax Provider

```kotlin
class MiniMaxProvider(
    private val client: HttpClient,
    private val apiKey: String,
    private val groupId: String,
    private val model: String = "abab6.5s-chat"
) : AiProvider {
    override val name = "minimax"

    override fun chatStream(messages: List<Message>, tools: List<ToolDefinition>): Flow<AiChunk> = channelFlow {
        client.preparePost("https://api.minimax.chat/v1/text/chatcompletion_v2?GroupId=$groupId") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(buildMiniMaxRequestBody(messages, tools, stream = true))
        }.execute { response ->
            val channel: ByteReadChannel = response.body()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: continue
                if (line.startsWith("data: ")) {
                    val json = line.removePrefix("data: ")
                    val chunk = parseMiniMaxChunk(json)
                    chunk?.let { trySend(it) }
                }
            }
            trySend(AiChunk.Done)
        }
    }
}
```

---

## Architecture

**纯 Android 架构 — 无后端服务器。** AI 云端 API = "后端"，proot Ubuntu = 本地服务。仅 arm64-v8a。

```
┌──────────────────────────────────────────────────────────┐
│                   AIShell (Android)                      │
│                                                          │
│  ┌──────────┐  ┌──────────┐  ┌───────────────────────┐  │
│  │ Compose  │  │  Agent   │  │  Rust Native          │  │
│  │   UI     │  │  Engine  │  │  ├─PTY Manager        │  │
│  │          │  │          │  │  ├─Terminal Parser     │  │
│  │          │  │          │  │  ├─proot Bridge        │  │
│  │          │  │          │  │  └─libusb Stack        │  │
│  └──────────┘  └──────────┘  └───────────────────────┘  │
│       │             │               │                    │
│  ┌────▼─────────────▼───────────────▼──────────────────┐ │
│  │           Kotlin Coroutines + Flow                  │ │
│  │       (统一并发原语，全链路流式)                      │ │
│  └────────────────────┬───────────────────────────────┘ │
│                       │                                  │
│  ┌────────────────────▼───────────────────────────────┐ │
│  │          Room (WAL) + DataStore + EncryptedFile     │ │
│  └────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────┘
          │                              │
          ▼                              ▼
  ┌───────────────┐          ┌───────────────────┐
  │  AI Cloud API │          │  proot Ubuntu     │
  │  (Ktor SSE)   │          │  (本地 Linux 环境) │
  └───────────────┘          └───────────────────┘
```

### Core Modules

- **Agent Engine (v3)**: Channel-based 并发模型。Per-conversation Channel<AgentEvent>，背压控制，ToolParamParser 强类型参数解析。
- **Tool Registry**: 统一注册内置工具(31+)、插件工具、MCP 连接器。所有实现 `Tool` 接口，参数使用 sealed class `ToolParams`。
- **AI Provider Layer**: Ktor Client + SSE 流式。OpenAI 兼容/Anthropic/Ollama 统一 `Flow<AiChunk>` 接口。
- **Rust Native**: PTY 管理、ANSI Parser、proot Bridge、libusb Stack — 性能关键路径全 Rust 实现。
- **Plugin System**: 外部 APK 通过 ContentProvider 注册工具。
- **MCP Client**: 连接 MCP 服务器，发现工具，包装为 `Tool` 实现。

---

## System Prompt (核心)

AI 的 system prompt 强调终端命令执行能力，根据执行环境和设备状态动态调整：

```
你是 AIShell，一个 Android ARM64 终端上的 AI 助手。你的核心能力是通过执行终端命令帮助用户完成任务。

可用执行环境:
1. Ubuntu (proot) — 完整 Linux 环境: apt/python3/git/node/curl/wget/ssh
2. Android Shell — 系统命令: pm/am/input/settings/dumpsys/logcat
3. Shizuku (需授权) — 高级权限: 文件完全访问/应用管理/输入模拟/设置修改

可用设备操作:
- ADB 远程设备管理 (adb connect/shell/push/pull/install)
- USB 直连设备 (UsbManager 标准授权)
- Recovery 模式 (adb sideload/日志)
- Fastboot (flash/boot/unlock/erase)
- EDL 紧急下载 (Qualcomm 9008 救砖)

可用文件系统:
- 本地: /sdcard, /data, /home (Ubuntu)
- SFTP: sftp://user@host/path
- SMB: smb://server/share/path
- WebDAV: webdav://host/path

规则:
1. 优先使用 shell_exec 执行命令
2. 每次只执行一条命令，等待结果后再决定下一步
3. 危险操作（rm、格式化、刷机等）必须先告知风险再执行
4. 命令执行失败时，分析错误原因并尝试修正
5. 操作远程设备使用 adb 工具
6. 刷机操作使用 fastboot/edl 工具，需用户确认
7. 当前系统: Android (aarch64), proot Ubuntu 可用

当前工作目录: {cwd}
Shizuku 状态: {shizuku_status}
已连接设备: {connected_devices}
已挂载存储: {mounted_storages}
```

---

## Tool System

### Tool Interface

v3 使用 sealed class `ToolParams` 替代 `Map<String, Any>`，实现强类型参数。AI 返回的 JSON 由 `ToolParamParser` 解析为对应的 ToolParams 子类。

```kotlin
interface Tool {
    val name: String
    val description: String
    val riskLevel: RiskLevel
    val descriptor: ToolDescriptor  // JSON Schema for AI function calling

    suspend fun execute(params: ToolParams): Flow<ToolEvent>
}

data class ToolDescriptor(
    val name: String,
    val description: String,
    val parameters: String  // JSON Schema
)

sealed class ToolParams {
    // 31+ 子类 — 详见 architecture.md ToolParams 完整定义

    /** 核心：执行 shell 命令 */
    data class ShellExec(
        val command: String,
        val workDir: String? = null,
        val timeout: Int = 30,
        val environment: Map<String, String> = emptyMap()
    ) : ToolParams()

    // 文件操作: FileList, FileRead, FileWrite, FileMove, FileDelete, FileStat, FileSearch, FileChecksum
    // ADB: AdbConnect, AdbCommand, AdbPush, AdbPull, AdbInstall
    // USB: UsbList, UsbAction, UsbTransfer
    // 进程: ProcessList, ProcessSignal, BackgroundTask
    // tmux: TmuxAction
    // 守护: DaemonAction
    // 系统集成: ClipboardAction, AppLaunch, ShareAction, NotifyAction, Screenshot, InputAction, PackageAction
    // 网络: NetAction
    // 刷机: RecoverySideload, RecoveryLog, FastbootFlash, FastbootBoot, FastbootUnlock, FastbootInfo, FastbootErase, FastbootSetSlot, EdlFlash, EdlBackup, EdlGpt, DeviceModeDetect, DeviceModeSwitch
    // 备份: BackupAction
    // 设置: SettingsAction

    /** 外部工具 (Plugin/MCP) 使用 Generic */
    data class Generic(val map: Map<String, Any>) : ToolParams()
}

sealed class ToolEvent {
    data class Stdout(val line: String) : ToolEvent()
    data class Stderr(val line: String) : ToolEvent()
    data class Exit(val code: Int) : ToolEvent()
    data class Progress(val message: String) : ToolEvent()
    data class Output(val content: String) : ToolEvent()
    data class Error(val message: String) : ToolEvent()
}

enum class RiskLevel { READ_ONLY, MODIFY, DESTRUCTIVE }
```

### Built-in Tools (v3 完整列表)

| Tool | ToolParams | Risk Level | 说明 |
|---|---|---|---|
| `shell_exec` | `ShellExec` | 动态(看命令) | **核心工具**，AI 优先使用 |
| `file_list` | `FileList` | READ_ONLY | 列出目录 |
| `file_read` | `FileRead` | READ_ONLY | 读取文件 |
| `file_write` | `FileWrite` | MODIFY | 写入文件 |
| `file_move` | `FileMove` | MODIFY | 移动/重命名 |
| `file_delete` | `FileDelete` | DESTRUCTIVE | 删除文件 |
| `file_stat` | `FileStat` | READ_ONLY | 文件信息 |
| `file_search` | `FileSearch` | READ_ONLY | 搜索文件 |
| `file_checksum` | `FileChecksum` | READ_ONLY | 文件校验 |
| `adb_connect` | `AdbConnect` | READ_ONLY | ADB 连接/断开 |
| `adb_command` | `AdbCommand` | 动态 | ADB 命令 |
| `adb_push` | `AdbPush` | MODIFY | 推送文件 |
| `adb_pull` | `AdbPull` | READ_ONLY | 拉取文件 |
| `adb_install` | `AdbInstall` | MODIFY | 安装 APK |
| `usb_list` | `UsbList` | READ_ONLY | 枚举 USB 设备 |
| `usb_action` | `UsbAction` | MODIFY | USB 打开/关闭/claim |
| `usb_transfer` | `UsbTransfer` | MODIFY | USB 数据传输 |
| `process_list` | `ProcessList` | READ_ONLY | 进程列表 |
| `process_signal` | `ProcessSignal` | DESTRUCTIVE | 发送信号 |
| `background_task` | `BackgroundTask` | MODIFY | 后台任务管理 |
| `tmux` | `TmuxAction` | 动态 | 终端多窗口会话 |
| `daemon` | `DaemonAction` | MODIFY | 守护进程管理 |
| `clipboard` | `ClipboardAction` | MODIFY | 剪贴板读写 |
| `app_launch` | `AppLaunch` | READ_ONLY | 启动应用 |
| `share` | `ShareAction` | READ_ONLY | 系统分享 |
| `notify` | `NotifyAction` | MODIFY | 发送通知 |
| `screenshot` | `Screenshot` | READ_ONLY | 截屏 |
| `input` | `InputAction` | MODIFY | 输入模拟 |
| `package` | `PackageAction` | 动态 | 包管理 |
| `net` | `NetAction` | 动态 | 网络操作 |
| `backup` | `BackupAction` | DESTRUCTIVE | 备份/恢复 |
| `settings` | `SettingsAction` | MODIFY | 系统设置 |
| `recovery_sideload` | `RecoverySideload` | DESTRUCTIVE | Recovery 刷入 |
| `fastboot_flash` | `FastbootFlash` | DESTRUCTIVE | Fastboot 刷写分区 |
| `fastboot_boot` | `FastbootBoot` | MODIFY | Fastboot 临时启动 |
| `fastboot_unlock` | `FastbootUnlock` | DESTRUCTIVE | 解锁 Bootloader |
| `edl_flash` | `EdlFlash` | DESTRUCTIVE | EDL 刷机 |
| `edl_backup` | `EdlBackup` | READ_ONLY | EDL 分区备份 |
| `device_mode` | `DeviceModeDetect` | READ_ONLY | 检测设备模式 |
| `device_switch` | `DeviceModeSwitch` | MODIFY | 切换设备模式 |
| `ble_scan` | `BleScan` | READ_ONLY | BLE 设备扫描 |
| `ble_connect` | `BleConnect` | MODIFY | BLE GATT 连接 |
| `ble_read` | `BleRead` | READ_ONLY | BLE 特征读取 |
| `ble_write` | `BleWrite` | MODIFY | BLE 特征写入 |
| `ble_notify` | `BleNotify` | MODIFY | BLE 通知订阅 |
| `classic_scan` | `ClassicScan` | READ_ONLY | Classic 蓝牙发现 |
| `spp_connect` | `SppConnect` | MODIFY | SPP 串口连接 |
| `spp_send` | `SppSend` | MODIFY | SPP 发送数据 |
| `spp_receive` | `SppReceive` | READ_ONLY | SPP 接收数据 |

### shell_exec 详细设计

v3: 命令通过 `CommandRouter` 路由到三层执行环境（Android shell / proot Ubuntu / Shizuku），参数使用 `ToolParams.ShellExec` 强类型。

```kotlin
class ShellExecTool(
    private val commandRouter: CommandRouter
) : Tool {
    override val name = "shell_exec"
    override val description = "Execute a shell command and stream output in real-time"
    override val riskLevel = RiskLevel.MODIFY  // 默认，实际按命令动态判断
    override val descriptor = ToolDescriptor(
        name = "shell_exec",
        description = "Execute a shell command and stream output in real-time",
        parameters = """{"type":"object","properties":{"command":{"type":"string"},"work_dir":{"type":"string"},"timeout":{"type":"integer"},"env":{"type":"object"}},"required":["command"]}"""
    )

    override suspend fun execute(params: ToolParams): Flow<ToolEvent> {
        val p = params as ToolParams.ShellExec
        val executor = commandRouter.resolveExecutor(p.command)
        return executor.execute(p.command, p.workDir, p.timeout, p.environment)
    }
}
```

### 命令风险分析

AI 调用 `shell_exec` 时，自动分析命令风险等级：

```kotlin
fun analyzeCommandRisk(command: String): RiskLevel {
    val trimmed = command.trim().lowercase()
    return when {
        // DESTRUCTIVE
        trimmed.matches(Regex(".*\\brm\\s+(-r|-rf|-fr).*")) -> RiskLevel.DESTRUCTIVE
        trimmed.matches(Regex(".*\\brm\\s+-.*\\s+/.*")) -> RiskLevel.DESTRUCTIVE
        trimmed.contains("format") -> RiskLevel.DESTRUCTIVE
        trimmed.contains("dd if=") -> RiskLevel.DESTRUCTIVE

        // MODIFY
        trimmed.matches(Regex(".*\\b(mv|cp|mkdir|touch|chmod|chown|pip install|apt install).*")) -> RiskLevel.MODIFY
        trimmed.matches(Regex(".*\\brm\\s+.*")) -> RiskLevel.MODIFY  // rm without -rf

        // READ_ONLY (default)
        else -> RiskLevel.READ_ONLY
    }
}
```

### Plugin Architecture

**Plugin APK 结构：**

```
plugin-app.apk
├── AndroidManifest.xml
│   └── <meta-data android:name="aishell_plugin"
│         android:value="com.example.plugin.AisShellPluginProvider"/>
├── ContentProvider: query() → 工具描述, call() → 执行工具
└── Tool implementations
```

**发现流程：**

```
1. 扫描已安装应用的 <meta-data name="aishell_plugin">
2. 绑定 ContentProvider
3. query() → 获取工具名、描述、schema、风险级别
4. 注册为 PluginTool
5. AI 调用时 → call() 到 ContentProvider 执行
```

### MCP Integration

```kotlin
class McpTool(
    private val connector: McpConnector,
    override val descriptor: ToolDescriptor
) : Tool {
    override val name = descriptor.name
    override val description = descriptor.description
    override val riskLevel = RiskLevel.MODIFY

    override suspend fun execute(params: ToolParams): Flow<ToolEvent> = channelFlow {
        val genericParams = (params as? ToolParams.Generic)?.map ?: params.toGenericMap()
        val result = connector.callTool(name, genericParams)
        send(ToolEvent.Output(result.content))
    }
}
```

MCP 配置（Settings）：

```json
{
  "mcp_servers": [
    {"name": "filesystem", "transport": "stdio", "command": "mcp-server-filesystem", "args": ["/sdcard"]},
    {"name": "github", "transport": "http", "url": "http://localhost:3001"}
  ]
}
```

---

## AI Provider Layer

### Streaming Interface

```kotlin
interface AiProvider {
    val name: String

    fun chatStream(
        messages: List<Message>,
        tools: List<ToolDefinition>
    ): Flow<AiChunk>
}

sealed class AiChunk {
    data class TextDelta(val text: String) : AiChunk()
    data class ToolCallStart(val id: String, val name: String) : AiChunk()
    data class ToolCallDelta(val id: String, val argumentsDelta: String) : AiChunk()
    data class ToolCallEnd(val id: String) : AiChunk()
    data object Done : AiChunk()
    data class Error(val message: String) : AiChunk()
}
```

---

## Agent Engine v3 — Channel-Based 并发

v3 优化：每个 conversationId 拥有独立的 `Channel<AgentEvent>`，实现背压控制和并发隔离。工具参数使用 `ToolParamParser` 强类型解析。

```kotlin
class AgentEngine(
    private val provider: AiProvider,
    private val toolRegistry: ToolRegistry,
    private val confirmationGate: ConfirmationGate,
    private val paramParser: ToolParamParser
) {
    private val channels = ConcurrentHashMap<Long, Channel<AgentEvent>>()

    fun processLoop(conversationId: Long, userInput: String): Channel<AgentEvent> {
        val channel = Channel<AgentEvent>(Channel.BUFFERED)
        channels[conversationId] = channel

        CoroutineScope(Dispatchers.Default).launch {
            channel.send(AgentEvent.UserMessage(userInput))

            var continueLoop = true
            while (continueLoop) {
                continueLoop = false
                var pendingToolCalls = mutableListOf<PendingToolCall>()

                provider.chatStream(getHistory(conversationId), toolRegistry.getDefinitions())
                    .collect { chunk ->
                        when (chunk) {
                            is AiChunk.TextDelta -> channel.trySend(AgentEvent.StreamingText(chunk.text))
                            is AiChunk.ToolCallStart -> {
                                pendingToolCalls.add(PendingToolCall(chunk.id, chunk.name))
                            }
                            is AiChunk.ToolCallDelta -> {
                                pendingToolCalls.find { it.id == chunk.id }?.appendArgs(chunk.argumentsDelta)
                            }
                            is AiChunk.ToolCallEnd -> {
                                val call = pendingToolCalls.find { it.id == chunk.id }!!

                                // 强类型参数解析
                                val params = paramParser.parse(call.name, call.argsJson)
                                    ?: ToolParams.Generic(call.args)

                                val tool = toolRegistry.get(call.name)!!

                                // shell_exec 特殊处理：分析命令风险
                                val effectiveRisk = if (params is ToolParams.ShellExec) {
                                    analyzeCommandRisk(params.command)
                                } else tool.riskLevel

                                if (effectiveRisk != RiskLevel.READ_ONLY) {
                                    val confirmed = confirmationGate.requireConfirm(
                                        conversationId, call.name, effectiveRisk, params
                                    )
                                    if (!confirmed) {
                                        channel.trySend(AgentEvent.ToolCancelled(call.name))
                                        return@collect
                                    }
                                }

                                channel.trySend(AgentEvent.ToolExecuting(call.name))
                                tool.execute(params).collect { event ->
                                    channel.trySend(when (event) {
                                        is ToolEvent.Stdout -> AgentEvent.ToolOutput(call.name, event.line)
                                        is ToolEvent.Stderr -> AgentEvent.ToolError(call.name, event.line)
                                        is ToolEvent.Exit -> AgentEvent.ToolExit(call.name, event.code)
                                        is ToolEvent.Progress -> AgentEvent.ToolProgress(call.name, event.message)
                                        is ToolEvent.Output -> AgentEvent.ToolResult(call.name, event.content)
                                        is ToolEvent.Error -> AgentEvent.ToolError(call.name, event.message)
                                    })
                                }

                                continueLoop = true
                            }
                            else -> {}
                        }
                    }
            }

            channel.close()
            channels.remove(conversationId)
        }

        return channel
    }
}
```

### ConfirmationGate

Per-conversationId 的 `CompletableDeferred`，支持多会话并发：

```kotlin
class ConfirmationGate {
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    private val _pendingConfirmation = ConcurrentHashMap<Long, ConfirmationRequest>()
    val pendingConfirmations: Map<Long, ConfirmationRequest> get() = _pendingConfirmation

    suspend fun requireConfirm(
        conversationId: Long,
        toolName: String,
        riskLevel: RiskLevel,
        params: ToolParams
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pending[conversationId] = deferred
        _pendingConfirmation[conversationId] = ConfirmationRequest(toolName, riskLevel, params)
        return deferred.await()
    }

    fun confirm(conversationId: Long) {
        pending[conversationId]?.complete(true)
        pending.remove(conversationId)
        _pendingConfirmation.remove(conversationId)
    }

    fun cancel(conversationId: Long) {
        pending[conversationId]?.complete(false)
        pending.remove(conversationId)
        _pendingConfirmation.remove(conversationId)
    }
}
```

---

## UI Design — 仿终端体验

### 主界面 = 终端

应用启动后直接进入终端界面，这是核心交互场景。

```
┌──────────────────────────────────────┐
│ AIShell                    [DeepSeek▾]│  ← 右上角切换 AI 厂商
├──────────────────────────────────────┤
│                                      │  ← 黑色/深色背景
│ $ 整理 Download 里的图片             │  ← 用户输入（绿色）
│                                      │
│ > 我来帮你整理，先看看有哪些图片：    │  ← AI 回复（白色，流式）
│                                      │
│ $ find /sdcard/Download -name "*.jpg" │  ← 命令（亮绿色）
│   | wc -l                            │
│ 23                                   │  ← 命令输出（灰色）
│                                      │
│ $ mkdir -p /sdcard/Download/JPG_整理  │  ← 自动执行（MODIFY→已确认）
│                                      │
│ ⚠️ $ mv /sdcard/Download/*.jpg       │  ← 待确认（黄色高亮）
│      /sdcard/Download/JPG_整理/      │
│   [确认执行] [取消]                  │
│                                      │
│ $ _                                  │  ← 光标
├──────────────────────────────────────┤
│ [输入自然语言或命令...]        [发送] │
└──────────────────────────────────────┘
```

### 底部导航

```
[🖥️ 终端] [📋 会话] [🔌 插件] [⚙️ 设置]
```

### 会话列表

```
┌──────────────────────────────────────┐
│ AIShell                    [+ 新对话] │
├──────────────────────────────────────┤
│ ┌────────────────────────────────┐   │
│ │ 整理 Download 图片             │   │
│ │ DeepSeek · 3分钟前             │   │
│ └────────────────────────────────┘   │
│ ┌────────────────────────────────┐   │
│ │ 分析日志文件                    │   │
│ │ Ollama · 1小时前               │   │
│ └────────────────────────────────┘   │
└──────────────────────────────────────┘
```

### 设置页面

```
┌──────────────────────────────────────┐
│ Settings                             │
├──────────────────────────────────────┤
│ AI 厂商                              │
│   ● DeepSeek  ○ OpenAI  ○ Claude    │
│   ○ 智谱  ○ 通义  ○ MiniMax         │
│   ○ Kimi   ○ Ollama(本地)           │
│                                      │
│ API Key                              │
│   [sk-••••••••••••]                 │
│                                      │
│ 模型                                 │
│   [deepseek-chat          ▾]        │
│                                      │
│ 确认级别                             │
│   ○ 宽松  ● 正常  ○ 严格            │
│                                      │
│ MCP 服务器                           │
│   [+ 添加 MCP 服务器]               │
│   • filesystem (stdio)               │
│                                      │
│ 插件                                 │
│   • Termux Tools v1.2                │
│   [+ 浏览插件]                       │
│                                      │
│ 终端设置                             │
│   字体大小: [14      ▾]             │
│   主题: [Monokai    ▾]              │
└──────────────────────────────────────┘
```

### 终端主题

预置多种终端配色方案：

| 主题 | 背景 | 命令色 | 输出色 | 错误色 |
|---|---|---|---|---|
| Monokai | #272822 | #A6E22E | #F8F8F2 | #F92672 |
| Dracula | #282A36 | #50FA7B | #F8F8F2 | #FF5555 |
| Solarized Dark | #002B36 | #859900 | #839496 | #DC322F |
| One Dark | #282C34 | #98C379 | #ABB2BF | #E06C75 |

---

## Data Flow (Complete)

```
用户输入自然语言或命令
       ↓
AgentEngine.processUserInput()
       ↓
构造 system prompt（强调终端操作 + 当前工作目录）
       ↓
AiProvider.chatStream() → Flow<AiChunk>
       ↓
┌── TextDelta → 终端流式显示 AI 回复（白色）─────┐
│                                                   │
├── ToolCallStart/End → toolRegistry.get(name)      │
│       ↓                                           │
│   shell_exec? → analyzeCommandRisk()              │
│       ↓                                           │
│   风险检查 ─── READ_ONLY → 立即执行               │
│       │                                           │
│       ├── MODIFY/DESTRUCTIVE → ConfirmationGate   │
│       │         ↓                                 │
│       │     终端显示黄色确认提示                    │
│       │         ↓                                 │
│       │     用户确认/取消                           │
│       │         ↓                                 │
│       │     继续或中止                              │
│       ↓                                           │
│   tool.execute(params) → Flow<ToolEvent>          │
│       ↓                                           │
│   Stdout → 终端实时显示（灰色）                     │
│   Stderr → 终端显示（红色）                        │
│   Exit → 记录退出码                               │
│       ↓                                           │
│   结果写入消息历史                                  │
│       ↓                                           │
│   再次调用 AI（自动链接循环）                       │
└───────────────────────────────────────────────────┘
```

---

## Session Persistence

**Room 数据库表：**

| 表 | 字段 |
|---|---|
| Conversation | id, title, providerId, model, createdAt, updatedAt |
| Message | id, conversationId, role, content, toolCallId, toolName, timestamp |
| ToolCall | id, messageId, toolName, params, exitCode, duration, riskLevel |
| McpServer | id, name, transport, command/url, enabled |
| ProviderConfig | id, displayName, baseUrl, apiKey(encrypted), defaultModel, protocol |

**自动保存：**
- 每条消息和工具调用即时持久化
- 对话标题从首条用户消息自动生成
- 工具调用时长追踪

---

## Tech Stack

| 层 | 技术 | 版本 | 说明 |
|---|---|---|---|
| 目标架构 | arm64-v8a | - | 仅 ARM64，不支持 armeabi-v7a |
| 最低 API | Android 8.0 | API 26 | minSdk |
| 语言 | Kotlin + Rust | 2.1.0 + 1.82 | Kotlin 业务 + Rust 性能关键路径 |
| UI | Jetpack Compose + Canvas | BOM 2024.12 | Canvas 自绘终端 |
| DI | Hilt | 2.53.1 | |
| 网络 | Ktor Client + SSE | 3.0.1 | 原生协程 SSE，替代 OkHttp+Retrofit |
| 数据库 | Room (WAL) | 2.6.1 | 并发读写 |
| 偏好 | DataStore Proto | 1.1.1 | |
| 加密 | AndroidKeyStore + EncryptedFile | 1.1.0 | |
| 构建 | AGP + KSP | 8.7.3 + 2.1.0 | |
| Native | JNI + cargo-ndk | NDK 27 | aarch64-linux-android target |
| 序列化 | kotlinx.serialization | 1.7.3 | 替代 Moshi |
| 终端 | Rust PTY + ANSI Parser | 自研 | ARM64 原生性能 |
| 终端渲染 | Compose Canvas | 自研 | 60fps 差分 |
| proot | 内置 arm64 proot binary | 修补版 | 支持 seccomp 模式 |
| 权限提升 | Shizuku | - | 系统级权限 |
| 插件 | ContentProvider APK | - | 外部工具扩展 |
| MCP | 自定义 MCP Client | - | stdio/HTTP |

---

## proot Ubuntu — 内置 Linux 环境

### 概述

AIShell 内置 proot-distro 运行完整的 Ubuntu 文件系统，无需 root。AI 执行命令时默认在 Ubuntu 环境中运行，可使用 apt、python、git、node 等完整 Linux 工具链。

v3: proot 执行由 Rust ProotBridge 驱动，包含持久 helper 进程优化（避免 ~200ms 冷启动），三种 ARM64 模式自动检测（Ptrace/Seccomp/SymlinkOnly）。

### 架构

```
┌───────────────────────────────────────────────────┐
│                  AIShell App                      │
│                                                   │
│  ┌─────────────────────────────────────────────┐  │
│  │           shell_exec → CommandRouter         │  │
│  │                    ↓                         │  │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  │  │
│  │  │ Android  │  │  proot   │  │  Shizuku │  │  │
│  │  │ Shell    │  │  Ubuntu  │  │  (Layer3)│  │  │
│  │  │ (Layer1) │  │ (Layer2) │  │          │  │  │
│  │  └──────────┘  └────┬─────┘  └──────────┘  │  │
│  └─────────────────────┼───────────────────────┘  │
│                        ↓                          │
│  ┌─────────────────────────────────────────────┐  │
│  │  Rust ProotBridge                            │  │
│  │  ├─ 持久 helper 进程 (pipe 通信, 避免冷启动)  │  │
│  │  ├─ ARM64 模式检测: Ptrace/Seccomp/SymlinkOnly│ │
│  │  └─ 内置 arm64 proot binary (seccomp 修补版)  │  │
│  └─────────────────────────────────────────────┘  │
│                        ↓                          │
│  ┌─────────────────────────────────────────────┐  │
│  │       Ubuntu rootfs (ARM64)                  │  │
│  │  /data/.../aishell/ubuntu-root/              │  │
│  │  ├── bin/ usr/ lib/ etc/                     │  │
│  │  ├── home/ (用户目录)                        │  │
│  │  └── usr/bin/python3, git, ...               │  │
│  └─────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────┘
```

### 命令路由

v3: CommandRouter 分析命令首词，路由到对应执行层。

```kotlin
class CommandRouter @Inject constructor(
    private val androidExecutor: AndroidShellExecutor,
    private val prootBridge: ProotBridge,  // Rust 层
    private val shizukuExecutor: ShizukuExecutor
) {
    fun resolveExecutor(command: String): CommandExecutor {
        val firstWord = command.trim().split("\\s+".toRegex()).firstOrNull() ?: ""
        return when {
            // Ubuntu 专属命令 → proot
            isUbuntuCommand(firstWord) -> prootBridge
            // 需要 Shizuku 权限的命令 → Shizuku
            needsShizuku(firstWord) -> shizukuExecutor
            // 默认 → Android sh
            else -> androidExecutor
        }
    }

    private fun isUbuntuCommand(cmd: String): Boolean {
        val ubuntuCommands = setOf(
            "apt", "dpkg", "python3", "pip3", "gcc", "g++",
            "git", "curl", "wget", "ssh", "scp", "rsync",
            "docker", "npm", "node", "go", "rustc", "cargo"
        )
        return cmd in ubuntuCommands
    }

    private fun needsShizuku(cmd: String): Boolean {
        val systemCmds = setOf("pm", "am", "input", "settings", "dumpsys",
                              "screencap", "cmd", "wm", "service")
        return cmd in systemCmds
    }
}
```

### proot 执行器 (Rust ProotBridge)

v3: Rust 实现的 ProotBridge，包含持久 helper 进程：

```kotlin
// Kotlin 侧 — 通过 JNI 调用 Rust
class ProotBridge @Inject constructor(
    @ApplicationContext private val context: Context
) : CommandExecutor {

    private val prootBinary: File
        get() = File(context.filesDir, "bin/proot")

    /** 通过 Rust native 层执行 proot 命令 */
    override suspend fun execute(
        command: String,
        workDir: String?,
        timeout: Int,
        env: Map<String, String>
    ): Flow<ToolEvent> = channelFlow {
        // JNI 调用 Rust ProotBridge
        nativeProotExec(
            prootPath = prootBinary.absolutePath,
            rootfsPath = getUbuntuRoot().absolutePath,
            command = command,
            workDir = workDir ?: "/home",
            timeout = timeout
        ) { event -> trySend(event) }
    }

    /** 确保 helper 进程运行 (持久 bash，避免冷启动) */
    suspend fun ensureHelperRunning() {
        nativeEnsureHelper(prootBinary.absolutePath, getUbuntuRoot().absolutePath)
    }

    private external fun nativeProotExec(
        prootPath: String, rootfsPath: String, command: String,
        workDir: String, timeout: Int, callback: (ToolEvent) -> Unit
    ): Int

    private external fun nativeEnsureHelper(prootPath: String, rootfsPath: String)
}
```

### Ubuntu 初始化

首次启动时自动安装 Ubuntu rootfs：

```kotlin
class UbuntuInstaller(private val context: Context) {
    private val ubuntuRoot = File(context.filesDir, "ubuntu-root")

    suspend fun ensureInstalled(): Boolean {
        if (ubuntuRoot.exists() && File(ubuntuRoot, "bin/bash").exists()) {
            return true // 已安装
        }

        // 下载 Ubuntu rootfs (约 60MB 压缩)
        val rootfsUrl = "https://cloud-images.ubuntu.com/releases/24.04/release/ubuntu-24.04-server-cloudimg-arm64-root.tar.xz"
        val archive = File(context.cacheDir, "ubuntu-rootfs.tar.xz")

        downloadFile(rootfsUrl, archive)

        // 解压到 ubuntu-root/
        extractTarXz(archive, ubuntuRoot)

        // 初始化
        prootExec("apt update && apt install -y python3 git curl wget")

        archive.delete()
        return true
    }
}
```

### System Prompt 更新

```
你是 AIShell，一个 Android 终端上的 AI 助手。你的核心能力是通过执行终端命令帮助用户完成任务。

可用执行环境:
1. Ubuntu (proot) — 完整 Linux 环境，可用 apt/python3/git/node 等，路径以 /home 开头
2. Android Shell — 系统命令，可用 pm/am/input 等系统工具
3. Shizuku — 系统级权限命令（需 Shizuku 已授权）

规则:
1. 需要完整 Linux 工具时使用 Ubuntu 环境（apt install, python3 等）
2. 操作 Android 系统时使用 Android Shell（pm, am, input 等）
3. 每次只执行一条命令，等待结果后再决定下一步
4. 危险操作必须先告知风险
5. 当前 Ubuntu 工作目录: {ubuntu_home}
6. 当前 Android 工作目录: {android_home}
```

---

## Shizuku — 系统级权限

### 概述

Shizuku 让 AIShell 获得 shell 级别（uid=2000）甚至 root 级别的权限，突破 Android 沙箱限制。这是实现文件完全访问、系统命令、应用管理、自动化的基础。

### 权限层级

```
┌───────────────────────────────────────────┐
│            权限层级                        │
│                                           │
│  Layer 3: Shizuku (ADB/root)             │
│  ├── 文件系统完全访问 (/sdcard, /data)    │
│  ├── 系统命令 (pm install, am start)      │
│  ├── 应用管理 (卸载、禁用、授权)          │
│  ├── 输入模拟 (input tap, input swipe)    │
│  └── 设置修改 (settings put, cmd overlay) │
│                                           │
│  Layer 2: proot Ubuntu                    │
│  ├── 完整 Linux 工具链                    │
│  ├── 网络工具 (curl, wget, ssh)           │
│  └── 开发工具 (python3, node, git)        │
│                                           │
│  Layer 1: Android App 沙箱                │
│  ├── 基本文件访问 (app 私有目录)          │
│  ├── SAF (需要用户选择)                   │
│  └── 基本系统 API                         │
└───────────────────────────────────────────┘
```

### Shizuku 集成

```kotlin
class ShizukuExecutor @Inject constructor() : CommandExecutor {
    fun checkPermission(): Boolean {
        return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 通过 Shizuku 执行命令（以 shell 身份） — CommandExecutor 接口
     */
    override suspend fun execute(
        command: String,
        workDir: String?,
        timeout: Int,
        env: Map<String, String>
    ): Flow<ToolEvent> = channelFlow {
        if (!checkPermission()) {
            send(ToolEvent.Error("Shizuku 未授权，请在设置中启用"))
            return@channelFlow
        }

        val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, workDir)

        launch { streamOutput(process.inputStream, ::trySend, ToolEvent::Stdout) }
        launch { streamOutput(process.errorStream, ::trySend, ToolEvent::Stderr) }

        withTimeoutOrNull(timeout * 1000L) {
            process.waitFor()
        } ?: run {
            process.destroyForcibly()
            send(ToolEvent.Error("Command timed out after ${timeout}s"))
        }

        send(ToolEvent.Exit(process.exitValue()))
    }
}
```

### Shizuku 解锁的能力 → 对应工具

| 能力 | 对应工具/命令 | 风险级别 |
|---|---|---|
| 文件完全访问 | `shell_exec` + `find/ls/cp/mv /sdcard` | MODIFY |
| 系统命令 | `shell_exec` + `pm/am/input/dumpsys` | MODIFY |
| 静默安装 APK | `pm install -r -g app.apk` | DESTRUCTIVE |
| 卸载应用 | `pm uninstall com.example.app` | DESTRUCTIVE |
| 禁用应用 | `pm disable-user com.example.app` | DESTRUCTIVE |
| 授权/撤销权限 | `pm grant/revoke com.example.app android.permission.CAMERA` | MODIFY |
| 模拟点击/滑动 | `input tap/swipe/keyevent` | MODIFY |
| 修改系统设置 | `settings put/system/global` | MODIFY |
| 截屏 | `screencap -p /sdcard/screen.png` | READ_ONLY |
| 读取剪贴板 | `am broadcast ... clipper.get` | READ_ONLY |
| 发送广播 | `am broadcast -a com.example.ACTION` | MODIFY |
| 启动服务 | `am startservice com.example/.Service` | MODIFY |
| 读取通知 | `dumpsys notification` | READ_ONLY |
| 查看日志 | `logcat -d -t 100` | READ_ONLY |

### Shizuku 状态管理

```kotlin
enum class ShizukuStatus {
    NOT_INSTALLED,   // 未安装 Shizuku
    NOT_RUNNING,     // 已安装但未启动
    NOT_AUTHORIZED,  // 运行中但未授权 AIShell
    AVAILABLE        // 可用
}

class ShizukuStatusProvider @Inject constructor() {
    private val _status = MutableStateFlow(ShizukuStatus.NOT_INSTALLED)
    val status: StateFlow<ShizukuStatus> = _status

    init {
        Shizuku.addBinderReceivedListener {
            _status.value = if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuStatus.AVAILABLE
            } else {
                ShizukuStatus.NOT_AUTHORIZED
            }
        }
        Shizuku.addBinderDeadListener {
            _status.value = ShizukuStatus.NOT_RUNNING
        }
    }
}
```

### 设置页面 — Shizuku 状态

```
┌──────────────────────────────────────┐
│ Shizuku 权限                         │
├──────────────────────────────────────┤
│                                      │
│ 状态: ✅ 已授权 (ADB 模式)           │
│                                      │
│ 可用能力:                            │
│  ✅ 文件系统完全访问                  │
│  ✅ 系统命令 (pm/am/input)           │
│  ✅ 静默安装/卸载应用                │
│  ✅ 输入模拟 (点击/滑动)             │
│  ✅ 系统设置修改                     │
│                                      │
│ [打开 Shizuku 管理]                  │
│ [重新授权]                           │
│                                      │
│ ⚠️ 未授权时部分功能受限              │
│    命令将在 Android 沙箱中执行        │
└──────────────────────────────────────┘
```

### 命令执行优先级

v3: 已整合到 `CommandRouter`，根据命令首词自动路由到三层执行环境：

```kotlin
class CommandRouter @Inject constructor(
    private val androidExecutor: AndroidShellExecutor,   // Layer 1
    private val prootBridge: ProotBridge,                 // Layer 2
    private val shizukuExecutor: ShizukuExecutor          // Layer 3
) : CommandExecutor {

    override suspend fun execute(
        command: String, workDir: String?, timeout: Int, env: Map<String, String>
    ): Flow<ToolEvent> {
        val executor = resolveExecutor(command)
        return executor.execute(command, workDir, timeout, env)
    }

    fun resolveExecutor(command: String): CommandExecutor {
        val firstWord = command.trim().split("\\s+".toRegex()).firstOrNull() ?: ""
        return when {
            isUbuntuCommand(firstWord) -> prootBridge
            needsShizuku(firstWord) && shizukuExecutor.checkPermission() -> shizukuExecutor
            needsShizuku(firstWord) && !shizukuExecutor.checkPermission() -> {
                // 回退到 Android shell，输出警告
                androidExecutor  // + 警告日志
            }
            else -> androidExecutor
        }
    }

    private fun isUbuntuCommand(cmd: String): Boolean { /* ... */ }
    private fun needsShizuku(cmd: String): Boolean { /* ... */ }
}
```

### AI System Prompt 动态更新

根据 Shizuku 状态、设备模式和蓝牙状态动态调整 system prompt：

```kotlin
fun buildSystemPrompt(
    shizukuStatus: ShizukuStatus,
    deviceMode: DeviceMode,
    bluetoothAvailable: Boolean,
    cwd: String
): String {
    val shizukuSection = when (shizukuStatus) {
        ShizukuStatus.AVAILABLE -> """
Shizuku 已授权，可用高级命令:
- pm install/uninstall/disable/enable/grant/revoke (应用管理)
- am start/startservice/broadcast (Activity/Service 管理)
- input tap/swipe/keyevent (输入模拟)
- settings put/get (系统设置)
- screencap (截屏)
- dumpsys (系统信息)
文件系统完全访问 (/sdcard, /data/local/tmp)
"""
        else -> """
Shizuku 未授权，系统命令受限。
文件访问仅限应用私有目录，建议在设置中启用 Shizuku 获取完整能力。
"""
    }

    val deviceSection = when (deviceMode) {
        is DeviceMode.FASTBOOT -> """
当前检测到 Fastboot 设备: ${deviceMode.device.serial}
可用 fastboot 工具: fastboot_flash, fastboot_boot, fastboot_unlock, fastboot_info, fastboot_erase
"""
        is DeviceMode.RECOVERY -> """
当前检测到 Recovery 设备: ${deviceMode.device.serial}
可用 recovery 工具: recovery_sideload, recovery_log
"""
        is DeviceMode.EDL -> """
当前检测到 EDL 设备 (Qualcomm 9008)
可用 EDL 工具: edl_flash, edl_backup, edl_gpt
"""
        else -> ""
    }

    val bluetoothSection = if (bluetoothAvailable) """
蓝牙可用:
- BLE 工具: ble_scan, ble_connect, ble_read, ble_write, ble_notify
- SPP 工具: spp_connect, spp_send, spp_receive
- Classic 工具: classic_scan
""" else ""

    return """
你是 AIShell，Android ARM64 终端上的 AI 助手。

$shizukuSection

$deviceSection

$bluetoothSection

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
```

---

## 安全设计

### API Key 存储

```kotlin
class SecureKeyStore(private val context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKeyScheme.AES256_GCM)
        .build()

    fun saveKey(providerId: String, apiKey: String) {
        val file = File(context.filesDir, "keys/$providerId")
        EncryptedFile.Builder(context, file, masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB)
            .build()
            .openFileOutput().use { it.write(apiKey.toByteArray()) }
    }

    fun readKey(providerId: String): String? {
        val file = File(context.filesDir, "keys/$providerId")
        if (!file.exists()) return null
        return EncryptedFile.Builder(context, file, masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB)
            .build()
            .openFileInput().bufferedReader().readText()
    }
}
```

### 命令注入防护

```kotlin
class CommandSanitizer {
    // 阻止明显危险的命令模式
    fun sanitize(command: String): String {
        // 不修改命令，但在分析时标记风险
        return command
    }

    fun isPotentiallyDangerous(command: String): Boolean {
        val dangerous = listOf(
            "rm -rf /", "dd if=", ":(){ :|:& };:",  // fork bomb
            "mkfs.", "format", "> /dev/sd",
            "chmod 777 /", "chown root"
        )
        return dangerous.any { command.contains(it) }
    }
}
```

### 确认机制

- MODIFY 操作：展示命令内容，单次确认
- DESTRUCTIVE 操作：展示命令 + 影响范围，需要勾选"我了解风险"后确认
- 自动拒绝 `isPotentiallyDangerous()` 返回 true 的命令（除非用户在设置中开启"允许极高危险操作"）

---

## ADB 远程设备管理

### 概述

AIShell 内置 ADB 客户端，可以通过 WiFi 或 USB 管理其他 Android 设备。AI 可以远程执行命令、安装应用、调试、截屏等。

### 架构

```
┌─────────────────────────────────────────────┐
│            AIShell (本机)                    │
│                                             │
│  ┌────────────────────────────────────┐     │
│  │         ADB Client                 │     │
│  │  ┌──────────┐  ┌───────────────┐  │     │
│  │  │ WiFi ADB │  │ USB ADB       │  │     │
│  │  │ (TCP)    │  │ (Wire Protocol│  │     │
│  │  │          │  │  或 libusb)   │  │     │
│  │  └────┬─────┘  └──────┬────────┘  │     │
│  └────────┼───────────────┼───────────┘     │
│           │               │                 │
│     ┌─────┴───────┐  ┌────┴──────┐          │
│     │ 目标设备     │  │ USB 设备  │          │
│     │ (WiFi)      │  │ (Android/ │          │
│     │             │  │  Linux)   │          │
│     └─────────────┘  └───────────┘          │
│                                             │
│  ADB 双模式:                                │
│  ├─ Binary 模式: 内置 arm64 adb (兼容)      │
│  └─ Wire Protocol: Rust 自研 ADB 协议栈     │
│     (CNXN/AUTH/OPEN/OKAY/WRTE/CLSE)         │
│     USB→Wire Protocol / TCP→Binary          │
└─────────────────────────────────────────────┘
```

v3: ADB 采用双模式架构。Binary 模式使用内置 arm64 adb 处理 WiFi 连接；Wire Protocol 模式由 Rust 自研 ADB 协议栈处理 USB 直连，无需 adb binary 即可完成 shell/push/pull 操作。详见 `architecture.md` "ADB Wire Protocol" 章节。

### ADB 工具定义

v3: 使用 sealed class ToolParams，分为 AdbConnect/AdbCommand/AdbPush/AdbPull/AdbInstall 五个独立工具。

```kotlin
// 每个工具对应一个 ToolParams 子类
// adb_connect → ToolParams.AdbConnect
// adb_command → ToolParams.AdbCommand
// adb_push → ToolParams.AdbPush
// adb_pull → ToolParams.AdbPull
// adb_install → ToolParams.AdbInstall

// 内置 arm64 adb binary，从 assets 解压
class AdbBinaryManager(@ApplicationContext private val context: Context) {
    private val adbFile: File
        get() = File(context.filesDir, "bin/adb")

    suspend fun ensureExtracted(): File {
        if (adbFile.exists() && adbFile.canExecute()) return adbFile
        context.assets.open("bin/adb").use { input ->
            FileOutputStream(adbFile).use { output -> input.copyTo(output) }
        }
        adbFile.setExecutable(true)
        return adbFile
    }
}

// ADB 工具执行示例
class AdbCommandTool(
    private val adbBinary: AdbBinaryManager,
    private val commandExecutor: CommandExecutor
) : Tool {
    override val name = "adb_command"
    override val riskLevel = RiskLevel.MODIFY
    override val descriptor = ToolDescriptor(...)

    override suspend fun execute(params: ToolParams): Flow<ToolEvent> {
        val p = params as ToolParams.AdbCommand
        val adb = adbBinary.ensureExtracted()
        val cmd = if (p.target != null) "$adb -s ${p.target} ${p.command}"
                  else "$adb ${p.command}"
        return commandExecutor.execute(cmd, timeout = p.timeout)
    }
}
```

### 设备管理 UI

```
┌──────────────────────────────────────┐
│ 设备管理                     [+ 添加] │
├──────────────────────────────────────┤
│                                      │
│ 📱 Pixel 7 (USB)                    │
│    Android 14 · 连接中              │
│    [Shell] [截屏] [安装应用] [日志]  │
│                                      │
│ 📱 Mi TV (WiFi: 192.168.1.100:5555) │
│    Android 12 · 连接中              │
│    [Shell] [截屏] [安装应用] [日志]  │
│                                      │
│ ⚪ 平板 (WiFi: 192.168.1.101:5555)  │
│    Android 13 · 离线                │
│    [连接] [删除]                    │
│                                      │
└──────────────────────────────────────┘
```

### 连接方式

| 方式 | 说明 | 自动发现 |
|---|---|---|
| WiFi (adb connect) | 输入 IP:port 连接 | mDNS 扫描局域网设备 |
| USB (libusb) | OTG 线直连 | USB 设备枚举 |
| 已配对 | 记住历史设备 | 启动时自动重连 |

---

## USB 三层架构

### 概述

AIShell 的 USB 访问采用**三层递进**策略：优先使用 Android 标准 USB 授权（无需 root），仅在高级场景下才需要 libusb + Shizuku。

v3: USB 协议栈由 Rust 实现，集成 Fastboot/EDL 刷机协议。

### 架构

```
┌───────────────────────────────────────────────────────────┐
│                    USB 三层架构                             │
│                                                           │
│  ┌─────────────────┐  ┌─────────────────┐  ┌───────────┐ │
│  │ Layer 1:        │  │ Layer 2:        │  │ Layer 3:  │ │
│  │ UsbManager API  │  │ libusb via      │  │ libusb    │ │
│  │ (标准授权弹窗)   │  │ UsbManager fd   │  │ direct    │ │
│  │                 │  │ (标准授权+精确   │  │ usbfs     │ │
│  │ 串口/存储/HID   │  │  时序控制)      │  │ (需Shizuku)│ │
│  └─────────────────┘  │ Fastboot/EDL    │  └───────────┘ │
│                       └─────────────────┘                │
│                                                           │
│  ┌───────────────────────────────────────────────────┐    │
│  │  Rust USB Stack (libusb-android.so)               │    │
│  │  ├─ libusb core (Rust FFI)                       │    │
│  │  ├─ FastbootProtocol (USB bulk transfer)          │    │
│  │  ├─ Sahara + Firehose (EDL 协议)                  │    │
│  │  └─ UsbRouter (自动选择访问层)                     │    │
│  └───────────────────────────────────────────────────┘    │
└───────────────────────────────────────────────────────────┘
```

### USB 工具定义 (v3)

```kotlin
// 使用 sealed class ToolParams
// UsbList, UsbAction, UsbTransfer — 详见 ToolParams 定义

// USB 路由决策 — 自动选择访问层
class UsbRouter @Inject constructor(
    private val usbManagerLayer: UsbManagerLayer,
    private val usbAdvancedLayer: UsbAdvancedLayer,
    private val shizukuStatus: ShizukuStatusProvider
) {
    fun resolveLayer(device: UsbDevice, requirement: UsbRequirement): UsbAccessLayer {
        if (requirement == UsbRequirement.STANDARD) return UsbAccessLayer.MANAGER_API
        if (requirement == UsbRequirement.PRECISE_TIMING) return UsbAccessLayer.LIBUSB_VIA_FD
        if (requirement == UsbRequirement.ISOCHRONOUS || requirement == UsbRequirement.BYPASS_KERNEL) {
            return if (shizukuStatus.isAvailable()) UsbAccessLayer.LIBUSB_DIRECT
                   else UsbAccessLayer.UNAVAILABLE
        }
        return UsbAccessLayer.MANAGER_API
    }
}

enum class UsbRequirement { STANDARD, PRECISE_TIMING, ISOCHRONOUS, BYPASS_KERNEL }
enum class UsbAccessLayer { MANAGER_API, LIBUSB_VIA_FD, LIBUSB_DIRECT, UNAVAILABLE }
```

### 支持的 USB 设备类型

| 类型 | USB Layer | 协议/驱动 | 用途 |
|---|---|---|---|
| Android 设备 (ADB) | Layer 1 | ADB protocol (Binary + Wire Protocol) | 远程管理、调试 |
| Android 设备 (Fastboot) | Layer 2 | Fastboot protocol | 刷写分区、解锁 |
| Android 设备 (EDL/9008) | Layer 2/3 | Sahara + Firehose | 救砖刷机 |
| 串口设备 (Arduino/IoT) | Layer 1 | CDC-ACM / FTDI / CH340 / CP2102 | 硬件交互 |
| USB 存储 (U盘) | Layer 1 | Mass Storage (BOT/SCSI/FAT32) | 读写外部存储 |
| MTP 设备 | Layer 1 | PTP/MTP (GetObject/SendObject) | 照片导入、文件传输 |
| Bluetooth (BLE/Classic) | N/A | BLE GATT / SPP RFCOMM | IoT 控制、串口通信 |
| 自定义设备 | Layer 2 | Raw USB | 任意 USB 协议 |

---

## 文件管理器

### 概述

AIShell 内置全功能文件管理器，支持本地文件、SFTP、SMB/NFS/WebDAV 网络存储。SFTP/SMB 由 Rust 原生协议栈驱动，不依赖第三方 Java 库。AI 可以直接操作所有挂载的文件系统。

### 架构

```
┌──────────────────────────────────────┐
│          File Manager UI             │
│  ┌──────┐ ┌──────┐ ┌──────┐        │
│  │ 本地 │ │ SFTP │ │ SMB  │ ...    │
│  └──┬───┘ └──┬───┘ └──┬───┘        │
│     └────────┼────────┘             │
│              ↓                       │
│  ┌──────────────────────────────┐   │
│  │     VFS (Virtual File System)│   │
│  │  统一接口，屏蔽底层差异       │   │
│  └──────────────────────────────┘   │
│              ↓                       │
│  ┌──────────────────────────────┐   │
│  │     File Provider Layer      │   │
│  │  ┌────┐ ┌────┐ ┌─────┐      │   │
│  │  │Local│ │SFTP│ │SMB/ │      │   │
│  │  │    │ │    │ │WebDAV│     │   │
│  │  └────┘ └────┘ └─────┘      │   │
│  └──────────────────────────────┘   │
└──────────────────────────────────────┘
```

### VFS 统一接口

```kotlin
interface FileProvider {
    val scheme: String  // "file", "sftp", "smb", "webdav"
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

### SFTP Provider — Rust 原生 SSH/SFTP

v3: SFTP 底层由 Rust 原生实现（curve25519 密钥交换、AES-GCM/ChaCha20 加密、SFTP v3-v6），通过 JNI 桥接到 Kotlin，不依赖 sshj 库。完整实现详见 `architecture.md` "SFTP 协议栈" 章节。

```kotlin
class SftpProvider(
    private val host: String,
    private val port: Int = 22,
    private val username: String,
    private val password: String? = null,
    private val privateKey: String? = null
) : FileProvider {
    override val scheme = "sftp"
    override val displayName = "SFTP: $host"

    // Rust 原生 SFTP — JNI 桥接
    private var connectionHandle: Long = 0

    private suspend fun ensureConnected() {
        if (connectionHandle == 0L) {
            connectionHandle = nativeSftpConnect(
                host, port, username, password, privateKey
            )
        }
    }

    override suspend fun list(path: String): List<FileEntry> {
        ensureConnected()
        return nativeSftpList(connectionHandle, path)
    }
    override suspend fun read(path: String): InputStream {
        ensureConnected()
        return nativeSftpRead(connectionHandle, path)
    }
    override suspend fun write(path: String): OutputStream {
        ensureConnected()
        return nativeSftpWrite(connectionHandle, path)
    }
    override suspend fun delete(path: String): Boolean {
        ensureConnected()
        return nativeSftpDelete(connectionHandle, path)
    }
    // ... move, mkdir, exists, stat

    fun close() {
        if (connectionHandle != 0L) {
            nativeSftpDisconnect(connectionHandle)
            connectionHandle = 0
        }
    }

    // JNI Bridge
    private external fun nativeSftpConnect(host: String, port: Int, user: String, pass: String?, key: String?): Long
    private external fun nativeSftpList(handle: Long, path: String): List<FileEntry>
    private external fun nativeSftpRead(handle: Long, path: String): InputStream
    private external fun nativeSftpWrite(handle: Long, path: String): OutputStream
    private external fun nativeSftpDelete(handle: Long, path: String): Boolean
    private external fun nativeSftpDisconnect(handle: Long)
}
```

**Rust SSH/SFTP 支持特性：**
- 密钥交换：curve25519-sha256、ecdh-sha2-nistp256
- 加密：aes128-gcm@openssh.com、chacha20-poly1305@openssh.com
- 认证：password、publickey (Ed25519/ECDSA/RSA)
- SFTP 版本：v3-v6，分块读写（64KB read / 32KB write）

### SMB/WebDAV Provider — Rust 原生 SMB2/3

v3: SMB 底层由 Rust 原生实现（NTLMv2 认证、SMB3 AES-128-CCM/GCM 加密），通过 JNI 桥接到 Kotlin，不依赖 jcifs 库。完整实现详见 `architecture.md` "SMB/CIFS 协议栈" 章节。

```kotlin
class SmbProvider(
    private val host: String,
    private val share: String,
    private val domain: String = "",
    private val username: String = "guest",
    private val password: String = ""
) : FileProvider {
    override val scheme = "smb"
    override val displayName = "SMB: //$host/$share"

    // Rust 原生 SMB2/3 — JNI 桥接
    private var connectionHandle: Long = 0

    private suspend fun ensureConnected() {
        if (connectionHandle == 0L) {
            connectionHandle = nativeSmbConnect(host, 445, share, domain, username, password)
        }
    }

    override suspend fun list(path: String): List<FileEntry> {
        ensureConnected()
        return nativeSmbList(connectionHandle, path)
    }
    override suspend fun read(path: String): InputStream {
        ensureConnected()
        return nativeSmbRead(connectionHandle, path)
    }
    override suspend fun write(path: String): OutputStream {
        ensureConnected()
        return nativeSmbWrite(connectionHandle, path)
    }
    // ... delete, move, mkdir, exists, stat

    fun close() {
        if (connectionHandle != 0L) {
            nativeSmbDisconnect(connectionHandle)
            connectionHandle = 0
        }
    }

    // JNI Bridge
    private external fun nativeSmbConnect(host: String, port: Int, share: String, domain: String, user: String, pass: String): Long
    private external fun nativeSmbList(handle: Long, path: String): List<FileEntry>
    private external fun nativeSmbRead(handle: Long, path: String): InputStream
    private external fun nativeSmbWrite(handle: Long, path: String): OutputStream
    private external fun nativeSmbDisconnect(handle: Long)
}
```

**Rust SMB2/3 支持特性：**
- 认证：NTLMv2（Type1→Type2→Type3 三步握手）
- SMB3 加密：AES-128-CCM / AES-128-GCM
- SMB3 签名：AES-128-CMAC
- 传输：Direct TCP (port 445)
- 操作：NEGOTIATE / SESSION_SETUP / TREE_CONNECT / CREATE / READ / WRITE / CLOSE / QUERY_DIRECTORY

class WebDavProvider(
    private val baseUrl: String,
    private val username: String = "",
    private val password: String = ""
) : FileProvider {
    override val scheme = "webdav"
    override val displayName = "WebDAV: $baseUrl"

    // 使用 sardine 库实现
    // ...
}
```

### AI 文件操作工具更新

v3: AI 的 `file_list`/`file_read` 等工具使用 sealed class `ToolParams`，通过 VFS 自动支持所有挂载的文件系统：

```kotlin
class VfsFileListTool(private val vfs: VirtualFileSystem) : Tool {
    override val name = "file_list"
    override val descriptor = ToolDescriptor(...)

    override suspend fun execute(params: ToolParams): Flow<ToolEvent> = flow {
        val p = params as ToolParams.FileList
        val provider = vfs.resolveProvider(p.path) // 自动根据路径前缀选择
        val entries = provider.list(p.path)
        val output = entries.joinToString("\n") { e ->
            val type = if (e.isDirectory) "DIR" else "FILE"
            "$type ${e.permissions ?: ""} ${e.name} (${e.size} bytes)"
        }
        emit(ToolEvent.Output(output))
    }
}
```

路径格式：`file:///sdcard/Download`, `sftp://user@host/home`, `smb://server/share/docs`

### 文件管理器 UI

```
┌──────────────────────────────────────┐
│ 📁 文件管理                  [⊕ 挂载] │
├──────────────────────────────────────┤
│ 📂 本地存储                          │
│   ├── /sdcard (内部存储)             │
│   └── /usb (U盘)                    │
│                                      │
│ 📂 网络存储                          │
│   ├── 🖥️ my-server (SFTP)           │
│   │   └── /home/user                │
│   ├── 📡 NAS (SMB)                   │
│   │   └── /共享文档                  │
│   └── ☁️ CloudDAV (WebDAV)           │
│       └── /docs                      │
│                                      │
│ 最近使用:                             │
│  • /sdcard/Download/report.pdf       │
│  • sftp://my-server/var/log/syslog   │
└──────────────────────────────────────┘
```

### 添加存储对话框

```
┌──────────────────────────────────────┐
│ 添加存储                             │
├──────────────────────────────────────┤
│ 类型: [SFTP       ▾]               │
│                                      │
│ 主机: [192.168.1.100    ]           │
│ 端口: [22                ]           │
│ 用户: [root              ]           │
│ 认证: ● 密码 ○ 密钥                │
│ 密码: [••••••••          ]           │
│                                      │
│ [测试连接]          [保存]          │
└──────────────────────────────────────┘
```

### 底部导航更新

```
[🖥️ 终端] [📁 文件] [📱 设备] [📋 会话] [⚙️ 设置]
```

新增：
- **文件** — 文件管理器（本地+远程）
- **设备** — ADB 设备管理

---

## 命令增强功能

### 命令历史与补全

```kotlin
class CommandHistory(private val dao: CommandHistoryDao) {
    fun search(prefix: String): Flow<List<String>> =
        dao.searchByPrefix(prefix)

    suspend fun add(command: String, exitCode: Int, durationMs: Long) {
        dao.insert(CommandHistoryEntry(command = command, exitCode = exitCode, durationMs = durationMs))
    }
}
```

终端输入框支持：
- **↑/↓ 键**：浏览历史命令
- **Tab**：命令名补全
- **Ctrl+R**：搜索历史

### 目录快捷方式

```kotlin
data class DirectoryShortcut(
    val name: String,    // "dl"
    val path: String     // "/sdcard/Download"
)

// 预置
val DEFAULT_SHORTCUTS = listOf(
    DirectoryShortcut("home", "/data/data/com.termux/files/home"),
    DirectoryShortcut("dl", "/sdcard/Download"),
    DirectoryShortcut("dcim", "/sdcard/DCIM"),
    DirectoryShortcut("sd", "/sdcard"),
    DirectoryShortcut("usb", "/mnt/media_rw/USB"),
)

// 在终端中: cd ~dl → cd /sdcard/Download
```

### 工作流模板

```
┌──────────────────────────────────────┐
│ 工作流模板                  [+ 创建] │
├──────────────────────────────────────┤
│ 📦 批量安装 APK                      │
│    遍历目录中所有 .apk 文件并安装     │
│    [使用] [编辑]                     │
│                                      │
│ 🔄 定时备份                          │
│    每日打包指定目录上传到 SFTP        │
│    [使用] [编辑]                     │
│                                      │
│ 🧹 存储清理                          │
│    扫描大文件/缓存/重复文件           │
│    [使用] [编辑]                     │
│                                      │
│ 📊 系统信息                          │
│    收集设备信息生成报告               │
│    [使用] [编辑]                     │
└──────────────────────────────────────┘
```

模板本质是一组预设的 system prompt + 工具权限配置，用户选择后自动创建新对话并设置好上下文。

---

## 更新后的 System Prompt (v3)

```
你是 AIShell，Android ARM64 终端上的 AI 助手。你的核心能力是通过执行终端命令帮助用户完成任务。

可用执行环境:
1. Ubuntu (proot) — 完整 Linux 环境: apt/python3/git/node/curl/wget/ssh
2. Android Shell — 系统命令: pm/am/input/settings/dumpsys/logcat
3. Shizuku (需授权) — 高级权限: 文件完全访问/应用管理/输入模拟/设置修改

可用设备操作:
- ADB 远程设备管理 (adb connect/shell/push/pull/install)
- USB 直连设备 (UsbManager 标准授权 → libusb 高级访问)
- Recovery 模式 (adb sideload/日志拉取)
- Fastboot (flash/boot/unlock/erase/slot切换)
- EDL 紧急下载 (Qualcomm 9008 救砖 — Sahara+Firehose协议)

可用文件系统:
- 本地: /sdcard, /data, /home (Ubuntu)
- SFTP: sftp://user@host/path
- SMB: smb://server/share/path
- WebDAV: webdav://host/path

规则:
1. 优先使用 shell_exec 执行命令
2. 操作远程设备使用 adb 工具
3. 操作 USB 设备使用 usb 工具
4. 刷机操作使用 fastboot/edl 工具，所有刷机操作需用户确认
5. 访问远程文件使用对应路径前缀 (sftp://, smb://)
6. 每次只执行一条命令，等待结果后再决定下一步
7. 危险操作必须先告知风险
8. 需要未知工具时提示用户安装

当前工作目录: {cwd}
Shizuku 状态: {shizuku_status}
已连接设备: {connected_devices}
已挂载存储: {mounted_storages}
设备模式: {device_mode}  // normal/recovery/fastboot/edl
```

---

## 进程与任务管理

### 概述

AIShell 支持长时间运行的后台任务、守护进程和终端会话复用，让终端不只是"一问一答"的交互。

### 后台任务

命令可以在后台运行，不阻塞终端输入。用户可以随时查看进度、获取结果。

```kotlin
data class BackgroundTask(
    val id: String,
    val command: String,
    val startTime: Long,
    val status: TaskStatus,
    val output: List<String>  // 缓冲的输出行
)

enum class TaskStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

class BackgroundTaskManager {
    private val tasks = ConcurrentHashMap<String, BackgroundTask>()
    private val _activeTasks = MutableStateFlow<List<BackgroundTask>>(emptyList())
    val activeTasks: StateFlow<List<BackgroundTask>> = _activeTasks

    fun startBackground(command: String, workDir: String?): String {
        val taskId = UUID.randomUUID().toString()
        val task = BackgroundTask(id = taskId, command = command, startTime = System.currentTimeMillis(), status = TaskStatus.RUNNING)
        tasks[taskId] = task

        // 启动进程，异步收集输出
        CoroutineScope(Dispatchers.IO).launch {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command), null, workDir?.let { File(it) })
            process.inputStream.bufferedReader().forEachLine { line ->
                tasks[taskId]?.let { t ->
                    tasks[taskId] = t.copy(output = t.output + line)
                }
            }
            val exitCode = process.waitFor()
            tasks[taskId]?.let { t ->
                tasks[taskId] = t.copy(status = if (exitCode == 0) TaskStatus.COMPLETED else TaskStatus.FAILED)
            }
            _activeTasks.value = tasks.values.toList()
        }

        return taskId
    }

    fun getOutput(taskId: String): List<String> = tasks[taskId]?.output ?: emptyList()
    fun cancel(taskId: String) { /* kill process, mark CANCELLED */ }
}
```

### 守护进程

支持注册守护进程：崩溃自动重启，可设为开机自启。

```kotlin
data class DaemonConfig(
    val name: String,
    val command: String,
    val workDir: String? = null,
    val autoRestart: Boolean = true,
    val startOnBoot: Boolean = false,
    val restartDelayMs: Long = 3000,
    val maxRestarts: Int = 5  // 一分钟内最多重启次数
)

class DaemonManager(private val taskManager: BackgroundTaskManager) {
    private val daemons = ConcurrentHashMap<String, DaemonInstance>()

    fun register(config: DaemonConfig) {
        val instance = DaemonInstance(config, taskManager)
        daemons[config.name] = instance
        instance.start()
    }

    fun stop(name: String) { daemons[name]?.stop() }
    fun listAll(): List<DaemonStatus> = daemons.values.map { it.status }
}

class DaemonInstance(private val config: DaemonConfig, private val taskManager: BackgroundTaskManager) {
    private var taskIds = mutableListOf<String>()
    private var restartCount = 0
    private var running = true

    fun start() {
        running = true
        launchDaemon()
    }

    fun stop() {
        running = false
        taskIds.forEach { taskManager.cancel(it) }
    }

    private fun launchDaemon() {
        if (!running) return
        val taskId = taskManager.startBackground(config.command, config.workDir)
        taskIds.add(taskId)

        // 监控任务状态，崩溃时重启
        CoroutineScope(Dispatchers.Default).launch {
            while (running) {
                val task = taskManager.getTask(taskId)
                if (task?.status == TaskStatus.COMPLETED || task?.status == TaskStatus.FAILED) {
                    if (config.autoRestart && running && restartCount < config.maxRestarts) {
                        delay(config.restartDelayMs)
                        restartCount++
                        launchDaemon()
                    }
                }
                delay(1000)
            }
        }
    }

    val status: DaemonStatus
        get() = DaemonStatus(config.name, running, restartCount)
}

data class DaemonStatus(val name: String, val running: Boolean, val restartCount: Int)
```

### 终端会话复用 (tmux-like)

v3: Rust 原生实现 tmux 语义（Session→Window→Pane），无需外部 tmux binary。支持 detach/attach、分屏、后台保活。

```kotlin
class TmuxSessionManager @Inject constructor(
    private val nativeSessionManager: NativeSessionManager  // Rust JNI
) {
    /** 创建新会话 */
    suspend fun createSession(name: String, command: String? = null): Result<TmuxSession> {
        val id = nativeSessionManager.createSession(name, command)
        return Result.success(TmuxSession(id, name))
    }

    /** Attach 到会话，获取 Pane 输出流 */
    suspend fun attach(sessionId: Long): Flow<PaneOutput> {
        return nativeSessionManager.attachPane(sessionId)
    }

    /** 分屏 */
    suspend fun splitPane(sessionId: Long, direction: SplitDirection, command: String? = null): Result<PaneId> {
        val paneId = nativeSessionManager.splitPane(sessionId, direction.ordinal, command)
        return Result.success(paneId)
    }

    /** Detach — PTY 进程继续运行，启动前台保活服务 */
    suspend fun detach(sessionId: Long) {
        nativeSessionManager.detach(sessionId)
        TerminalKeepService.startForeground(context, sessionId)
    }

    fun listSessions(): List<SessionInfo> = nativeSessionManager.listSessions()
    fun killSession(sessionId: Long) = nativeSessionManager.killSession(sessionId)
}

enum class SplitDirection { HORIZONTAL, VERTICAL }
```

### 后台任务 UI

```
┌──────────────────────────────────────┐
│ 后台任务                             │
├──────────────────────────────────────┤
│                                      │
│ 🟢 python3 server.py                │
│    运行中 · 2m 30s · PID 12345      │
│    [查看输出] [停止]                 │
│                                      │
│ 🟢 tmux: build-session              │
│    运行中 · 5m 10s                   │
│    [连接] [停止]                     │
│                                      │
│ ✅ rsync -av src/ backup/            │
│    已完成 · 1m 05s · 退出码 0        │
│    [查看输出]                        │
│                                      │
│ 🔄 守护进程: nginx                  │
│    运行中 · 自动重启 0 次            │
│    [查看日志] [停止]                 │
│                                      │
└──────────────────────────────────────┘
```

### AI 后台任务工具

v3: 使用 sealed class ToolParams：

```kotlin
// background_task 工具使用 ToolParams.BackgroundTask
// daemon 工具使用 ToolParams.DaemonAction
// 详见 ToolParams 完整定义

// 后台任务 ToolParams:
data class BackgroundTask(
    val action: String,             // "start" | "stop" | "list" | "status"
    val command: String? = null,
    val taskId: String? = null,
    val workDir: String? = null
) : ToolParams()

// 守护进程 ToolParams:
data class DaemonAction(
    val action: String,             // "start" | "stop" | "restart" | "status" | "list"
    val name: String? = null,
    val command: String? = null,
    val restartOnCrash: Boolean = true,
    val restartDelayMs: Long = 5000
) : ToolParams()
```

---

## 自动化引擎

### 概述

AIShell 内置自动化引擎，支持定时任务（cron）、事件触发、AI 自主代理三种模式。

### 架构

```
┌─────────────────────────────────────────┐
│           Automation Engine             │
│                                         │
│  ┌─────────────┐  ┌──────────────────┐  │
│  │  Cron        │  │  Event Listener  │  │
│  │  Scheduler   │  │  (File/Net/App)  │  │
│  └──────┬──────┘  └────────┬─────────┘  │
│         │                  │             │
│         └────────┬─────────┘             │
│                  ↓                       │
│  ┌──────────────────────────────────┐    │
│  │       Automation Runner          │    │
│  │  ┌────────────────────────────┐  │    │
│  │  │  AI Agent Loop             │  │    │
│  │  │  (自主决策+执行+反馈)      │  │    │
│  │  └────────────────────────────┘  │    │
│  └──────────────────────────────────┘    │
│                  ↓                       │
│  ┌──────────────────────────────────┐    │
│  │       Tool Registry              │    │
│  └──────────────────────────────────┘    │
└─────────────────────────────────────────┘
```

### Cron 定时任务

```kotlin
data class CronJob(
    val id: String,
    val name: String,
    val schedule: String,     // cron 表达式: "*/30 * * * *"
    val prompt: String,       // 自然语言任务描述
    val providerId: String,   // 使用的 AI 厂商
    val enabled: Boolean = true,
    val lastRun: Long? = null,
    val lastResult: String? = null
)

class CronScheduler(
    private val agentEngine: AgentEngine,
    private val provider: AiProvider
) {
    private val jobs = ConcurrentHashMap<String, CronJob>()
    private val scheduler = Executors.newScheduledThreadPool(1)

    fun addJob(job: CronJob) {
        jobs[job.id] = job
        scheduleJob(job)
    }

    private fun scheduleJob(job: CronJob) {
        val cron = CronParser.parse(job.schedule)
        val delay = cron.nextDelay()
        scheduler.schedule({
            if (job.enabled) {
                runJob(job)
            }
            scheduleJob(job)  // 重新调度
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun runJob(job: CronJob) {
        CoroutineScope(Dispatchers.Default).launch {
            // 创建新对话，执行自动化任务
            val conversationId = createConversation("Cron: ${job.name}")
            agentEngine.processUserInput(job.prompt, conversationId).collect { event ->
                // 记录结果
                when (event) {
                    is AgentEvent.ToolResult -> {
                        jobs[job.id] = job.copy(lastResult = event.content.take(500))
                    }
                    else -> {}
                }
            }
        }
    }
}
```

### 事件触发

```kotlin
sealed class AutomationTrigger {
    data class FileChanged(val path: String, val event: FileEventType) : AutomationTrigger()
    data class NetworkChanged(val event: NetworkEventType) : AutomationTrigger()
    data class AppInstalled(val packageName: String) : AutomationTrigger()
    data class BatteryLevel(val level: Int) : AutomationTrigger()
    data class TimeScheduled(val cron: String) : AutomationTrigger()
    data class DeviceConnected(val deviceType: String) : AutomationTrigger()
    data class CustomBroadcast(val action: String) : AutomationTrigger()
}

enum class FileEventType { CREATED, MODIFIED, DELETED }
enum class NetworkEventType { CONNECTED, DISCONNECTED, WIFI_CHANGED }

data class AutomationRule(
    val id: String,
    val name: String,
    val trigger: AutomationTrigger,
    val prompt: String,          // 触发时执行的 AI 任务
    val cooldownMs: Long = 60000, // 最小触发间隔
    val enabled: Boolean = true
)

class EventAutomationEngine(
    private val agentEngine: AgentEngine,
    private val fileWatcher: FileWatcher,
    private val networkMonitor: NetworkMonitor,
    private val packageMonitor: PackageMonitor
) {
    private val rules = ConcurrentHashMap<String, AutomationRule>()

    fun registerRule(rule: AutomationRule) {
        rules[rule.id] = rule
        when (rule.trigger) {
            is AutomationTrigger.FileChanged ->
                fileWatcher.watch(rule.trigger.path) { event ->
                    fireRule(rule)
                }
            is AutomationTrigger.NetworkChanged ->
                networkMonitor.observe { event ->
                    if (event == rule.trigger.event) fireRule(rule)
                }
            is AutomationTrigger.AppInstalled ->
                packageMonitor.observe { pkg ->
                    fireRule(rule)
                }
            // ...
        }
    }

    private fun fireRule(rule: AutomationRule) {
        CoroutineScope(Dispatchers.Default).launch {
            val conversationId = createConversation("Auto: ${rule.name}")
            agentEngine.processUserInput(rule.prompt, conversationId).collect { /* log */ }
        }
    }
}
```

### AI 自主代理模式

用户设定目标后，AI 自主规划、执行、评估，直到目标完成或达到重试上限。

```kotlin
data class AutonomousConfig(
    val goal: String,                // 目标描述
    val maxSteps: Int = 20,          // 最大执行步骤
    val maxCost: Double = 1.0,       // 最大 API 花费 ($)
    val requireConfirmationAt: Int = 10,  // 每 N 步汇报一次
    val timeoutMinutes: Int = 30,    // 超时时间
    val allowDestructive: Boolean = false  // 是否允许破坏性操作
)

class AutonomousAgent(
    private val agentEngine: AgentEngine,
    private val config: AutonomousConfig
) {
    private val _state = MutableStateFlow<AutonomousState>(AutonomousState.Idle)
    val state: StateFlow<AutonomousState> = _state

    private var stepCount = 0
    private var totalCost = 0.0

    fun start() {
        _state.value = AutonomousState.Running(stepCount, config.goal)
        CoroutineScope(Dispatchers.Default).launch {
            val conversationId = createConversation("Auto: ${config.goal.take(50)}")

            // 初始目标
            agentEngine.processUserInput(config.goal, conversationId).collect { event ->
                when (event) {
                    is AgentEvent.StreamingText -> {
                        _state.value = AutonomousState.Running(stepCount, event.text)
                    }
                    is AgentEvent.ToolResult -> {
                        stepCount++
                        _state.value = AutonomousState.Running(stepCount, "Step $stepCount: ${event.content.take(100)}")

                        // 检查是否需要汇报
                        if (stepCount % config.requireConfirmationAt == 0) {
                            _state.value = AutonomousState.Checkpoint(stepCount, event.content)
                            // 等待用户确认继续
                        }

                        // 检查限制
                        if (stepCount >= config.maxSteps) {
                            _state.value = AutonomousState.Completed("达到最大步骤数 $stepCount")
                        }
                    }
                    is AgentEvent.ToolCancelled -> {
                        _state.value = AutonomousState.Paused("用户取消了操作")
                    }
                    else -> {}
                }
            }
        }
    }

    fun pause() { /* 暂停执行 */ }
    fun resume() { /* 恢复执行 */ }
    fun stop() { /* 终止执行 */ }
}

sealed class AutonomousState {
    data object Idle : AutonomousState()
    data class Running(val step: Int, val status: String) : AutonomousState()
    data class Checkpoint(val step: Int, val summary: String) : AutonomousState()
    data class Paused(val reason: String) : AutonomousState()
    data class Completed(val result: String) : AutonomousState()
}
```

### 自动化 UI

```
┌──────────────────────────────────────┐
│ 自动化                      [+ 创建] │
├──────────────────────────────────────┤
│                                      │
│ ⏰ 定时任务                          │
│  • 备份照片 — 每6小时               │
│  • 系统清理 — 每天 3:00             │
│  • 同步配置 — 每周日                │
│                                      │
│ 🔔 事件触发                          │
│  • USB插入 → 自动备份               │
│  • 新APK → 自动安装                 │
│  • 网络恢复 → 同步数据              │
│                                      │
│ 🤖 自主代理                          │
│  • "整理所有下载文件" — 运行中       │
│    步骤 3/20 · 上次: 分类了23个文件  │
│    [暂停] [停止] [查看]             │
│                                      │
│  • "分析存储空间" — 已完成           │
│    12步 · 识别了 2.3GB 可清理        │
│    [查看报告]                        │
└──────────────────────────────────────┘
```

---

## 数据备份与恢复

### 概述

AIShell 支持完整的本地备份/恢复，保护用户数据不丢失。

### 备份内容

| 类别 | 数据 | 大小估计 |
|---|---|---|
| 对话历史 | 所有 Conversation + Message + ToolCall | 1-50MB |
| 设置 | AI 配置、确认级别、主题等 | <1MB |
| 存储配置 | SFTP/SMB/WebDAV 连接信息 | <1KB |
| 工作流 | Cron 任务、事件规则、守护进程配置 | <1MB |
| API Keys | 加密的 API 密钥 | <1KB |
| 终端配置 | 快捷方式、历史命令、别名 | <1MB |
| Ubuntu rootfs | proot Ubuntu 文件系统 | 不备份（可重建） |

### 备份格式

ZIP 文件，结构：

```
aishell-backup-2026-04-25.zip
├── manifest.json           # 版本、时间戳、校验和
├── database/               # Room 数据库文件
│   └── aishell.db
├── preferences/            # DataStore 偏好
│   └── settings.pb
├── keys/                   # 加密的 API 密钥
│   ├── openai.enc
│   ├── deepseek.enc
│   └── ...
├── storage/                # 远程存储配置
│   └── connections.json
├── automation/             # 自动化配置
│   ├── cron.json
│   ├── rules.json
│   └── daemons.json
└── terminal/               # 终端配置
    ├── shortcuts.json
    └── history.json
```

### 备份/恢复 API

```kotlin
class BackupManager(private val context: Context) {
    suspend fun createBackup(outputPath: String): Result<String> = withContext(Dispatchers.IO) {
        val zipFile = File(outputPath)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zip ->
            // 1. 数据库
            addFileToZip(zip, "database/aishell.db", getDatabaseFile())

            // 2. 偏好设置
            addFileToZip(zip, "preferences/settings.pb", getPreferencesFile())

            // 3. 加密密钥
            getKeysDir().listFiles()?.forEach { keyFile ->
                addFileToZip(zip, "keys/${keyFile.name}", keyFile)
            }

            // 4. 配置文件
            val configs = BackupConfigs(
                storageConnections = loadStorageConnections(),
                cronJobs = loadCronJobs(),
                automationRules = loadAutomationRules(),
                daemonConfigs = loadDaemonConfigs(),
                shortcuts = loadShortcuts(),
                providerConfigs = loadProviderConfigs()  // 不含 API Key
            )
            addJsonToZip(zip, "config.json", configs)

            // 5. Manifest
            val manifest = BackupManifest(
                version = BuildConfig.VERSION_CODE,
                timestamp = System.currentTimeMillis(),
                checksum = calculateChecksum(zipFile)
            )
            addJsonToZip(zip, "manifest.json", manifest)
        }
        Result.success(zipFile.absolutePath)
    }

    suspend fun restoreBackup(backupPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        // 1. 验证 manifest
        // 2. 关闭数据库
        // 3. 解压覆盖文件
        // 4. 重新打开数据库
        // 5. 重载配置
        Result.success(Unit)
    }
}

data class BackupManifest(
    val version: Int,
    val timestamp: Long,
    val checksum: String
)
```

### 备份 UI

```
┌──────────────────────────────────────┐
│ 备份与恢复                           │
├──────────────────────────────────────┤
│                                      │
│ 📦 创建备份                         │
│    [选择位置: /sdcard/AIShell_backup] │
│    [创建备份]                       │
│                                      │
│ 📂 恢复备份                         │
│    [选择备份文件]                    │
│                                      │
│ 最近备份:                            │
│  • 2026-04-25 14:30 — 12.3 MB       │
│  • 2026-04-22 09:00 — 11.8 MB       │
│  • 2026-04-18 18:15 — 10.5 MB       │
│                                      │
│ ⚠️ 恢复将覆盖当前所有数据           │
└──────────────────────────────────────┘
```

### AI 备份工具

v3: 使用 sealed class ToolParams：

```kotlin
// backup 工具使用 ToolParams.BackupAction
data class BackupAction(
    val action: String,     // "create" | "restore" | "list"
    val path: String? = null
) : ToolParams()
```

---

## 品牌与视觉设计

### 品牌定位

**AIShell** — 暗黑科技风的 AI 终端。视觉语言：深色底、霓虹绿/青色点缀、等宽字体、网格线、扫描线效果。传达"黑客终端 + AI 增强"的感觉。

### 色彩系统

```
主色 (Primary):       #00FF88  霓虹绿 — 命令文本、活跃状态
辅助色 (Secondary):   #00D4FF  青蓝色 — AI 回复、链接、提示
警告色 (Warning):     #FFB800  琥珀色 — 确认提示、MODIFY 操作
危险色 (Error):       #FF3366  霓虹红 — DESTRUCTIVE 操作、错误
背景色 (Background):  #0A0E14  深黑蓝 — 主背景
表面色 (Surface):     #141A22  暗灰蓝 — 卡片、面板
文本色 (Text):        #E6E6E6  浅灰 — 普通文本
暗文本 (Muted):       #5C6773  灰色 — 输出、注释
```

### 字体

| 用途 | 字体 | 备选 |
|---|---|---|
| 终端内容 | JetBrains Mono | Fira Code, Source Code Pro |
| UI 文本 | Inter | Roboto, Noto Sans |
| 品牌标题 | Orbitron | Rajdhani |

### 图标

应用图标：深黑底 + 霓虹绿的终端提示符 `>_` + AI 闪光效果

```
┌──────────┐
│          │
│   >_✦    │   ← 霓虹绿 >_ + 青蓝色 ✦ (AI 星光)
│          │
└──────────┘
  深黑背景
```

### 启动页

```
┌──────────────────────────────────────┐
│                                      │
│                                      │
│         >_ AIShell                   │  ← 霓虹绿，逐字打出动画
│                                      │
│         AI-Powered Terminal          │  ← 青蓝色，淡入
│                                      │
│         ▓▓▓▓▓▓▓░░░░░               │  ← 加载进度条，霓虹绿
│                                      │
│                                      │
└──────────────────────────────────────┘
          深黑背景 + 微弱网格线
```

### 终端视觉效果

| 效果 | 实现 | 场景 |
|---|---|---|
| 扫描线 | Canvas 叠加半透明横线 | 始终，营造 CRT 感 |
| 光标闪烁 | 200ms 间隔切换显示/隐藏 | 输入等待时 |
| 命令高亮 | 霓虹绿 + 微弱发光(Shadow) | 命令文本 |
| 错误脉动 | 红色背景淡入淡出 | 命令失败时 |
| AI 流式光标 | 青蓝色方块 ▌ | AI 正在输出时 |
| 确认呼吸 | 琥珀色边框呼吸动画 | 等待确认时 |

---

## 用户体验细节

### 终端文本选择与复制

**核心交互：拖动选择 → 松手自动复制到剪贴板**

```kotlin
@Composable
fun TerminalTextSelection(
    text: String,
    onCopy: (String) -> Unit
) {
    var selectionStart by remember { mutableStateOf<Int?>(null) }
    var selectionEnd by remember { mutableStateOf<Int?>(null) }
    var isSelecting by remember { mutableStateOf(false) }

    // 自定义 SelectionContainer
    SelectionContainer(
        text = text,
        onSelectionChange = { start, end ->
            selectionStart = start
            selectionEnd = end
            isSelecting = true
        },
        onSelectionEnd = {
            // 松手时自动复制选中内容
            if (selectionStart != null && selectionEnd != null) {
                val selectedText = text.substring(selectionStart!!, selectionEnd!!)
                onCopy(selectedText)
            }
            isSelecting = false
        }
    )
}
```

**交互细节：**

| 操作 | 行为 |
|---|---|
| 长按 | 进入选择模式，显示光标手柄 |
| 拖动 | 选择文本范围，高亮显示（霓虹绿半透明背景） |
| 松手 | 自动复制到剪贴板 + 底部 Toast "已复制" |
| 双击 | 选中整行 |
| 三击 | 选中全部输出 |
| 长按已有选区 | 弹出菜单：复制/分享/执行选中文本 |

### Toast 反馈

```kotlin
// 复制反馈 — 底部微弱发光条
AnimatedVisibility(visible = showCopyToast) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(Color(0x3300FF88), RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text("已复制到剪贴板", color = Color(0xFF00FF88), fontSize = 13.sp)
    }
}
```

### 命令执行反馈

```
命令执行中:
  $ find /sdcard -name "*.jpg"▌          ← 霓虹绿 + 闪烁光标
  ⏳ 正在执行...                          ← 灰色旋转指示器

命令成功:
  $ find /sdcard -name "*.jpg"           ← 霓虹绿
  ✓ 0.3s                                  ← 绿色对勾 + 耗时

命令失败:
  $ find /sdcard -name "*.jpg"           ← 霓虹绿
  ✗ 退出码 1 (0.1s)                       ← 红色叉 + 耗时
  find: '/sdcard/Android': Permission denied  ← 红色错误输出
```

### 滑动手势

| 手势 | 行为 |
|---|---|
| 左滑对话消息 | 删除该消息 |
| 右滑命令输出 | 复制命令 |
| 底部上滑 | 展开命令历史面板 |
| 双指缩放 | 调整终端字体大小 |

### 无障碍

| 功能 | 实现 |
|---|---|
| 字体大小 | 设置中可调 12-24sp |
| 高对比度模式 | 切换为白底黑字 |
| TalkBack | Compose 语义化标记 |
| 键盘导航 | 外接键盘 Tab/Enter 导航 |
| 减弱动画 | 尊重系统"减弱动画"设置 |

### 性能优化

| 场景 | 优化策略 |
|---|---|
| 终端渲染 | Rust PTY + ANSI Parser → DirectByteBuffer → Canvas 差分绘制，60fps |
| 长输出 | Rust 侧 scrollback buffer，仅渲染可见行，差分更新 |
| 大量历史 | 分页加载，保留最近 5000 行在 Rust 侧内存 |
| 后台任务 | WorkManager 调度，前台服务保活 tmux 会话 |
| AI 流式 | 背压控制 Channel.BUFFERED，Compose 状态差分更新 |
| proot | 持久 helper 进程避免冷启动，首次安装后缓存 |
| USB | 三层架构优先 UsbManager API，libusb 仅在高级场景使用 |
| 数据库 | Room WAL 模式，并发读写无阻塞 |

---

## Android 设备刷机模式 — Recovery / Fastboot / EDL

AIShell 面向 Android 终端用户，设备刷机是核心刚需。三种刷机模式覆盖从日常维护到救砖的全场景。

> **完整设计详见 `architecture.md` "Android 设备刷机模式" 章节（第717-2188行）**，包含 Rust 协议实现、Kotlin 管理器、刷机安全策略、JNI Bridge 等。本节为概要。

### 三种模式概要

| 模式 | 通信协议 | USB Layer | 用途 |
|---|---|---|---|
| Recovery | ADB (sideload) | Layer 1 | OTA 刷入、日志拉取、文件推送 |
| Fastboot | Fastboot protocol | Layer 2 | 分区刷写、临时启动、解锁 Bootloader |
| EDL (Qualcomm 9008) | Sahara + Firehose | Layer 2/3 | 救砖刷机、分区备份、GPT 读取 |

### 模式切换

```
ADB ──→ Recovery:      adb reboot recovery
ADB ──→ Fastboot:      adb reboot bootloader
ADB ──→ EDL:           adb reboot edl (Qualcomm)
Fastboot ──→ Recovery: fastboot reboot recovery
Fastboot ──→ EDL:      fastboot reboot edl
Fastboot ──→ System:   fastboot reboot
Recovery ──→ System:   adb reboot
```

### 统一管理器 — FlashDeviceManager

自动检测设备模式（EDL > Fastboot > Recovery > ADB），选择正确的协议执行操作。所有刷机操作必须经过 `FlashSafetyGuard` 安全检查：

- 分区风险分级（SAFE / HIGH / BRICK / UNKNOWN）
- 刷机前检查清单（电量、校验和、USB 稳定性）
- 用户确认（EXPERT / WARNING / BLOCKED 三级）

### 内置 Binaries

```
app/src/main/assets/bin/
├── adb              # arm64 adb (8MB)
├── fastboot         # arm64 fastboot (5MB)
└── proot            # arm64 proot (1.5MB)

app/src/main/assets/edl/programmers/
├── sahara_generic.mbn
├── prog_emmc_firehose_8998.mbn    # Snapdragon 835
├── prog_emmc_firehose_Sdm660.mbn  # Snapdragon 660
└── prog_ufs_firehose_sm8150.mbn   # Snapdragon 855
```

### 刷机工具（13个新增 ToolParams）

| Tool | ToolParams | Risk Level |
|---|---|---|
| `recovery_sideload` | `RecoverySideload` | DESTRUCTIVE |
| `recovery_log` | `RecoveryLog` | READ_ONLY |
| `fastboot_flash` | `FastbootFlash` | DESTRUCTIVE |
| `fastboot_boot` | `FastbootBoot` | MODIFY |
| `fastboot_unlock` | `FastbootUnlock` | DESTRUCTIVE |
| `fastboot_info` | `FastbootInfo` | READ_ONLY |
| `fastboot_erase` | `FastbootErase` | DESTRUCTIVE |
| `fastboot_set_slot` | `FastbootSetSlot` | MODIFY |
| `edl_flash` | `EdlFlash` | DESTRUCTIVE |
| `edl_backup` | `EdlBackup` | READ_ONLY |
| `edl_gpt` | `EdlGpt` | READ_ONLY |
| `device_mode` | `DeviceModeDetect` | READ_ONLY |
| `device_switch` | `DeviceModeSwitch` | MODIFY |

---

## Bluetooth 协议栈 — BLE / Classic / SPP

AIShell 支持 BLE 和 Classic Bluetooth，覆盖设备发现、SPP 串口通信、GATT 读写/通知等场景。

> **完整设计详见 `architecture.md` "Bluetooth 协议栈" 章节**，包含 Rust btleplug GATT 客户端、SPP RFCOMM、JNI Bridge 等。本节为概要。

### 双层架构

| 层 | BLE | Classic/SPP |
|---|---|---|
| Rust 原生 | btleplug GATT 客户端（扫描/连接/读写/通知） | — |
| Android API | — | BluetoothAdapter（设备发现/配对） |
| 桥接方式 | JNI | BluetoothSocket fd |

### BLE 功能

- **设备扫描**: btleplug Central.scan()，支持 UUID 过滤
- **GATT 连接**: 连接 → 发现服务 → 读写特征 → 订阅通知
- **特征操作**: Read / Write(WithResponse/WithoutResponse) / Notify / Indicate
- **通知流**: 订阅后通过 `Channel<ByteArray>` 实时推送

### Classic SPP

- **RFCOMM 串口**: 标准 SPP UUID (00001101-...)，通过 BluetoothSocket I/O 流
- **设备配对**: Android BluetoothAdapter.createBond()
- **数据传输**: 异步读写，支持 Flow<ByteArray> 数据流

### Bluetooth 工具（9个新增 ToolParams）

| Tool | ToolParams | Risk Level |
|---|---|---|
| `ble_scan` | `BleScan` | READ_ONLY |
| `classic_scan` | `ClassicScan` | READ_ONLY |
| `ble_connect` | `BleConnect` | MODIFY |
| `ble_read` | `BleRead` | READ_ONLY |
| `ble_write` | `BleWrite` | MODIFY |
| `ble_notify` | `BleNotify` | MODIFY |
| `spp_connect` | `SppConnect` | MODIFY |
| `spp_send` | `SppSend` | MODIFY |
| `spp_receive` | `SppReceive` | READ_ONLY |

### Android 权限

```
BLUETOOTH / BLUETOOTH_ADMIN (Android 11-)
BLUETOOTH_SCAN / BLUETOOTH_CONNECT / BLUETOOTH_ADVERTISE (Android 12+)
ACCESS_FINE_LOCATION (BLE 扫描, Android 11-)
```
