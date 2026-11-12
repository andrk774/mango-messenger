package com.mango.backend.auth

import com.mango.backend.common.AppException
import com.mango.backend.common.RateLimiter
import com.mango.backend.common.RateLimits
import com.mango.backend.common.Database
import com.mango.backend.common.toIpLiteralOrNull
import com.mango.backend.common.toUuidOrNull
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.request.userAgent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

/** Учётные данные, извлечённые из проверенного access token. */
data class CallerIdentity(val userId: UUID, val sessionId: UUID)

/**
 * Идентификация вызывающего.
 *
 * Если дошли сюда без принципала — это ошибка конфигурации маршрута,
 * а не ситуация пользователя: отсутствие `authenticate` вокруг обработчика.
 * Поэтому 401, а не попытка продолжить без идентификации.
 */
fun RoutingContext.callerIdentity(): CallerIdentity {
    val principal = call.principal<JWTPrincipal>()
        ?: throw AppException.Unauthorized()

    val userId = principal.subject?.toUuidOrNull()
    val sessionId = principal.payload
        .getClaim(TokenService.CLAIM_SESSION)
        .asString()
        ?.toUuidOrNull()

    if (userId == null || sessionId == null) throw AppException.Unauthorized()
    return CallerIdentity(userId, sessionId)
}

/**
 * Ограничение частоты.
 *
 * Ключ строится из операции и адреса клиента, а для входа дополнительно
 * из адреса почты: иначе перебор паролей к одному аккаунту с меняющихся
 * адресов обходил бы защиту, а один клиент за NAT блокировал бы всех
 * остальных за тем же адресом.
 */
private suspend fun RoutingContext.enforceLimit(
    limiter: RateLimiter,
    key: String,
    limit: Int,
    window: java.time.Duration,
) {
    val verdict = limiter.acquire(key, limit, window)
    if (!verdict.allowed) {
        throw AppException.RateLimited(verdict.retryAfterSeconds)
    }
}

fun Route.authRoutes(
    service: AuthService,
    database: Database,
    repository: AuthRepository,
    rateLimiter: RateLimiter,
) {
    route("/v1/auth") {

        post("/register") {
            enforceLimit(
                limiter = rateLimiter,
                key = "register:${call.clientIp() ?: "unknown"}",
                limit = RateLimits.REGISTER_LIMIT,
                window = RateLimits.REGISTER_WINDOW,
            )
            val body = call.receive<RegisterRequest>()
            service.register(
                RegisterCommand(
                    email = body.email,
                    password = body.password,
                    displayName = body.displayName,
                    username = body.username,
                ),
            )
            // 202, а не 201: ответ намеренно одинаков и для нового адреса,
            // и для уже занятого, поэтому «создан» утверждать нельзя.
            call.respond(
                HttpStatusCode.Accepted,
                AcceptedResponse("Если адрес свободен, на него отправлено письмо с подтверждением"),
            )
        }

        post("/verify-email") {
            val body = call.receive<VerifyEmailRequest>()
            service.verifyEmail(body.token)
            call.respond(HttpStatusCode.OK, AcceptedResponse("Адрес подтверждён"))
        }

        post("/login") {
            val body = call.receive<LoginRequest>()
            enforceLimit(
                limiter = rateLimiter,
                key = "login:${body.email.trim().lowercase()}:${call.clientIp() ?: "unknown"}",
                limit = RateLimits.LOGIN_LIMIT,
                window = RateLimits.LOGIN_WINDOW,
            )
            val result = service.login(
                LoginCommand(
                    email = body.email,
                    password = body.password,
                    device = body.device?.let {
                        DeviceInfo(
                            platform = it.platform,
                            model = it.model,
                            appVersion = it.appVersion,
                        )
                    },
                    ip = call.clientIp(),
                    userAgent = call.request.userAgent(),
                ),
            )
            call.respond(HttpStatusCode.OK, result.toResponse())
        }

        post("/refresh") {
            val body = call.receive<RefreshRequest>()
            val result = service.refresh(
                refreshToken = body.refreshToken,
                ip = call.clientIp(),
                userAgent = call.request.userAgent(),
            )
            call.respond(HttpStatusCode.OK, result.toResponse())
        }

        post("/password/forgot") {
            val body = call.receive<ForgotPasswordRequest>()
            enforceLimit(
                limiter = rateLimiter,
                key = "reset:${body.email.trim().lowercase()}",
                limit = RateLimits.PASSWORD_RESET_LIMIT,
                window = RateLimits.PASSWORD_RESET_WINDOW,
            )
            service.requestPasswordReset(body.email)
            // Ответ одинаков для известного и неизвестного адреса.
            call.respond(
                HttpStatusCode.Accepted,
                AcceptedResponse("Если адрес зарегистрирован, на него отправлено письмо"),
            )
        }

        post("/password/reset") {
            val body = call.receive<ResetPasswordRequest>()
            service.resetPassword(body.token, body.newPassword)
            call.respond(
                HttpStatusCode.OK,
                AcceptedResponse("Пароль изменён, все сессии завершены"),
            )
        }

        authenticate(JWT_AUTH) {

            post("/logout") {
                val caller = callerIdentity()
                service.logout(caller.sessionId)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/sessions") {
                val caller = callerIdentity()
                val sessions = service.listSessions(caller.userId, caller.sessionId)
                call.respond(sessions.map { it.toResponse() })
            }

            delete("/sessions/{sessionId}") {
                val caller = callerIdentity()
                val sessionId = call.parameters["sessionId"]?.toUuidOrNull()
                    ?: throw AppException.BadRequest("Некорректный идентификатор сессии")
                service.revokeSession(caller.userId, sessionId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }

    route("/v1/users") {
        authenticate(JWT_AUTH) {
            get("/me") {
                val caller = callerIdentity()
                val user = database.readOnly { repository.findUserById(it, caller.userId) }
                    ?: throw AppException.NotFound("Пользователь")
                call.respond(user.toResponse())
            }
        }
    }
}

/**
 * Адрес клиента.
 *
 * За обратным прокси реальный адрес приходит в X-Forwarded-For. Берётся
 * ПЕРВЫЙ элемент списка, но доверять ему можно только при корректно
 * настроенном прокси: заголовок подделывается клиентом. Значение
 * используется лишь для журнала сессий, не для принятия решений о доступе.
 */
internal fun io.ktor.server.application.ApplicationCall.clientIp(): String? {
    val forwarded = request.headers[HttpHeaders.XForwardedFor]
        ?.split(',')
        ?.firstOrNull()
        .toIpLiteralOrNull()

    // Проверка обязательна: значение попадает в колонку типа inet, и любая
    // строка, не являющаяся адресом, прервала бы транзакцию входа.
    // Заголовок управляется клиентом, поэтому без проверки это был бы
    // способ сломать аутентификацию одним запросом.
    return forwarded ?: request.origin.remoteAddress.toIpLiteralOrNull()
}

const val JWT_AUTH = "jwt"
