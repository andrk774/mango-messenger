package com.mango.backend.common

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MailConfigTest {
    @Test
    fun `local mail remains unauthenticated`() {
        assertEquals(MailSecurity.NONE, AppConfig.load { null }.mail.security)
    }

    @Test
    fun `credentials cannot be sent in plaintext`() {
        assertFailsWith<IllegalArgumentException> {
            MailConfig("smtp.example.com", 587, "test@example.com", true, "test", "secret")
        }
    }

    @Test
    fun `partial credentials are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            MailConfig("smtp.example.com", 587, "test@example.com", true, username = "test", security = MailSecurity.STARTTLS)
        }
    }

    @Test
    fun `SMTP environment selects required TLS and credentials`() {
        val values = mapOf("MANGO_SMTP_SECURITY" to "starttls", "MANGO_SMTP_USERNAME" to "test", "MANGO_SMTP_PASSWORD" to "secret")
        val mail = AppConfig.load(values::get).mail
        assertEquals(MailSecurity.STARTTLS, mail.security)
        assertEquals("test", mail.username)
    }
}
