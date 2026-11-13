package com.mango.messenger.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Менеджер фоновых задач.
     *
     * Берётся через `getInstance`, а не создаётся заново: WorkManager
     * инициализируется из Application и обязан существовать в одном
     * экземпляре на процесс.
     */
    /**
     * Адрес сервера из настройки сборки.
     *
     * По умолчанию — хост-машина для эмулятора. Для телефона в локальной
     * сети: `-Pmango.serverUrl=https://mango.home.arpa/`
     * (см. README, «Запуск в локальной сети»).
     */
    @Provides
    @Singleton
    fun provideServerConfig(): com.mango.core.network.di.ServerConfig =
        com.mango.core.network.di.ServerConfig(
            baseUrl = com.mango.messenger.BuildConfig.SERVER_URL,
            fallbackIp = com.mango.messenger.BuildConfig.SERVER_FALLBACK_IP,
        )

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
