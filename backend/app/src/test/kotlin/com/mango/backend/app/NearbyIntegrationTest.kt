package com.mango.backend.app

import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class NearbyIntegrationTest:IntegrationTestBase() {
    @BeforeEach fun clean()=resetDatabase()
    @Test fun `nearby API requires authentication opt in and hides immediately`()=withApp { client ->
        val alice=client.newUser("nearby-a@example.test","nearby_a")
        val bob=client.newUser("nearby-b@example.test","nearby_b")
        assertEquals(HttpStatusCode.Unauthorized,client.get("/v1/nearby").status)
        assertEquals(HttpStatusCode.Forbidden,client.get("/v1/nearby") {bearerAuth(alice.accessToken)}.status)
        for(token in listOf(alice.accessToken,bob.accessToken)) {
            assertEquals(HttpStatusCode.OK,client.post("/v1/nearby") {
                bearerAuth(token);contentType(ContentType.Application.Json);setBody(NearbyLocation(55.0,37.0))
            }.status)
        }
        val peers=client.get("/v1/nearby") {bearerAuth(alice.accessToken)}.body<List<NearbyPerson>>()
        assertEquals("nearby_b",peers.single().username)
        assertEquals(HttpStatusCode.OK,client.delete("/v1/nearby") {bearerAuth(bob.accessToken)}.status)
        assertTrue(client.get("/v1/nearby") {bearerAuth(alice.accessToken)}.body<List<NearbyPerson>>().isEmpty())
    }
}
