# Kotlin Shell Intelligence + ToolParams 生成器

> Created: 2026-04-25
> Part of: aishell-implementation-v4 (Task 21-24, ToolParams Generator)

## Task 21-22: Kotlin 高亮渲染 + 补全 UI

### 模块结构

```
core/terminal/src/main/kotlin/com/aishell/terminal/
├── highlight/
│   ├── HighlightNative.kt       # JNI 声明
│   ├── HighlightRenderer.kt     # Canvas 高亮渲染
│   └── HighlightColors.kt      # 颜色定义
├── complete/
│   ├── CompletionNative.kt      # JNI 声明
│   ├── CompletionPopup.kt       # 补全弹窗 UI
│   ├── CompletionViewModel.kt   # 补全状态管理
│   └── CompletionItem.kt        # 补全项模型
└── explain/
    ├── ExplainService.kt        # AI 解释服务
    ├── ExplainPopup.kt          # 解释弹窗 UI
    └── ExplainViewModel.kt      # 解释状态管理
```

---

### Step 1: HighlightNative (JNI 声明)

```kotlin
// highlight/HighlightNative.kt
package com.aishell.terminal.highlight

import kotlinx.serialization.Serializable

@Serializable
enum class TokenType {
    COMMAND, OPTION, PATH, STRING, PIPE, VARIABLE, COMMENT
}

@Serializable
data class ShellToken(
    val type: TokenType,
    val value: String,
    val start: Int,
    val end: Int
)

@Serializable
data class HighlightedLine(
    val tokens: List<ShellToken>
)

object HighlightNative {
    init {
        System.loadLibrary("aishell-native")
    }

    // 对一行输入进行词法分析，返回 token 列表
    external fun tokenize(input: String): String  // JSON string

    // Kotlin 侧解析
    fun tokenizeLine(input: String): HighlightedLine {
        val json = tokenize(input)
        return kotlinx.serialization.json.Json.decodeFromString<HighlightedLine>(json)
    }
}
```

---

### Step 2: HighlightRenderer (Canvas 渲染)

```kotlin
// highlight/HighlightRenderer.kt
package com.aishell.terminal.highlight

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

object HighlightRenderer {

    // 将 token 列表转为 AnnotatedString
    fun render(line: String, tokens: List<ShellToken>): androidx.compose.ui.text.AnnotatedString {
        if (tokens.isEmpty()) return buildAnnotatedString { append(line) }

        return buildAnnotatedString {
            var lastEnd = 0
            for (token in tokens) {
                // 填充 token 之前的空白
                if (token.start > lastEnd) {
                    append(line.substring(lastEnd, token.start))
                }

                // 渲染 token
                withStyle(SpanStyle(color = getColor(token.type))) {
                    append(token.value)
                }

                lastEnd = token.end
            }

            // 填充最后一个 token 之后的内容
            if (lastEnd < line.length) {
                append(line.substring(lastEnd))
            }
        }
    }

    private fun getColor(type: TokenType): Color = when (type) {
        TokenType.COMMAND  -> HighlightColors.Command
        TokenType.OPTION   -> HighlightColors.Option
        TokenType.PATH     -> HighlightColors.Path
        TokenType.STRING   -> HighlightColors.String
        TokenType.PIPE     -> HighlightColors.Pipe
        TokenType.VARIABLE -> HighlightColors.Variable
        TokenType.COMMENT  -> HighlightColors.Comment
    }
}
```

---

### Step 3: HighlightColors (颜色定义)

```kotlin
// highlight/HighlightColors.kt
package com.aishell.terminal.highlight

import androidx.compose.ui.graphics.Color

object HighlightColors {
    val Command   = Color(0xFF00E676)  // 绿色
    val Option    = Color(0xFF64B5F6)  // 蓝色
    val Path      = Color(0xFF26C6DA)  // 青色
    val String    = Color(0xFFFFD54F)  // 黄色
    val Pipe      = Color(0xFF6E7681)  // 灰色
    val Variable  = Color(0xFFCE93D8)  // 紫色
    val Comment   = Color(0xFF6E7681)  // 暗灰
    val Foreground = Color(0xFFE6EDF3) // 默认前景
    val Background = Color(0xFF0D1117) // 背景
    val Cursor    = Color(0xFF00E676)  // 光标
}
```

---

### Step 4: CompletionNative (JNI 声明)

