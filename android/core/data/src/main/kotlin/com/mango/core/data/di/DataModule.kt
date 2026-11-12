package com.mango.core.data.di

import android.util.Log
import com.mango.core.common.DefaultDispatcherProvider
import com.mango.core.common.DispatcherProvider
import com.mango.core.common.IdGenerator
import com.mango.core.common.Logger
import com.mango.core.common.RetryPolicy
import com.mango.core.common.SystemTimeProvider
import com.mango.core.common.TimeProvider
import com.mango.core.common.UuidV7Generator
import com.mango.core.data.repository.AuthRepository
import com.mango.core.data.repository.ConversationRepository
import com.mango.core.data.repository.DefaultAuthRepository
import com.mango.core.data.repository.DefaultConversationRepository
import com.mango.core.data.repository.DefaultMessageRepository
import com.mango.core.data.repository.LocalSyncStatusRepository
import com.mango.core.data.repository.LocalTaskRepository
import com.mango.core.data.repository.LocalUserRepository
import com.mango.core.data.repository.MessageRepository
import com.mango.core.data.repository.SyncStatusRepository
import com.mango.core.data.repository.TaskRepository
import com.mango.core.data.repository.UserRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Привязка интерфейсов репозиториев к реализациям.
 *
 * Feature-модули инжектируют интерфейсы и не знают, что сейчас реализация
 * локальная. На Этапе 4 здесь поменяется только правая часть @Binds.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: DefaultAuthRepository): AuthRepository

    @Binds
    @Singleton
    abstract fun bindConversationRepository(
        impl: DefaultConversationRepository,
    ): ConversationRepository

    @Binds
    @Singleton
    abstract fun bindMessageRepository(impl: DefaultMessageRepository): MessageRepository

    @Binds
    @Singleton
    abstract fun bindTaskRepository(impl: LocalTaskRepository): TaskRepository

    @Binds
    @Singleton
    abstract fun bindUserRepository(impl: LocalUserRepository): UserRepository

    @Binds
    @Singleton
    abstract fun bindSyncStatusRepository(impl: LocalSyncStatusRepository): SyncStatusRepository
}

@Module
@InstallIn(SingletonComponent::class)
object CoreProvidersModule {

    @Provides
    @Singleton
    fun provideIdGenerator(): IdGenerator = UuidV7Generator()

    @Provides
    @Singleton
    fun provideTimeProvider(): TimeProvider = SystemTimeProvider()

    /**
     * Политика повторов для очереди исходящих.
     *
     * Задаётся явной привязкой, а не значением параметра по умолчанию:
     * Dagger значения по умолчанию не видит, и сборка графа падала бы
     * с отсутствующей зависимостью.
     */
    @Provides
    @Singleton
    fun provideRetryPolicy(): RetryPolicy = RetryPolicy.Default

    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()

    @Provides
    @Singleton
    fun provideLogger(): Logger = AndroidLogger()
}

/**
 * Логгер на android.util.Log.
 *
 * Debug-уровень отбрасывается, если он не включён для тега: это и экономия,
 * и дополнительная страховка от попадания отладочных данных в release-логи.
 * Персональные данные редактируются вызывающей стороной через `Redact`.
 */
class AndroidLogger : Logger {
    override fun d(tag: String, message: String) {
        if (Log.isLoggable(tag, Log.DEBUG)) Log.d(tag, message)
    }

    override fun i(tag: String, message: String) = Log.i(tag, message).let { }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
    }
}
