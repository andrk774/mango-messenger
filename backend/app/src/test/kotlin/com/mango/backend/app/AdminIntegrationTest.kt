package com.mango.backend.app

import com.mango.backend.auth.*
import com.mango.backend.common.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.close
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class AdminIntegrationTest:IntegrationTestBase() {
    @Test fun `kubernetes operator settings require management role and reject command injection`()=withApp { client ->
        val admin=client.admin()
        val user=client.newUser("operator-user@example.com","operatoruser")
        fun config(context:String="docker-desktop")=buildJsonObject {
            put("context",context);put("namespace","mango-dev");put("deployment","backend");put("service","backend")
        }
        assertEquals(HttpStatusCode.Unauthorized,client.get("/v1/admin/kubernetes").status)
        assertEquals(HttpStatusCode.Forbidden,client.change(user,"/kubernetes",config()).status)
        assertEquals(HttpStatusCode.OK,client.change(admin,"/kubernetes",config()).status)
        val saved=client.get("/v1/admin/kubernetes") {bearerAuth(admin.accessToken)}.body<JsonObject>()
        assertEquals(config(),saved)
        for(value in listOf("x;whoami","$(whoami)","--help","a b","x\nwhoami")) {
            assertEquals(HttpStatusCode.UnprocessableEntity,client.change(admin,"/kubernetes",config(value)).status)
        }
        assertEquals(HttpStatusCode.UnprocessableEntity,client.change(admin,"/kubernetes",buildJsonObject {
            config().forEach { (k,v)->put(k,v) };put("token","must-not-be-stored")
        }).status)
        val audit=client.get("/v1/admin/audit") {bearerAuth(admin.accessToken)}.body<JsonArray>()
        assertEquals(1,audit.count {it.jsonObject["action"]?.jsonPrimitive?.content=="KUBERNETES_SETTINGS"})
        val adminId=client.id(admin)
        database.transaction {it.update("UPDATE service_admins SET role='MODERATOR' WHERE user_id=?",adminId)}
        assertEquals(HttpStatusCode.Forbidden,client.get("/v1/admin/kubernetes") {bearerAuth(admin.accessToken)}.status)
        assertEquals(HttpStatusCode.Forbidden,client.change(admin,"/kubernetes",config()).status)
    }
    @BeforeEach fun cleanup()=resetDatabase()
    private suspend fun HttpClient.id(token:TokenPairResponse):UUID=get("/v1/users/me") { bearerAuth(token.accessToken) }.body<CurrentUserResponse>().id
    private suspend fun HttpClient.admin():TokenPairResponse {
        val token=newUser("admin@example.com","administrator")
        val id=id(token)
        database.transaction { it.update("INSERT INTO service_admins(user_id) VALUES (?)",id) }
        return token
    }
    private suspend fun HttpClient.change(token:TokenPairResponse,path:String,body:JsonObject)=post("/v1/admin$path") {
        bearerAuth(token.accessToken); contentType(ContentType.Application.Json); setBody(body)
    }
    @Test fun `panel and all admin data require a service grant`()=withApp { client ->
        val user=client.newUser("user@example.com","regular")
        assertEquals(HttpStatusCode.OK,client.get("/admin/").status)
        for(path in listOf("/me","/overview","/users","/reports","/audit","/settings","/smtp-profiles")) {
            assertEquals(HttpStatusCode.Unauthorized,client.get("/v1/admin$path").status)
            assertEquals(HttpStatusCode.Forbidden,client.get("/v1/admin$path") { bearerAuth(user.accessToken) }.status)
        }
        val admin=client.admin()
        assertEquals(HttpStatusCode.OK,client.get("/v1/admin/overview") { bearerAuth(admin.accessToken) }.status)
        assertEquals(HttpStatusCode.Forbidden,client.change(user,"/settings",buildJsonObject { put("serverName","No");put("registrationOpen",false) }).status)
    }
    @Test fun `block revokes existing tokens login refresh and unblock does not restore old sessions`()=withApp { client ->
        val admin=client.admin(); val user=client.newUser("user@example.com","regular"); val id=client.id(user)
        fun block(value:Boolean)=buildJsonObject { put("blocked",value);put("reason","Acceptance moderation") }
        assertEquals(HttpStatusCode.OK,client.change(admin,"/users/$id/block",block(true)).status)
        assertEquals(HttpStatusCode.Unauthorized,client.get("/v1/users/me") { bearerAuth(user.accessToken) }.status)
        assertEquals(HttpStatusCode.Forbidden,client.post("/v1/auth/login") { contentType(ContentType.Application.Json);setBody(LoginRequest("user@example.com","ТестовыйПароль-2026")) }.status)
        assertEquals(HttpStatusCode.Unauthorized,client.post("/v1/auth/refresh") { contentType(ContentType.Application.Json);setBody(RefreshRequest(user.refreshToken)) }.status)
        assertEquals(HttpStatusCode.OK,client.change(admin,"/users/$id/block",block(false)).status)
        assertEquals(HttpStatusCode.Unauthorized,client.get("/v1/users/me") { bearerAuth(user.accessToken) }.status)
        assertEquals(HttpStatusCode.OK,client.post("/v1/auth/login") { contentType(ContentType.Application.Json);setBody(LoginRequest("user@example.com","ТестовыйПароль-2026")) }.status)
        val adminId=client.id(admin)
        assertEquals(HttpStatusCode.UnprocessableEntity,client.change(admin,"/users/$adminId/block",block(true)).status)
    }
    @Test fun `balance changes are atomic idempotent and cannot overdraft`()=withApp { client ->
        val admin=client.admin();val user=client.newUser("user@example.com","regular");val id=client.id(user)
        fun body(key:UUID,points:Long)=buildJsonObject {put("requestId",key.toString());put("points",points);put("days",2);put("months",1);put("reason","Test credit")}
        val key=UUID.randomUUID();val request=body(key,50)
        coroutineScope {
            val a=async {client.change(admin,"/users/$id/balance",request).status};val b=async {client.change(admin,"/users/$id/balance",request).status}
            assertEquals(HttpStatusCode.OK,a.await());assertEquals(HttpStatusCode.OK,b.await())
        }
        assertEquals(50,database.readOnly { it.queryOne("SELECT points FROM account_balances WHERE user_id=?",id) { r->r.getLong(1) } })
        assertEquals(1,database.readOnly { it.queryOne("SELECT count(*) FROM balance_ledger WHERE user_id=?",id) { r->r.getInt(1) } })
        val expected=time.now().atZone(java.time.ZoneOffset.UTC).plusMonths(1).plusDays(2).toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        assertTrue(kotlin.math.abs(java.time.Duration.between(expected,database.readOnly {it.queryOne("SELECT subscription_until FROM account_balances WHERE user_id=?",id) {r->r.instant("subscription_until")} }).toNanos()) <= 1_000, "PostgreSQL timestamp precision is one microsecond")
        assertEquals(HttpStatusCode.Conflict,client.change(admin,"/users/$id/balance",body(key,100)).status)
        assertEquals(HttpStatusCode.UnprocessableEntity,client.change(admin,"/users/$id/balance",body(UUID.randomUUID(),-51)).status)
        assertEquals(50,database.readOnly {it.queryOne("SELECT points FROM account_balances WHERE user_id=?",id) {r->r.getLong(1)}})
    }
    @Test fun `identity and password change preserve account and revoke previous credentials`()=withApp { client ->
        val admin=client.admin();val user=client.newUser("user@example.com","regular");val id=client.id(user)
        assertEquals(HttpStatusCode.OK,client.change(admin,"/users/$id/identity",buildJsonObject {put("name","Новое имя");put("username","renamed");put("reason","Requested rename")}).status)
        val me=client.get("/v1/users/me") {bearerAuth(user.accessToken)}.body<CurrentUserResponse>()
        assertEquals("Новое имя",me.displayName);assertEquals("renamed",me.username);assertEquals(id,me.id)
        val secret="New-secret-test-2026"
        assertEquals(HttpStatusCode.OK,client.change(admin,"/users/$id/password",buildJsonObject {put("password",secret);put("reason","Requested reset")}).status)
        assertEquals(HttpStatusCode.Unauthorized,client.get("/v1/users/me") {bearerAuth(user.accessToken)}.status)
        assertEquals(HttpStatusCode.Unauthorized,client.post("/v1/auth/login") {contentType(ContentType.Application.Json);setBody(LoginRequest("user@example.com","ТестовыйПароль-2026"))}.status)
        assertEquals(HttpStatusCode.OK,client.post("/v1/auth/login") {contentType(ContentType.Application.Json);setBody(LoginRequest("user@example.com",secret))}.status)
        val audit=client.get("/v1/admin/audit") {bearerAuth(admin.accessToken)}.body<JsonArray>().toString()
        assertFalse(audit.contains(secret));assertTrue(audit.contains("PASSWORD_RESET"))
    }
    @Test fun `reports and registration settings have real effects`()=withApp { client ->
        val admin=client.admin();val user=client.newUser("user@example.com","regular");val id=client.id(user)
        val report=client.post("/v1/reports") {bearerAuth(user.accessToken);contentType(ContentType.Application.Json);setBody(buildJsonObject {put("targetId",id.toString());put("reason","Test report")})}.body<JsonObject>()
        val reportId=report["id"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.OK,client.change(admin,"/reports/$reportId",buildJsonObject {put("status","RESOLVED");put("resolution","Reviewed")}).status)
        val reports=client.get("/v1/admin/reports") {bearerAuth(admin.accessToken)}.body<JsonArray>()
        assertEquals("RESOLVED",reports.single().jsonObject["status"]!!.jsonPrimitive.content)
        client.change(admin,"/settings",buildJsonObject {put("serverName","Test server");put("registrationOpen",false)})
        assertEquals(HttpStatusCode.Forbidden,client.post("/v1/auth/register") {contentType(ContentType.Application.Json);setBody("{}")}.status)
    }
    @Test fun `SMTP passwords are encrypted never returned and selected configuration is used`()=withApp {client ->
        val admin=client.admin();val profile=UUID.randomUUID();val secret="smtp-test-password"
        val body=buildJsonObject {put("id",profile.toString());put("name","Test SMTP");put("host","smtp.example.com");put("port",587);put("security","STARTTLS");put("sender","mail@example.com");put("username","mail@example.com");put("password",secret)}
        assertEquals(HttpStatusCode.OK,client.change(admin,"/smtp-profiles",body).status)
        val listed=client.get("/v1/admin/smtp-profiles") {bearerAuth(admin.accessToken)}.body<JsonArray>().toString()
        assertFalse(listed.contains(secret));assertFalse(listed.contains("password_cipher"))
        val encrypted=database.readOnly {it.queryOne("SELECT password_cipher FROM smtp_profiles WHERE id=?",profile) {r->r.getString(1)}}
        assertNotNull(encrypted);assertNotEquals(secret,encrypted)
        client.change(admin,"/smtp-select",buildJsonObject {put("id",profile.toString())})
        val selected=AdminService(database,config,time).selectedMailConfig()!!
        assertEquals(secret,selected.password);assertEquals("smtp.example.com",selected.host)
        val keep=JsonObject(body.toMutableMap().apply {put("password",JsonPrimitive(""))})
        assertEquals(HttpStatusCode.OK,client.change(admin,"/smtp-profiles",keep).status)
        assertEquals(secret,AdminService(database,config,time).selectedMailConfig()!!.password)
        val unsafe=JsonObject(body.toMutableMap().apply {put("security",JsonPrimitive("NONE"))})
        assertEquals(HttpStatusCode.UnprocessableEntity,client.change(admin,"/smtp-profiles",unsafe).status)
        client.change(admin,"/smtp-select",buildJsonObject {put("id",JsonNull)})
        assertNull(AdminService(database,config,time).selectedMailConfig())
    }
    @Test fun `blocked websocket is closed and cannot reconnect`()=withApp {client ->
        val admin=client.admin();val user=client.newUser("socket@example.com","socketuser");val id=client.id(user)
        val sockets=createClient {install(WebSockets)}
        val ws=sockets.webSocketSession("/v1/ws")
        ws.send(Frame.Text("""{"type":"AUTH","token":"${user.accessToken}"}"""))
        val hello=kotlinx.coroutines.withTimeout(3000) {ws.incoming.receive()} as Frame.Text
        assertTrue(hello.readText().contains("SERVER_HELLO"))
        client.change(admin,"/users/$id/block",buildJsonObject {put("blocked",true);put("reason","Socket test")})
        assertNotNull(kotlinx.coroutines.withTimeout(8000) {ws.closeReason.await()})
        val denied=sockets.webSocketSession("/v1/ws")
        denied.send(Frame.Text("""{"type":"AUTH","token":"${user.accessToken}"}"""))
        val frame=kotlinx.coroutines.withTimeout(3000) {denied.incoming.receive()} as Frame.Text
        assertTrue(frame.readText().contains("UNAUTHENTICATED"));denied.close()
    }
    @Test fun `home and web entry are public while account balance is private`()=withApp {client ->
        for(path in listOf("/","/web/","/web/app.js","/web/style.css","/admin/")) {
            val r=client.get(path);assertEquals(HttpStatusCode.OK,r.status)
            assertTrue(r.headers["Content-Security-Policy"]!!.contains("frame-ancestors 'none'"))
        }
        assertEquals(HttpStatusCode.Unauthorized,client.get("/v1/users/me/account").status)
        val user=client.newUser("account@example.com","accountuser")
        val account=client.get("/v1/users/me/account") {bearerAuth(user.accessToken)}.body<JsonObject>()
        assertEquals(0,account["points"]!!.jsonPrimitive.int)
        assertEquals(JsonNull,account["subscriptionUntil"])
    }
}