```kotlin
// complete/CompletionNative.kt
package com.aishell.terminal.complete

import kotlinx.serialization.Serializable

@Serializable
enum class CompletionType {
    COMMAND, FILE, DIRECTORY, OPTION, HISTORY
}

@Serializable
data class CompletionItem(
    val text: String,
    val display: String,
    val description: String,
    val type: CompletionType
)

object CompletionNative {
    init {
        System.loadLibrary("aishell-native")
    }

    // 获取补全列表
    external fun complete(input: String, cursor: Int): String  // JSON

    fun getCompletions(input: String, cursor: Int): List<CompletionItem> {
        val json = complete(input, cursor)
        return kotlinx.serialization.json.Json.decodeFromString<List<CompletionItem>>(json)
    }
}
```

---

### Step 5: CompletionPopup (补全弹窗 UI)

```kotlin
// complete/CompletionPopup.kt
package com.aishell.terminal.complete

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CompletionPopup(
    items: List<CompletionItem>,
    selectedIndex: Int,
    onSelect: (CompletionItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.width(IntrinsicSize.Max),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 8.dp,
        tonalElevation = 4.dp
    ) {
        LazyColumn(
            modifier = Modifier.sizeIn(maxHeight = 200.dp)
        ) {
            itemsIndexed(items) { index, item ->
                CompletionItemRow(
                    item = item,
                    isSelected = index == selectedIndex,
                    onClick = { onSelect(item) }
                )
            }
        }
    }
}

@Composable
fun CompletionItemRow(
    item: CompletionItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // 图标 + 名称
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 类型图标
            Text(
                text = when (item.type) {
                    CompletionType.COMMAND -> "$"
                    CompletionType.FILE -> "#"
                    CompletionType.DIRECTORY -> "/"
                    CompletionType.OPTION -> "-"
                    CompletionType.HISTORY -> "^"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace
            )

            // 补全文本
            Text(
                text = item.display,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }

        // 描述
        Text(
            text = item.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
```

---

### Step 6: CompletionViewModel (补全状态管理)

```kotlin
// complete/CompletionViewModel.kt
package com.aishell.terminal.complete

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class CompletionViewModel @Inject constructor() {

    var items by mutableStateOf<List<CompletionItem>>(emptyList())
        private set

    var selectedIndex by mutableStateOf(0)
        private set

    var isVisible by mutableStateOf(false)
        private set

    // 输入变化时更新补全列表
    suspend fun onInputChanged(input: String, cursor: Int) {
        withContext(Dispatchers.IO) {
            val completions = CompletionNative.getCompletions(input, cursor)
            items = completions
            selectedIndex = 0
            isVisible = completions.isNotEmpty()
        }
    }

    // Tab 键：选择下一个
    fun selectNext() {
        if (items.isNotEmpty()) {
            selectedIndex = (selectedIndex + 1) % items.size
        }
    }

    // Shift+Tab：选择上一个
    fun selectPrevious() {
        if (items.isNotEmpty()) {
            selectedIndex = (selectedIndex - 1 + items.size) % items.size
        }
    }

    // 确认选择
    fun confirm(): CompletionItem? {
        return items.getOrNull(selectedIndex)?.also {
            isVisible = false
        }
    }

    // 取消
    fun cancel() {
        isVisible = false
        items = emptyList()
    }
}
```

---

## Task 23-24: AI 命令解释

### Step 7: ExplainService (AI 解释服务)

```kotlin
// explain/ExplainService.kt
package com.aishell.terminal.explain

import com.aishell.domain.entity.Message
import com.aishell.domain.entity.MessageRole
import com.aishell.domain.service.AiChunk
import com.aishell.domain.service.AiConfig
import com.aishell.domain.service.AiProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ExplainService @Inject constructor(
    private val provider: AiProvider,
    private val config: AiConfig
) {
    // 解释命令的 system prompt
    private val systemPrompt = """
        你是一个 Linux 命令解释助手。用户会输入一个命令，你需要：
        1. 用简洁的中文解释命令的作用
        2. 如果有风险，明确警告
        3. 解释每个参数的含义
        4. 如果有更安全的替代方案，提供建议

        回复格式：
        📋 命令解释：[一句话总结]
        ⚠️ 风险评估：[无/低/中/高] + [说明]
        📖 参数详解：[逐个参数解释]
    """.trimIndent()

    // 流式解释命令
    fun explain(command: String): Flow<String> {
        val messages = listOf(
            Message(
                conversationId = 0,
                role = MessageRole.SYSTEM,
                content = systemPrompt
            ),
            Message(
                conversationId = 0,
                role = MessageRole.USER,
                content = command
            )
        )

        return provider.chatStream(config, messages, emptyList())
            .map { chunk ->
                if (chunk.isDone) ""
                else if (chunk.error != null) "[错误] ${chunk.error}"
                else chunk.content
            }
    }
}
```

---

### Step 8: ExplainPopup (解释弹窗 UI)

