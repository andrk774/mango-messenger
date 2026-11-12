package com.mango.core.common

/**
 * Редактирование персональных данных перед записью в лог.
 *
 * Требование из плана безопасности: содержимое переписки, email, токены
 * и телефоны не должны попадать в логи — ни в debug-, ни в release-сборке.
 * Логи уезжают в crash-репортинг, попадают в bugreport и остаются на диске,
 * поэтому фильтровать нужно в точке записи, а не «не логировать лишнего».
 */
object Redact {

    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val PHONE = Regex("""\+?\d[\d\s\-()]{7,}\d""")
    private val BEARER = Regex("""(?i)bearer\s+[A-Za-z0-9\-._~+/]+=*""")
    private val JWT = Regex("""eyJ[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]*""")

    /**
     * Маскирует email, сохраняя домен: `andrey@example.com` → `a***@example.com`.
     * Домен оставляем, потому что он помогает в диагностике и сам по себе не ПД.
     */
    fun email(value: String): String {
        val at = value.indexOf('@')
        if (at <= 0) return "***"
        return value[0] + "***" + value.substring(at)
    }

    /** Токены не маскируются частично — только полностью. */
    fun token(@Suppress("UNUSED_PARAMETER") value: String?): String = "<redacted>"

    /**
     * Содержимое сообщения заменяется на длину.
     * Длина не раскрывает текст, но позволяет отлаживать проблемы с пустыми
     * или обрезанными сообщениями.
     */
    fun messageBody(value: String?): String =
        if (value == null) "null" else "<text:${value.length}>"

    /** Идентификатор укорачивается до первых 8 символов — достаточно для корреляции логов. */
    fun id(value: String?): String = when {
        value == null -> "null"
        value.length <= 8 -> value
        else -> value.take(8) + "…"
    }

    /**
     * Полная зачистка произвольной строки.
     * Применяется к сообщениям исключений и телам HTTP-ответов, где заранее
     * неизвестно, что внутри.
     */
    fun text(value: String?): String {
        if (value == null) return "null"
        return value
            .replace(BEARER, "Bearer <redacted>")
            .replace(JWT, "<jwt>")
            .replace(EMAIL) { email(it.value) }
            .replace(PHONE, "<phone>")
    }
}

/**
 * Логгер приложения.
 *
 * Абстракция нужна не ради «чистоты», а чтобы реализация в release-сборке
 * могла отбрасывать debug-уровень целиком и чтобы тесты могли проверять,
 * что в лог не утекли персональные данные.
 */
interface Logger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

/** Логгер-заглушка для тестов и для модулей без Android-зависимости. */
class NoOpLogger : Logger {
    override fun d(tag: String, message: String) = Unit
    override fun i(tag: String, message: String) = Unit
    override fun w(tag: String, message: String, throwable: Throwable?) = Unit
    override fun e(tag: String, message: String, throwable: Throwable?) = Unit
}

/** Собирает записи в память — используется в тестах на утечку ПД. */
class RecordingLogger : Logger {
    data class Entry(val level: String, val tag: String, val message: String)

    private val _entries = mutableListOf<Entry>()
    val entries: List<Entry> get() = _entries.toList()

    override fun d(tag: String, message: String) {
        _entries += Entry("D", tag, message)
    }

    override fun i(tag: String, message: String) {
        _entries += Entry("I", tag, message)
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        _entries += Entry("W", tag, message)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        _entries += Entry("E", tag, message)
    }
}
