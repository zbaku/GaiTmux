package com.aishell.feature.terminal

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aishell.feature.terminal.TerminalCanvas

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel = hiltViewModel(),
    onOpenAi: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val sessions by viewModel.sessions.collectAsState()
    val currentSessionId by viewModel.currentSessionId.collectAsState()
    val currentSession = sessions.find { it.id == currentSessionId }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("AIShell") },
                    actions = {
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                    }
                )
                SessionTabRow(
                    sessions = sessions,
                    currentSessionId = currentSessionId,
                    onSessionSelect = { viewModel.selectSession(it) },
                    onSessionAdd = { viewModel.addSession() },
                    onSessionClose = { viewModel.closeSession(it) },
                    onSessionRename = { id, title -> viewModel.renameSession(id, title) }
                )
            }
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
        } ?: run {
            // No session available
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
fun AiFab(onClick: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(Icons.Default.SmartToy, contentDescription = "AI") },
        text = { Text("AI") },
        containerColor = MaterialTheme.colorScheme.primary
    )
}
