package com.mango.backend.app

import com.mango.backend.common.AppConfig
import com.mango.backend.common.AppException
import com.mango.backend.common.Redact
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.header
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class ErrorResponse(
    val code: String,
    val message: String,
    val traceId: String? = null,
    val fieldErrors: Map<String, String>? = null,
)

/**
 * Единственное место, где исключение превращается в HTTP-ответ.
 *
 * Централизация здесь не косметическая: она гарантирует, что ни один
 * обработчик не вернёт 200 при отказе и что наружу никогда не уйдут
 * детали внутренней ошибки — они попадают только в журнал вместе
 * с идентификатором запроса, по которому обращение пользователя
 * сопоставляется с записью.
 */
fun Application.configureErrorHandling(config: AppConfig) {
    val log = LoggerFactory.getLogger("ErrorHandling")

    install(StatusPages) {
        exception<AppException> { call, cause ->
            val traceId = call.callId

            val status = when (cause) {
                is AppException.BadRequest -> HttpStatusCode.BadRequest
                is AppException.Unauthorized -> HttpStatusCode.Unauthorized
                is AppException.Forbidden -> HttpStatusCode.Forbidden
                is AppException.NotFound -> HttpStatusCode.NotFound
                is AppException.Conflict -> HttpStatusCode.Conflict
                is AppException.Validation -> HttpStatusCode.UnprocessableEntity
                is AppException.PayloadTooLarge -> HttpStatusCode.PayloadTooLarge
                is AppException.UnsupportedMediaType -> HttpStatusCode.UnsupportedMediaType
                is AppException.RateLimited -> HttpStatusCode.TooManyRequests
                is AppException.Unavailable -> HttpStatusCode.ServiceUnavailable
                is AppException.Internal -> HttpStatusCode.InternalServerError
            }

            if (status.value >= 500) {
                log.error("[{}] {}: {}", traceId, cause.code, Redact.text(cause.message), cause)
            } else {
                log.info("[{}] {}: {}", traceId, cause.code, Redact.text(cause.message))
            }

            if (cause is AppException.RateLimited) {
                call.response.header(HttpHeaders.RetryAfter, cause.retryAfterSeconds.toString())
            }

            call.respond(
                status,
                ErrorResponse(
                    code = cause.code,
                    // Сообщение внутренней ошибки наружу не отдаётся.
                    message = if (cause is AppException.Internal) {
                        "Внутренняя ошибка"
                    } else {
                        cause.message
                    },
                    traceId = traceId,
                    fieldErrors = (cause as? AppException.Validation)?.fieldErrors,
                ),
            )
        }

        // Неразобранное тело запроса: клиент прислал не тот JSON.
        exception<BadRequestException> { call, cause ->
            val traceId = call.callId
            log.info("[{}] некорректный запрос: {}", traceId, Redact.text(cause.message))
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    code = "BAD_REQUEST",
                    message = "Тело запроса не соответствует ожидаемому формату",
                    traceId = traceId,
                ),
            )
        }

        exception<Throwable> { call, cause ->
            val traceId = call.callId
            log.error("[{}] необработанная ошибка", traceId, cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(
                    code = "INTERNAL",
                    // В локальной среде текст помогает отладке; в продакшне
                    // он мог бы раскрыть устройство системы.
                    message = if (config.environment.isProduction) {
                        "Внутренняя ошибка"
                    } else {
                        Redact.text(cause.message ?: cause::class.simpleName)
                    },
                    traceId = traceId,
                ),
            )
        }

        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(
                status,
                ErrorResponse(code = "NOT_FOUND", message = "Ресурс не найден", traceId = call.callId),
            )
        }
    }
}
