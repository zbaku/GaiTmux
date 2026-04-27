package com.aishell.executor.di

import com.aishell.domain.service.RiskAssessor
import com.aishell.domain.tool.Tool
import com.aishell.executor.CommandRouter
import com.aishell.executor.ShellTool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ExecutorModule {

    @Provides
    @Singleton
    fun provideShellTool(): Tool = ShellTool()

    @Provides
    fun bindRiskAssessor(commandRouter: CommandRouter): RiskAssessor = commandRouter
}
