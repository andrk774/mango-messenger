package com.mango.backend.app

import com.mango.backend.auth.JWT_AUTH
import com.mango.backend.auth.callerIdentity
import com.mango.backend.common.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.sql.Connection
import java.time.Instant
import java.time.Duration
import java.util.UUID

@Serializable data class PromoCreate(val code:String,val points:Long=0,val days:Int=0,val maxUses:Int=1,val expiresAt:String)
@Serializable data class PromoCodeInput(val code:String)
@Serializable data class PromoEnabled(val enabled:Boolean)
@Serializable data class CloudContactInput(val id:String,val name:String,val phone:String="",val email:String="")
@Serializable data class CloudContactsBatch(val items:List<CloudContactInput>)
private data class PromoRecord(val id:UUID,val points:Long,val days:Int,val used:Int,val maxUses:Int,val expires:Instant,val enabled:Boolean)
private fun normalizedPromo(value:String)=value.trim().uppercase(java.util.Locale.ROOT).also {
    if(!it.matches(Regex("[A-Z0-9_-]{4,40}"))) invalid("Промокод: 4–40 латинских букв, цифр, дефисов или подчёркиваний")
}
private fun promoAdmin(c:Connection,user:UUID) {
    val role=c.queryOne("SELECT role FROM service_admins WHERE user_id=? FOR SHARE",user){it.getString(1)}
    if(role !in setOf("OWNER","ADMIN") || c.isBlocked(user)) throw AppException.Forbidden("Требуются права владельца или администратора")
}
private fun contactJson(r:java.sql.ResultSet)=buildJsonObject {
    put("id",r.getString("id"));put("name",r.getString("name"));put("phone",r.getString("phone"));put("email",r.getString("email"));put("updatedAt",r.getTimestamp("updated_at").toInstant().toString())
}
private fun saveContact(c:Connection,user:UUID,body:CloudContactInput):JsonObject {
    val id=runCatching{UUID.fromString(body.id)}.getOrElse{invalid("Неверный ID контакта")}
    val name=body.name.trim();val phone=body.phone.trim();val email=body.email.trim()
    if(name.length !in 1..120 || phone.length>64 || email.length>254 || phone.isBlank()&&email.isBlank())invalid("Укажите имя и телефон или email")
    val exists=c.queryOne("SELECT 1 FROM cloud_contacts WHERE owner_id=? AND id=?",user,id){true}==true
    if(!exists&&(c.queryOne("SELECT count(*) FROM cloud_contacts WHERE owner_id=?",user){it.getInt(1)}?:0)>=10000)invalid("Можно сохранить до 10000 записей")
    c.update("INSERT INTO cloud_contacts(owner_id,id,name,phone,email) VALUES (?,?,?,?,?) ON CONFLICT(owner_id,id) DO UPDATE SET name=EXCLUDED.name,phone=EXCLUDED.phone,email=EXCLUDED.email,updated_at=now()",user,id,name,phone,email)
    return c.queryOne("SELECT * FROM cloud_contacts WHERE owner_id=? AND id=?",user,id,map=::contactJson)!!
}

