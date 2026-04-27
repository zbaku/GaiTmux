package com.aishell.feature.install

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aishell.platform.proot.ProotManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class InstallState {
    object Idle : InstallState()
    object Downloading : InstallState()
    object Extracting : InstallState()
    object Initializing : InstallState()
    object Complete : InstallState()
    data class Error(val message: String) : InstallState()
}

@HiltViewModel
class InstallViewModel @Inject constructor(
    private val prootManager: ProotManager
) : ViewModel() {

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _status = MutableStateFlow("准备安装...")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    init {
        checkIfReady()
    }

    private fun checkIfReady() {
        viewModelScope.launch {
            _isReady.value = prootManager.isReady()
        }
    }

    fun startInstallation() {
        viewModelScope.launch {
            try {
                _error.value = null
                _status.value = "正在下载 Ubuntu rootfs..."

                prootManager.install { prog ->
                    _progress.value = prog
                    when {
                        prog < 0.5f -> _status.value = "正在下载... ${(prog * 200).toInt()}%"
                        prog < 0.9f -> _status.value = "正在解压... ${((prog - 0.5f) * 200).toInt()}%"
                        else -> _status.value = "安装完成"
                    }
                }

                _progress.value = 1f
                _status.value = "环境已就绪"
                _isReady.value = true
            } catch (e: Exception) {
                _error.value = e.message ?: "安装失败"
                _status.value = "安装失败"
            }
        }
    }

    fun retry() {
        startInstallation()
    }
}