```kotlin
// explain/ExplainPopup.kt
package com.aishell.terminal.explain

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun ExplainPopup(
    command: String,
    explanation: String,
    isStreaming: Boolean,
    onDismiss: () -> Unit,
    onCopyCommand: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 命令行
            Text(
                text = "$ $command",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // AI 解释（流式输出）
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (isStreaming) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    repeat(3) { i ->
                        Surface(
                            modifier = Modifier.size(6.dp),
                            shape = RoundedCornerShape(3.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        ) {}
                    }
                }
            }

            // 操作按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                TextButton(onClick = onCopyCommand) {
                    Text("复制命令")
                }

                Spacer(modifier = Modifier.width(8.dp))

                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        }
    }
}
```

---

### Step 9: ExplainViewModel (解释状态管理)

```kotlin
// explain/ExplainViewModel.kt
package com.aishell.terminal.explain

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import javax.inject.Inject

class ExplainViewModel @Inject constructor(
    private val explainService: ExplainService
) {
    var explanation by mutableStateOf("")
        private set

    var isStreaming by mutableStateOf(false)
        private set

    var isVisible by mutableStateOf(false)
        private set

    var currentCommand by mutableStateOf("")
        private set

    private var explainJob: Job? = null

    // 触发解释（用户按 ? 键）
    fun explain(command: String) {
        if (command.isBlank()) return

        currentCommand = command
        explanation = ""
        isStreaming = true
        isVisible = true

        explainJob?.cancel()
        explainJob = CoroutineScope(Dispatchers.Main).launch {
            explainService.explain(command).collect { chunk ->
                explanation += chunk
            }
            isStreaming = false
        }
    }

    // 关闭
    fun dismiss() {
        explainJob?.cancel()
        isVisible = false
        isStreaming = false
        explanation = ""
    }

    // 复制命令
    fun copyCommand(): String = currentCommand
}
```

---

## ToolParams 生成器

### 问题

Agent Engine 和 AI Provider 之间，AI 返回的 tool_call 参数是 JSON，需要转换为 `ToolParams` 的具体子类型。

### 解决方案：ToolParamsSerializer

```kotlin
// domain/tool/ToolParamsSerializer.kt
package com.aishell.domain.tool

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolParamsSerializer @Inject constructor() {

    // 从 AI 返回的 JSON + tool 名称，反序列化为 ToolParams
    fun deserialize(toolName: String, jsonParams: String): ToolParams {
        val json = Json.parseToJsonElement(jsonParams).jsonObject

        return when (toolName) {
            "shell_exec" -> ToolParams.ShellExec(
                command = json["command"]?.jsonPrimitive?.content ?: "",
                timeout = json["timeout"]?.jsonPrimitive?.longOrNull ?: 30000,
                workingDir = json["working_dir"]?.jsonPrimitive?.contentOrNull
            )

            "file_read" -> ToolParams.FileRead(
                path = json["path"]?.jsonPrimitive?.content ?: ""
            )

            "file_write" -> ToolParams.FileWrite(
                path = json["path"]?.jsonPrimitive?.content ?: "",
                content = json["content"]?.jsonPrimitive?.content ?: ""
            )

            "file_delete" -> ToolParams.FileDelete(
                path = json["path"]?.jsonPrimitive?.content ?: "",
                recursive = json["recursive"]?.jsonPrimitive?.booleanOrNull ?: false
            )

            "adb_exec" -> ToolParams.AdbExec(
                command = json["command"]?.jsonPrimitive?.content ?: "",
                deviceId = json["device_id"]?.jsonPrimitive?.contentOrNull
            )

            "adb_push" -> ToolParams.AdbPush(
                localPath = json["local_path"]?.jsonPrimitive?.content ?: "",
                remotePath = json["remote_path"]?.jsonPrimitive?.content ?: "",
                deviceId = json["device_id"]?.jsonPrimitive?.contentOrNull
            )

            "fastboot_flash" -> ToolParams.FastbootFlash(
                partition = json["partition"]?.jsonPrimitive?.content ?: "",
                imagePath = json["image_path"]?.jsonPrimitive?.content ?: ""
            )

            "ssh_exec" -> ToolParams.SshExec(
                host = json["host"]?.jsonPrimitive?.content ?: "",
                command = json["command"]?.jsonPrimitive?.content ?: "",
                username = json["username"]?.jsonPrimitive?.content ?: "",
                port = json["port"]?.jsonPrimitive?.intOrNull ?: 22
            )

            "edl_flash" -> ToolParams.EdlFlash(
                partition = json["partition"]?.jsonPrimitive?.content ?: "",
                imagePath = json["image_path"]?.jsonPrimitive?.content ?: ""
            )

            else -> throw IllegalArgumentException("Unknown tool: $toolName")
        }
    }
}
```

