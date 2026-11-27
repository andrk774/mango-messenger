package com.mango.backend.app

import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class BrowserSessionIntegrationTest : IntegrationTestBase() {
    @BeforeEach fun clean()=resetDatabase()
    @Test fun `browser cookie restores rotates and logout revokes session`()=withApp { c ->
        c.newUser("browser@example.test","browseruser",password="BrowserPassword123!")
        val login=c.post("/v1/auth/login"){
            header("X-Mango-Browser","1");contentType(ContentType.Application.Json)
            setBody(buildJsonObject{put("email","browser@example.test");put("password","BrowserPassword123!")})
        }
        assertEquals(HttpStatusCode.OK,login.status)
        val cookie=login.headers[HttpHeaders.SetCookie]!!
        assertTrue(cookie.contains("HttpOnly",true));assertTrue(cookie.contains("SameSite=Strict",true))
        assertEquals("browser-cookie",login.body<JsonObject>()["refreshToken"]!!.jsonPrimitive.content)
        val restored=c.post("/v1/auth/refresh"){
            header("X-Mango-Browser","1");header(HttpHeaders.Cookie,cookie.substringBefore(';'))
            contentType(ContentType.Application.Json);setBody(buildJsonObject{put("refreshToken","")})
        }
        assertEquals(HttpStatusCode.OK,restored.status)
        val rotated=restored.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        assertNotEquals(cookie.substringBefore(';'),rotated)
        val token=restored.body<JsonObject>()["accessToken"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.Forbidden,c.get("/v1/admin/me"){bearerAuth(token)}.status)
        assertEquals(HttpStatusCode.Forbidden,c.post("/v1/auth/refresh"){
            header("X-Mango-Browser","1");header("Origin","https://evil.example");header(HttpHeaders.Cookie,rotated)
            contentType(ContentType.Application.Json);setBody(buildJsonObject{put("refreshToken","")})
        }.status)
        val logout=c.post("/v1/auth/logout"){bearerAuth(token);header("X-Mango-Browser","1")}
        assertEquals(HttpStatusCode.NoContent,logout.status)
        assertTrue(logout.headers[HttpHeaders.SetCookie]!!.contains("Max-Age=0",true))
        assertEquals(HttpStatusCode.Unauthorized,c.post("/v1/auth/refresh"){
            header("X-Mango-Browser","1");header(HttpHeaders.Cookie,rotated)
            contentType(ContentType.Application.Json);setBody(buildJsonObject{put("refreshToken","")})
        }.status)
    }
}
