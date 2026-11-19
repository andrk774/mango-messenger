package com.mango.backend.app

import com.mango.backend.auth.JWT_AUTH
import com.mango.backend.auth.callerIdentity
import com.mango.backend.common.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.util.UUID

fun Route.referralRoutes(database:Database) {
    authenticate(JWT_AUTH) {
        patch("/v1/users/me/referral") {
            val user=callerIdentity().userId
            val code=call.receive<JsonObject>()["code"]?.jsonPrimitive?.content.orEmpty().trim().uppercase(java.util.Locale.ROOT)
            if(!code.matches(Regex("[A-Z0-9_]{3,32}"))) throw AppException.BadRequest("Код: от 3 до 32 латинских букв, цифр или подчёркиваний")
            try {
                database.transaction {c ->
                    c.update("INSERT INTO referral_codes(user_id,code) VALUES (?,?) ON CONFLICT(user_id) DO UPDATE SET code=EXCLUDED.code",user,code)
                }
            } catch(e:java.sql.SQLException) {
                if(e.isUniqueViolation()) throw AppException.Conflict("Этот реферальный код уже занят")
                throw e
            }
            call.respond(buildJsonObject {put("code",code)})
        }
        get("/v1/users/me/referral") {
            val user=callerIdentity().userId
            call.respond(database.transaction {c ->
                c.update("INSERT INTO referral_codes(user_id,code) VALUES (?,?) ON CONFLICT(user_id) DO NOTHING",user,UUID.randomUUID().toString().replace("-", "").uppercase())
                val code=c.queryOne("SELECT code FROM referral_codes WHERE user_id=?",user){it.getString(1)}!!
                val count=c.queryOne("SELECT count(*) FROM referrals WHERE inviter_id=?",user){it.getInt(1)}!!
                val applied=c.queryOne("SELECT 1 FROM referrals WHERE invitee_id=?",user){true} ?: false
                buildJsonObject {put("code",code);put("invited",count);put("applied",applied)}
            })
        }
        post("/v1/users/me/referral") {
            val user=callerIdentity().userId
            val code=call.receive<JsonObject>()["code"]?.jsonPrimitive?.content.orEmpty().trim().uppercase(java.util.Locale.ROOT)
            if(!code.matches(Regex("[A-Z0-9_]{3,32}"))) throw AppException.BadRequest("Неверный код приглашения")
            call.respond(database.transaction {c ->
                val created=c.queryOne("SELECT created_at FROM users WHERE id=? AND email_verified=true FOR UPDATE",user){it.getTimestamp(1).toInstant()}
                    ?: throw AppException.Forbidden("Подтвердите email")
                val inviter=c.queryOne("SELECT r.user_id FROM referral_codes r JOIN users u ON u.id=r.user_id WHERE r.code=? AND u.created_at<? AND u.email_verified=true",code,created){it.getObject(1,UUID::class.java)}
                    ?: throw AppException.BadRequest("Код не найден или принадлежит более новому аккаунту")
                if(user==inviter || c.isBlocked(inviter)) throw AppException.BadRequest("Приглашение недоступно")
                val previous=c.queryOne("SELECT inviter_id FROM referrals WHERE invitee_id=?",user){it.getObject(1,UUID::class.java)}
                if(previous!=null&&previous!=inviter) throw AppException.Conflict("Вы уже использовали другое приглашение")
                if(previous==null) {
                    if(created.isBefore(java.time.Instant.now().minus(java.time.Duration.ofDays(7)))) throw AppException.BadRequest("Код можно применить в первые 7 дней после регистрации")
                    c.update("INSERT INTO referrals(invitee_id,inviter_id) VALUES (?,?)",user,inviter)
                    for(target in listOf(user,inviter).sortedBy {it.toString()}) {
                        c.update("INSERT INTO account_balances(user_id) VALUES (?) ON CONFLICT DO NOTHING",target)
                        val old=c.queryOne("SELECT points,subscription_until FROM account_balances WHERE user_id=? FOR UPDATE",target) {
                            it.getLong(1) to it.getTimestamp(2)?.toInstant()
                        }!!
                        val now=java.time.Instant.now()
                        val until=maxOf(old.second ?: now,now).plus(java.time.Duration.ofDays(7))
                        c.update("UPDATE account_balances SET subscription_until=? WHERE user_id=?",until,target)
                        c.update("""INSERT INTO balance_ledger(request_id,actor_id,user_id,request_json,old_points,new_points,old_subscription,new_subscription,reason)
                            VALUES (?,?,?,?,?,?,?,?,?)""",UUID.randomUUID(),user,target,"{\"referral\":\"$user\"}",old.first,old.first,old.second,until,"Приглашение подтверждённого пользователя: +7 дней Premium")
                    }
                }
                buildJsonObject {put("applied",true)}
            })
        }
    }
}
