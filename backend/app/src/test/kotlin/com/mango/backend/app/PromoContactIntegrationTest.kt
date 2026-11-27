package com.mango.backend.app

import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class PromoContactIntegrationTest:IntegrationTestBase(){
 @BeforeEach fun clean()=resetDatabase()
 private fun grant(email:String,role:String){postgres.createConnection("").use{c->c.prepareStatement("INSERT INTO service_admins(user_id,role) SELECT id,? FROM users WHERE email=?").use{s->s.setString(1,role);s.setString(2,email);s.executeUpdate()}}}
 private fun promo(code:String="WELCOME26",max:Int=10)=PromoCreate(code,100,7,max,Instant.now().plusSeconds(86400).toString())
 @Test fun `promos enforce roles and grant once under concurrent retries`()=withApp{client->
  val admin=client.newUser("admin@example.com","adminpromo");grant("admin@example.com","ADMIN")
  val user=client.newUser("member@example.com","memberpromo")
  val mod=client.newUser("mod@example.com","modpromo");grant("mod@example.com","MODERATOR")
  for(token in listOf(user.accessToken,mod.accessToken))assertEquals(HttpStatusCode.Forbidden,client.post("/v1/admin/promos"){bearerAuth(token);contentType(ContentType.Application.Json);setBody(promo())}.status)
  assertEquals(HttpStatusCode.OK,client.post("/v1/admin/promos"){bearerAuth(admin.accessToken);contentType(ContentType.Application.Json);setBody(promo())}.status)
  val responses=coroutineScope{(1..5).map{async{client.post("/v1/users/me/promos"){bearerAuth(user.accessToken);contentType(ContentType.Application.Json);setBody(PromoCodeInput(" welcome26 "))}}}.awaitAll()}
  responses.forEach{assertEquals(HttpStatusCode.OK,it.status)}
  assertEquals(1,responses.map{it.body<JsonObject>()}.count{!it["alreadyApplied"]!!.jsonPrimitive.boolean})
  val balance=client.get("/v1/users/me/account"){bearerAuth(user.accessToken)}.body<JsonObject>()
  assertEquals(100,balance["points"]!!.jsonPrimitive.int)
  val history=client.get("/v1/users/me/account/history"){bearerAuth(user.accessToken)}.body<JsonArray>();assertEquals(1,history.size)
  assertEquals(HttpStatusCode.Forbidden,client.get("/v1/admin/promos"){bearerAuth(user.accessToken)}.status)
 }
 @Test fun `last activation capacity disabled and expiry do not grant rewards`()=withApp{client->
  val admin=client.newUser("owner@example.com","ownerpromo");grant("owner@example.com","OWNER")
  val a=client.newUser("a@example.com","promoa");val b=client.newUser("b@example.com","promob")
  suspend fun create(code:String)=client.post("/v1/admin/promos"){bearerAuth(admin.accessToken);contentType(ContentType.Application.Json);setBody(promo(code,1))}.body<JsonObject>()["id"]!!.jsonPrimitive.content
  suspend fun redeem(token:String,code:String)=client.post("/v1/users/me/promos"){bearerAuth(token);contentType(ContentType.Application.Json);setBody(PromoCodeInput(code))}
  create("LASTONE")
  val statuses=coroutineScope{listOf(a,b).map{async{redeem(it.accessToken,"LASTONE").status}}.awaitAll()}
  assertEquals(1,statuses.count{it==HttpStatusCode.OK});assertEquals(1,statuses.count{it==HttpStatusCode.BadRequest})
  val disabled=create("DISABLED")
  client.patch("/v1/admin/promos/$disabled"){bearerAuth(admin.accessToken);contentType(ContentType.Application.Json);setBody(PromoEnabled(false))}
  assertEquals(HttpStatusCode.BadRequest,redeem(a.accessToken,"DISABLED").status)
  create("EXPIRED")
  postgres.createConnection("").use{c->c.createStatement().executeUpdate("UPDATE promo_codes SET expires_at=now()-interval '1 second' WHERE code='EXPIRED'")}
  assertEquals(HttpStatusCode.BadRequest,redeem(a.accessToken,"EXPIRED").status)
 }
 @Test fun `cloud contacts belong to owner retries merge and invalid batches roll back`()=withApp{client->
  val a=client.newUser("contacta@example.com","contacta");val b=client.newUser("contactb@example.com","contactb")
  val id=UUID.randomUUID().toString();val row=CloudContactInput(id,"Friend","+10000000000")
  suspend fun sync(token:String,rows:List<CloudContactInput>)=client.post("/v1/users/me/contacts/sync"){bearerAuth(token);contentType(ContentType.Application.Json);setBody(CloudContactsBatch(rows))}
  repeat(2){assertEquals(HttpStatusCode.OK,sync(a.accessToken,listOf(row)).status)}
  assertEquals(HttpStatusCode.OK,sync(b.accessToken,listOf(row.copy(name="Other owner"))).status)
  val rows=client.get("/v1/users/me/contacts"){bearerAuth(a.accessToken)}.body<JsonArray>();assertEquals(1,rows.size);assertEquals("Friend",rows[0].jsonObject["name"]!!.jsonPrimitive.content)
  assertEquals(HttpStatusCode.UnprocessableEntity,sync(a.accessToken,listOf(row.copy(name="Should rollback"),row.copy(id="invalid"))).status)
  val after=client.get("/v1/users/me/contacts"){bearerAuth(a.accessToken)}.body<JsonArray>();assertEquals("Friend",after[0].jsonObject["name"]!!.jsonPrimitive.content)
  assertEquals(HttpStatusCode.Unauthorized,client.get("/v1/users/me/contacts").status)
 }
}
