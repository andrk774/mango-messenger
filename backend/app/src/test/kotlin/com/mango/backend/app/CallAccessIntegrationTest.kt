package com.mango.backend.app

import com.mango.backend.common.update
import com.mango.backend.messaging.CreateConversationRequest
import com.mango.backend.messaging.ConversationDto
import com.mango.backend.auth.CurrentUserResponse
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CallAccessIntegrationTest:IntegrationTestBase() {
    @BeforeEach fun reset()=resetDatabase()
    @Test fun `call access excludes strangers and removed participants and needs authentication`()=withApp {client->
        val owner=client.newUser("call-owner@example.test","callowner")
        val peer=client.newUser("call-peer@example.test","callpeer")
        val stranger=client.newUser("call-other@example.test","callother")
        val peerUser=client.get("/v1/users/me"){bearerAuth(peer.accessToken)}.body<CurrentUserResponse>()
        val chat=client.post("/v1/conversations"){bearerAuth(owner.accessToken);contentType(ContentType.Application.Json);setBody(CreateConversationRequest(type="GROUP",title="Call test",memberIds=listOf(peerUser.id)))}.body<ConversationDto>()
        val path="/v1/conversations/${chat.id}/call/join"
        assertEquals(HttpStatusCode.Unauthorized,client.post(path).status)
        assertEquals(HttpStatusCode.Forbidden,client.post(path){bearerAuth(stranger.accessToken)}.status)
        // No external service is configured in this test environment; authorization succeeds first.
        assertEquals(HttpStatusCode.ServiceUnavailable,client.post(path){bearerAuth(peer.accessToken)}.status)
        database.transaction{c->c.update("UPDATE conversation_members SET left_at=now() WHERE conversation_id=? AND user_id=?",chat.id,peerUser.id)}
        assertEquals(HttpStatusCode.Forbidden,client.post(path){bearerAuth(peer.accessToken)}.status)
    }
}
