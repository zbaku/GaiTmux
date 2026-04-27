package com.aishell.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aishell.domain.entity.ConfirmationLevel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
            // AI Model Section
            SettingsSection(title = "AI 模型") {
                SettingsItem(
                    title = "当前模型",
                    subtitle = "${settings.currentProvider} / ${settings.currentModel}",
                    onClick = { /* Show model selector */ }
                )
                SettingsItem(
                    title = "API Key",
                    subtitle = if (settings.apiKey.isNotEmpty()) "****${settings.apiKey.takeLast(4)}" else "未设置",
                    onClick = { viewModel.showApiKeyEditor() }
                )
                SettingsItem(
                    title = "API 地址",
                    subtitle = settings.baseUrl,
                    onClick = { /* Show base URL editor */ }
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
                    subtitle = when (settings.confirmationLevel) {
                        ConfirmationLevel.LENIENT -> "宽松 (仅危险操作)"
                        ConfirmationLevel.NORMAL -> "正常 (修改+危险)"
                        ConfirmationLevel.STRICT -> "严格 (所有操作)"
                    },
                    onClick = { /* Show confirmation level selector */ }
                )
                SettingsSwitch(
                    title = "自动补全",
                    subtitle = "输入时自动补全命令",
                    checked = settings.autoComplete,
                    onCheckedChange = { viewModel.setAutoComplete(it) }
                )
                SettingsSwitch(
                    title = "语法高亮",
                    subtitle = "命令语法着色",
                    checked = settings.syntaxHighlight,
                    onCheckedChange = { viewModel.setSyntaxHighlight(it) }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // About Section
            SettingsSection(title = "关于") {
                SettingsItem(
                    title = "版本",
                    subtitle = "1.0.0",
                    onClick = { }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
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
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
fun SettingsSwitch(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    )
}
