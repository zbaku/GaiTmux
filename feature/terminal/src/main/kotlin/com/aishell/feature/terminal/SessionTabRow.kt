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
    onSessionClose: (Long) -> Unit,
    onSessionRename: (Long, String) -> Unit
) {
    val selectedIndex = sessions.indexOfFirst { it.id == currentSessionId }.coerceAtLeast(0)

    SecondaryTabRow(
        selectedTabIndex = selectedIndex,
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        sessions.forEachIndexed { index, session ->
            Tab(
                selected = index == selectedIndex,
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
                        if (sessions.size > 1) {
                            IconButton(
                                onClick = { onSessionClose(session.id) },
                                modifier = Modifier.size(18.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "关闭",
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                },
                modifier = Modifier.combinedClickable(
                    onClick = { onSessionSelect(session.id) },
                    onLongClick = { onSessionRename(session.id, session.title) }
                )
            )
        }

        Tab(
            selected = false,
            onClick = onSessionAdd,
            icon = {
                Icon(Icons.Default.Add, contentDescription = "新建会话")
            }
        )
    }
}

@Composable
fun RenameDialog(
    currentTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(currentTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名会话") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("会话名称") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank()
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
