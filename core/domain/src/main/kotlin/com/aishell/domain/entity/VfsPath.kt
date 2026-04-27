package com.aishell.domain.entity

import kotlinx.serialization.Serializable

/**
 * Value class representing a Virtual File System path.
 * Parses scheme://host/path format into structured components.
 */
@Serializable
data class VfsPath(
    val scheme: VfsScheme,
    val host: String = "",
    val port: Int = scheme.defaultPort,
    val path: String = "/"
) {
    companion object {
        fun parse(raw: String): VfsPath {
            val schemeEnd = raw.indexOf("://")
            if (schemeEnd < 0) {
                // No scheme, treat as local file path
                return VfsPath(scheme = VfsScheme.FILE, path = raw)
            }
            val schemeStr = raw.substring(0, schemeEnd).uppercase()
            val scheme = try {
                VfsScheme.valueOf(schemeStr)
            } catch (_: IllegalArgumentException) {
                VfsScheme.FILE
            }
            val rest = raw.substring(schemeEnd + 3)
            val slashIdx = rest.indexOf('/')
            val hostPort = if (slashIdx >= 0) rest.substring(0, slashIdx) else rest
            val pathPart = if (slashIdx >= 0) rest.substring(slashIdx) else "/"

            val (host, port) = if (hostPort.contains(':')) {
                val parts = hostPort.split(':')
                parts[0] to (parts.getOrNull(1)?.toIntOrNull() ?: scheme.defaultPort)
            } else {
                hostPort to scheme.defaultPort
            }

            return VfsPath(scheme = scheme, host = host, port = port, path = pathPart)
        }
    }

    val raw: String
        get() = if (scheme == VfsScheme.FILE) path
        else "${scheme.name.lowercase()}://$host:$port$path"

    val fileName: String
        get() = path.substringAfterLast('/')

    val parentPath: String
        get() = path.substringBeforeLast('/').ifEmpty { "/" }
}

@Serializable
enum class VfsScheme(val defaultPort: Int) {
    FILE(0),
    SFTP(22),
    SMB(445),
    MTP(0)
}