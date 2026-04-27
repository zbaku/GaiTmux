package com.aishell.ai.di

import com.aishell.ai.provider.ClaudeProvider
import com.aishell.ai.provider.MiniMaxProvider
import com.aishell.ai.provider.OpenAiCompatibleProvider
import com.aishell.domain.service.AiProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AiModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @IntoSet
    fun provideOpenAiProvider(client: OkHttpClient): AiProvider =
        OpenAiCompatibleProvider(
            providerId = "openai",
            displayName = "OpenAI",
            baseUrl = "https://api.openai.com",
            client = client
        )

    @Provides
    @IntoSet
    fun provideClaudeProvider(client: OkHttpClient): AiProvider =
        ClaudeProvider(client)

    @Provides
    @IntoSet
    fun provideMiniMaxProvider(client: OkHttpClient): AiProvider =
        MiniMaxProvider(client)
}
