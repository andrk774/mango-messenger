package com.mango.backend.realtime

import com.mango.backend.auth.TokenService
import com.mango.backend.common.ApiJson
import com.mango.backend.common.Redact
import com.mango.backend.sync.SyncService
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.UUID

private val log = LoggerFactory.getLogger("RealtimeRoutes")

/**
 * Сколько ждём кадр AUTH, прежде чем закрыть соединение.
 *
 * Добросовестный клиент отправляет его сразу после подключения, поэтому
 * запас небольшой: открытое неаутентифицированное соединение занимает
 * ресурсы и ничего полезного не делает.
 */
const val DEFAULT_AUTH_TIMEOUT_MILLIS = 5_000L

/** Как часто сервер шлёт ping на уровне протокола. */
const val HEARTBEAT_SECONDS = 30

fun Route.realtimeRoutes(
    hub: WebSocketHub,
    tokenService: TokenService,
    syncService: SyncService,
    authTimeoutMillis: Long = DEFAULT_AUTH_TIMEOUT_MILLIS,
    sessionAllowed: suspend (com.mango.backend.auth.AccessClaims) -> Boolean = { true },
) {
    webSocket("/v1/ws") {
        // Аутентификация выполняется первым кадром, а не заголовком и не
        // параметром адреса. Браузерный WebSocket не позволяет задать
        // заголовок Authorization, а параметр адреса утёк бы в журналы
        // прокси. Кадр видит только приложение.
        val claims = withTimeoutOrNull(authTimeoutMillis) { awaitAuthentication(tokenService) }

        if (claims == null || !sessionAllowed(claims)) {
            sendFrame(
                ServerFrame.Error(
                    CloseReasons.UNAUTHENTICATED,
                    "Ожидался кадр AUTH с действующим токеном",
                ),
            )
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, CloseReasons.UNAUTHENTICATED))
            return@webSocket
        }

        val session = ClientSession(sessionId = UUID.randomUUID(), userId = claims.userId)
        hub.register(session)

        try {
            // Положение журнала на момент подключения. Если оно больше
            // курсора клиента, он обязан догнать пропущенное через /v1/sync:
            // события, произошедшие до подключения, в сокет не придут.
            sendFrame(
                ServerFrame.ServerHello(
                    lastEventId = syncService.lastEventId(claims.userId),
                    heartbeatSeconds = HEARTBEAT_SECONDS,
                ),
            )

            // Отправка вынесена в отдельную корутину: чтение входящих
            // кадров не должно ждать медленного клиента, и наоборот.
            val sender = launch {
                for (event in session.outgoing) {
                    if (!sessionAllowed(claims)) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, CloseReasons.UNAUTHENTICATED))
                        break
                    }
                    sendFrame(ServerFrame.Event(event))
                }
            }
            val accessWatcher = launch {
                while (true) {
                    kotlinx.coroutines.delay(5_000)
                    if (!sessionAllowed(claims)) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, CloseReasons.UNAUTHENTICATED))
                        break
                    }
                }
            }

            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    if (!sessionAllowed(claims)) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, CloseReasons.UNAUTHENTICATED))
                        break
                    }
                    handleClientFrame(frame.readText(), session)
                }
            } finally {
                sender.cancel()
                accessWatcher.cancel()
            }
        } catch (_: ClosedReceiveChannelException) {
            // Обычное завершение: клиент закрыл соединение.
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.warn(
                "соединение {} завершилось с ошибкой: {}",
                Redact.id(session.sessionId), Redact.text(e.message),
            )
        } finally {
            hub.unregister(session)
        }
    }
}

/**
 * Ожидание кадра AUTH.
 *
 * Любой другой кадр до аутентификации — нарушение протокола: соединение
 * не должно обрабатывать ничего, пока не известно, кто на том конце.
 */
private suspend fun DefaultWebSocketServerSession.awaitAuthentication(
    tokenService: TokenService,
): com.mango.backend.auth.AccessClaims? {
    for (frame in incoming) {
        if (frame !is Frame.Text) continue

        val parsed = runCatching { ApiJson.decodeFromString<ClientFrame>(frame.readText()) }
            .getOrNull()

        val auth = parsed as? ClientFrame.Auth ?: return null
        return tokenService.verifyAccessToken(auth.token)
    }
    return null
}

private suspend fun DefaultWebSocketServerSession.handleClientFrame(
    raw: String,
    session: ClientSession,
) {
    val frame = runCatching { ApiJson.decodeFromString<ClientFrame>(raw) }.getOrNull()
        ?: return // Неразобранный кадр игнорируется: разрывать из-за него соединение незачем.

    when (frame) {
        is ClientFrame.Ping -> sendFrame(ServerFrame.Pong)

        is ClientFrame.Ack -> session.lastAckedEventId = frame.eventId

        is ClientFrame.Auth -> {
            // Повторная аутентификация в открытом соединении не предусмотрена:
            // смена личности внутри сокета — путь к ошибкам доступа.
            sendFrame(
                ServerFrame.Error(
                    CloseReasons.PROTOCOL_VIOLATION,
                    "Соединение уже аутентифицировано",
                ),
            )
        }
    }
}

private suspend fun DefaultWebSocketServerSession.sendFrame(frame: ServerFrame) {
    send(Frame.Text(ApiJson.encodeToString(frame)))
}
