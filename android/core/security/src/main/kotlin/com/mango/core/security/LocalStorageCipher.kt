package com.mango.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Local AEAD primitive. Not E2EE; not yet connected to history migration. */
class LocalStorageCipher(private val alias: String = "mango_local_storage_v1") {
    fun encrypt(plaintext: ByteArray, binding: ByteArray): ByteArray {
        require(binding.isNotEmpty())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        cipher.updateAAD(HEADER)
        cipher.updateAAD(binding)
        return HEADER + cipher.iv + cipher.doFinal(plaintext)
    }

    /** Missing keys never trigger replacement during reads. No plaintext fallback. */
    fun decrypt(envelope: ByteArray, binding: ByteArray): ByteArray {
        require(binding.isNotEmpty())
        require(envelope.size >= HEADER.size + 12 + 16) { "Truncated local ciphertext" }
        require(envelope.copyOfRange(0, HEADER.size).contentEquals(HEADER)) { "Unsupported local ciphertext" }
        val key = existingKey() ?: error("Local storage key unavailable; recovery required")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.copyOfRange(HEADER.size, HEADER.size + 12)))
        cipher.updateAAD(HEADER)
        cipher.updateAAD(binding)
        return cipher.doFinal(envelope, HEADER.size + 12, envelope.size - HEADER.size - 12)
    }

    private fun existingKey(): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun encryptionKey(): SecretKey = synchronized(KEY_LOCK) {
        existingKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private companion object {
        val HEADER = byteArrayOf(0x4d, 0x4c, 0x53, 1)
        val KEY_LOCK = Any()
    }
}
