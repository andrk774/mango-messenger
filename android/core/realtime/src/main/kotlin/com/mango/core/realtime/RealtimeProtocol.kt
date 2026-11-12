package com.mango.core.realtime

import com.mango.core.network.dto.SyncEventDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Кадры протокола.
 *
 * Зеркало серверного описания. Дублирование намеренно: модули собираются
 * раздельно и обновляются независимо, а общая библиотека типов между
 * клиентом и сервером связала бы их версии намертво. Цена — обязанность
 * держать описания согласованными; её закрывают тесты, разбирающие
 * настоящие ответы сервера.
 */
@Serializable
sealed interface ClientFrame {

    @Serializable
    @SerialName("AUTH")
    data class Auth(val token: String) : ClientFrame

    @Serializable
    @SerialName("PING")
    data object Ping : ClientFrame

    @Serializable
    @SerialName("ACK")
    data class Ack(val eventId: Long) : ClientFrame
}

@Serializable
sealed interface ServerFrame {

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

/** Состояние соединения — показывается в интерфейсе. */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,

    /**
     * Сервер отказал в аутентификации.
     *
     * Отличается от [DISCONNECTED] тем, что повторные попытки бесполезны
     * до обновления токена: переподключение в цикле только тратило бы
     * батарею.
     */
    UNAUTHENTICATED,
}
