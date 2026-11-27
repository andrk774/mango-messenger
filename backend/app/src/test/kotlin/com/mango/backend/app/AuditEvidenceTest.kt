package com.mango.backend.app

import com.mango.backend.auth.*
import com.mango.backend.common.*
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

// Regression tests for the security findings of 2026-11-21.
class AuditEvidenceTest : IntegrationTestBase() {
    @BeforeEach fun cleanup() = resetDatabase()

    @Test fun `forwarded chain stops at first untrusted hop`() {
        val trusted = setOf("192.0.2.10", "192.0.2.11")
        assertEquals("198.51.100.1", resolveClientIp("198.51.100.1", "203.0.113.9", trusted))
        assertEquals("198.51.100.1", resolveClientIp("192.0.2.10", "203.0.113.9, 198.51.100.1, 192.0.2.11", trusted))
        assertEquals("192.0.2.10", resolveClientIp("192.0.2.10", "invalid", trusted))
        assertEquals("192.0.2.10", resolveClientIp("192.0.2.10", "198.51.100.1", emptySet()))
    }

    @Test fun `rotating accounts cannot bypass source limit`() = withApp(InMemoryRateLimiter(time)) { client ->
        repeat(100) { index ->
            assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest("audit-$index@example.com", "Wrong-password-2026"))
            }.status)
        }
        assertEquals(HttpStatusCode.TooManyRequests, client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("another@example.com", "Wrong-password-2026"))
        }.status)
    }

    @Test fun `administrator must not take over owner by password reset`() = withApp { client ->
        val admin = client.newUser("audit-admin@example.com", "auditadmin")
        val owner = client.newUser("audit-owner@example.com", "auditowner")
        val adminId = client.get("/v1/users/me") { bearerAuth(admin.accessToken) }.body<CurrentUserResponse>().id
        val ownerId = client.get("/v1/users/me") { bearerAuth(owner.accessToken) }.body<CurrentUserResponse>().id
        database.transaction {
            it.update("INSERT INTO service_admins(user_id,role) VALUES (?, 'ADMIN')", adminId)
            it.update("INSERT INTO service_admins(user_id,role) VALUES (?, 'OWNER')", ownerId)
        }
        val response = client.post("/v1/admin/users/$ownerId/password") {
            bearerAuth(admin.accessToken)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("password", "Audit-only-password-2026"); put("reason", "isolated security test") })
        }
        if (response.status == HttpStatusCode.OK) {
            val takeover = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest("audit-owner@example.com", "Audit-only-password-2026"))
            }.body<TokenPairResponse>()
            val role = client.get("/v1/admin/me") { bearerAuth(takeover.accessToken) }.body<JsonObject>()
            assertEquals("OWNER", role["role"]!!.jsonPrimitive.content)
        }
        assertEquals(HttpStatusCode.Forbidden, response.status, "ADMIN must not reset OWNER credentials")
    }

    @Test fun `forged forwarded address must not bypass login limit`() = withApp(InMemoryRateLimiter(time)) { client ->
        suspend fun attempt(ip: String) = client.post("/v1/auth/login") {
            header(HttpHeaders.XForwardedFor, ip)
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("audit-missing@example.com", "Wrong-password-2026"))
        }
        repeat(RateLimits.LOGIN_LIMIT) { assertEquals(HttpStatusCode.Unauthorized, attempt("198.51.100.1").status) }
        assertEquals(HttpStatusCode.TooManyRequests, attempt("198.51.100.1").status)
        assertEquals(HttpStatusCode.TooManyRequests, attempt("198.51.100.2").status,
            "Changing untrusted X-Forwarded-For must not create a new allowance")
    }
}
