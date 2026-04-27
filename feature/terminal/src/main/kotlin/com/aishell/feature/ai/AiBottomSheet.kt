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
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.cancelCurrentJob()
            onDismiss()
        },
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .padding(horizontal = 16.dp)
        ) {
            // Title bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "AI 助手",
                    style = MaterialTheme.typography.titleMedium
                )
                if (messages.isNotEmpty()) {
                    TextButton(onClick = { viewModel.clearMessages() }) {
                        Text("清空")
                    }
                }
            }

            // Error message
            error?.let { errorMessage ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Text(
                        text = errorMessage,
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // Messages list
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
                isLoading = isLoading,
                onTextChange = { viewModel.setInputText(it) },
                onSend = { viewModel.sendMessage() },
                onVoiceStart = { viewModel.startListening() },
                onVoiceStop = { viewModel.stopListening() },
                modifier = Modifier.fillMaxWidth()
            )

            // Bottom padding for navigation bar
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
