package com.mango.backend.app

import com.mango.backend.common.queryOne
import com.mango.backend.common.update
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class ReferralIntegrationTest:IntegrationTestBase() {
    @Test fun `referral rewards both once and rejects reuse and self invitation`()=withApp {client->
        val inviter=client.newUser("ref1@example.com","refone")
        val friend=client.newUser("ref2@example.com","reftwo")
        val other=client.newUser("ref3@example.com","refthree")
        database.transaction {c ->
            c.update("UPDATE users SET created_at=created_at-interval '2 minutes' WHERE username='refone'")
            c.update("UPDATE users SET created_at=created_at-interval '1 minute' WHERE username='reftwo'")
        }
        suspend fun code(token:String)=client.get("/v1/users/me/referral"){bearerAuth(token)}.body<JsonObject>()["code"]!!.jsonPrimitive.content
        suspend fun claim(token:String,code:String)=client.post("/v1/users/me/referral") {
            bearerAuth(token);contentType(ContentType.Application.Json);setBody(buildJsonObject {put("code",code)})
        }
        suspend fun rename(token:String,code:String)=client.patch("/v1/users/me/referral") {
            bearerAuth(token);contentType(ContentType.Application.Json);setBody(buildJsonObject {put("code",code)})
        }
        val original=code(inviter.accessToken)
        assertEquals(HttpStatusCode.OK,rename(inviter.accessToken," andrey774 ").status)
        val invite=code(inviter.accessToken)
        assertEquals("ANDREY774",invite)
        assertEquals(HttpStatusCode.Conflict,rename(friend.accessToken,"andrey774").status)
        assertEquals(HttpStatusCode.BadRequest,rename(inviter.accessToken,"bad code").status)
        assertEquals(HttpStatusCode.BadRequest,rename(inviter.accessToken,"AB").status)
        assertEquals(HttpStatusCode.BadRequest,claim(friend.accessToken,original).status)
        assertEquals(HttpStatusCode.BadRequest,claim(inviter.accessToken,invite).status)
        assertEquals(HttpStatusCode.BadRequest,claim(inviter.accessToken,code(other.accessToken)).status)
        repeat(2){assertEquals(HttpStatusCode.OK,claim(friend.accessToken,invite).status)}
        val rows=database.readOnly {it.queryOne("SELECT count(*),min(subscription_until),max(subscription_until) FROM account_balances WHERE subscription_until IS NOT NULL") {r->Triple(r.getInt(1),r.getTimestamp(2).toInstant(),r.getTimestamp(3).toInstant())}}!!
        assertEquals(2,rows.first)
        assertTrue(rows.second.isAfter(java.time.Instant.now().plus(java.time.Duration.ofDays(6))))
        assertTrue(rows.third.isBefore(java.time.Instant.now().plus(java.time.Duration.ofDays(8))))
        assertEquals(2,database.readOnly {it.queryOne("SELECT count(*) FROM balance_ledger"){r->r.getInt(1)}})
        assertEquals(HttpStatusCode.OK,rename(inviter.accessToken,"SECOND_CODE").status)
        assertEquals(HttpStatusCode.OK,claim(friend.accessToken,"second_code").status)
        assertEquals(2,database.readOnly {it.queryOne("SELECT count(*) FROM balance_ledger"){r->r.getInt(1)}})
        assertEquals(HttpStatusCode.Unauthorized,client.patch("/v1/users/me/referral") {contentType(ContentType.Application.Json);setBody("{\"code\":\"NOAUTH\"}")}.status)
        assertEquals(HttpStatusCode.BadRequest,claim(friend.accessToken,code(other.accessToken)).status)
        assertEquals(HttpStatusCode.Unauthorized,client.get("/v1/users/me/referral").status)
        assertEquals(1,client.get("/v1/users/me/referral"){bearerAuth(inviter.accessToken)}.body<JsonObject>()["invited"]!!.jsonPrimitive.int)
    }
}
