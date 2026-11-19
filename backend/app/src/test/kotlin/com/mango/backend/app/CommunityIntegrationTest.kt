package com.mango.backend.app

import com.mango.backend.auth.*
import com.mango.backend.common.*
import com.mango.backend.messaging.*
import com.mango.backend.storage.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.*

class CommunityIntegrationTest:IntegrationTestBase() {
    @BeforeEach fun cleanup()=resetDatabase()
    private suspend fun HttpClient.id(token:TokenPairResponse)=get("/v1/users/me"){bearerAuth(token.accessToken)}.body<CurrentUserResponse>().id
    private suspend fun grant(client:HttpClient,token:TokenPairResponse,role:String) {val id=client.id(token);database.transaction {it.update("INSERT INTO service_admins(user_id,role) VALUES (?,?)",id,role)}}
    private suspend fun HttpClient.change(token:TokenPairResponse,path:String,body:JsonObject=buildJsonObject {})=post("/v1/admin$path") {bearerAuth(token.accessToken);contentType(ContentType.Application.Json);setBody(body)}

    @Test fun `moderator reviews complaints but cannot change identity money SMTP settings or roles`()=withApp {client ->
        val mod=client.newUser("mod@example.com","moderator");grant(client,mod,"MODERATOR")
        val user=client.newUser("user@example.com","regular");val id=client.id(user)
        for(path in listOf("/users/$id/balance","/users/$id/password","/users/$id/identity","/users/$id/role","/smtp-profiles","/settings"))
            assertEquals(HttpStatusCode.Forbidden,client.change(mod,path).status,path)
        for(path in listOf("/smtp-profiles","/settings","/audit","/users/$id/ledger"))
            assertEquals(HttpStatusCode.Forbidden,client.get("/v1/admin$path"){bearerAuth(mod.accessToken)}.status,path)
        assertEquals(HttpStatusCode.OK,client.get("/v1/admin/users"){bearerAuth(mod.accessToken)}.status)
        val report=client.post("/v1/reports"){bearerAuth(user.accessToken);contentType(ContentType.Application.Json);setBody(buildJsonObject {put("targetId",id.toString());put("reason","Moderation test")})}.body<JsonObject>()["id"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.OK,client.change(mod,"/reports/$report",buildJsonObject {put("status","RESOLVED");put("resolution","Reviewed")}).status)
        assertEquals(HttpStatusCode.OK,client.change(mod,"/users/$id/block",buildJsonObject {put("blocked",true);put("reason","Moderation")}).status)
    }

    @Test fun `only owners manage grants and simultaneous demotions retain an owner`()=withApp {client ->
        val first=client.newUser("first@example.com","firstowner");grant(client,first,"OWNER");val a=client.id(first)
        val second=client.newUser("second@example.com","secondowner");grant(client,second,"ADMIN");val b=client.id(second)
        fun role(value:String)=buildJsonObject {put("role",value);put("reason","Role test")}
        assertEquals(HttpStatusCode.Forbidden,client.change(second,"/users/$b/role",role("OWNER")).status)
        assertEquals(HttpStatusCode.UnprocessableEntity,client.change(first,"/users/$a/role",role("USER")).status)
        assertEquals(HttpStatusCode.OK,client.change(first,"/users/$b/role",role("OWNER")).status)
        coroutineScope {
            val x=async {client.change(first,"/users/$a/role",role("USER")).status}
            val y=async {client.change(second,"/users/$b/role",role("USER")).status}
            assertEquals(setOf(HttpStatusCode.OK,HttpStatusCode.UnprocessableEntity),setOf(x.await(),y.await()))
        }
        assertEquals(1,database.readOnly {it.queryOne("SELECT count(*) FROM service_admins WHERE role='OWNER'"){r->r.getInt(1)}})
    }

    @Test fun `subscription history exposes only the current users entries`()=withApp {client ->
        val admin=client.newUser("admin@example.com","administrator");grant(client,admin,"ADMIN")
        val one=client.newUser("one@example.com","firstuser");val two=client.newUser("two@example.com","seconduser")
        val id=client.id(one)
        assertEquals(HttpStatusCode.OK,client.change(admin,"/users/$id/balance",buildJsonObject {put("requestId",UUID.randomUUID().toString());put("points",25);put("days",7);put("months",0);put("reason","Weekly access")}).status)
        val history=client.get("/v1/users/me/account/history"){bearerAuth(one.accessToken)}.body<JsonArray>()
        assertEquals(1,history.size);assertEquals(25,history[0].jsonObject["newPoints"]!!.jsonPrimitive.int)
        assertEquals(0,client.get("/v1/users/me/account/history?userId=$id"){bearerAuth(two.accessToken)}.body<JsonArray>().size)
        assertEquals(HttpStatusCode.Unauthorized,client.get("/v1/users/me/account/history").status)
    }

    @Test fun `transcript is cached but inaccessible to outsiders and after removal from group`()=withApp {client ->
        val owner=client.newUser("owner@example.com","chatowner");val peer=client.newUser("peer@example.com","chatpeer");val outsider=client.newUser("outsider@example.com","outsider")
        val ownerId=client.id(owner);val peerId=client.id(peer);val outsiderId=client.id(outsider)
        val chat=client.post("/v1/conversations"){bearerAuth(owner.accessToken);contentType(ContentType.Application.Json);setBody(CreateConversationRequest("GROUP",listOf(peerId),"Speech"))}.body<ConversationDto>()
        val bytes="OggS-test-audio-fixture".toByteArray();val attachment=UUID.randomUUID()
        val ticket=client.post("/v1/attachments/upload-ticket"){bearerAuth(owner.accessToken);contentType(ContentType.Application.Json);setBody(UploadTicketRequest(attachment,chat.id,"audio/ogg",bytes.size.toLong(),MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)},"test.ogg"))}.body<UploadTicketResponse>()
        assertEquals(HttpStatusCode.NoContent,client.put(ticket.uploadUrl){setBody(bytes)}.status)
        client.post("/v1/conversations/${chat.id}/messages"){bearerAuth(owner.accessToken);contentType(ContentType.Application.Json);setBody(SendMessageRequest(UUID.randomUUID().toString(),"VOICE",attachmentIds=listOf(attachment)))}
        val attachments=AttachmentService(database,AttachmentsRepository(),FileSystemBlobStorage(Path.of(config.storage.directory)),UrlSigner(config.storage.signingKey),time,config.storage.maxFileBytes)
        var calls=0
        val service=TranscriptService(database,attachments,recognize={audio->assertContentEquals(bytes,audio);calls++;buildJsonObject {put("text","Проверка речи");put("language","ru");put("model","test")}})
        assertFailsWith<AppException.Forbidden>{service.transcribe(outsiderId,attachment)}
        assertEquals("Проверка речи",service.transcribe(ownerId,attachment)["text"]!!.jsonPrimitive.content)
        service.transcribe(peerId,attachment);assertEquals(1,calls)
        assertEquals(HttpStatusCode.NoContent,client.delete("/v1/conversations/${chat.id}/members/$peerId"){bearerAuth(owner.accessToken)}.status)
        assertFailsWith<AppException.Forbidden>{service.transcribe(peerId,attachment)}
        assertEquals(1,calls)
    }
}
