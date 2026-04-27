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
    val isReady by viewModel.isReady.collectAsState()

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

        // Title
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

        if (isReady) {
            // Already installed
            Text(
                text = "✓ 环境已就绪",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(onClick = onInstallComplete) {
                Text("进入终端")
            }
        } else {
            // Progress bar
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Status text
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium
            )

            // Percentage
            if (progress > 0 && progress < 1) {
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Error handling
            error?.let { errorMsg ->
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "安装失败: $errorMsg",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(onClick = { viewModel.retry() }) {
                    Text("重试")
                }
            }

            // Start button when idle
            if (error == null && progress == 0f) {
                Spacer(modifier = Modifier.height(24.dp))

                Button(onClick = { viewModel.startInstallation() }) {
                    Text("安装 Ubuntu 环境")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "需要下载约 50MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // Auto-navigate when complete
    LaunchedEffect(isReady) {
        if (isReady && error == null) {
            onInstallComplete()
        }
    }
}
