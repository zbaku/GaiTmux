package com.aishell.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aishell.domain.engine.ConfirmationLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppSettings(
    val currentProvider: String = "openai",
    val currentModel: String = "gpt-4o",
    val apiKey: String = "",
    val baseUrl: String = "https://api.openai.com",
    val confirmationLevel: ConfirmationLevel = ConfirmationLevel.NORMAL,
    val autoComplete: Boolean = true,
    val syntaxHighlight: Boolean = true,
    val terminalTheme: String = "暗色",
    val fontSize: Int = 14
)

@HiltViewModel
class SettingsViewModel @Inject constructor() : ViewModel() {

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _editingApiKey = MutableStateFlow(false)
    val editingApiKey: StateFlow<Boolean> = _editingApiKey.asStateFlow()

    init {
        // Load settings from DataStore in real implementation
        // For now, use defaults
    }

    fun setCurrentProvider(provider: String) {
        _settings.update { it.copy(currentProvider = provider) }
        // Persist to DataStore
    }

    fun setCurrentModel(model: String) {
        _settings.update { it.copy(currentModel = model) }
        // Persist to DataStore
    }

    fun setApiKey(apiKey: String) {
        _settings.update { it.copy(apiKey = apiKey) }
        // Persist to DataStore
    }

    fun setBaseUrl(baseUrl: String) {
        _settings.update { it.copy(baseUrl = baseUrl) }
        // Persist to DataStore
    }

    fun setConfirmationLevel(level: ConfirmationLevel) {
        _settings.update { it.copy(confirmationLevel = level) }
        // Persist to DataStore
    }

    fun setAutoComplete(enabled: Boolean) {
        _settings.update { it.copy(autoComplete = enabled) }
        // Persist to DataStore
    }

    fun setSyntaxHighlight(enabled: Boolean) {
        _settings.update { it.copy(syntaxHighlight = enabled) }
        // Persist to DataStore
    }

    fun setTerminalTheme(theme: String) {
        _settings.update { it.copy(terminalTheme = theme) }
        // Persist to DataStore
    }

    fun setFontSize(size: Int) {
        _settings.update { it.copy(fontSize = size) }
        // Persist to DataStore
    }

    fun showApiKeyEditor() {
        _editingApiKey.value = true
    }

    fun hideApiKeyEditor() {
        _editingApiKey.value = false
    }
}
