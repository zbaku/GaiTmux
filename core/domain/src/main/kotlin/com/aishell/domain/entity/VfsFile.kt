package com.aishell.domain.entity

import kotlinx.serialization.Serializable

@Serializable
data class VfsFile(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long,
    val permissions: String = "",
    val mimeType: String? = null,
) {
    val extension: String
        get() = name.substringAfterLast('.', "")

    val isHidden: Boolean
        get() = name.startsWith('.')
}

fun VfsFile.directory(path: String, name: String) = VfsFile(
    path = path,
    name = name,
    isDirectory = true,
    size = 0,
    modifiedAt = System.currentTimeMillis()
)

fun VfsFile.file(path: String, name: String, size: Long) = VfsFile(
    path = path,
    name = name,
    isDirectory = false,
    size = size,
    modifiedAt = System.currentTimeMillis()
)