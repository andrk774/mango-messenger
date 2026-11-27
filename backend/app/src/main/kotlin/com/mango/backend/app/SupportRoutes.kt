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
import java.time.Duration

fun Route.supportRoutes(db:Database,admin:AdminService,limiter:RateLimiter) {
    authenticate(JWT_AUTH) {
        for(staff in listOf(false,true))route(if(staff)"/v1/admin/support" else "/v1/support") {
            get {
                val actor=callerIdentity().userId
                val offset=call.request.queryParameters["offset"]?.toIntOrNull()?:0
                if(offset !in 0..1000000)invalid("Неверная страница")
                call.respond(db.readOnly { c ->
                    if(staff)admin.requireAdmin(c,actor,"moderate")
                    JsonArray(c.query("SELECT id,user_id,subject,status,created_at,updated_at FROM support_tickets WHERE (? OR user_id=?) ORDER BY updated_at DESC,id LIMIT 50 OFFSET ?",staff,actor,offset){r->buildJsonObject {
                        for(k in listOf("id","subject","status"))put(k,r.getString(k));put("userId",r.getString("user_id"));put("createdAt",r.getString("created_at"));put("updatedAt",r.getString("updated_at"))
                    }})
                })
            }
            if(!staff)post {
                val actor=callerIdentity().userId;val quota=limiter.acquire("support-create:$actor",5,Duration.ofMinutes(10));if(!quota.allowed)throw AppException.RateLimited(quota.retryAfterSeconds)
                val body=call.receive<JsonObject>();val id=body.id("id");val subject=body.text("subject",160);val text=body.text("body",5000)
                db.transaction { c ->
                    val inserted=c.update("INSERT INTO support_tickets(id,user_id,subject) VALUES(?,?,?) ON CONFLICT(id) DO NOTHING",id,actor,subject)
                    if(c.queryOne("SELECT user_id FROM support_tickets WHERE id=?",id){it.getObject(1,UUID::class.java)}!=actor)throw AppException.Forbidden()
                    if(inserted>0)c.update("INSERT INTO support_messages(id,ticket_id,author_id,staff,body) VALUES(?,?,?,false,?)",UUID.randomUUID(),id,actor,text)
                }
                call.respond(buildJsonObject{put("id",id.toString())})
            }
            get("/{id}") {
                val actor=callerIdentity().userId;val id=runCatching{UUID.fromString(call.parameters["id"])}.getOrElse{invalid("Неверный тикет")}
                val before=call.request.queryParameters["before"]?.toLongOrNull()?:Long.MAX_VALUE
                call.respond(db.readOnly { c ->
                    if(staff)admin.requireAdmin(c,actor,"moderate")
                    val owner=c.queryOne("SELECT user_id FROM support_tickets WHERE id=?",id){it.getObject(1,UUID::class.java)}?:throw AppException.NotFound("Обращение не найдено")
                    if(!staff&&owner!=actor)throw AppException.Forbidden()
                    JsonArray(c.query("SELECT seq,id,staff,body,created_at FROM support_messages WHERE ticket_id=? AND seq<? ORDER BY seq DESC LIMIT 100",id,before){r->buildJsonObject {
                        put("seq",r.getLong("seq"));put("id",r.getString("id"));put("staff",r.getBoolean("staff"));put("body",r.getString("body"));put("createdAt",r.getString("created_at"))
                    }}.reversed())
                })
            }
            post("/{id}/reply") {
                val actor=callerIdentity().userId;val id=runCatching{UUID.fromString(call.parameters["id"])}.getOrElse{invalid("Неверный тикет")}
                val quota=limiter.acquire("support-reply:$actor",30,Duration.ofMinutes(1));if(!quota.allowed)throw AppException.RateLimited(quota.retryAfterSeconds)
                val body=call.receive<JsonObject>();val messageId=body.id("id");val text=body.text("body",5000)
                db.transaction { c ->
                    if(staff)admin.requireAdmin(c,actor,"moderate")
                    val ticket=c.queryOne("SELECT user_id,status FROM support_tickets WHERE id=? FOR UPDATE",id){it.getObject(1,UUID::class.java) to it.getString(2)}?:throw AppException.NotFound("Обращение не найдено")
                    if(!staff&&ticket.first!=actor)throw AppException.Forbidden()
                    if(ticket.second=="CLOSED")invalid("Обращение закрыто")
                    val inserted=c.update("INSERT INTO support_messages(id,ticket_id,author_id,staff,body) VALUES(?,?,?,?,?) ON CONFLICT(id) DO NOTHING",messageId,id,actor,staff,text)
                    if(inserted>0){c.update("UPDATE support_tickets SET status=?,updated_at=now() WHERE id=?",if(staff)"WAITING_USER" else "OPEN",id)
                        if(staff)c.update("INSERT INTO admin_audit(actor_id,action,target,detail) VALUES (?,?,?,?)",actor,"SUPPORT_REPLY",id.toString(),"Reply added")}
                    else if(c.queryOne("SELECT ticket_id,author_id FROM support_messages WHERE id=?",messageId){it.getObject(1,UUID::class.java) to it.getObject(2,UUID::class.java)} != (id to actor))throw AppException.Forbidden()
                }
                call.respond(buildJsonObject{put("ok",true)})
            }
            if(staff)post("/{id}/status") {
                val actor=callerIdentity().userId;val id=runCatching{UUID.fromString(call.parameters["id"])}.getOrElse{invalid("Неверный тикет")}
                val status=call.receive<JsonObject>().text("status",20);if(status !in setOf("OPEN","IN_PROGRESS","WAITING_USER","CLOSED"))invalid("Неверный статус")
                db.transaction{c->admin.requireAdmin(c,actor,"moderate");if(c.update("UPDATE support_tickets SET status=?,updated_at=now() WHERE id=?",status,id)==0)throw AppException.NotFound("Обращение не найдено")
                    c.update("INSERT INTO admin_audit(actor_id,action,target,detail) VALUES (?,?,?,?)",actor,"SUPPORT_STATUS",id.toString(),status)}
                call.respond(buildJsonObject{put("ok",true)})
            }
        }
    }
}
