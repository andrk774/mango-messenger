package com.mango.core.security

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Тесты хранилища токенов.
 *
 * Настоящий Android Keystore на JVM недоступен, поэтому [Crypto] подменяется
 * управляемой реализацией. Это проверяет логику хранилища — сериализацию,
 * поведение при повреждении данных, очистку. Работоспособность самого
 * [KeystoreCrypto] требует инструментального теста на устройстве
 * и в текущем окружении НЕ проверена.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class EncryptedTokenStoreTest {

    /** Обратимое «шифрование» для теста: XOR с постоянным байтом. */
    private class ReversibleCrypto(var broken: Boolean = false) : Crypto {
        var encryptCalls = 0
            private set

        override fun encrypt(plaintext: String): ByteArray {
            encryptCalls++
            return plaintext.toByteArray(Charsets.UTF_8).map { (it.toInt() xor 0x5A).toByte() }
                .toByteArray()
        }

        override fun decrypt(ciphertext: ByteArray): String? {
            if (broken) return null
            return String(
                ciphertext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray(),
                Charsets.UTF_8,
            )
        }
    }

    private fun newStore(crypto: Crypto) = EncryptedTokenStore(
        context = ApplicationProvider.getApplicationContext(),
        crypto = crypto,
    )

    private val sample = AuthTokens(
        accessToken = "access.token.value",
        refreshToken = "refresh.token.value",
        accessExpiresAtMillis = 1_700_000_900_000,
    )

    @Test
    fun `saved tokens are restored by a fresh store instance`() = runTest {
        val crypto = ReversibleCrypto()
        newStore(crypto).save(sample)

        // Новый экземпляр — как после перезапуска процесса.
        val restored = newStore(crypto).load()

        assertEquals(sample, restored)
    }

    @Test
    fun `tokens are encrypted before being written`() = runTest {
        val crypto = ReversibleCrypto()
        val store = newStore(crypto)

        store.save(sample)

        assertEquals("Шифрование должно вызываться при каждой записи", 1, crypto.encryptCalls)

        val raw = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("mango_secure_tokens", android.content.Context.MODE_PRIVATE)
            .getString("tokens_v1", null)

        assertTrue(raw != null)
        assertFalse(
            "Refresh token не должен быть виден в хранилище открытым текстом",
            raw!!.contains("refresh.token.value"),
        )
        assertFalse(
            "Access token не должен быть виден в хранилище открытым текстом",
            raw.contains("access.token.value"),
        )
    }

    @Test
    fun `undecryptable payload clears the session instead of crashing`() = runTest {
        val crypto = ReversibleCrypto()
        newStore(crypto).save(sample)

        // Имитируем сброс ключа Keystore: расшифровка больше не удаётся.
        crypto.broken = true
        val store = newStore(crypto)

        assertNull("Сессия должна считаться потерянной", store.load())
        assertNull("Повреждённые данные должны быть удалены", store.tokens.value)

        // После восстановления расшифровки данных уже нет — они стёрты.
        crypto.broken = false
        assertNull(newStore(crypto).load())
    }

    @Test
    fun `clear removes tokens from storage and state`() = runTest {
        val crypto = ReversibleCrypto()
        val store = newStore(crypto)
        store.save(sample)
        assertEquals(sample, store.tokens.value)

        store.clear()

        assertNull(store.tokens.value)
        assertNull(newStore(crypto).load())
    }

    @Test
    fun `load on empty storage returns null`() = runTest {
        assertNull(newStore(ReversibleCrypto()).load())
    }

    @Test
    fun `access expiry accounts for clock skew`() {
        val tokens = AuthTokens("a", "r", accessExpiresAtMillis = 100_000)

        assertFalse("Задолго до истечения — валиден", tokens.isAccessExpired(nowMillis = 50_000))
        assertTrue(
            "За 10 секунд до истечения считается истёкшим: запрос не успеет долететь",
            tokens.isAccessExpired(nowMillis = 90_000),
        )
        assertTrue(tokens.isAccessExpired(nowMillis = 100_000))
    }

    @Test
    fun `tokens containing separator-like characters round trip`() = runTest {
        // base64url-токены не содержат NUL, но проверяем устойчивость разбора.
        val crypto = ReversibleCrypto()
        val tricky = AuthTokens(
            accessToken = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig-with_dashes",
            refreshToken = "rt_AAAA1111-_bbbb==",
            accessExpiresAtMillis = 42,
        )

        newStore(crypto).save(tricky)

        assertEquals(tricky, newStore(crypto).load())
    }
}
