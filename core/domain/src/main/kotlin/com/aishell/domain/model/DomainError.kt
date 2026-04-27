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