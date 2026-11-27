package com.mango.backend.app

import com.mango.backend.common.*
import com.mango.backend.auth.JWT_AUTH
import com.mango.backend.auth.callerIdentity
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal fun turnIce(urls:List<String>,secret:String,identity:String,now:Instant=Instant.now()):JsonArray {
    if(urls.isEmpty())return JsonArray(emptyList())
    val username="${now.epochSecond+86400}:$identity"
    val mac=Mac.getInstance("HmacSHA1");mac.init(SecretKeySpec(secret.toByteArray(),"HmacSHA1"))
    return buildJsonArray {add(buildJsonObject {
        put("urls",JsonArray(urls.map(::JsonPrimitive)));put("username",username)
        put("credential",Base64.getEncoder().encodeToString(mac.doFinal(username.toByteArray())))
    })}
}

internal fun validateTurnUrls(urls:List<String>) {
    if(urls.size>4 || urls.any { !Regex("^turns?:[A-Za-z0-9.-]+:[0-9]{1,5}(\\?transport=(udp|tcp))?$").matches(it) ||
                it.substringBefore('?').substringAfterLast(':').toIntOrNull() !in 1..65535 }) invalid("TURN: укажите turn:host:3478 или turns:host:443?transport=tcp")
}

/** Own encryption key, distinct from JWT/SMTP. No secret is returned by the settings API. */
class CallSettingsStore(private val db:Database,key:ByteArray?=SmtpCipher.readKey(System.getenv("MANGO_CALLS_KEY_FILE")?.takeIf { java.nio.file.Files.isRegularFile(java.nio.file.Path.of(it)) })) {
    private val cipher=SmtpCipher(key?.let { MessageDigest.getInstance("SHA-256").digest("mango-calls-v1".toByteArray()+it) },"")
    private suspend fun saved():Pair<Long,JsonObject>?=db.readOnly { c ->
        c.queryOne("SELECT revision,cipher FROM call_settings WHERE id=1") {it.getLong(1) to kotlinx.serialization.json.Json.parseToJsonElement(cipher.decrypt(it.getString(2))).jsonObject}
    }
    suspend fun current():CallConfig {
        val data=saved()?.second ?: return CallConfig.load()
        return decode(data)
    }
    private fun decode(d:JsonObject):CallConfig {
        fun s(k:String)=d[k]?.jsonPrimitive?.content.orEmpty()
        if(d["enabled"]?.jsonPrimitive?.booleanOrNull!=true)return CallConfig("","","")
        return CallConfig(s("url"),s("key"),s("secret"),s("internalUrl"),
            d["turnUrls"]?.jsonArray?.map{it.jsonPrimitive.content}.orEmpty(),s("turnSecret"),d["relayOnly"]?.jsonPrimitive?.booleanOrNull==true)
    }
    suspend fun publicSettings():JsonObject {
        val saved=saved();val cfg=saved?.second;val current=current()
        return buildJsonObject {
            put("revision",saved?.first?:0);put("canSave",cipher.canMigrate);put("enabled",current.configured)
            for(k in listOf("url","key","internalUrl"))put(k,cfg?.get(k)?:JsonPrimitive(when(k){"url"->current.url;"key"->current.key;else->current.internalUrl}))
            put("hasSecret",(cfg?.get("secret")?.jsonPrimitive?.content?:current.secret).isNotEmpty())
            put("hasTurnSecret",cfg?.get("turnSecret")?.jsonPrimitive?.content?.isNotEmpty()==true)
            put("turnUrls",cfg?.get("turnUrls")?:JsonArray(emptyList()));put("relayOnly",cfg?.get("relayOnly")?:JsonPrimitive(false))
        }
    }
    private suspend fun candidate(body:JsonObject):JsonObject {
        val old=saved()?.second
        fun s(k:String)=body[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().also{if(it.length>2048)invalid("Слишком длинное значение $k")}
        val enabled=body.flag("enabled")
        val urls=body["turnUrls"]?.jsonArray?.map{it.jsonPrimitive.content.trim()}.orEmpty();validateTurnUrls(urls)
        val secret=s("secret").ifEmpty{old?.get("secret")?.jsonPrimitive?.content?:CallConfig.load().secret}
        val turnSecret=s("turnSecret").ifEmpty{old?.get("turnSecret")?.jsonPrimitive?.content.orEmpty()}
        if(urls.isNotEmpty()&&turnSecret.length<32)invalid("TURN: нужен общий секрет не короче 32 символов")
        val relay=body.flag("relayOnly");if(relay&&urls.isEmpty())invalid("Для режима только TURN задайте TURN-сервер")
        val result=buildJsonObject {
            put("enabled",enabled);put("url",s("url"));put("key",s("key"));put("secret",secret);put("internalUrl",s("internalUrl"))
            put("turnUrls",JsonArray(urls.map(::JsonPrimitive)));put("turnSecret",turnSecret);put("relayOnly",relay)
        }
        if(enabled){val cfg=decode(result);if(!cfg.configured)invalid("Заполните адрес LiveKit, ключ и секрет (минимум 32 символа)");cfg.origin();cfg.apiOrigin()}
        return result
    }
    suspend fun test(body:JsonObject):JsonObject {
        val cfg=decode(candidate(body));if(!cfg.configured)invalid("Включите звонки для проверки")
        CallGateway(cfg).request("ListRooms")
        return buildJsonObject{put("message","LiveKit: соединение и авторизация проверены. TURN требует отдельного тестового звонка с relay-кандидатом.")}
    }
    suspend fun save(actor:java.util.UUID,admin:AdminService,body:JsonObject) {
        val candidate=candidate(body)
        if(!cipher.canMigrate)throw AppException.Unavailable("Не настроен отдельный ключ MANGO_CALLS_KEY_FILE")
        if(candidate["enabled"]!!.jsonPrimitive.boolean)CallGateway(decode(candidate)).request("ListRooms")
        val encrypted=cipher.encrypt(candidate.toString())
        db.transaction { c ->
            admin.requireAdmin(c,actor)
            c.queryOne("SELECT pg_advisory_xact_lock(71832160)"){true}
            val revision=c.queryOne("SELECT revision FROM call_settings WHERE id=1"){it.getLong(1)}?:0
            if(body["revision"]?.jsonPrimitive?.longOrNull!=revision)invalid("Настройки изменились. Обновите страницу.")
            c.update("INSERT INTO call_settings(id,revision,cipher) VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET revision=EXCLUDED.revision,cipher=EXCLUDED.cipher,updated_at=now()",revision+1,encrypted)
            c.update("INSERT INTO admin_audit(actor_id,action,target,detail) VALUES (?,?,?,?)",actor,"CALL_SETTINGS","server","revision=${revision+1}; enabled=${candidate["enabled"]}")
        }
    }
}

fun Route.callSettingsRoutes(admin:AdminService,store:CallSettingsStore) {
    authenticate(JWT_AUTH) {
        get("/v1/admin/calls") { admin.authorize(callerIdentity().userId);call.response.headers.append("Cache-Control","no-store");call.respond(store.publicSettings()) }
        post("/v1/admin/calls/test") { admin.authorize(callerIdentity().userId);call.respond(store.test(call.receive())) }
        post("/v1/admin/calls") { val actor=callerIdentity().userId;admin.authorize(actor);store.save(actor,admin,call.receive());call.respond(buildJsonObject{put("ok",true)}) }
    }
}
