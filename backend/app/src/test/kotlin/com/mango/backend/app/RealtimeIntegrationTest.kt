package com.mango.backend.app

import com.mango.backend.auth.CurrentUserResponse
import com.mango.backend.auth.TokenPairResponse
import com.mango.backend.common.ApiJson
import com.mango.backend.messaging.ConversationDto
import com.mango.backend.messaging.CreateConversationRequest
import com.mango.backend.messaging.MessageCreatedPayload
import com.mango.backend.messaging.SendMessageRequest
import com.mango.backend.realtime.ClientFrame
import com.mango.backend.realtime.ServerFrame
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверки доставки в реальном времени.
 *
 * Сокет здесь — ускоритель, а не источник истины: всё, что он доставляет,
 * уже записано в журнал. Поэтому проверяется не «доставлено обязательно»,
 * а «доставлено правильно и только тем, кому можно».
 */
class RealtimeIntegrationTest : IntegrationTestBase() {

    @BeforeEach
    fun cleanup() = resetDatabase()

    @Test
    fun `соединение без аутентификации закрывается`() = withApp { client ->
        val ws = ws()

        val first = ws.nextServerFrame()
        assertTrue(first is ServerFrame.Error, "получено: $first")
        assertEquals("UNAUTHENTICATED", (first as ServerFrame.Error).code)

        ws.close()
    }

    @Test
    fun `недействительный токен отклоняется`() = withApp { client ->
        val ws = ws()
        ws.sendFrame(ClientFrame.Auth("не.настоящий.токен"))

        val response = ws.nextServerFrame()
        assertTrue(response is ServerFrame.Error, "получено: $response")

        ws.close()
    }

    @Test
    fun `после аутентификации приходит положение журнала`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")

        val ws = ws()
        ws.sendFrame(ClientFrame.Auth(anna.accessToken))

        val hello = ws.nextServerFrame()
        assertTrue(hello is ServerFrame.ServerHello, "получено: $hello")
        hello as ServerFrame.ServerHello
        assertTrue(hello.lastEventId >= 0)
        assertTrue(hello.heartbeatSeconds > 0, "клиенту нужен интервал проверки связи")

