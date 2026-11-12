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

/** Пара токенов и срок действия access token. */
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAtMillis: Long,
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

    override suspend fun save(tokens: AuthTokens) {
        val encoded = listOf(
            tokens.accessToken,
            tokens.refreshToken,
            tokens.accessExpiresAtMillis.toString(),
        ).joinToString(SEPARATOR)

        val encrypted = crypto.encrypt(encoded)
        preferences.edit()
            .putString(KEY_PAYLOAD, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit() // commit, не apply: потеря токенов при падении недопустима
        _tokens.value = tokens
    }

    override suspend fun load(): AuthTokens? {
        _tokens.value?.let { return it }

        val stored = preferences.getString(KEY_PAYLOAD, null) ?: return null
        val decrypted = runCatching { Base64.decode(stored, Base64.NO_WRAP) }
            .getOrNull()
            ?.let(crypto::decrypt)

        if (decrypted == null) {
            // Расшифровать не удалось: ключ Keystore мог быть сброшен
            // (сброс блокировки экрана, восстановление из бэкапа).
            // Единственный корректный выход — считать сессию потерянной.
            clear()
            return null
        }

        val parts = decrypted.split(SEPARATOR)
        if (parts.size != 3) {
            clear()
            return null
        }

        val expiresAt = parts[2].toLongOrNull() ?: run {
            clear()
            return null
        }

        return AuthTokens(
            accessToken = parts[0],
            refreshToken = parts[1],
            accessExpiresAtMillis = expiresAt,
        ).also { _tokens.value = it }
    }

    override suspend fun clear() {
        preferences.edit().remove(KEY_PAYLOAD).commit()
        _tokens.value = null
    }

    private companion object {
        const val PREFS_NAME = "mango_secure_tokens"
        const val KEY_PAYLOAD = "tokens_v1"

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
