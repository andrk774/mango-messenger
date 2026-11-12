package com.mango.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Симметричное шифрование строк.
 *
 * Абстракция нужна по двум причинам: тесты не могут обращаться к настоящему
 * Android Keystore на JVM, и в будущем сюда же встанет вариант с
 * биометрической привязкой ключа.
 */
interface Crypto {
    fun encrypt(plaintext: String): ByteArray
    fun decrypt(ciphertext: ByteArray): String?
}

/**
 * Реализация на Android Keystore.
 *
 * Ключ AES-256 генерируется внутри Keystore и **не покидает его**: приложение
 * работает с хендлом, а не с байтами ключа. Поэтому даже чтение файла
 * настроек с устройства не даёт возможности расшифровать refresh token.
 *
 * Режим GCM выбран как AEAD: он даёт и конфиденциальность, и целостность,
 * то есть подмена шифротекста будет обнаружена, а не приведёт к тихому
 * получению мусора. IV генерируется случайно для каждой операции и
 * сохраняется в начале результата — повторное использование IV в GCM
 * полностью разрушает безопасность шифра.
 */
@Singleton
class KeystoreCrypto @Inject constructor() : Crypto {

    override fun encrypt(plaintext: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // [IV | ciphertext+tag]
        return cipher.iv + encrypted
    }

    override fun decrypt(ciphertext: ByteArray): String? {
        if (ciphertext.size <= IV_LENGTH) return null
        return runCatching {
            val iv = ciphertext.copyOfRange(0, IV_LENGTH)
            val payload = ciphertext.copyOfRange(IV_LENGTH, ciphertext.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                loadOrCreateKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, iv),
            )
            String(cipher.doFinal(payload), Charsets.UTF_8)
        }.getOrNull() // повреждение или подмена данных → null, а не исключение
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                // Случайный IV генерирует сам Keystore — запрещаем его задавать
                // извне, чтобы исключить повторное использование IV.
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "mango_token_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
    }
}
