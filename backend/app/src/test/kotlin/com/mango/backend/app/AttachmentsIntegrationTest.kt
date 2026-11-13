package com.mango.backend.app

import com.mango.backend.auth.CurrentUserResponse
import com.mango.backend.auth.TokenPairResponse
import com.mango.backend.messaging.ConversationDto
import com.mango.backend.messaging.CreateConversationRequest
import com.mango.backend.messaging.MessageDto
import com.mango.backend.messaging.MessagesResponse
import com.mango.backend.messaging.SendMessageRequest
import com.mango.backend.storage.DownloadUrlResponse
import com.mango.backend.storage.UploadTicketRequest
import com.mango.backend.storage.UploadTicketResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Вложения: разрешение → загрузка → сообщение → скачивание.
 *
 * Проверяются три вида защиты: от подмены типа файла, от чтения
 * чужих файлов и от переполнения хранилища.
 */
class AttachmentsIntegrationTest : IntegrationTestBase() {

    @BeforeEach
    fun cleanup() = resetDatabase()

    private class Person(val tokens: TokenPairResponse, val id: UUID)

    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
    ) + ByteArray(200) { it.toByte() }

    @Test
    fun `картинка проходит весь путь до получателя`() = withApp { client ->
        val (anna, boris, chat) = client.twoInChat()
        val attachmentId = UUID.randomUUID()

        val ticket = client.ticket(anna, chat.id, attachmentId, "image/png", png, "Фото отпуска.png")
        assertEquals(HttpStatusCode.Created, ticket.status)
        val upload = client.upload(ticket.body(), png)
        assertEquals(HttpStatusCode.NoContent, upload.status)

        val message = client.sendWith(anna, chat.id, "IMAGE", attachmentId)
        assertEquals(HttpStatusCode.Created, message.status)
        val sent: MessageDto = message.body()
        assertEquals(listOf(attachmentId), sent.attachments.map { it.id })
        assertEquals("Фото отпуска.png", sent.attachments.single().fileName)

        val history: MessagesResponse = client.get("/v1/conversations/${chat.id}/messages") {
            bearerAuth(boris.tokens.accessToken)
        }.body()
        assertEquals(listOf(attachmentId), history.items.single().attachments.map { it.id })

        val link: DownloadUrlResponse = client.get("/v1/attachments/$attachmentId/url") {
            bearerAuth(boris.tokens.accessToken)
        }.body()
        val file = client.get(link.url)
        assertEquals(HttpStatusCode.OK, file.status)
        assertContentEquals(png, file.readRawBytes())
        assertEquals("image/png", file.headers[HttpHeaders.ContentType])
        val disposition = file.headers[HttpHeaders.ContentDisposition].orEmpty()
        assertTrue(disposition.startsWith("inline"), disposition)
        assertTrue("filename*=UTF-8''" in disposition, "Кириллическое имя передаётся в UTF-8: $disposition")
    }

    @Test
    fun `HTML под видом картинки отклоняется`() = withApp { client ->
        val (anna, _, chat) = client.twoInChat()
        val html = "<html><script>alert(1)</script></html>".toByteArray()
        val attachmentId = UUID.randomUUID()

        val ticket: UploadTicketResponse = client.ticket(anna, chat.id, attachmentId, "image/png", html).body()
        assertEquals(HttpStatusCode.UnsupportedMediaType, client.upload(ticket, html).status)

        // Отклонённый файл нельзя ни отправить, ни скачать.
        assertEquals(HttpStatusCode.UnprocessableEntity, client.sendWith(anna, chat.id, "IMAGE", attachmentId).status)
        assertEquals(
            HttpStatusCode.Conflict,
            client.get("/v1/attachments/$attachmentId/url") { bearerAuth(anna.tokens.accessToken) }.status,
        )
    }

    @Test
    fun `запрещённый тип не получает разрешения`() = withApp { client ->
        val (anna, _, chat) = client.twoInChat()
        val svg = "<svg onload='alert(1)'/>".toByteArray()

        val response = client.ticket(anna, chat.id, UUID.randomUUID(), "image/svg+xml", svg)
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun `сервер принимает не больше заявленного`() = withApp { client ->
        val (anna, _, chat) = client.twoInChat()
        val small = png.copyOf(100)
        val ticket: UploadTicketResponse = client.ticket(anna, chat.id, UUID.randomUUID(), "image/png", small).body()

        // Заявлено 100 байт, отправлено больше: иначе маленькое разрешение
        // позволило бы залить в хранилище сколько угодно.
        assertEquals(HttpStatusCode.PayloadTooLarge, client.upload(ticket, png).status)
    }

    @Test
    fun `повреждённый файл не принимается, повтор исправным проходит`() = withApp { client ->
        val (anna, _, chat) = client.twoInChat()
        val ticket: UploadTicketResponse = client.ticket(anna, chat.id, UUID.randomUUID(), "image/png", png).body()

        val corrupted = png.copyOf().also { it[100] = (it[100] + 1).toByte() }
        assertEquals(HttpStatusCode.BadRequest, client.upload(ticket, corrupted).status)
        assertEquals(HttpStatusCode.NoContent, client.upload(ticket, png).status)
    }

    @Test
    fun `повторный запрос разрешения и повторная загрузка безопасны`() = withApp { client ->
        val (anna, _, chat) = client.twoInChat()
        val attachmentId = UUID.randomUUID()

        val first = client.ticket(anna, chat.id, attachmentId, "image/png", png)
        assertEquals(HttpStatusCode.Created, first.status)
        client.upload(first.body(), png)

        val again = client.ticket(anna, chat.id, attachmentId, "image/png", png)
        assertEquals(HttpStatusCode.OK, again.status)
        assertTrue(again.body<UploadTicketResponse>().uploaded, "Клиент понимает, что загружать не нужно")
        assertEquals(HttpStatusCode.NoContent, client.upload(first.body(), png).status)
    }

    @Test
    fun `чужой идентификатор вложения не подтверждает его существование`() = withApp { client ->
        val (anna, boris, chat) = client.twoInChat()
        val attachmentId = UUID.randomUUID()
        client.ticket(anna, chat.id, attachmentId, "image/png", png)

        assertEquals(HttpStatusCode.Forbidden, client.ticket(boris, chat.id, attachmentId, "image/png", png).status)
    }

    @Test
    fun `подделанная или просроченная ссылка не работает`() = withApp { client ->
        val (anna, _, chat) = client.twoInChat()
        val ticket: UploadTicketResponse = client.ticket(anna, chat.id, UUID.randomUUID(), "image/png", png).body()

        val forged = ticket.copy(uploadUrl = ticket.uploadUrl.replace("signature=", "signature=x"))
        assertEquals(HttpStatusCode.Forbidden, client.upload(forged, png).status)

        time.advance(16 * 60_000L)
        assertEquals(HttpStatusCode.Forbidden, client.upload(ticket, png).status)
    }

    @Test
    fun `ссылка на загрузку не годится для скачивания`() = withApp { client ->
        val (anna, _, chat) = client.twoInChat()
        val ticket: UploadTicketResponse = client.ticket(anna, chat.id, UUID.randomUUID(), "image/png", png).body()
        client.upload(ticket, png)

        assertEquals(HttpStatusCode.Forbidden, client.get(ticket.uploadUrl).status)
    }

    @Test
    fun `посторонний не получает ни разрешения, ни ссылки`() = withApp { client ->
        val (anna, _, chat) = client.twoInChat()
        val stranger = client.person("stranger")
        val attachmentId = UUID.randomUUID()
        client.upload(client.ticket(anna, chat.id, attachmentId, "image/png", png).body(), png)
        client.sendWith(anna, chat.id, "IMAGE", attachmentId)

        assertEquals(
            HttpStatusCode.Forbidden,
            client.ticket(stranger, chat.id, UUID.randomUUID(), "image/png", png).status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/v1/attachments/$attachmentId/url") { bearerAuth(stranger.tokens.accessToken) }.status,
        )
    }

    @Test
    fun `до отправки файл виден только автору`() = withApp { client ->
        val (anna, boris, chat) = client.twoInChat()
        val attachmentId = UUID.randomUUID()
        client.upload(client.ticket(anna, chat.id, attachmentId, "image/png", png).body(), png)

        assertEquals(
            HttpStatusCode.OK,
            client.get("/v1/attachments/$attachmentId/url") { bearerAuth(anna.tokens.accessToken) }.status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/v1/attachments/$attachmentId/url") { bearerAuth(boris.tokens.accessToken) }.status,
        )
    }

    @Test
    fun `вложение нельзя прикрепить к двум сообщениям или чужому чату`() = withApp { client ->
        val (anna, boris, chat) = client.twoInChat()
        val vera = client.person("vera")
        val other: ConversationDto = client.post("/v1/conversations") {
            bearerAuth(anna.tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(CreateConversationRequest(type = "DIRECT", memberIds = listOf(vera.id)))
        }.body()
        val attachmentId = UUID.randomUUID()
        client.upload(client.ticket(anna, chat.id, attachmentId, "image/png", png).body(), png)

        assertEquals(HttpStatusCode.UnprocessableEntity, client.sendWith(anna, other.id, "IMAGE", attachmentId).status)
        // Чужое вложение по идентификатору тоже не прикрепить.
        assertEquals(HttpStatusCode.UnprocessableEntity, client.sendWith(boris, chat.id, "IMAGE", attachmentId).status)

        assertEquals(HttpStatusCode.Created, client.sendWith(anna, chat.id, "IMAGE", attachmentId).status)
        assertEquals(
            HttpStatusCode.UnprocessableEntity,
            client.sendWith(anna, chat.id, "IMAGE", attachmentId, clientMsgId = "second").status,
        )
    }

    @Test
    fun `после удаления сообщения файл недоступен собеседнику`() = withApp { client ->
        val (anna, boris, chat) = client.twoInChat()
        val attachmentId = UUID.randomUUID()
        client.upload(client.ticket(anna, chat.id, attachmentId, "image/png", png).body(), png)
        val message: MessageDto = client.sendWith(anna, chat.id, "IMAGE", attachmentId).body()

        client.delete("/v1/messages/${message.id}") { bearerAuth(anna.tokens.accessToken) }

        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/v1/attachments/$attachmentId/url") { bearerAuth(boris.tokens.accessToken) }.status,
        )
    }

    @Test
    fun `документ отдаётся только на скачивание`() = withApp { client ->
        val (anna, boris, chat) = client.twoInChat()
        val pdf = "%PDF-1.7\n%âãÏÓ\n1 0 obj\n".toByteArray()
        val attachmentId = UUID.randomUUID()
        client.upload(client.ticket(anna, chat.id, attachmentId, "application/pdf", pdf, "отчёт.pdf").body(), pdf)
        client.sendWith(anna, chat.id, "FILE", attachmentId)

        val link: DownloadUrlResponse = client.get("/v1/attachments/$attachmentId/url") {
            bearerAuth(boris.tokens.accessToken)
        }.body()
        val file = client.get(link.url)
        assertTrue(file.headers[HttpHeaders.ContentDisposition].orEmpty().startsWith("attachment"))
        assertTrue(file.headers["Content-Security-Policy"].orEmpty().contains("sandbox"))
    }

    @Test
    fun `сообщение-картинка без вложения отклоняется`() = withApp { client ->
        val (anna, _, chat) = client.twoInChat()
        val response = client.post("/v1/conversations/${chat.id}/messages") {
            bearerAuth(anna.tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(SendMessageRequest(clientMsgId = "img", type = "IMAGE"))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    }

    // ──────────────────────── вспомогательное ────────────────────────

    private suspend fun HttpClient.person(name: String): Person {
        val tokens = newUser("$name@example.com", name)
        val me: CurrentUserResponse = get("/v1/users/me") { bearerAuth(tokens.accessToken) }.body()
        return Person(tokens, me.id)
    }

    private suspend fun HttpClient.twoInChat(): Triple<Person, Person, ConversationDto> {
        val anna = person("anna")
        val boris = person("boris")
        val chat: ConversationDto = post("/v1/conversations") {
            bearerAuth(anna.tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(CreateConversationRequest(type = "DIRECT", memberIds = listOf(boris.id)))
        }.body()
        return Triple(anna, boris, chat)
    }

    private suspend fun HttpClient.ticket(
        actor: Person,
        conversationId: UUID,
        attachmentId: UUID,
        mimeType: String,
        content: ByteArray,
        fileName: String = "file",
    ): HttpResponse = post("/v1/attachments/upload-ticket") {
        bearerAuth(actor.tokens.accessToken)
        contentType(ContentType.Application.Json)
        setBody(
            UploadTicketRequest(
                attachmentId = attachmentId,
                conversationId = conversationId,
                mimeType = mimeType,
                sizeBytes = content.size.toLong(),
                checksumSha256 = sha256(content),
                fileName = fileName,
            ),
        )
    }

    private suspend fun HttpClient.upload(ticket: UploadTicketResponse, content: ByteArray): HttpResponse =
        put(ticket.uploadUrl) {
            contentType(ContentType.Application.OctetStream)
            setBody(content)
        }

    private suspend fun HttpClient.sendWith(
        actor: Person,
        conversationId: UUID,
        type: String,
        vararg attachmentIds: UUID,
        clientMsgId: String = "msg-${attachmentIds.first()}",
    ): HttpResponse = post("/v1/conversations/$conversationId/messages") {
        bearerAuth(actor.tokens.accessToken)
        contentType(ContentType.Application.Json)
        setBody(SendMessageRequest(clientMsgId = clientMsgId, type = type, attachmentIds = attachmentIds.toList()))
    }

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
