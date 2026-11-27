package com.mango.backend.app

import com.mango.backend.auth.JWT_AUTH
import com.mango.backend.auth.callerIdentity
import com.mango.backend.common.Database
import com.mango.backend.common.queryOne
import com.mango.backend.common.query
import com.mango.backend.common.update
import com.mango.backend.common.AppException
import io.ktor.server.request.receive
import io.ktor.http.ContentType
import io.ktor.server.auth.authenticate
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

fun Route.webRoutes(database:Database,limiter:com.mango.backend.common.RateLimiter=com.mango.backend.common.InMemoryRateLimiter(com.mango.backend.common.SystemTime),calls:CallSettingsStore?=null) {
    referralRoutes(database)
    promoContactRoutes(database)
    callRoutes(database,limiter=limiter,provider={calls?.current()?:CallConfig.load()})
    for ((path,file,type) in listOf(
        Triple("/web/mango-mark.svg","mango-mark.svg","image/svg+xml"),
        Triple("/web/admin-sso.js","admin-sso.js","application/javascript"),
        Triple("/web/browser-session.js","browser-session.js","application/javascript"),
        Triple("/admin/browser-session.js","browser-session.js","application/javascript"),
        Triple("/admin/mango-mark.svg","mango-mark.svg","image/svg+xml"),
        Triple("/","index.html","text/html"),Triple("/web","web.html","text/html"),Triple("/web/","web.html","text/html"),
        Triple("/web/call.html","call.html","text/html"),Triple("/web/call.css","call.css","text/css"),Triple("/web/call.js","call.js","application/javascript"),Triple("/web/calls-ui.js","calls-ui.js","application/javascript"),Triple("/web/livekit-client.umd.js","livekit-client.umd.js","application/javascript"),
        Triple("/web/studio.css","studio.css","text/css"),Triple("/web/kanban.css","kanban.css","text/css"),Triple("/web/chat-live.js","chat-live.js","application/javascript"),Triple("/web/kanban.js","kanban.js","application/javascript"),Triple("/web/media.js","media.js","application/javascript"),Triple("/web/app.js","app.js","application/javascript"),Triple("/web/welcome.js","welcome.js","application/javascript"),Triple("/web/style.css","style.css","text/css"),
    )) get(path) {
        call.response.headers.append("Cache-Control","no-store")
        val mediaOrigin=if(file=="call.html") runCatching {(calls?.current()?:CallConfig.load()).takeIf{it.configured}?.origin().orEmpty()}.getOrDefault("") else ""
        val mediaHttp=mediaOrigin.replaceFirst("wss://","https://").replaceFirst("ws://","http://")
        call.response.headers.append("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self' $mediaOrigin $mediaHttp; frame-src 'self'; worker-src 'self' blob:; img-src 'self' blob: data:; media-src 'self' blob:; base-uri 'none'; frame-ancestors ${if(file=="call.html") "'self'" else "'none'"}; form-action 'self'")
        call.respondText(checkNotNull(AdminService::class.java.classLoader.getResource("web/$file")).readText(),ContentType.parse(type))
    }
    get("/v1/site") {
        call.respond(database.readOnly { c -> c.queryOne("SELECT server_name,registration_open FROM server_settings WHERE id=1") { r ->
            buildJsonObject {put("name",r.getString(1));put("registrationOpen",r.getBoolean(2))}
        }!! })
    }
    authenticate(JWT_AUTH) {
        post("/v1/users/me/avatar") {
            val user=callerIdentity().userId
            val encoded=call.receive<JsonObject>()["image"]?.jsonPrimitive?.content.orEmpty()
            if(encoded.length !in 1..350_000) throw AppException.BadRequest("Выберите изображение до 250 КБ")
            val bytes=try { java.util.Base64.getDecoder().decode(encoded) } catch(e:IllegalArgumentException) {throw AppException.BadRequest("Некорректное изображение")}
            val canonical=try {
                javax.imageio.ImageIO.createImageInputStream(bytes.inputStream()).use { input ->
                    val readers=javax.imageio.ImageIO.getImageReaders(input)
                    if(!readers.hasNext()) throw IllegalArgumentException()
                    val reader=readers.next()
                    try {
                        reader.input=input
                        if(reader.getWidth(0) !in 1..512 || reader.getHeight(0) !in 1..512) throw IllegalArgumentException()
                        val source=reader.read(0)
                        val rgb=java.awt.image.BufferedImage(source.width,source.height,java.awt.image.BufferedImage.TYPE_INT_RGB)
                        val graphics=rgb.createGraphics()
                        try {graphics.drawImage(source,0,0,null)} finally {graphics.dispose()}
                        java.io.ByteArrayOutputStream().use { out -> javax.imageio.ImageIO.write(rgb,"jpg",out);out.toByteArray() }
                    } finally {reader.dispose()}
                }
            } catch(e:Exception) {throw AppException.BadRequest("Нужно изображение размером до 512 × 512")}
            val url="data:image/jpeg;base64,"+java.util.Base64.getEncoder().encodeToString(canonical)
            database.transaction {c -> c.update("UPDATE users SET avatar_url=?,updated_at=now() WHERE id=?",url,user)}
            call.respond(buildJsonObject {put("avatarUrl",url)})
        }
        get("/v1/users/me/welcome") {
            val user = callerIdentity().userId
            call.respond(database.readOnly { c -> JsonArray(c.query(
                "SELECT kind,status FROM welcome_tasks WHERE user_id=? ORDER BY created_at,kind", user,
            ) { r -> buildJsonObject { put("kind",r.getString("kind"));put("status",r.getString("status")) } }) })
        }
        post("/v1/users/me/welcome/{kind}") {
            val user = callerIdentity().userId
            val kind = call.parameters["kind"]
            val status = call.receive<JsonObject>()["status"]?.jsonPrimitive?.content
            if (kind !in listOf("AVATAR","INVITE","CHAT","TASK") || status !in listOf("DONE","SKIPPED"))
                throw AppException.BadRequest("Некорректный статус приветственного задания")
            call.respond(database.transaction { c ->
                val changed = c.update("UPDATE welcome_tasks SET status=?,updated_at=now() WHERE user_id=? AND kind=?",status,user,kind)
                if (changed == 0) throw AppException.BadRequest("Задание не найдено")
                buildJsonObject { put("kind",kind);put("status",status) }
            })
        }
        get("/v1/users/me/account/history") {
            val user=callerIdentity().userId
            val offset=(call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceIn(0,1_000_000)
            call.respond(database.readOnly { c -> JsonArray(c.query("SELECT * FROM balance_ledger WHERE user_id=? ORDER BY created_at DESC,request_id LIMIT 50 OFFSET ?",user,offset) { r ->
                buildJsonObject {
                    put("id",r.getString("request_id"));put("createdAt",r.getTimestamp("created_at").toInstant().toString())
                    put("oldPoints",r.getLong("old_points"));put("newPoints",r.getLong("new_points"));put("reason",r.getString("reason"))
                    put("oldSubscription",r.getTimestamp("old_subscription")?.toInstant()?.toString()?.let(::JsonPrimitive) ?: JsonNull)
                    put("newSubscription",r.getTimestamp("new_subscription")?.toInstant()?.toString()?.let(::JsonPrimitive) ?: JsonNull)
                }
            }) })
        }
        get("/v1/users/me/account") {
            val user=callerIdentity().userId
            call.respond(database.readOnly { c -> c.queryOne("SELECT points,subscription_until FROM account_balances WHERE user_id=?",user) { r ->
                buildJsonObject {put("points",r.getLong(1));put("subscriptionUntil",r.getTimestamp("subscription_until")?.toInstant()?.toString()?.let(::JsonPrimitive) ?: JsonNull)}
            } ?: buildJsonObject {put("points",0);put("subscriptionUntil",JsonNull)} })
        }
    }
}
