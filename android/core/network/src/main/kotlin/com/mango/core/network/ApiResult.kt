package com.mango.core.network

import com.mango.core.common.AppError
import com.mango.core.common.Outcome
import com.mango.core.network.dto.ErrorResponseDto
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Разбор JSON ответов.
 *
 * `ignoreUnknownKeys` обязателен: сервер может добавить поле раньше, чем
 * обновится приложение, и старый клиент не должен от этого ломаться.
 */
internal val NetworkJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/**
 * Перевод HTTP-ответа в доменный результат.
 *
 * Выполняется в одном месте, поэтому ни один репозиторий не может
 * «забыть» обработать код состояния или вернуть успех при отказе.
 */
internal fun <T> Response<T>.toOutcome(): Outcome<T> {
    if (isSuccessful) {
        val body = body()
        @Suppress("UNCHECKED_CAST")
        return when {
            body != null -> Outcome.Success(body)
            // 204 No Content: тело пустое, и это корректный успех.
            code() == 204 -> Outcome.Success(Unit as T)
            else -> Outcome.Failure(AppError.Unknown(IllegalStateException("Пустой ответ")))
        }
    }
    return Outcome.Failure(toAppError())
}

internal fun <T> Response<T>.toAppError(): AppError {
    val raw = runCatching { errorBody()?.string() }.getOrNull()
    val parsed = raw?.let {
        runCatching { NetworkJson.decodeFromString<ErrorResponseDto>(it) }.getOrNull()
    }

    return when (code()) {
        400 -> AppError.Validation(
            parsed?.fieldErrors ?: mapOf("request" to (parsed?.message ?: "Некорректный запрос")),
        )
        401 -> AppError.Unauthorized
        403 -> AppError.Forbidden
        404 -> AppError.NotFound(parsed?.message)
        409 -> AppError.Conflict(parsed?.fieldErrors?.keys?.toList().orEmpty())
        // 410 Gone: курсор синхронизации устарел. Отдельный разбор
        // выполняет движок синхронизации — ему нужны области перезагрузки.
        410 -> AppError.Conflict(listOf(CURSOR_TOO_OLD))
        422 -> AppError.Validation(
            parsed?.fieldErrors ?: mapOf("request" to (parsed?.message ?: "Данные не приняты")),
        )
        429 -> AppError.RateLimited(
            headers()["Retry-After"]?.toLongOrNull(),
        )
        in 500..599 -> AppError.Server(code())
        else -> AppError.Unknown(IllegalStateException("HTTP ${code()}"))
    }
}

/**
 * Выполнение сетевого вызова с переводом исключений в доменные ошибки.
 *
 * Исключения уровня сокета не должны всплывать выше слоя сети: их
 * обработка в каждом репозитории неизбежно разошлась бы, и часть
 * сбоев выглядела бы для пользователя как неизвестная ошибка.
 */
internal suspend fun <T> apiCall(block: suspend () -> Response<T>): Outcome<T> = try {
    block().toOutcome()
} catch (e: UnknownHostException) {
    Outcome.Failure(AppError.Network(e))
} catch (e: SocketTimeoutException) {
    Outcome.Failure(AppError.Timeout(e))
} catch (e: IOException) {
    Outcome.Failure(AppError.Network(e))
} catch (e: Exception) {
    Outcome.Failure(AppError.Unknown(e))
}

/** Признак устаревшего курсора в [AppError.Conflict]. */
const val CURSOR_TOO_OLD = "CURSOR_TOO_OLD"
