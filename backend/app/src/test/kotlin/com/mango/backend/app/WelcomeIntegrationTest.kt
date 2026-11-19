package com.mango.backend.app

import com.mango.backend.auth.*
import com.mango.backend.common.queryOne
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class WelcomeIntegrationTest:IntegrationTestBase() {
    @BeforeEach fun cleanup()=resetDatabase()

    @Test fun `verification creates four tasks once and progress belongs to authenticated user`()=withApp {client ->
        val email="welcome@example.com"
        client.post("/v1/auth/register") {contentType(ContentType.Application.Json);setBody(RegisterRequest(email,"Password-2026","Welcome","welcome"))}
        assertEquals(0,database.readOnly {it.queryOne("SELECT count(*) FROM welcome_tasks") {r->r.getInt(1)}})
        val token=mail.tokenFor(email)
        assertEquals(HttpStatusCode.OK,client.post("/v1/auth/verify-email") {contentType(ContentType.Application.Json);setBody(VerifyEmailRequest(token,email))}.status)
        val first=client.post("/v1/auth/login") {contentType(ContentType.Application.Json);setBody(LoginRequest(email,"Password-2026"))}.body<TokenPairResponse>()
        val second=client.newUser("another@example.com","another")
        suspend fun tasks(access:String)=client.get("/v1/users/me/welcome") {bearerAuth(access)}.body<JsonArray>()
        assertEquals(setOf("AVATAR","INVITE","CHAT","TASK"),tasks(first.accessToken).map {it.jsonObject["kind"]!!.jsonPrimitive.content}.toSet())
        for((kind,status) in listOf("AVATAR" to "DONE","INVITE" to "SKIPPED")) {
            repeat(2) {assertEquals(HttpStatusCode.OK,client.post("/v1/users/me/welcome/$kind") {bearerAuth(first.accessToken);contentType(ContentType.Application.Json);setBody(buildJsonObject {put("status",status)})}.status)}
        }
        client.post("/v1/auth/verify-email") {contentType(ContentType.Application.Json);setBody(VerifyEmailRequest(token,email))}
        assertEquals(4,tasks(first.accessToken).size)
        assertEquals(2,tasks(first.accessToken).count {it.jsonObject["status"]!!.jsonPrimitive.content!="PENDING"})
        assertTrue(tasks(second.accessToken).all {it.jsonObject["status"]!!.jsonPrimitive.content=="PENDING"})
        assertEquals(HttpStatusCode.Unauthorized,client.get("/v1/users/me/welcome").status)
        assertEquals(HttpStatusCode.BadRequest,client.post("/v1/users/me/welcome/CHAT") {bearerAuth(first.accessToken);contentType(ContentType.Application.Json);setBody(buildJsonObject {put("status","UNKNOWN")})}.status)
    }

    @Test fun `avatar accepts bounded image and rejects invalid content`()=withApp {client ->
        val user=client.newUser("avatar@example.com","avatar")
        val image=java.awt.image.BufferedImage(32,32,java.awt.image.BufferedImage.TYPE_INT_RGB)
        val bytes=java.io.ByteArrayOutputStream().also {javax.imageio.ImageIO.write(image,"png",it)}.toByteArray()
        val result=client.post("/v1/users/me/avatar") {bearerAuth(user.accessToken);contentType(ContentType.Application.Json);setBody(buildJsonObject {put("image",java.util.Base64.getEncoder().encodeToString(bytes))})}
        assertEquals(HttpStatusCode.OK,result.status)
        assertTrue(result.body<JsonObject>()["avatarUrl"]!!.jsonPrimitive.content.startsWith("data:image/jpeg;base64,"))
        assertEquals(HttpStatusCode.BadRequest,client.post("/v1/users/me/avatar") {bearerAuth(user.accessToken);contentType(ContentType.Application.Json);setBody(buildJsonObject {put("image","not-an-image")})}.status)
    }
}
