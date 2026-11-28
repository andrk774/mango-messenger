package com.mango.backend.app

import com.mango.backend.auth.JWT_AUTH
import com.mango.backend.auth.callerIdentity
import com.mango.backend.common.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.security.MessageDigest
import java.util.UUID

internal fun validateImagePrompt(prompt:String):String = prompt.trim().also {
    if(it.length !in 3..1500) throw AppException.BadRequest("Описание: от 3 до 1500 символов")
}

/** Fixed HTTPS provider; no arbitrary URL, redirects, secrets or prompts in logs. */
class ImageGenerationStore(private val db:Database) {
    private val key = SmtpCipher.readKey(System.getenv("MANGO_CALLS_KEY_FILE")?.takeIf { java.nio.file.Files.isRegularFile(java.nio.file.Path.of(it)) })
    private val cipher = SmtpCipher(key?.let { MessageDigest.getInstance("SHA-256").digest("mango-image-ai-v1".toByteArray()+it) }, "")
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    suspend fun current():JsonObject = db.readOnly { c -> c.queryOne("SELECT cipher FROM ai_image_settings WHERE id=1") {
        kotlinx.serialization.json.Json.parseToJsonElement(cipher.decrypt(it.getString(1))).jsonObject
    } } ?: buildJsonObject { put("enabled",false);put("model","gpt-image-1.5") }
    suspend fun publicSettings():JsonObject {
        val d=current()
        return buildJsonObject { put("enabled",d["enabled"]?:JsonPrimitive(false));put("model",d["model"]?:JsonPrimitive("gpt-image-1.5"));put("hasKey",!d["key"]?.jsonPrimitive?.content.isNullOrEmpty());put("verified",d["verified"]?.jsonPrimitive?.booleanOrNull==true);put("canSave",key!=null);put("provider","OpenAI") }
    }
    suspend fun save(actor:UUID, admin:AdminService, body:JsonObject) {
        if(key==null)throw AppException.Unavailable("На сервере не настроен ключ хранения секретов MANGO_CALLS_KEY_FILE")
        val old=current()
        val model=body["model"]?.jsonPrimitive?.content.orEmpty()
        if(!Regex("[a-zA-Z0-9.-]{3,80}").matches(model))throw AppException.BadRequest("Некорректная модель")
        val secret=body["key"]?.jsonPrimitive?.content.orEmpty().ifBlank { old["key"]?.jsonPrimitive?.content.orEmpty() }
        val enabled=body["enabled"]?.jsonPrimitive?.booleanOrNull==true
        if(secret.length>500 || (enabled && secret.isBlank()))throw AppException.BadRequest("Укажите API-ключ")
        val encoded=cipher.encrypt(buildJsonObject {put("enabled",enabled);put("model",model);put("key",secret);put("verified",false)}.toString())
        db.transaction { c -> admin.requireAdmin(c,actor,"manage")
            c.update("INSERT INTO ai_image_settings(id,cipher) VALUES(1,?) ON CONFLICT(id) DO UPDATE SET cipher=excluded.cipher",encoded)
            c.update("INSERT INTO admin_audit(actor_id,action,target,detail) VALUES(?,?,?,?)",actor,"AI_SETTINGS","server","enabled=$enabled; model=$model")
        }
    }
    suspend fun test() {
        val d=current();val secret=d["key"]?.jsonPrimitive?.content.orEmpty()
        if(secret.isBlank())throw AppException.Unavailable("Сначала сохраните API-ключ")
        withContext(Dispatchers.IO) {
            val request=HttpRequest.newBuilder(URI("https://api.openai.com/v1/models/"+d["model"]!!.jsonPrimitive.content))
                .timeout(Duration.ofSeconds(20)).header("Authorization","Bearer $secret").GET().build()
            val status=try {client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode()} catch(_:Exception){throw AppException.Unavailable("Нет соединения с ИИ")}
            if(status!=200)throw AppException.Unavailable("Модель недоступна (HTTP $status). Проверьте ключ и доступ к модели")
        }
        db.transaction { c ->
            val saved=c.queryOne("SELECT cipher FROM ai_image_settings WHERE id=1 FOR UPDATE"){it.getString(1)}
            if(saved==null || kotlinx.serialization.json.Json.parseToJsonElement(cipher.decrypt(saved)).jsonObject!=d)
                throw AppException.Conflict("Settings changed; test again")
            c.update("UPDATE ai_image_settings SET cipher=? WHERE id=1",cipher.encrypt(JsonObject(d + ("verified" to JsonPrimitive(true))).toString()))
        }
    }
    suspend fun generate(actor:UUID, body:JsonObject):JsonObject {
        val prompt=validateImagePrompt(body["prompt"]?.jsonPrimitive?.content.orEmpty())
        val id=runCatching {UUID.fromString(body["id"]?.jsonPrimitive?.content)}.getOrNull()?:throw AppException.BadRequest("Нужен ID запроса")
        val d=current()
        if(d["enabled"]?.jsonPrimitive?.booleanOrNull!=true || d["verified"]?.jsonPrimitive?.booleanOrNull!=true)throw AppException.Unavailable("Генератор ИИ пока не настроен администратором")
        db.transaction { c ->
            c.queryOne("SELECT id FROM users WHERE id=? FOR UPDATE",actor){true}
            if(c.isBlocked(actor) || c.queryOne("SELECT email_verified FROM users WHERE id=?",actor){it.getBoolean(1)}!=true)throw AppException.Forbidden()
            if(c.queryOne("SELECT count(*) FROM ai_image_requests WHERE user_id=? AND created_at>now()-interval '24 hours'",actor){it.getInt(1)}!!>=5)throw AppException.RateLimited(86400)
            if(c.update("INSERT INTO ai_image_requests(id,user_id) VALUES(?,?) ON CONFLICT DO NOTHING",id,actor)==0)throw AppException.Conflict("Этот запрос уже выполнялся. Повтор автоматически не запускается")
        }
        return withContext(Dispatchers.IO) {
            val payload=buildJsonObject {put("model",d["model"]!!);put("prompt","Create a standalone messenger sticker, clean silhouette, no text unless requested. $prompt");put("n",1);put("size","1024x1024");put("output_format","png")}
            val request=HttpRequest.newBuilder(URI("https://api.openai.com/v1/images/generations")).timeout(Duration.ofSeconds(180))
                .header("Authorization","Bearer "+d["key"]!!.jsonPrimitive.content).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build()
            try {
                val response=client.send(request,HttpResponse.BodyHandlers.ofInputStream())
                val bytes=response.body().use {it.readNBytes(12_000_001)}
                if(response.statusCode()!=200 || bytes.size>12_000_000)throw AppException.Unavailable("ИИ не создал изображение. Проверьте лимит и настройки сервиса")
                val image=kotlinx.serialization.json.Json.parseToJsonElement(String(bytes,Charsets.UTF_8)).jsonObject["data"]!!.jsonArray.first().jsonObject["b64_json"]!!.jsonPrimitive.content
                buildJsonObject {put("image",image)}
            } catch(e:AppException){throw e} catch(_:Exception){throw AppException.Unavailable("Не удалось получить изображение от ИИ")}
        }
    }
}

fun Route.imageGenerationRoutes(db:Database,admin:AdminService) {
    val store=ImageGenerationStore(db)
    authenticate(JWT_AUTH) {
        get("/v1/admin/image-ai") {admin.authorize(callerIdentity().userId,"manage");call.response.headers.append("Cache-Control","no-store");call.respond(store.publicSettings())}
        post("/v1/admin/image-ai") {val actor=callerIdentity().userId;admin.authorize(actor,"manage");store.save(actor,admin,call.receive());call.respond(buildJsonObject{put("ok",true)})}
        post("/v1/admin/image-ai/test") {admin.authorize(callerIdentity().userId,"manage");store.test();call.respond(buildJsonObject{put("message","Ключ и модель доступны. Генерация не запускалась.")})}
        get("/v1/ai/images/status") { val d=store.publicSettings();call.respond(buildJsonObject {put("enabled",d["enabled"]!!.jsonPrimitive.boolean && d["hasKey"]!!.jsonPrimitive.boolean && d["verified"]!!.jsonPrimitive.boolean)}) }
        post("/v1/ai/images") {call.response.headers.append("Cache-Control","no-store");call.respond(store.generate(callerIdentity().userId,call.receive()))}
    }
}
