package com.mango.core.network.di

import com.mango.core.common.Logger
import com.mango.core.network.NetworkJson
import com.mango.core.network.api.AuthApi
import com.mango.core.network.api.MessagingApi
import com.mango.core.network.api.SyncApi
import com.mango.core.network.auth.AuthInterceptor
import com.mango.core.network.auth.TokenAuthenticator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Адрес сервера.
 *
 * `10.0.2.2` — это хост-машина с точки зрения эмулятора Android;
 * `localhost` внутри эмулятора указывал бы на сам эмулятор.
 * На реальном устройстве значение нужно заменить на адрес машины
 * в локальной сети.
 */
data class ServerConfig(
    val baseUrl: String,
    /** Адрес сервера на случай, когда DNS телефона его не знает (VPN). См. FallbackDns. */
    val fallbackIp: String? = null,
) {
    companion object {
        const val EMULATOR_HOST = "http://10.0.2.2:8081/"
        const val WEBSOCKET_PATH = "v1/ws"
    }
}

/**
 * Клиент без перехватчиков.
 *
 * Нужен в двух местах: самому обновлению токена (иначе ответ 401 на запрос
 * обновления вызвал бы новое обновление — и так до переполнения стека)
 * и WebSocket, который аутентифицируется первым кадром, а не заголовком.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlainClient

/** Клиент с подстановкой токена и прозрачным обновлением. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthenticatedClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    // ServerConfig предоставляет модуль приложения: адрес сервера — настройка
    // сборки (mango.serverUrl), а у библиотечных модулей BuildConfig выключен.

    @Provides
    @Singleton
    fun provideJson(): Json = NetworkJson

    /**
     * Базовый клиент.
     *
     * Таймауты заданы явно: значения по умолчанию в OkHttp — десять секунд
     * на всё, и в мобильной сети это приводит к обрывам на ровном месте,
     * а бесконечное ожидание удерживало бы операцию в очереди навсегда.
     */
    @Provides
    @Singleton
    @PlainClient
    fun provideBaseClient(logger: Logger, config: ServerConfig): OkHttpClient = OkHttpClient.Builder()
        // Резервный адрес домашнего сервера, если DNS телефона идёт мимо
        // роутера (VPN). От этого клиента строятся все остальные — API,
        // WebSocket и передача файлов получают резерв одинаково.
        .dns(com.mango.core.network.FallbackDns.forServer(config.baseUrl, config.fallbackIp))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Повтор при сбое соединения безопасен: сервер идемпотентен
        // по ключу операции, поэтому дубликатов не возникнет.
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    @AuthenticatedClient
    fun provideAuthenticatedClient(
        @PlainClient base: OkHttpClient,
        authInterceptor: AuthInterceptor,
        tokenAuthenticator: TokenAuthenticator,
    ): OkHttpClient = base.newBuilder()
        .addInterceptor(authInterceptor)
        .authenticator(tokenAuthenticator)
        .build()

    @Provides
    @Singleton
    @PlainClient
    fun provideRefreshRetrofit(
        @PlainClient client: OkHttpClient,
        config: ServerConfig,
        json: Json,
    ): Retrofit = Retrofit.Builder()
        .baseUrl(config.baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    @AuthenticatedClient
    fun provideRetrofit(
        @AuthenticatedClient client: OkHttpClient,
        config: ServerConfig,
        json: Json,
    ): Retrofit = Retrofit.Builder()
        .baseUrl(config.baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    /**
     * API обновления токена.
     *
     * Построено на клиенте без перехватчика обновления: иначе ответ 401
     * на сам запрос обновления вызвал бы повторное обновление,
     * и так до переполнения стека.
     */
    @Provides
    @Singleton
    @PlainClient
    fun provideRefreshAuthApi(@PlainClient retrofit: Retrofit): AuthApi =
        retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideAuthApi(@AuthenticatedClient retrofit: Retrofit): AuthApi =
        retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideMessagingApi(@AuthenticatedClient retrofit: Retrofit): MessagingApi =
        retrofit.create(MessagingApi::class.java)

    @Provides
    @Singleton
    fun provideSyncApi(@AuthenticatedClient retrofit: Retrofit): SyncApi =
        retrofit.create(SyncApi::class.java)

    @Provides
    @Singleton
    fun provideAttachmentsApi(@AuthenticatedClient retrofit: Retrofit): com.mango.core.network.api.AttachmentsApi =
        retrofit.create(com.mango.core.network.api.AttachmentsApi::class.java)

    /** Передача файлов — без токена: ссылка подписана сама (см. BlobTransferApi). */
    @Provides
    @Singleton
    fun provideBlobTransferApi(@PlainClient retrofit: Retrofit): com.mango.core.network.api.BlobTransferApi =
        retrofit.create(com.mango.core.network.api.BlobTransferApi::class.java)

    @Provides
    @Singleton
    fun provideTasksApi(@AuthenticatedClient retrofit: Retrofit): com.mango.core.network.api.TasksApi =
        retrofit.create(com.mango.core.network.api.TasksApi::class.java)
}