---

### 生成 ToolSpec (给 AI 的工具描述)

```kotlin
// domain/tool/ToolSpecGenerator.kt
package com.aishell.domain.tool

import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolSpecGenerator @Inject constructor() {

    // 为每个 Tool 生成 OpenAI function calling 格式的 spec
    fun generateSpecs(tools: List<Tool>): List<ToolSpec> {
        return tools.map { tool ->
            ToolSpec(
                name = tool.name,
                description = tool.description,
                parameters = generateParams(tool.name)
            )
        }
    }

    private fun generateParams(toolName: String): Map<String, Any> {
        return when (toolName) {
            "shell_exec" -> mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "command" to mapOf("type" to "string", "description" to "Shell command to execute"),
                    "timeout" to mapOf("type" to "integer", "description" to "Timeout in ms", "default" to 30000),
                    "working_dir" to mapOf("type" to "string", "description" to "Working directory")
                ),
                "required" to listOf("command")
            )

            "file_read" -> mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string", "description" to "File path to read")
                ),
                "required" to listOf("path")
            )

            "file_write" -> mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string", "description" to "File path to write"),
                    "content" to mapOf("type" to "string", "description" to "Content to write")
                ),
                "required" to listOf("path", "content")
            )

            "file_delete" -> mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string", "description" to "File path to delete"),
                    "recursive" to mapOf("type" to "boolean", "description" to "Delete recursively", "default" to false)
                ),
                "required" to listOf("path")
            )

            "adb_exec" -> mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "command" to mapOf("type" to "string", "description" to "ADB command"),
                    "device_id" to mapOf("type" to "string", "description" to "Target device ID")
                ),
                "required" to listOf("command")
            )

            "fastboot_flash" -> mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "partition" to mapOf("type" to "string", "description" to "Partition name (boot/recovery/system/vendor)"),
                    "image_path" to mapOf("type" to "string", "description" to "Path to flash image file")
                ),
                "required" to listOf("partition", "image_path")
            )

            "ssh_exec" -> mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "host" to mapOf("type" to "string", "description" to "SSH host"),
                    "command" to mapOf("type" to "string", "description" to "Command to execute"),
                    "username" to mapOf("type" to "string", "description" to "SSH username"),
                    "port" to mapOf("type" to "integer", "description" to "SSH port", "default" to 22)
                ),
                "required" to listOf("host", "command", "username")
            )

            "edl_flash" -> mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "partition" to mapOf("type" to "string", "description" to "Partition name"),
                    "image_path" to mapOf("type" to "string", "description" to "Path to flash image")
                ),
                "required" to listOf("partition", "image_path")
            )

            else -> mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        }
    }
}
```

---

### 整合到 AgentEngine

```kotlin
// 修改后的 AgentEngine，使用 ToolParamsSerializer 和 ToolSpecGenerator

@Singleton
class AgentEngine @Inject constructor(
    private val confirmationGate: ConfirmationGate,
    private val toolRouter: ToolRouter,
    private val paramsSerializer: ToolParamsSerializer,
    private val specGenerator: ToolSpecGenerator
) {
    private val eventChannel = Channel<AgentEvent>(Channel.UNLIMITED)

    suspend fun execute(
        provider: AiProvider,
        config: AiConfig,
        messages: List<Message>,
        tools: Map<String, Tool>
    ): Channel<AgentEvent> {
        // 生成 ToolSpec（给 AI 的描述）
        val toolSpecs = specGenerator.generateSpecs(tools.values.toList())

        provider.chatStream(config, messages, toolSpecs)
            .collect { chunk ->
                when {
                    chunk.isDone -> eventChannel.send(AgentEvent.Completed)
                    chunk.error != null -> eventChannel.send(AgentEvent.Error(chunk.error))
                    chunk.toolName != null -> {
                        // 反序列化 AI 返回的参数为 ToolParams
                        val params = paramsSerializer.deserialize(
                            toolName = chunk.toolName,
                            jsonParams = chunk.rawToolParams ?: "{}"
                        )
                        handleToolCall(chunk.toolName, chunk.toolCallId!!, params, tools)
                    }
                    chunk.content.isNotEmpty() -> eventChannel.send(AgentEvent.TextDelta(chunk.content))
                }
            }

        return eventChannel
    }

    // ... 其余不变
}
```

---

## 变更历史

| 日期 | 版本 | 变更 |
|------|------|------|
| 2026-04-25 | v1.0 | Kotlin Shell 组件 + ToolParams 生成器 |