        ws.close()
    }

    @Test
    fun `сообщение собеседника приходит в сокет`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val boris = client.newUser("boris@example.com", "boris")
        val borisId = client.me(boris).id

        val conversation: ConversationDto = client.post("/v1/conversations") {
            bearerAuth(anna.accessToken)
            contentType(ContentType.Application.Json)
            setBody(CreateConversationRequest(type = "DIRECT", memberIds = listOf(borisId)))
        }.body()

        // Борис слушает.
        val ws = ws()
        ws.sendFrame(ClientFrame.Auth(boris.accessToken))
        ws.nextServerFrame() // SERVER_HELLO

        // Анна пишет.
        client.post("/v1/conversations/${conversation.id}/messages") {
            bearerAuth(anna.accessToken)
            contentType(ContentType.Application.Json)
            setBody(SendMessageRequest(clientMsgId = "op-1", body = "Через сокет"))
        }

        val event = ws.awaitEvent("MESSAGE_CREATED")
        val payload = ApiJson.decodeFromJsonElement(
            MessageCreatedPayload.serializer(),
            event.event.payload,
        )

        assertEquals("Через сокет", payload.message.body)
        assertEquals(1L, payload.message.seq)
        assertEquals(conversation.id, payload.message.conversationId)
        assertTrue(event.event.id > 0, "у события обязан быть идентификатор для курсора")

        ws.close()
    }

    @Test
    fun `отправитель тоже получает своё событие`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val boris = client.newUser("boris@example.com", "boris")
        val conversation: ConversationDto = client.createDirect(anna, client.me(boris).id)

        // Второе устройство того же человека обязано узнать о сообщении,
        // отправленном с первого.
        val ws = ws()
        ws.sendFrame(ClientFrame.Auth(anna.accessToken))
        ws.nextServerFrame()

        client.post("/v1/conversations/${conversation.id}/messages") {
            bearerAuth(anna.accessToken)
            contentType(ContentType.Application.Json)
            setBody(SendMessageRequest(clientMsgId = "op-1", body = "Со своего же устройства"))
        }

        val event = ws.awaitEvent("MESSAGE_CREATED")
        assertNotNull(event)

        ws.close()
    }

    @Test
    fun `чужие события в сокет не попадают`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val boris = client.newUser("boris@example.com", "boris")
        val stranger = client.newUser("stranger@example.com", "stranger")

        val conversation: ConversationDto = client.createDirect(anna, client.me(boris).id)

        val ws = ws()
        ws.sendFrame(ClientFrame.Auth(stranger.accessToken))
        ws.nextServerFrame()

        client.post("/v1/conversations/${conversation.id}/messages") {
            bearerAuth(anna.accessToken)
            contentType(ContentType.Application.Json)
            setBody(SendMessageRequest(clientMsgId = "op-1", body = "Приватное"))
        }

        val leaked = withTimeoutOrNull(1_500) {
            ws.nextServerFrame()
        }
        assertNull(leaked, "постороннему не должно приходить ничего: $leaked")

        ws.close()
    }

    @Test
    fun `сервер отвечает на проверку связи`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")

        val ws = ws()
        ws.sendFrame(ClientFrame.Auth(anna.accessToken))
        ws.nextServerFrame()

        ws.sendFrame(ClientFrame.Ping)

        val pong = ws.nextServerFrame()
        assertTrue(pong is ServerFrame.Pong, "получено: $pong")

        ws.close()
    }

    @Test
    fun `повторная аутентификация в открытом соединении отклоняется`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val boris = client.newUser("boris@example.com", "boris")

        val ws = ws()
        ws.sendFrame(ClientFrame.Auth(anna.accessToken))
        ws.nextServerFrame()

        // Смена личности внутри уже открытого соединения не предусмотрена.
        ws.sendFrame(ClientFrame.Auth(boris.accessToken))

        val response = ws.nextServerFrame()
        assertTrue(response is ServerFrame.Error, "получено: $response")
        assertEquals("PROTOCOL_VIOLATION", (response as ServerFrame.Error).code)

        ws.close()
    }

    @Test
    fun `событие в сокете согласовано с журналом`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val boris = client.newUser("boris@example.com", "boris")
        val conversation: ConversationDto = client.createDirect(anna, client.me(boris).id)

        val ws = ws()
        ws.sendFrame(ClientFrame.Auth(boris.accessToken))
        val hello = ws.nextServerFrame() as ServerFrame.ServerHello

        client.post("/v1/conversations/${conversation.id}/messages") {
            bearerAuth(anna.accessToken)
            contentType(ContentType.Application.Json)
            setBody(SendMessageRequest(clientMsgId = "op-1", body = "Проверка"))
        }

        val fromSocket = ws.awaitEvent("MESSAGE_CREATED")

        // То же событие обязано быть доступно через догонку: сокет не
        // создаёт событий, он лишь доставляет записанные в журнал.
        val fromJournal: com.mango.backend.sync.SyncResponse =
            client.get("/v1/sync?since=${hello.lastEventId}") {
                bearerAuth(boris.accessToken)
            }.body()

        assertTrue(
            fromJournal.events.any { it.id == fromSocket.event.id },
            "Событие ${fromSocket.event.id} не найдено в журнале: " +
                "${fromJournal.events.map { it.id }}",
        )

        ws.close()
    }

    // ──────────────────────── вспомогательное ────────────────────────

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.ws(): WebSocketSession {
        val wsClient = createClient { install(WebSockets) }
        return wsClient.webSocketSession("/v1/ws")
    }

    private suspend fun WebSocketSession.sendFrame(frame: ClientFrame) {
        send(Frame.Text(ApiJson.encodeToString(ClientFrame.serializer(), frame)))
    }

    /** Следующий кадр сервера; `null`, если за отведённое время ничего не пришло. */
    private suspend fun WebSocketSession.nextServerFrame(timeoutMillis: Long = 5_000): ServerFrame? =
        try {
            withTimeout(timeoutMillis) {
                while (true) {
                    val frame = incoming.receive()
                    if (frame is Frame.Text) {
                        return@withTimeout ApiJson.decodeFromString<ServerFrame>(frame.readText())
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
        } catch (_: TimeoutCancellationException) {
            null
        } catch (_: Exception) {
            null
        }

    /** Ждёт событие указанного типа, пропуская служебные кадры. */
    private suspend fun WebSocketSession.awaitEvent(type: String): ServerFrame.Event {
        repeat(10) {
            val frame = nextServerFrame()
            if (frame is ServerFrame.Event && frame.event.type == type) return frame
        }
        error("Событие $type не получено")
    }

    private suspend fun HttpClient.me(tokens: TokenPairResponse): CurrentUserResponse =
        get("/v1/users/me") { bearerAuth(tokens.accessToken) }.body()

    private suspend fun HttpClient.createDirect(
        tokens: TokenPairResponse,
        otherId: UUID,
    ): ConversationDto = post("/v1/conversations") {
        bearerAuth(tokens.accessToken)
        contentType(ContentType.Application.Json)
        setBody(CreateConversationRequest(type = "DIRECT", memberIds = listOf(otherId)))
    }.body()
}
