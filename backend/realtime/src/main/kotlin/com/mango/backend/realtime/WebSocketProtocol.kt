@file:UseSerializers(InstantSerializer::class, UuidSerializer::class)

package com.mango.backend.realtime

import com.mango.backend.common.InstantSerializer
import com.mango.backend.common.UuidSerializer
import com.mango.backend.sync.SyncEventDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * Кадры, которые шлёт клиент.
 *
 * Токен передаётся в кадре AUTH, а НЕ в строке запроса: адрес соединения
 * попадает в журналы прокси, в историю браузера и в отчёты об ошибках,
 * и токен оттуда уже не убрать.
 */
@Serializable
sealed interface ClientFrame {

    @Serializable
    @SerialName("AUTH")
    data class Auth(val token: String) : ClientFrame

    @Serializable
    @SerialName("PING")
    data object Ping : ClientFrame

    /**
     * Подтверждение применения события.
     *
     * Сервер не хранит состояние доставки — курсор ведёт клиент. Кадр
     * нужен для диагностики отставания, а не для гарантии доставки:
     * гарантию даёт журнал и сверка по курсору.
     */
    @Serializable
    @SerialName("ACK")
    data class Ack(val eventId: Long) : ClientFrame
}

/** Кадры, которые шлёт сервер. */
@Serializable
sealed interface ServerFrame {

    /**
     * Приветствие после успешной аутентификации.
     *
     * [lastEventId] — положение журнала на момент подключения. Если оно
     * больше курсора клиента, клиент обязан догнать пропущенное через
     * `/v1/sync`, а не ждать событий в сокете: всё, что произошло до
     * подключения, в сокет уже не придёт.
     */
    @Serializable
    @SerialName("SERVER_HELLO")
    data class ServerHello(
        val lastEventId: Long,
        val heartbeatSeconds: Int,
    ) : ServerFrame

    @Serializable
    @SerialName("PONG")
    data object Pong : ServerFrame

    @Serializable
    @SerialName("EVENT")
    data class Event(val event: SyncEventDto) : ServerFrame

    @Serializable
    @SerialName("ERROR")
    data class Error(val code: String, val message: String) : ServerFrame
}

object CloseReasons {
    const val UNAUTHENTICATED = "UNAUTHENTICATED"
    const val PROTOCOL_VIOLATION = "PROTOCOL_VIOLATION"
}
