package com.mango.core.security

import android.content.Context
import android.util.Base64
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Пара токенов и срок действия access token.
 *
 * [userId] — владелец сессии, взятый из выданного сервером токена
 * (`sub`). По нему фоновые операции сверяются с владельцем локальной
 * базы: операции одного аккаунта не должны уйти под токеном другого.
 * `null` означает, что владелец не подтверждён, и фоновая работа
 * запрещена.
 */
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAtMillis: Long,
    val userId: String? = null,
) {
    /**
     * Запас в 30 секунд: если токен истечёт во время полёта запроса,
     * сервер ответит 401 и мы потеряем попытку.
     */
    fun isAccessExpired(nowMillis: Long, skewMillis: Long = 30_000): Boolean =
        nowMillis + skewMillis >= accessExpiresAtMillis
}

/**
 * Хранилище токенов.
 *
 * Refresh token — это долгоживущий доступ к аккаунту, поэтому он обязан
 * храниться в зашифрованном виде с ключом из Android Keystore, а не в
 * обычных SharedPreferences и тем более не в логах.
 */
interface TokenStore {
    val tokens: StateFlow<AuthTokens?>

    suspend fun save(tokens: AuthTokens)
    suspend fun load(): AuthTokens?
    suspend fun clear()
}

/**
 * Реализация: шифротекст лежит в SharedPreferences, ключ — в Keystore.
 *
 * SharedPreferences здесь безопасны именно потому, что в них попадают
 * только зашифрованные байты. Расшифровать их без ключа из Keystore
 * (который не извлекаем) нельзя.
 */
@Singleton
class EncryptedTokenStore @Inject constructor(
    @ApplicationContext context: Context,
    private val crypto: Crypto,
) : TokenStore {

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _tokens = MutableStateFlow<AuthTokens?>(null)
    override val tokens: StateFlow<AuthTokens?> = _tokens.asStateFlow()

    // KTX edit returns Unit; the durable commit result must be checked before publishing tokens.
    @android.annotation.SuppressLint("UseKtx")
    override suspend fun save(tokens: AuthTokens) {
        val encoded = listOf(
            tokens.accessToken,
            tokens.refreshToken,
            tokens.accessExpiresAtMillis.toString(),
            tokens.userId.orEmpty(),
        ).joinToString(SEPARATOR)

        val encrypted = crypto.encrypt(encoded)
        // Старый формат удаляется той же записью, что и пишется новый:
        // при сбое между ними осталась бы прежняя сессия без владельца.
        check(preferences.edit()
            .putString(KEY_PAYLOAD_V2, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .remove(KEY_PAYLOAD_V1)
            .commit()) { "Could not persist session" }
        _tokens.value = tokens
    }

    override suspend fun load(): AuthTokens? {
        _tokens.value?.let { return it }

        preferences.getString(KEY_PAYLOAD_V2, null)?.let { stored ->
            val parts = decryptParts(stored) ?: return null
            if (parts.size != 4) return corrupted()
            val expiresAt = parts[2].toLongOrNull() ?: return corrupted()
            return AuthTokens(
                accessToken = parts[0],
                refreshToken = parts[1],
                accessExpiresAtMillis = expiresAt,
                userId = parts[3].ifEmpty { null },
            ).also { _tokens.value = it }
        }

        // Миграция tokens_v1: владелец берётся из выданного сервером токена.
        // Если его не извлечь, сессия остаётся без владельца — фоновая
        // работа будет запрещена до повторного входа, но данные не теряются.
        val legacy = preferences.getString(KEY_PAYLOAD_V1, null) ?: return null
        val parts = decryptParts(legacy) ?: return null
        if (parts.size != 3) return corrupted()
        val expiresAt = parts[2].toLongOrNull() ?: return corrupted()
        val tokens = AuthTokens(
            accessToken = parts[0],
            refreshToken = parts[1],
            accessExpiresAtMillis = expiresAt,
            userId = JwtClaims.subject(parts[0]),
        )
        if (tokens.userId != null) save(tokens) else _tokens.value = tokens
        return tokens
    }

    private fun decryptParts(stored: String): List<String>? {
        val decrypted = runCatching { Base64.decode(stored, Base64.NO_WRAP) }
            .getOrNull()
            ?.let(crypto::decrypt)

        if (decrypted == null) {
            // Расшифровать не удалось: ключ Keystore мог быть сброшен
            // (сброс блокировки экрана, восстановление из бэкапа).
            // Единственный корректный выход — считать сессию потерянной.
            clearStored()
            return null
        }
        return decrypted.split(SEPARATOR)
    }

    private fun corrupted(): AuthTokens? {
        clearStored()
        return null
    }

    // Logout must verify persistence; KTX edit discards the commit result.
    @android.annotation.SuppressLint("UseKtx")
    private fun clearStored() {
        check(preferences.edit().remove(KEY_PAYLOAD_V2).remove(KEY_PAYLOAD_V1).commit()) { "Could not clear session" }
        _tokens.value = null
    }

    override suspend fun clear() = clearStored()

    private companion object {
        const val PREFS_NAME = "mango_secure_tokens"
        const val KEY_PAYLOAD_V1 = "tokens_v1"
        const val KEY_PAYLOAD_V2 = "tokens_v2"

        /** Символ, который не может встретиться в base64url-токене. */
        const val SEPARATOR = "\u0000"
    }
}

@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    @Provides
    @Singleton
    fun provideCrypto(impl: KeystoreCrypto): Crypto = impl

    @Provides
    @Singleton
    fun provideTokenStore(impl: EncryptedTokenStore): TokenStore = impl
}