fun Route.promoContactRoutes(db:Database) {
 authenticate(JWT_AUTH) {
    get("/v1/admin/promos") {
        val user=callerIdentity().userId
        call.respond(db.transaction {c->promoAdmin(c,user);c.query("SELECT * FROM promo_codes ORDER BY created_at DESC LIMIT 500") {r->buildJsonObject {
            put("id",r.getString("id"));put("code",r.getString("code"));put("points",r.getLong("points"));put("days",r.getInt("days"));put("maxUses",r.getInt("max_uses"));put("used",r.getInt("used"));put("enabled",r.getBoolean("enabled"));put("expiresAt",r.getTimestamp("expires_at").toInstant().toString())
        }}})
    }
    post("/v1/admin/promos") {
        val user=callerIdentity().userId;val body=call.receive<PromoCreate>();val code=normalizedPromo(body.code)
        val expiry=runCatching {Instant.parse(body.expiresAt)}.getOrElse{invalid("Неверный срок действия")}
        if(body.points !in 0..1_000_000L || body.days !in 0..3650 || body.points==0L&&body.days==0 || body.maxUses !in 1..1_000_000 || !expiry.isAfter(Instant.now()) || expiry.isAfter(Instant.now().plus(Duration.ofDays(3650)))) invalid("Проверьте награду, лимит и срок действия")
        val id=UUID.randomUUID()
        try {db.transaction {c->promoAdmin(c,user)
            c.update("INSERT INTO promo_codes(id,code,points,days,max_uses,expires_at,creator_id) VALUES (?,?,?,?,?,?,?)",id,code,body.points,body.days,body.maxUses,expiry,user)
            c.update("INSERT INTO admin_audit(actor_id,action,target,detail) VALUES (?,?,?,?)",user,"promo.create",id.toString(),"points=${body.points}; days=${body.days}; maxUses=${body.maxUses}; expires=$expiry")
        }} catch(e:java.sql.SQLException){if(e.isUniqueViolation())throw AppException.Conflict("Такой промокод уже существует");throw e}
        call.respond(buildJsonObject {put("id",id.toString())})
    }
    patch("/v1/admin/promos/{id}") {
        val user=callerIdentity().userId;val id=runCatching{UUID.fromString(call.parameters["id"])}.getOrElse{invalid("Неверный ID")};val body=call.receive<PromoEnabled>()
        db.transaction {c->promoAdmin(c,user)
            if(c.queryOne("SELECT id FROM promo_codes WHERE id=? FOR UPDATE",id){true}!=true)throw AppException.NotFound("Промокод не найден")
            c.update("UPDATE promo_codes SET enabled=? WHERE id=?",body.enabled,id)
            c.update("INSERT INTO admin_audit(actor_id,action,target,detail) VALUES (?,?,?,?)",user,"promo.enabled",id.toString(),body.enabled.toString())
        };call.respond(buildJsonObject {put("ok",true)})
    }
    post("/v1/users/me/promos") {
        val user=callerIdentity().userId;val code=normalizedPromo(call.receive<PromoCodeInput>().code)
        call.respond(db.transaction {c->
            if(c.queryOne("SELECT email_verified FROM users WHERE id=? FOR UPDATE",user){it.getBoolean(1)}!=true || c.isBlocked(user))throw AppException.Forbidden("Подтвердите email и проверьте доступ к аккаунту")
            val promo=c.queryOne("SELECT * FROM promo_codes WHERE code=? FOR UPDATE",code) {r->PromoRecord(r.getObject("id",UUID::class.java),r.getLong("points"),r.getInt("days"),r.getInt("used"),r.getInt("max_uses"),r.getTimestamp("expires_at").toInstant(),r.getBoolean("enabled"))} ?: throw AppException.BadRequest("Промокод не найден")
            val id=promo.id;val points=promo.points;val days=promo.days
            val already=c.queryOne("SELECT 1 FROM promo_redemptions WHERE promo_id=? AND user_id=?",id,user){true}==true
            if(!already){
                if(!promo.enabled || !promo.expires.isAfter(Instant.now()) || promo.used>=promo.maxUses)throw AppException.BadRequest("Промокод отключён, истёк или исчерпан")
                c.update("INSERT INTO account_balances(user_id) VALUES (?) ON CONFLICT DO NOTHING",user)
                val old=c.queryOne("SELECT points,subscription_until FROM account_balances WHERE user_id=? FOR UPDATE",user){it.getLong(1) to it.getTimestamp(2)?.toInstant()}!!
                val total=old.first+points;if(total>1_000_000_000_000L)invalid("Превышен предел баланса")
                val now=Instant.now();val until=if(days>0)maxOf(old.second?:now,now).plus(Duration.ofDays(days.toLong())) else old.second
                c.update("UPDATE account_balances SET points=?,subscription_until=? WHERE user_id=?",total,until,user)
                c.update("INSERT INTO promo_redemptions(promo_id,user_id) VALUES (?,?)",id,user)
                c.update("UPDATE promo_codes SET used=used+1 WHERE id=?",id)
                c.update("INSERT INTO balance_ledger(request_id,actor_id,user_id,request_json,old_points,new_points,old_subscription,new_subscription,reason) VALUES (?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),user,user,"{\"promoId\":\"$id\"}",old.first,total,old.second,until,"Промокод $code: +$points баллов, +$days дней Premium")
            }
            buildJsonObject {put("alreadyApplied",already);put("points",points);put("days",days)}
        })
    }
    get("/v1/users/me/contacts") {
        val user=callerIdentity().userId
        call.respond(db.readOnly {c->c.query("SELECT * FROM cloud_contacts WHERE owner_id=? ORDER BY name,id LIMIT 10000",user,map=::contactJson)})
    }
    put("/v1/users/me/contacts") {
        val user=callerIdentity().userId;val body=call.receive<CloudContactInput>()
        call.respond(db.transaction {c->
            c.queryOne("SELECT id FROM users WHERE id=? FOR UPDATE",user){true}
            saveContact(c,user,body)
        })
    }
    post("/v1/users/me/contacts/sync") {
        val user=callerIdentity().userId;val body=call.receive<CloudContactsBatch>()
        if(body.items.size !in 1..500)invalid("Пакет должен содержать от 1 до 500 записей")
        db.transaction {c->c.queryOne("SELECT id FROM users WHERE id=? FOR UPDATE",user){true};body.items.forEach{saveContact(c,user,it)}}
        call.respond(buildJsonObject {put("saved",body.items.size)})
    }
 }
}
