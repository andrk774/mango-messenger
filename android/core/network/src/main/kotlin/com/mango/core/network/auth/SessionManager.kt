package com.mango.core.network.auth

import com.mango.core.common.Logger
import com.mango.core.common.Redact
import com.mango.core.common.TimeProvider
import com.mango.core.network.api.AuthApi
import com.mango.core.network.di.PlainClient
import com.mango.core.network.dto.RefreshRequestDto
import com.mango.core.security.AuthTokens
import com.mango.core.security.TokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Состояние сессии и обновление токенов.
 *
 * Единственное место, где приложение знает, вошёл ли пользователь.
 * Репозитории и экраны про токены не знают вовсе.
 */
@Singleton
class SessionManager @Inject constructor(
    private val tokenStore: TokenStore,
    /**
     * API для обновления берётся через [Provider] и построено на клиенте
     * БЕЗ перехватчика обновления. Иначе получилась бы цикличная
     * зависимость, а ответ 401 на сам запрос обновления уходил бы
     * в бесконечную рекурсию.
     */
    @PlainClient private val refreshApi: Provider<AuthApi>,
    private val time: TimeProvider,
    private val logger: Logger,
) {

    /**
     * Обновление выполняется под замком.
     *
     * Несколько параллельных запросов легко получают 401 одновременно.
     * Без замка каждый начал бы своё обновление, и все, кроме первого,
     * предъявили бы уже отозванный refresh — сервер расценил бы это как
     * кражу токена и завершил ВСЕ сессии пользователя.
     */
    private val refreshMutex = Mutex()

    val isAuthenticated: Flow<Boolean> = tokenStore.tokens.map { it != null }

    /** Восстановление сессии при старте приложения. */
    suspend fun restore(): Boolean = tokenStore.load() != null

    fun currentAccessToken(): String? = tokenStore.tokens.value?.accessToken

    suspend fun save(tokens: AuthTokens) = tokenStore.save(tokens)

    suspend fun clear() = tokenStore.clear()

    /**
     * Обновляет пару токенов.
     *
     * @return `true`, если после вызова есть действующий access token.
     */
    suspend fun refresh(): Boolean = refreshMutex.withLock {
        val current = tokenStore.tokens.value ?: tokenStore.load() ?: return false

        // Пока ждали замок, другой вызов мог уже обновить токены.
        // Повторять обмен нельзя: предъявление отозванного refresh
        // трактуется сервером как компрометация.
        if (!current.isAccessExpired(time.nowMillis())) return true

        val response = runCatching {
            refreshApi.get().refresh(RefreshRequestDto(current.refreshToken))
        }.getOrElse { error ->
            logger.w(TAG, "обновление токена не удалось: ${Redact.text(error.message)}")
            return false
        }

        val body = response.body()
        if (!response.isSuccessful || body == null) {
            if (response.code() == 401) {
                // Сервер отверг refresh: сессия завершена, и локальные
                // токены бесполезны. Хранить их означало бы бесконечно
                // повторять заведомо провальный обмен.
                logger.i(TAG, "сессия завершена сервером, требуется повторный вход")
                tokenStore.clear()
            }
            return false
        }

        tokenStore.save(
            AuthTokens(
                accessToken = body.accessToken,
                refreshToken = body.refreshToken,
                accessExpiresAtMillis = body.accessExpiresAt.toEpochMilli(),
            ),
        )
        return true
    }

    private companion object {
        const val TAG = "SessionManager"
    }
}
