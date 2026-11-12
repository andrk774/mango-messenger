package com.mango.backend.common

import java.time.Instant

/**
 * Источник времени.
 *
 * Инжектируется, чтобы тесты на сроки действия токенов, ротацию сессий
 * и напоминания выполнялись мгновенно и детерминированно.
 */
fun interface TimeSource {
    fun now(): Instant
}

val SystemTime = TimeSource { Instant.now() }

/** Управляемое время для тестов. */
class MutableTimeSource(@Volatile private var current: Instant = Instant.EPOCH) : TimeSource {
    override fun now(): Instant = current

    fun set(instant: Instant) {
        current = instant
    }

    fun advance(millis: Long) {
        current = current.plusMillis(millis)
    }
}

/**
 * Редактирование персональных данных перед записью в журнал.
 *
 * Серверные журналы живут дольше клиентских, уходят в системы сбора
 * и доступны эксплуатации. Содержимое переписки, адреса и токены
 * в них попадать не должны — фильтрация выполняется в точке записи,
 * а не полагается на дисциплину «не логировать лишнего».
 */
object Redact {

    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val BEARER = Regex("""(?i)bearer\s+[A-Za-z0-9\-._~+/]+=*""")
    private val JWT = Regex("""eyJ[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]*""")

    /** `andrey@example.com` → `a***@example.com`: домен помогает в диагностике. */
    fun email(value: String?): String {
        if (value == null) return "null"
        val at = value.indexOf('@')
        if (at <= 0) return "***"
        return value[0] + "***" + value.substring(at)
    }

    /** Токены не маскируются частично — только полностью. */
    fun token(@Suppress("UNUSED_PARAMETER") value: String?): String = "<redacted>"

    /** Тело сообщения заменяется длиной: она помогает отлаживать, но не раскрывает текст. */
    fun body(value: String?): String = if (value == null) "null" else "<text:${value.length}>"

    /** Идентификатор укорачивается до первых 8 символов — хватает для корреляции. */
    fun id(value: Any?): String = when (value) {
        null -> "null"
        else -> value.toString().take(8) + "…"
    }

    /** Полная зачистка произвольной строки — для сообщений исключений и тел ответов. */
    fun text(value: String?): String {
        if (value == null) return "null"
        return value
            .replace(BEARER, "Bearer <redacted>")
            .replace(JWT, "<jwt>")
            .replace(EMAIL) { email(it.value) }
    }
}
