package com.mango.backend.common

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Сериализация времени в ISO-8601 с зоной UTC (`2026-11-12T15:30:00Z`).
 *
 * Не epoch millis: контракт API читают люди и чужие инструменты, а число
 * миллисекунд ничего им не сообщает и провоцирует ошибки с часовым поясом.
 */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant {
        val raw = decoder.decodeString()
        return try {
            Instant.parse(raw)
        } catch (e: DateTimeParseException) {
            throw AppException.BadRequest("Некорректная метка времени: $raw")
        }
    }
}

object UuidSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.util.UUID", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): UUID {
        val raw = decoder.decodeString()
        return raw.toUuidOrNull() ?: throw AppException.BadRequest("Некорректный идентификатор")
    }
}

/**
 * Единая настройка JSON для API.
 *
 * `ignoreUnknownKeys` обязателен: старый клиент не должен падать на поле,
 * добавленном новой версией сервера, и наоборот.
 * `explicitNulls = false` убирает из ответов `"field": null` — это заметно
 * сокращает трафик на списках сообщений, где необязательных полей много.
 */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    prettyPrint = false
}
