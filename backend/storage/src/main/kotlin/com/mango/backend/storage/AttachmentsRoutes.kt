package com.mango.backend.storage

import com.mango.backend.auth.JWT_AUTH
import com.mango.backend.auth.callerIdentity
import com.mango.backend.common.AppException
import com.mango.backend.common.toUuidOrNull
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.request.receiveStream
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.UUID

/**
 * Заголовок `Content-Disposition` с именем файла (RFC 6266).
 *
 * Имя дублируется: ASCII-вариант для старых клиентов и `filename*`
 * в UTF-8 — иначе кириллическое имя превращается в нечитаемое.
 * Имя уже очищено от кавычек и управляющих символов при сохранении.
 */
internal fun contentDisposition(type: String, fileName: String): String {
    val ascii = fileName.map { if (it.code in 0x20..0x7E && it != '\\') it else '_' }.joinToString("")
    val encoded = URLEncoder.encode(fileName, Charsets.UTF_8).replace("+", "%20")
    return "$type; filename=\"$ascii\"; filename*=UTF-8''$encoded"
}

private fun RoutingContext.pathId(): UUID =
    call.parameters["attachmentId"]?.toUuidOrNull()
        ?: throw AppException.BadRequest("Некорректный идентификатор вложения")

private fun RoutingContext.signedParams(): Pair<Long, String> {
    val expires = call.request.queryParameters["expires"]?.toLongOrNull()
    val signature = call.request.queryParameters["signature"]
    if (expires == null || signature.isNullOrBlank()) {
        throw AppException.Forbidden("Ссылка недействительна или истекла")
    }
    return expires to signature
}

fun Route.attachmentsRoutes(service: AttachmentService) {

    route("/v1/attachments") {

        authenticate(JWT_AUTH) {
            post("/upload-ticket") {
                val caller = callerIdentity()
                val result = service.createTicket(caller.userId, call.receive<UploadTicketRequest>())
                call.respond(if (result.created) HttpStatusCode.Created else HttpStatusCode.OK, result.response)
            }

            get("/{attachmentId}/url") {
                val caller = callerIdentity()
                call.respond(service.downloadUrl(caller.userId, pathId()))
            }
        }

        // Содержимое — по подписанной ссылке, без токена (см. AttachmentService.upload).
        put("/{attachmentId}/content") {
            val (expires, signature) = signedParams()
            call.receiveStream().use { body ->
                service.upload(pathId(), expires, signature, call.request.contentLength(), body)
            }
            call.respond(HttpStatusCode.NoContent)
        }

        get("/{attachmentId}/content") {
            val (expires, signature) = signedParams()
            val file = service.open(pathId(), expires, signature)
            val record = file.record

            // Картинки показываются в приложении, всё остальное — только
            // скачивание: браузер не должен ничего открывать сам.
            call.response.header(
                HttpHeaders.ContentDisposition,
                contentDisposition(if (file.inline) "inline" else "attachment", record.fileName),
            )
            // Даже если файл откроют в браузере, он не сможет выполнить
            // скрипт и обратиться к API от имени пользователя.
            call.response.header("Content-Security-Policy", "default-src 'none'; sandbox")
            call.response.header(HttpHeaders.CacheControl, "private, max-age=300")

            // Поток закрывается внутри записи: тело пишется ПОСЛЕ возврата
            // из respondOutputStream, и закрытый снаружи поток дал бы
            // пустой ответ с ненулевой длиной.
            call.respondOutputStream(
                contentType = ContentType.parse(record.mimeType),
                status = HttpStatusCode.OK,
                contentLength = record.sizeBytes,
            ) {
                withContext(Dispatchers.IO) {
                    file.content.use { input -> input.copyTo(this@respondOutputStream) }
                }
            }
        }
    }
}
