package com.mango.core.sync

import com.mango.core.common.MutableTimeProvider
import com.mango.core.common.NoOpLogger
import com.mango.core.data.session.AccountGuard
import com.mango.core.database.MangoDatabase
import com.mango.core.network.api.AuthApi
import com.mango.core.network.auth.SessionManager
import com.mango.core.security.AuthTokens
import com.mango.core.security.TokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Provider

/** Хранилище токенов в памяти: шифрование проверяется в :core:security. */
internal class InMemoryTokenStore : TokenStore {
    private val state = MutableStateFlow<AuthTokens?>(null)
    override val tokens: StateFlow<AuthTokens?> = state
    override suspend fun save(tokens: AuthTokens) { state.value = tokens }
    override suspend fun load(): AuthTokens? = state.value
    override suspend fun clear() { state.value = null }
}

/** Неподписанный JWT с нужным `sub`: клиент подпись не проверяет. */
internal fun testJwt(subject: String): String {
    val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
    fun part(json: String) = encoder.encodeToString(json.toByteArray(Charsets.UTF_8))
    return "${part("""{"alg":"HS256"}""")}.${part("""{"sub":"$subject"}""")}.signature"
}

/**
 * Настоящие [SessionManager] и [AccountGuard] поверх тестовой базы.
 *
 * Допуск фоновой работы проверяется тем же кодом, что и в приложении:
 * подмена охранника заглушкой скрыла бы ровно ту ошибку, от которой он защищает.
 */
internal class TestSession(database: MangoDatabase) {
    val tokens = InMemoryTokenStore()
    val manager = SessionManager(
        tokenStore = tokens,
        refreshApi = Provider<AuthApi> { error("обновление токена в этом тесте не ожидается") },
        time = MutableTimeProvider(),
        logger = NoOpLogger(),
    )
    val guard = AccountGuard(manager, database)

    suspend fun signIn(userId: String) =
        manager.save(AuthTokens(testJwt(userId), "refresh-$userId", Long.MAX_VALUE))

    suspend fun signOut() = manager.clear()
}
