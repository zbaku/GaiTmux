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
