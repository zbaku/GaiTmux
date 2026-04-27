package com.aishell.terminal.highlight

enum class TokenType {
    COMMAND,
    OPTION,
    PATH,
    STRING,
    PIPE,
    VARIABLE,
    COMMENT
}

data class ShellToken(
    val type: TokenType,
    val value: String,
    val start: Int,
    val end: Int
)