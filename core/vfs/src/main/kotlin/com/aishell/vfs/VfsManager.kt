package com.aishell.vfs

import com.aishell.domain.entity.VfsPath
import com.aishell.domain.entity.VfsScheme
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VfsManager @Inject constructor(
    private val localProvider: LocalProvider,
    private val sftpProvider: SftpProvider
) {
    private val providers = mutableMapOf<VfsScheme, VfsProvider>()

    init {
        providers[VfsScheme.FILE] = localProvider
        providers[VfsScheme.SFTP] = sftpProvider
    }

    fun getProvider(scheme: VfsScheme): VfsProvider? = providers[scheme]

    suspend fun getOrCreateProvider(config: VfsConfig): VfsProvider? {
        return when (config.scheme) {
            VfsScheme.FILE -> localProvider
            VfsScheme.SFTP -> {
                if (!sftpProvider.isConnected()) {
                    sftpProvider.connect(config)
                }
                sftpProvider
            }
            VfsScheme.MTP -> {
                providers.getOrPut(VfsScheme.MTP) {
                    MtpProvider()
                }
            }
            VfsScheme.SMB -> {
                providers.getOrPut(VfsScheme.SMB) {
                    SmbProvider()
                }
            }
        }
    }

    fun parsePath(path: String): Pair<VfsProvider?, VfsPath> {
        val vfsPath = VfsPath.parse(path)
        val provider = providers[vfsPath.scheme]
        return provider to vfsPath
    }
}