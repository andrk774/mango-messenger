package com.mango.backend.app

import com.mango.backend.common.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

class SmtpMigrationIntegrationTest : IntegrationTestBase() {
    @BeforeEach fun cleanup() = resetDatabase()
    @Test fun `migration preserves originals is atomic and can be repeated`() = withApp { _ ->
        val iv=ByteArray(12) { it.toByte() }
        val legacy=System.getenv("MANGO_ADMIN_ENCRYPTION_KEY")?.takeIf { it.isNotBlank() } ?: config.jwt.secret
        val key=MessageDigest.getInstance("SHA-256").digest(("mango-smtp-v1:"+legacy).toByteArray())
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,iv))
        val original=Base64.getEncoder().encodeToString(iv+cipher.doFinal("migration-test-password".toByteArray()))
        val first=UUID.randomUUID(); val second=UUID.randomUUID()
        database.transaction { c ->
            for ((id,password) in listOf(first to original,second to "invalid")) {
                c.update("INSERT INTO smtp_profiles(id,name,host,port,security,sender,username,password_cipher) VALUES (?,?,'smtp.example.com',587,'STARTTLS','test@example.com','test',?)",id,id.toString(),password)
            }
        }
        val service=AdminService(database,config,time)
        assertFails { service.migrateSmtpKey() }
        assertEquals(original,database.readOnly { it.queryOne("SELECT password_cipher FROM smtp_profiles WHERE id=?",first) { r -> r.getString(1) } })
        assertEquals(0,database.readOnly { it.queryOne("SELECT count(*) FROM smtp_cipher_backup") { r -> r.getInt(1) } })
        database.transaction { it.update("UPDATE smtp_profiles SET password_cipher=? WHERE id=?",original,second) }
        service.migrateSmtpKey(); service.migrateSmtpKey()
        assertEquals(2,database.readOnly { it.queryOne("SELECT count(*) FROM smtp_cipher_backup WHERE password_cipher=?",original) { r -> r.getInt(1) } })
        val encrypted=database.readOnly { it.queryOne("SELECT password_cipher FROM smtp_profiles WHERE id=?",first) { r -> r.getString(1) } }!!
        assertTrue(encrypted.startsWith("v2:"))
        assertEquals("migration-test-password",SmtpCipher(ByteArray(32) {42},"different-jwt").decrypt(encrypted))
    }
}
