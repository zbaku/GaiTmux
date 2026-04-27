package com.aishell.platform.di

import android.content.Context
import com.aishell.platform.proot.ProotManager
import com.aishell.platform.proot.RootfsDownloader
import com.aishell.platform.proot.DefaultRootfsDownloader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PlatformModule {

    @Provides
    @Singleton
    fun provideRootfsDownloader(client: OkHttpClient): RootfsDownloader {
        return DefaultRootfsDownloader(client)
    }

    @Provides
    @Singleton
    fun provideProotManager(
        @ApplicationContext context: Context,
        rootfsDownloader: RootfsDownloader
    ): ProotManager {
        return ProotManager(context, rootfsDownloader)
    }
}