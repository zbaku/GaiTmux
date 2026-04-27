package com.aishell.feature.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aishell.terminal.TerminalSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class SessionInfo(
    val id: Long,
    val title: String,
    val terminalSession: TerminalSession
)

@HiltViewModel
class TerminalViewModel @Inject constructor() : ViewModel() {

    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions.asStateFlow()

    private val _currentSessionId = MutableStateFlow<Long?>(null)
    val currentSessionId: StateFlow<Long?> = _currentSessionId.asStateFlow()

    private var nextId = 1L

    init {
        // Create initial session
        addSession()
    }

    fun addSession() {
        val id = nextId++
        val title = "终端 ${_sessions.value.size + 1}"
        val terminalSession = TerminalSession()

        terminalSession.start()

        _sessions.update { it + SessionInfo(id, title, terminalSession) }
        _currentSessionId.value = id
    }

    fun selectSession(sessionId: Long) {
        _currentSessionId.value = sessionId
    }

    fun closeSession(sessionId: Long) {
        val session = _sessions.value.find { it.id == sessionId }
        session?.terminalSession?.destroy()

        _sessions.update { sessions ->
            sessions.filter { it.id != sessionId }
        }

        // If we closed the current session, switch to another
        if (_currentSessionId.value == sessionId) {
            _currentSessionId.value = _sessions.value.firstOrNull()?.id
        }

        // If no sessions left, create a new one
        if (_sessions.value.isEmpty()) {
            addSession()
        }
    }

    fun renameSession(sessionId: Long, newTitle: String) {
        _sessions.update { sessions ->
            sessions.map {
                if (it.id == sessionId) it.copy(title = newTitle) else it
            }
        }
    }

    fun writeToSession(sessionId: Long, text: String) {
        _sessions.value.find { it.id == sessionId }?.terminalSession?.write(text)
    }

    override fun onCleared() {
        super.onCleared()
        _sessions.value.forEach { it.terminalSession.destroy() }
    }
}
