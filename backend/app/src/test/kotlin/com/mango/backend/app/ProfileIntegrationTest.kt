package com.mango.backend.app

import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class ProfileIntegrationTest : IntegrationTestBase() {
    @Test fun `profile edit persists and cannot overwrite another account`() = withApp { client ->
        val first=client.newUser("profile1@example.com","profileone")
        val second=client.newUser("profile2@example.com","profiletwo")
        suspend fun edit(token:String,username:String,name:String="Updated",bio:String="About me")=client.patch("/v1/users/me") {
            bearerAuth(token);contentType(ContentType.Application.Json)
            setBody(buildJsonObject {put("displayName",name);put("username",username);put("bio",bio)})
        }
        assertEquals(HttpStatusCode.OK,edit(first.accessToken,"newusername").status)
        val saved=client.get("/v1/users/me"){bearerAuth(first.accessToken)}.body<JsonObject>()
        assertEquals("Updated",saved["displayName"]!!.jsonPrimitive.content)
        assertEquals("About me",saved["bio"]!!.jsonPrimitive.content)
        assertEquals("newusername",saved["username"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Conflict,edit(second.accessToken,"newusername").status)
        assertEquals("profiletwo",client.get("/v1/users/me"){bearerAuth(second.accessToken)}.body<JsonObject>()["username"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.UnprocessableEntity,edit(first.accessToken,"bad name").status)
        assertEquals(HttpStatusCode.BadRequest,edit(first.accessToken,"newusername",bio="x".repeat(281)).status)
        assertEquals(HttpStatusCode.Unauthorized,client.patch("/v1/users/me") {contentType(ContentType.Application.Json);setBody("{}")}.status)
    }
}
