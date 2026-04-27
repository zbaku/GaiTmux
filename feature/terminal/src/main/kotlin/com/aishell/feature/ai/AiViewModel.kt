package com.aishell.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aishell.engine.AgentEngine
import com.aishell.engine.AgentEvent
import com.aishell.domain.entity.Message
import com.aishell.domain.entity.MessageRole
import com.aishell.domain.service.AiConfig
import com.aishell.domain.service.AiProvider
import com.aishell.domain.tool.ToolSpec
import com.aishell.engine.ToolRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AiViewModel @Inject constructor(
    private val agentEngine: AgentEngine,
    private val toolRegistry: ToolRegistry,
    private val providers: Set<@JvmSuppressWildcards AiProvider>
) : ViewModel() {

    private val _messages = MutableStateFlow<List<AiMessage>>(emptyList())
    val messages: StateFlow<List<AiMessage>> = _messages.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _currentProvider = MutableStateFlow("openai")
    private val _currentModel = MutableStateFlow("gpt-4o")
    private val _apiKey = MutableStateFlow("")
    private val _baseUrl = MutableStateFlow("https://api.openai.com")

    private var messageIdCounter = 0L
    private var currentJob: Job? = null

    fun setInputText(text: String) {
        _inputText.value = text
    }

    fun setAiConfig(providerId: String, model: String, apiKey: String, baseUrl: String) {
        _currentProvider.value = providerId
        _currentModel.value = model
        _apiKey.value = apiKey
        _baseUrl.value = baseUrl
    }

    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isBlank()) return

        val userMessage = AiMessage.User(
            id = messageIdCounter++,
            timestamp = System.currentTimeMillis(),
            content = text
        )
        _messages.update { it + userMessage }
        _inputText.value = ""

        streamAiResponse(text)
    }

    private fun streamAiResponse(userMessage: String) {
        _isLoading.value = true
        _error.value = null

        currentJob = viewModelScope.launch {
            try {
                val provider = providers.find { it.providerId == _currentProvider.value }
                    ?: throw IllegalStateException("Provider not found: ${_currentProvider.value}")

                val config = AiConfig(
                    providerId = _currentProvider.value,
                    modelId = _currentModel.value,
                    apiKey = _apiKey.value,
                    baseUrl = _baseUrl.value
                )

                val historyMessages = _messages.value.map { msg ->
                   Message(
                        id = 0,
                        conversationId = 0,
                        role = if (msg is AiMessage.Assistant) MessageRole.ASSISTANT else MessageRole.USER,
                        content = msg.content
                    )
                }

                val tools = toolRegistry.getAll().associateBy { it.name }

                var assistantMessageId: Long? = null
                var currentContent = StringBuilder()

                agentEngine.execute(provider, config, historyMessages, tools)
                    .collect { event ->
                        when (event) {
                            is AgentEvent.TextDelta -> {
                                if (assistantMessageId == null) {
                                    assistantMessageId = messageIdCounter++
                                    val assistantMsg = AiMessage.Assistant(
                                         id = assistantMessageId!!,
                                         timestamp = System.currentTimeMllis(),
                                         content = ""
                                    )
                                     _messages.update { it + assistantMsg }
                                }
                                currentContent.append(event.content)
                                _messages.update { msgs ->
                                       msgs.map { msg ->
                                        if (msg is AiMessage.Assistant && msg.id == assistantMessageId) {
                                            msg.copy(content = currentContent.toString())
                                        } else msg
                                     }
                                }
                            }
                            is AgentEvent.ToolCallResult -> {
                                _messages.update { msgs ->
                                    msgs + AiMessage.ToolResult(
                                         id = messageIdCounter++,
                                         timestamp = System.currentTimeMillis(),
                                         toolName = event.toolCallId,
                                         content = event.result.output.ifEmpty { event.result.error ?: "no output" }
                                    )
                                 }
                            }
                            is AgentEvent.Error -> {
                                _error.value = event.message
                            }
                            is AgentEvent.Completed -> {
                                _isLoading.value = false
                            }
                            is AgentEvent.ConfirmationRequired -> {
                                event.onConfirm()
                            }
                            is AgentEvent.IterationStart -> { /* progress info */ }
                            is AgentEvent.ToolCallRequested -> { /* tool call pending */ }
                        }
                    }
            } catch (e: Exception) {
                _error.value = e.message ?: "发生错误"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun startListening() {
        _isListening.value = true
    }

    fun stopListening() {
        _isListening.value = false
    }

    fun clearMessages() {
        _messages.value = emptyList()
    }

    fun cancelCurrentJob() {
        currentJob?.cancel()
        _isLoading.value = false
    }

    override fun onCleared() {
        super.onCleared()
        currentJob?.cancel()
    }
}
