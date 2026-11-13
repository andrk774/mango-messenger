package com.mango.backend.common

/**
 * Доменная ошибка сервера.
 *
 * Слой маршрутизации переводит её в HTTP-ответ ровно в одном месте
 * (StatusPages), поэтому бизнес-код не занимается кодами состояния
 * и не может случайно вернуть 200 там, где произошёл отказ.
 */
sealed class AppException(
    val code: String,
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    /** 400 — запрос синтаксически разобран, но бессмыслен. */
    class BadRequest(message: String) : AppException("BAD_REQUEST", message)

    /** 401 — нет действительных учётных данных. */
    class Unauthorized(message: String = "Требуется аутентификация") :
        AppException("UNAUTHORIZED", message)

    /**
     * 403 — доступ запрещён.
     *
     * Возвращается в том числе когда объект существует, но пользователь
     * не является участником чата. Текст НЕ раскрывает, существует ли
     * объект: иначе эндпоинт превращается в средство разведки.
     */
    class Forbidden(message: String = "Доступ запрещён") :
        AppException("FORBIDDEN", message)

    /** 404 — объекта нет либо он недоступен запрашивающему. */
    class NotFound(what: String = "Объект") :
        AppException("NOT_FOUND", "$what не найден")

    /** 409 — состояние ресурса не позволяет выполнить операцию. */
    class Conflict(message: String, val conflictingFields: List<String> = emptyList()) :
        AppException("CONFLICT", message)

    /** 422 — данные не прошли проверку. Поля перечисляются явно. */
    class Validation(val fieldErrors: Map<String, String>) :
        AppException("VALIDATION_FAILED", "Данные не прошли проверку")

    /** 413 — файл больше допустимого. */
    class PayloadTooLarge(message: String) : AppException("PAYLOAD_TOO_LARGE", message)

    /** 415 — тип файла не разрешён или содержимое не совпадает с заявленным типом. */
    class UnsupportedMediaType(message: String) : AppException("UNSUPPORTED_MEDIA_TYPE", message)

    /** 429 — превышен лимит. [retryAfterSeconds] уходит в заголовок Retry-After. */
    class RateLimited(val retryAfterSeconds: Long) :
        AppException("RATE_LIMITED", "Слишком много запросов")

    /** 503 — зависимость недоступна (БД, Redis, хранилище). */
    class Unavailable(message: String, cause: Throwable? = null) :
        AppException("UNAVAILABLE", message, cause)

    /** 500 — всё остальное. Детали НЕ уходят клиенту, только в журнал. */
    class Internal(message: String = "Внутренняя ошибка", cause: Throwable? = null) :
        AppException("INTERNAL", message, cause)
}

/** Накопитель ошибок валидации: позволяет вернуть все проблемы сразу. */
class Validator {
    private val errors = linkedMapOf<String, String>()

    fun check(condition: Boolean, field: String, message: String) {
        if (!condition && field !in errors) errors[field] = message
    }

    fun requireNotBlank(value: String?, field: String, message: String = "Поле обязательно") {
        check(!value.isNullOrBlank(), field, message)
    }

    fun requireLength(value: String?, field: String, max: Int, min: Int = 0) {
        if (value == null) return
        check(value.length in min..max, field, "Длина должна быть от $min до $max символов")
    }

    fun requireMatches(value: String?, field: String, regex: Regex, message: String) {
        if (value == null) return
        check(regex.matches(value), field, message)
    }

    /**
     * Бросает исключение, если накопилась хотя бы одна ошибка.
     *
     * Вызывать обязательно: валидатор, у которого не спросили результат,
     * — это проверка, которой не было.
     */
    fun throwIfInvalid() {
        if (errors.isNotEmpty()) throw AppException.Validation(errors.toMap())
    }
}

inline fun validate(block: Validator.() -> Unit) {
    Validator().apply(block).throwIfInvalid()
}
