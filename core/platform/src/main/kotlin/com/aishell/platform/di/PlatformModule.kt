package com.aishell.platform.di

import com.aishell.platform.proot.ProotManager
import com.aishell.platform.proot.RootfsDownloader
import com.aishell.platform.proot.DefaultRootfsDownloader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PlatformModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRootfsDownloader(client: OkHttpClient): RootfsDownloader {
        return DefaultRootfsDownloader(client)
    }

    @Provides
    @Singleton
    fun provideProotManager(
        context: android.content.Context,
        rootfsDownloader: RootfsDownloader
    ): ProotManager {
        return ProotManager(context, rootfsDownloader)
    }
}