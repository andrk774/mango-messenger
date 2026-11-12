package com.mango.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactTest {

    @Test
    fun `email is masked keeping the domain`() {
        assertEquals("a***@example.com", Redact.email("andrey@example.com"))
        assertEquals("u***@mail.ru", Redact.email("user.name+tag@mail.ru"))
    }

    @Test
    fun `malformed email is fully hidden`() {
        assertEquals("***", Redact.email("@example.com"))
        assertEquals("***", Redact.email("not-an-email"))
    }

    @Test
    fun `token is never partially revealed`() {
        assertEquals("<redacted>", Redact.token("eyJhbGciOiJIUzI1NiJ9.payload.signature"))
        assertEquals("<redacted>", Redact.token(null))
    }

    @Test
    fun `message body is replaced by its length`() {
        assertEquals("<text:13>", Redact.messageBody("Привет, Мир!!"))
        assertEquals("null", Redact.messageBody(null))
        assertEquals("<text:0>", Redact.messageBody(""))
    }

    @Test
    fun `id is shortened for log correlation`() {
        assertEquals("01927f3a…", Redact.id("01927f3a-9c21-7b4e-8f1a-6d2c5e8b9a01"))
        assertEquals("short", Redact.id("short"))
        assertEquals("null", Redact.id(null))
    }

    @Test
    fun `bearer token is stripped from free text`() {
        val input = "GET /v1/tasks failed, headers: Authorization: Bearer abc123DEF456.ghi789=="
        val result = Redact.text(input)
        assertFalse("Токен остался в логе: $result", result.contains("abc123DEF456"))
        assertTrue(result.contains("Bearer <redacted>"))
    }

    @Test
    fun `jwt is stripped from free text`() {
        val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NSJ9.s1gn4tur3"
        val result = Redact.text("refresh failed for token=$jwt")
        assertFalse("JWT остался в логе: $result", result.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
        assertTrue(result.contains("<jwt>"))
    }

    @Test
    fun `email and phone are stripped from free text`() {
        val result = Redact.text("user andrey@example.com phone +7 912 345-67-89 failed")
        assertFalse("Email остался: $result", result.contains("andrey@example.com"))
        assertFalse("Телефон остался: $result", result.contains("912 345-67-89"))
        assertTrue(result.contains("a***@example.com"))
        assertTrue(result.contains("<phone>"))
    }

    @Test
    fun `recording logger holds no pii when used correctly`() {
        val logger: Logger = RecordingLogger()
        val email = "andrey@example.com"
        val body = "секретное сообщение"

        logger.i("Auth", "login attempt for ${Redact.email(email)}")
        logger.d("Sync", "outgoing message ${Redact.messageBody(body)}")

        val dump = (logger as RecordingLogger).entries.joinToString(" ") { it.message }
        assertFalse(dump.contains(email))
        assertFalse(dump.contains(body))
    }

    @Test
    fun `text without pii is left unchanged`() {
        val input = "sync cursor advanced from 120 to 155"
        assertEquals(input, Redact.text(input))
    }
}
