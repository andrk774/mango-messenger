package com.mango.core.realtime

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Соответствие кадров формату сервера.
 *
 * Описание протокола продублировано на клиенте и на сервере, поэтому
 * расхождение не обнаружит ни один компилятор: соединение просто
 * перестанет работать в бою. Эти проверки фиксируют формат по образцам,
 * совпадающим с серверной сериализацией.
 */
class RealtimeProtocolTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    @Test
    fun `кадр аутентификации сериализуется с полем type`() {
        val encoded = json.encodeToString(ClientFrame.serializer(), ClientFrame.Auth("токен"))

        assertTrue(
            encoded.contains("\"type\":\"AUTH\""),
            "Сервер различает кадры по полю type: $encoded",
        )
        assertTrue(encoded.contains("\"token\":\"токен\""))
    }

    @Test
    fun `кадр проверки связи не содержит лишних полей`() {
        val encoded = json.encodeToString(ClientFrame.serializer(), ClientFrame.Ping)
        assertEquals("""{"type":"PING"}""", encoded)
    }

    @Test
    fun `подтверждение события несёт его идентификатор`() {
        val encoded = json.encodeToString(ClientFrame.serializer(), ClientFrame.Ack(42))
        assertTrue(encoded.contains("\"eventId\":42"), encoded)
    }

    @Test
    fun `приветствие сервера разбирается`() {
        val frame = json.decodeFromString<ServerFrame>(
            """{"type":"SERVER_HELLO","lastEventId":120,"heartbeatSeconds":30}""",
        )

        val hello = assertIs<ServerFrame.ServerHello>(frame)
        assertEquals(120L, hello.lastEventId)
        assertEquals(30, hello.heartbeatSeconds)
    }

    @Test
    fun `событие разбирается вместе с полезной нагрузкой`() {
        val frame = json.decodeFromString<ServerFrame>(
            """
            {"type":"EVENT","event":{
              "id":7,"scope":"CONVERSATION","scopeId":"conv-1",
              "type":"MESSAGE_CREATED",
              "payload":{"message":{"id":"m1"}},
              "createdAt":"2026-11-12T10:00:00Z"
            }}
            """.trimIndent(),
        )

        val event = assertIs<ServerFrame.Event>(frame)
        assertEquals(7L, event.event.id)
        assertEquals("MESSAGE_CREATED", event.event.type)
        assertEquals("conv-1", event.event.scopeId)
    }

    @Test
    fun `отказ сервера разбирается`() {
        val frame = json.decodeFromString<ServerFrame>(
            """{"type":"ERROR","code":"UNAUTHENTICATED","message":"Нужен токен"}""",
        )

        val error = assertIs<ServerFrame.Error>(frame)
        assertEquals("UNAUTHENTICATED", error.code)
    }

    @Test
    fun `ответ на проверку связи разбирается`() {
        assertIs<ServerFrame.Pong>(json.decodeFromString<ServerFrame>("""{"type":"PONG"}"""))
    }

    @Test
    fun `неизвестный кадр не ломает разбор молча`() {
        // Новый тип кадра должен приводить к понятной ошибке разбора,
        // а не к тихому пропуску: иначе протокол разойдётся незаметно.
        val result = runCatching {
            json.decodeFromString<ServerFrame>("""{"type":"СОВСЕМ_НОВЫЙ","x":1}""")
        }
        assertTrue(result.isFailure, "Ожидалась ошибка разбора неизвестного кадра")
    }

    @Test
    fun `событие без необязательных полей разбирается`() {
        val frame = json.decodeFromString<ServerFrame>(
            """
            {"type":"EVENT","event":{
              "id":1,"scope":"PROFILE","type":"X","payload":{},
              "createdAt":"2026-11-12T10:00:00Z"
            }}
            """.trimIndent(),
        )

        assertNull(assertIs<ServerFrame.Event>(frame).event.scopeId)
    }
}
