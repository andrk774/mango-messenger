package com.mango.backend.app

import org.junit.jupiter.api.Test
import kotlin.test.*
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SmtpCipherTest {
    @Test fun `new encryption independent of JWT and rejects wrong key and tampering`() {
        val key = ByteArray(32) { it.toByte() }
        val first = SmtpCipher(key,"old-jwt")
        val encrypted = first.encrypt("test-password")
        assertEquals("test-password", SmtpCipher(key,"rotated-jwt").decrypt(encrypted))
        assertNotEquals(encrypted,first.encrypt("test-password"))
        assertFails { SmtpCipher(ByteArray(32) { 99 },"old-jwt").decrypt(encrypted) }
        val data = Base64.getDecoder().decode(encrypted.substring(3)); data[data.lastIndex] = (data.last().toInt() xor 1).toByte()
        assertFails { first.decrypt("v2:"+Base64.getEncoder().encodeToString(data)) }
        assertFails { SmtpCipher(null,"old-jwt").encrypt("no fallback") }
    }
    @Test fun `legacy decryption survives migration and original remains usable`() {
        val iv=ByteArray(12) { it.toByte() }
        val oldKey=MessageDigest.getInstance("SHA-256").digest("mango-smtp-v1:legacy-secret".toByteArray())
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(oldKey,"AES"),GCMParameterSpec(128,iv))
        val old=Base64.getEncoder().encodeToString(iv+cipher.doFinal("legacy-password".toByteArray()))
        val modern=SmtpCipher(ByteArray(32) { 7 },"legacy-secret")
        val migrated=modern.encrypt(modern.decrypt(old))
        assertEquals("legacy-password",modern.decrypt(migrated))
        assertEquals("legacy-password",SmtpCipher(null,"legacy-secret").decrypt(old))
    }
}
