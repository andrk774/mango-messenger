package com.mango.backend.app

import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class AdminFeaturesIntegrationTest:IntegrationTestBase() {
    @BeforeEach fun clean()=resetDatabase()
    @Test fun `server command grants additional owners and is idempotent`()=withApp {client->
        client.newUser("firstowner@example.test","firstowner")
        client.newUser("secondowner@example.test","secondowner")
        assertTrue(bootstrapOwner(database,"firstowner"))
        assertTrue(bootstrapOwner(database,"secondowner"))
        assertFalse(bootstrapOwner(database,"secondowner"))
        assertFailsWith<OwnerCommandRejected>{bootstrapOwner(database,"missinguser")}
        postgres.createConnection("").use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT count(*) FROM service_admins WHERE role='OWNER'").use {r->r.next();assertEquals(2,r.getInt(1))}
                s.executeQuery("SELECT count(*) FROM admin_audit WHERE action='OWNER_BOOTSTRAP'").use {r->r.next();assertEquals(2,r.getInt(1))}
                s.executeUpdate("UPDATE users SET email_verified=false WHERE username='secondowner'")
            }
        }
        assertFailsWith<OwnerCommandRejected>{bootstrapOwner(database,"secondowner")}
        postgres.createConnection("").use { c -> c.createStatement().use { s ->
            s.executeUpdate("UPDATE users SET email_verified=true WHERE username='secondowner'")
            s.executeUpdate("INSERT INTO user_blocks(user_id,actor_id,reason) SELECT id,id,'Test blocked account' FROM users WHERE username='secondowner'")
        } }
        assertFailsWith<OwnerCommandRejected>{bootstrapOwner(database,"secondowner")}
    }
    @Test fun `new service roles enforce permissions on every endpoint`()=withApp {client->
        val owner=client.newUser("newrolesowner@example.test","newrolesowner");grant("newrolesowner@example.test","OWNER")
        val target=client.newUser("roleclient@example.test","roleclient")
        val id=client.get("/v1/users/me"){bearerAuth(target.accessToken)}.body<JsonObject>()["id"]!!.jsonPrimitive.content
        for(role in listOf("ANALYST","BOARD","MANAGER")) {
            assertEquals(HttpStatusCode.OK,client.post("/v1/admin/users/$id/role"){bearerAuth(owner.accessToken);contentType(ContentType.Application.Json);setBody(buildJsonObject{put("role",role);put("reason","Role permission test")})}.status)
            assertEquals(role,client.get("/v1/admin/me"){bearerAuth(target.accessToken)}.body<JsonObject>()["role"]!!.jsonPrimitive.content)
            for((path,permission) in mapOf("overview" to "overview","analytics" to "analytics","users" to "moderate","reports" to "moderate","support" to "moderate","audit" to "auditRead","settings" to "manage","smtp-profiles" to "manage","calls" to "manage","kubernetes/connections" to "manage")) {
                assertEquals(if(ServiceRoles.allows(role,permission)) HttpStatusCode.OK else HttpStatusCode.Forbidden,client.get("/v1/admin/$path"){bearerAuth(target.accessToken)}.status,"$role $path")
            }
            for(path in listOf("users/$id/role","users/$id/password","users/$id/balance","settings","smtp-profiles","calls")) {
                assertEquals(HttpStatusCode.Forbidden,client.post("/v1/admin/$path"){bearerAuth(target.accessToken);contentType(ContentType.Application.Json);setBody(buildJsonObject{})}.status,"$role POST $path")
            }
            assertEquals(if(role=="MANAGER") HttpStatusCode.OK else HttpStatusCode.Forbidden,client.post("/v1/admin/audit/archive"){bearerAuth(target.accessToken);contentType(ContentType.Application.Json);setBody(buildJsonObject{put("confirmation","ARCHIVE_AUDIT")})}.status)
        }
        assertFalse(ServiceRoles.allows("UNKNOWN","manage"))
        assertFalse(ServiceRoles.allows("OWNER","unknown_permission"))
    }
    @Test fun `audit profiles resolve and archive preserves original records`()=withApp {client->
        val owner=client.newUser("auditowner@example.test","auditowner");grant("auditowner@example.test","OWNER")
        val admin=client.newUser("auditadmin@example.test","auditadmin");grant("auditadmin@example.test","ADMIN")
        val target=client.newUser("audittarget@example.test","audittarget")
        val targetId=client.get("/v1/users/me"){bearerAuth(target.accessToken)}.body<JsonObject>()["id"]!!.jsonPrimitive.content
        client.post("/v1/admin/users/$targetId/role"){bearerAuth(owner.accessToken);contentType(ContentType.Application.Json);setBody(buildJsonObject{put("role","MODERATOR");put("reason","Test audit")})}
        val log=client.get("/v1/admin/audit"){bearerAuth(owner.accessToken)}.body<JsonArray>()
        val event=log.first{it.jsonObject["target"]?.jsonPrimitive?.content==targetId}.jsonObject
        assertEquals("auditowner",event["actor"]!!.jsonObject["username"]!!.jsonPrimitive.content)
        assertEquals("audittarget",event["targetUser"]!!.jsonObject["username"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK,client.get("/v1/admin/users/$targetId"){bearerAuth(admin.accessToken)}.status)
        val outsider=client.newUser("outsider@example.test","outsider")
        assertEquals(HttpStatusCode.Forbidden,client.get("/v1/admin/users/$targetId"){bearerAuth(outsider.accessToken)}.status)
        val confirmation=buildJsonObject{put("confirmation","ARCHIVE_AUDIT")}
        assertEquals(HttpStatusCode.Forbidden,client.post("/v1/admin/audit/archive"){bearerAuth(outsider.accessToken);contentType(ContentType.Application.Json);setBody(confirmation)}.status)
        assertTrue(client.post("/v1/admin/audit/archive"){bearerAuth(owner.accessToken);contentType(ContentType.Application.Json);setBody(buildJsonObject{})}.status.value in 400..499)
        for(token in listOf(owner.accessToken,admin.accessToken,target.accessToken)){
            assertEquals(HttpStatusCode.OK,client.get("/v1/admin/audit"){bearerAuth(token)}.status)
            assertEquals(HttpStatusCode.OK,client.post("/v1/admin/audit/archive"){bearerAuth(token);contentType(ContentType.Application.Json);setBody(confirmation)}.status)
        }
        val current=client.get("/v1/admin/audit"){bearerAuth(owner.accessToken)}.body<JsonArray>()
        assertEquals(1,current.size);assertEquals("AUDIT_ARCHIVED",current[0].jsonObject["action"]!!.jsonPrimitive.content)
        val archived=client.get("/v1/admin/audit?archived=true"){bearerAuth(owner.accessToken)}.body<JsonArray>()
        assertTrue(archived.any{it.jsonObject["id"]==event["id"]})
    }
    @Test fun `only owner edits roles and last owner is protected`()=withApp {client->
        val owner=client.newUser("roleowner@example.test","roleowner");grant("roleowner@example.test","OWNER")
        val admin=client.newUser("roleadmin@example.test","roleadmin");grant("roleadmin@example.test","ADMIN")
        val user=client.newUser("roletarget@example.test","roletarget")
        val targetId=client.get("/v1/users/me"){bearerAuth(user.accessToken)}.body<JsonObject>()["id"]!!.jsonPrimitive.content
        val ownerId=client.get("/v1/users/me"){bearerAuth(owner.accessToken)}.body<JsonObject>()["id"]!!.jsonPrimitive.content
        fun body(role:String)=buildJsonObject{put("role",role);put("reason","Acceptance test")}
        assertEquals(HttpStatusCode.Forbidden,client.post("/v1/admin/users/$targetId/role"){bearerAuth(admin.accessToken);contentType(ContentType.Application.Json);setBody(body("OWNER"))}.status)
        assertEquals(HttpStatusCode.OK,client.post("/v1/admin/users/$targetId/role"){bearerAuth(owner.accessToken);contentType(ContentType.Application.Json);setBody(body("MODERATOR"))}.status)
        assertEquals("MODERATOR",client.get("/v1/admin/me"){bearerAuth(user.accessToken)}.body<JsonObject>()["role"]!!.jsonPrimitive.content)
        assertTrue(client.post("/v1/admin/users/$ownerId/role"){bearerAuth(owner.accessToken);contentType(ContentType.Application.Json);setBody(body("USER"))}.status.value in 400..499)
        assertEquals(HttpStatusCode.OK,client.post("/v1/admin/users/$targetId/role"){bearerAuth(owner.accessToken);contentType(ContentType.Application.Json);setBody(body("USER"))}.status)
        assertEquals(HttpStatusCode.Forbidden,client.get("/v1/admin/me"){bearerAuth(user.accessToken)}.status)
    }
    @Test fun `Kubernetes API denies ordinary users and moderators`()=withApp {client->
        val user=client.newUser("kubeuser@example.test","kubeuser")
        val mod=client.newUser("kubemod@example.test","kubemod");grant("kubemod@example.test","MODERATOR")
        val owner=client.newUser("kubeowner@example.test","kubeowner");grant("kubeowner@example.test","OWNER")
        for(path in listOf("/v1/admin/kubernetes/connections","/v1/admin/kubernetes/status?context=local")){
            assertEquals(HttpStatusCode.Unauthorized,client.get(path).status)
            for(token in listOf(user.accessToken,mod.accessToken))
                assertEquals(HttpStatusCode.Forbidden,client.get(path){bearerAuth(token)}.status)
        }
        assertEquals(HttpStatusCode.OK,client.get("/v1/admin/kubernetes/connections"){bearerAuth(owner.accessToken)}.status)
        assertEquals(HttpStatusCode.BadRequest,client.get("/v1/admin/kubernetes/status?context=../secrets"){bearerAuth(owner.accessToken)}.status)
    }
    private fun grant(email:String,role:String){postgres.createConnection("").use{c->c.prepareStatement("INSERT INTO service_admins(user_id,role) SELECT id,? FROM users WHERE email=?").use{s->s.setString(1,role);s.setString(2,email);s.executeUpdate()}}}
    @Test fun `support isolation reply retry closure and moderator role`()=withApp {client->
        val user=client.newUser("supportuser@example.test","supportuser")
        val other=client.newUser("supportother@example.test","supportother")
        val mod=client.newUser("supportmod@example.test","supportmod");grant("supportmod@example.test","MODERATOR")
        val id=UUID.randomUUID().toString()
        val body=buildJsonObject{put("id",id);put("subject","Help");put("body","Question <script>")}
        repeat(2){assertEquals(HttpStatusCode.OK,client.post("/v1/support"){bearerAuth(user.accessToken);contentType(ContentType.Application.Json);setBody(body)}.status)}
        assertEquals(1,client.get("/v1/support"){bearerAuth(user.accessToken)}.body<JsonArray>().size)
        assertEquals(0,client.get("/v1/support"){bearerAuth(other.accessToken)}.body<JsonArray>().size)
        assertEquals(HttpStatusCode.Forbidden,client.get("/v1/support/$id"){bearerAuth(other.accessToken)}.status)
        assertEquals(HttpStatusCode.Forbidden,client.get("/v1/admin/support"){bearerAuth(user.accessToken)}.status)
        val reply=buildJsonObject{put("id",UUID.randomUUID().toString());put("body","Support reply")}
        repeat(2){assertEquals(HttpStatusCode.OK,client.post("/v1/admin/support/$id/reply"){bearerAuth(mod.accessToken);contentType(ContentType.Application.Json);setBody(reply)}.status)}
        val messages=client.get("/v1/support/$id"){bearerAuth(user.accessToken)}.body<JsonArray>()
        assertEquals(2,messages.size);assertTrue(messages.last().jsonObject["staff"]!!.jsonPrimitive.boolean)
        assertEquals(HttpStatusCode.OK,client.post("/v1/admin/support/$id/status"){bearerAuth(mod.accessToken);contentType(ContentType.Application.Json);setBody(buildJsonObject{put("status","CLOSED")})}.status)
        assertTrue(client.post("/v1/support/$id/reply"){bearerAuth(user.accessToken);contentType(ContentType.Application.Json);setBody(reply)}.status.value in 400..499)
        assertEquals(HttpStatusCode.Forbidden,client.get("/v1/admin/analytics"){bearerAuth(mod.accessToken)}.status)
    }
    @Test fun `call configuration admin access encrypted preserved secrets and revision control`()=withApp {client->
        val owner=client.newUser("callsowner@example.test","callsowner");grant("callsowner@example.test","OWNER")
        val admin=client.newUser("callsadmin@example.test","callsadmin");grant("callsadmin@example.test","ADMIN")
        assertEquals(HttpStatusCode.OK,client.get("/v1/admin/calls"){bearerAuth(admin.accessToken)}.status)
        val mod=client.newUser("callsmod@example.test","callsmod");grant("callsmod@example.test","MODERATOR")
        assertEquals(HttpStatusCode.Forbidden,client.get("/v1/admin/calls"){bearerAuth(mod.accessToken)}.status)
        assertEquals(HttpStatusCode.Forbidden,client.post("/v1/admin/calls"){bearerAuth(mod.accessToken);contentType(ContentType.Application.Json);setBody(buildJsonObject{})}.status)
        val fake=com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1",0),0)
        fake.createContext("/twirp/livekit.RoomService/ListRooms"){exchange->val bytes="{\"rooms\":[]}".toByteArray();exchange.sendResponseHeaders(200,bytes.size.toLong());exchange.responseBody.use{it.write(bytes)}};fake.start()
        try {
            val secret="s".repeat(48);val turn="t".repeat(48)
            fun body(revision:Int,clear:Boolean=false)=buildJsonObject{
                put("revision",revision);put("enabled",true);put("url","wss://calls.example.test");put("internalUrl","http://127.0.0.1:${fake.address.port}");put("key","test-key");put("secret",if(clear)"" else secret)
                put("turnUrls",buildJsonArray{add("turns:turn.example.test:443?transport=tcp")});put("turnSecret",if(clear)"" else turn);put("relayOnly",true)
            }
            assertEquals(HttpStatusCode.OK,client.post("/v1/admin/calls"){bearerAuth(admin.accessToken);contentType(ContentType.Application.Json);setBody(body(0))}.status)
            val shown=client.get("/v1/admin/calls"){bearerAuth(owner.accessToken)}.body<JsonObject>()
            assertEquals(1,shown["revision"]!!.jsonPrimitive.int);assertFalse(shown.toString().contains(secret));assertFalse(shown.toString().contains(turn))
            assertEquals(HttpStatusCode.OK,client.post("/v1/admin/calls"){bearerAuth(owner.accessToken);contentType(ContentType.Application.Json);setBody(body(1,true))}.status)
            assertTrue(client.post("/v1/admin/calls"){bearerAuth(admin.accessToken);contentType(ContentType.Application.Json);setBody(body(0))}.status.value in 400..499)
            postgres.createConnection("").use{c->c.createStatement().use{s->s.executeQuery("SELECT cipher FROM call_settings").use{r->assertTrue(r.next());assertFalse(r.getString(1).contains(secret));assertFalse(r.getString(1).contains("calls.example.test"))}}}
            val data=client.get("/v1/admin/analytics"){bearerAuth(owner.accessToken)}.body<JsonObject>()
            assertEquals(14,data["daily"]!!.jsonArray.size)
            assertEquals("available",data["calls"]!!.jsonObject["live"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        }finally{fake.stop(0)}
    }
    @Test fun `turn urls reject injection and credentials expire`() {
        assertFails{validateTurnUrls(listOf("https://evil.test"))}
        assertFails{validateTurnUrls(listOf("turn:host:99999"))}
        val now=java.time.Instant.ofEpochSecond(1000)
        val server=turnIce(listOf("turn:example.test:3478"),"s".repeat(32),"user",now).first().jsonObject
        assertEquals("87400:user",server["username"]!!.jsonPrimitive.content)
        assertEquals(28,server["credential"]!!.jsonPrimitive.content.length)
    }
}
