package com.mango.backend.app

import com.mango.backend.auth.JWT_AUTH
import com.mango.backend.auth.callerIdentity
import com.mango.backend.common.*
import com.mango.backend.storage.AttachmentService
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

class TranscriptService(private val db:Database,private val attachments:AttachmentService,
    private val endpoint:String?=System.getenv("MANGO_SPEECH_URL"),
    private val recognize:suspend(ByteArray)->JsonObject={ bytes ->
        if(endpoint.isNullOrBlank()) throw AppException.Unavailable("Расшифровка не настроена на сервере")
        withContext(Dispatchers.IO) {
            val request=HttpRequest.newBuilder(URI.create(endpoint.trimEnd('/')+"/transcribe"))
                .timeout(Duration.ofSeconds(180)).header("Content-Type","application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build()
            val response=try {HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(request,HttpResponse.BodyHandlers.ofString())}
                catch(_:java.io.IOException){throw AppException.Unavailable("Сервис расшифровки пока недоступен. Повторите позже")}
            when(response.statusCode()) {
                200 -> ApiJson.parseToJsonElement(response.body()).jsonObject
                413 -> invalid("Расшифровка доступна для аудио до 5 минут")
                422 -> invalid("Не удалось прочитать аудиозапись")
                else -> throw AppException.Unavailable("Сервис расшифровки занят. Повторите позже")
            }
        }
    }
) {
    private val slot=Semaphore(1)
    private suspend fun cached(id:UUID)=db.readOnly { c -> c.queryOne("SELECT text,language,model FROM voice_transcripts WHERE attachment_id=?",id) { r ->
        buildJsonObject {put("text",r.getString(1));put("language",r.getString(2));put("model",r.getString(3))}
    } }
    suspend fun transcribe(user:UUID,id:UUID):JsonObject {
        // Authorize every read, including cached results after removal from a group.
        attachments.downloadUrl(user,id)
        cached(id)?.let {return it}
        if(!slot.tryAcquire()) throw AppException.Unavailable("Сервис расшифровки занят. Повторите позже")
        try {
            cached(id)?.let {return it}
            val bytes=withContext(Dispatchers.IO) {attachments.audioForTranscription(user,id).use {it.readNBytes(20*1024*1024+1)}}
            if(bytes.size>20*1024*1024) invalid("Аудиозапись слишком велика")
            val result=recognize(bytes)
            val text=result["text"]?.jsonPrimitive?.content ?: throw AppException.Unavailable("Некорректный ответ распознавания")
            if(text.length>20000) throw AppException.Unavailable("Ответ распознавания слишком велик")
            val language=result["language"]?.jsonPrimitive?.content.orEmpty().take(32)
            val model=result["model"]?.jsonPrimitive?.content.orEmpty().take(128)
            attachments.downloadUrl(user,id)
            db.transaction {it.update("INSERT INTO voice_transcripts(attachment_id,text,language,model) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",id,text,language,model)}
            return cached(id)!!
        } finally {slot.release()}
    }
}

fun Route.transcriptRoutes(service:TranscriptService) {
    authenticate(JWT_AUTH) {
        post("/v1/attachments/{id}/transcript") {
            val id=runCatching {UUID.fromString(call.parameters["id"])}.getOrElse {invalid("Некорректный ID")}
            call.respond(service.transcribe(callerIdentity().userId,id))
        }
    }
}
