package com.mango.core.common

/**
 * Доменная ошибка приложения.
 *
 * Слой данных обязан сводить любые исключения (IOException, HttpException,
 * SQLiteException) к одному из этих вариантов. Presentation-слой не должен
 * знать ни про Retrofit, ни про Room — поэтому здесь нет ни HTTP-кодов
 * в сыром виде, ни ссылок на библиотеки.
 */
sealed class AppError {

    /** Нет сети или сервер недостижим. Операцию имеет смысл повторить. */
    data class Network(val cause: Throwable? = null) : AppError()

    /** Сервер не ответил за отведённое время. */
    data class Timeout(val cause: Throwable? = null) : AppError()

    /** Access token недействителен — нужно обновление или повторный вход. */
    data object Unauthorized : AppError()

    /** Нет прав на операцию. Повтор не поможет. */
    data object Forbidden : AppError()

    /** Объект не найден или уже удалён. */
    data class NotFound(val what: String? = null) : AppError()

    /**
     * Конфликт версий при изменении задачи.
     * [conflictingFields] — поля, где серверная версия победила.
     */
    data class Conflict(
        val conflictingFields: List<String> = emptyList(),
    ) : AppError()

    /** Сработал rate limiting. [retryAfterSeconds] — подсказка сервера. */
    data class RateLimited(val retryAfterSeconds: Long? = null) : AppError()

    /** Данные не прошли валидацию. Ключ — имя поля, значение — сообщение. */
    data class Validation(val fieldErrors: Map<String, String>) : AppError()

    /** Внутренняя ошибка сервера (5xx). */
    data class Server(val statusCode: Int? = null) : AppError()

    /** Ошибка локального хранилища. */
    data class Storage(val cause: Throwable? = null) : AppError()

    /** Всё остальное. */
    data class Unknown(val cause: Throwable? = null) : AppError()

    /**
     * Имеет ли смысл автоматически повторять операцию.
     *
     * Это решение принимается здесь, а не в процессоре очереди, чтобы логика
     * повторов была в одном месте и покрывалась тестами.
     */
    val isRetryable: Boolean
        get() = when (this) {
            is Network, is Timeout, is Server, is RateLimited -> true
            is Storage -> false
            Unauthorized -> true // повтор после успешного refresh
            Forbidden, is NotFound, is Conflict, is Validation, is Unknown -> false
        }
}

/**
 * Результат операции.
 *
 * Своя реализация вместо kotlin.Result: нужен типизированный [AppError]
 * вместо Throwable, и нужна возможность исчерпывающего `when`.
 */
sealed interface Outcome<out T> {
    data class Success<out T>(val value: T) : Outcome<T>
    data class Failure(val error: AppError) : Outcome<Nothing>
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
    is Outcome.Success -> transform(value)
    is Outcome.Failure -> this
}

inline fun <T> Outcome<T>.onSuccess(action: (T) -> Unit): Outcome<T> = apply {
    if (this is Outcome.Success) action(value)
}

inline fun <T> Outcome<T>.onFailure(action: (AppError) -> Unit): Outcome<T> = apply {
    if (this is Outcome.Failure) action(error)
}

fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Success)?.value

fun <T> Outcome<T>.errorOrNull(): AppError? = (this as? Outcome.Failure)?.error
