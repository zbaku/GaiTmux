package com.aishell.engine

import com.aishell.domain.entity.ConfirmationLevel
import com.aishell.domain.entity.RiskLevel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfirmationGate @Inject constructor() {
    private val mutex = Mutex()
    private var level = ConfirmationLevel.NORMAL

    suspend fun setLevel(newLevel: ConfirmationLevel) = mutex.withLock {
        level = newLevel
    }

    fun getLevel(): ConfirmationLevel = level

    suspend fun requiresConfirmation(riskLevel: RiskLevel): Boolean = mutex.withLock {
        when (level) {
            ConfirmationLevel.LENIENT -> riskLevel >= RiskLevel.HIGH
            ConfirmationLevel.NORMAL -> riskLevel >= RiskLevel.MEDIUM
            ConfirmationLevel.STRICT -> true
        }
    }
}