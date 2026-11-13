package com.mango.core.network

import com.mango.core.network.dto.DeviceInfoDto
import com.mango.core.network.dto.LoginRequestDto
import com.mango.core.network.dto.SendMessageRequestDto
import com.mango.core.network.dto.VerifyEmailRequestDto
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Что именно уходит на сервер.
 *
 * Серверные тесты собирают запросы сами и не видят, как их кодирует
 * клиент. Здесь проверяется JSON, который отправит приложение, — на нём
 * и сломался вход: поле со значением по умолчанию не попадало в запрос.
 */
class RequestEncodingTest {

    @Test
    fun `подтверждение адреса отправляет email вместе с кодом`() {
        val json = NetworkJson.parseToJsonElement(
            NetworkJson.encodeToString(
                VerifyEmailRequestDto.serializer(),
                VerifyEmailRequestDto(token = "ABCDE-FGHJK", email = "anna@example.com"),
            ),
        ).jsonObject
        assertEquals("anna@example.com", json.getValue("email").jsonPrimitive.content)
        assertEquals("ABCDE-FGHJK", json.getValue("token").jsonPrimitive.content)
    }

    @Test
    fun `запрос входа содержит платформу устройства`() {
        val json = NetworkJson.encodeToString(
            LoginRequestDto.serializer(),
            LoginRequestDto("a@example.com", "secret", DeviceInfoDto(model = "Pixel")),
        )
        val device = NetworkJson.parseToJsonElement(json).jsonObject.getValue("device").jsonObject
        assertEquals("ANDROID", device.getValue("platform").jsonPrimitive.content)
    }

    @Test
    fun `тип сообщения уходит явно, отсутствующие поля — нет`() {
        val json = NetworkJson.parseToJsonElement(
            NetworkJson.encodeToString(SendMessageRequestDto.serializer(), SendMessageRequestDto("op-1", body = "привет")),
        ).jsonObject
        assertEquals("TEXT", json.getValue("type").jsonPrimitive.content)
        assertFalse("null не отправляется", "replyToId" in json)
    }
}
