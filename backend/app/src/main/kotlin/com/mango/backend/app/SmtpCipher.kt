package com.mango.backend.app

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Legacy key is read-only; new ciphertext never uses the JWT-derived key. */
class SmtpCipher(private val key: ByteArray?, legacySecret: String) {
    private val legacy = MessageDigest.getInstance("SHA-256").digest(("mango-smtp-v1:" + legacySecret).toByteArray())
    init { require(key == null || key.size == 32) { "SMTP key must contain 32 bytes" } }
    val canMigrate: Boolean get() = key != null
    fun encrypt(value: String): String {
        val active = requireNotNull(key) { "MANGO_SMTP_KEY_FILE must be configured" }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(active, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD("mango-smtp-v2".toByteArray())
        return "v2:" + Base64.getEncoder().encodeToString(iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }
    fun decrypt(value: String): String {
        val modern = value.startsWith("v2:")
        val bytes = Base64.getDecoder().decode(if (modern) value.substring(3) else value)
        require(bytes.size >= 28) { "Invalid SMTP ciphertext" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secret = if (modern) requireNotNull(key) { "SMTP key unavailable" } else legacy
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0,12)))
        if (modern) cipher.updateAAD("mango-smtp-v2".toByteArray())
        return String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)), Charsets.UTF_8)
    }
    companion object {
        fun readKey(file: String?): ByteArray? = file?.takeIf { it.isNotBlank() }?.let {
            Base64.getDecoder().decode(Files.readString(Path.of(it)).trim()).also { bytes ->
                require(bytes.size == 32) { "SMTP key file must contain base64 of 32 bytes" }
            }
        }
    }
}
