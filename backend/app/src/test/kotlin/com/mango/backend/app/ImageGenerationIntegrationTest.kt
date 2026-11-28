package com.mango.backend.app

import io.ktor.client.request.*
import io.ktor.client.call.body
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*
import java.util.UUID

class ImageGenerationIntegrationTest:IntegrationTestBase() {
    @BeforeEach fun clean()=resetDatabase()
    @Test fun `disabled generator is hidden and users cannot configure secrets`()=withApp {client ->
        val user=client.newUser("images@example.com","imagesuser")
        assertEquals(HttpStatusCode.Unauthorized,client.get("/v1/ai/images/status").status)
        val status=client.get("/v1/ai/images/status"){bearerAuth(user.accessToken)}.body<JsonObject>()
        assertEquals(false,status["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(HttpStatusCode.Forbidden,client.get("/v1/admin/image-ai"){bearerAuth(user.accessToken)}.status)
        assertEquals(HttpStatusCode.Forbidden,client.post("/v1/admin/image-ai"){bearerAuth(user.accessToken);contentType(ContentType.Application.Json);setBody("{}")}.status)
        assertEquals(HttpStatusCode.ServiceUnavailable,client.post("/v1/ai/images"){
            bearerAuth(user.accessToken);contentType(ContentType.Application.Json)
            setBody(buildJsonObject{put("id",UUID.randomUUID().toString());put("prompt","a smiling tiger")})
        }.status)
    }
}
