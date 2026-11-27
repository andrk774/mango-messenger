package com.mango.backend.auth

import com.mango.backend.common.AppException
import com.mango.backend.common.RateLimiter
import com.mango.backend.common.RateLimits
import com.mango.backend.common.Database
import com.mango.backend.common.queryOne
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
import io.ktor.server.routing.patch
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
    /**
     * Предел регистраций с одного адреса. В локальной среде выше: Docker
     * Desktop показывает серверу все устройства домашней сети под одним
     * адресом, и строгий предел делился бы на всех.
     */
    registerLimit: Int = RateLimits.REGISTER_LIMIT,
) {
    route("/v1/auth") {

        post("/register") {
            val enabled = database.readOnly { connection ->
                connection.queryOne("SELECT registration_open FROM server_settings WHERE id=1") { it.getBoolean(1) } ?: true
            }
            if (!enabled) throw AppException.Forbidden("Регистрация временно закрыта")
            enforceLimit(
                limiter = rateLimiter,
                key = "register:${call.clientIp() ?: "unknown"}",
                limit = registerLimit,
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
            // Попытки — по почте: дома все устройства выходят в сеть под
            // одним адресом, и счёт по адресу смешивал бы разных людей.
            // Перебор кода к одной почте упирается в предел с любого адреса.
            enforceLimit(
                limiter = rateLimiter,
                key = "code:${body.email.trim().lowercase()}",
                limit = RateLimits.CODE_LIMIT,
                window = RateLimits.CODE_WINDOW,
            )
            service.verifyEmail(body.email, body.token)
            call.respond(HttpStatusCode.OK, AcceptedResponse("Адрес подтверждён"))
        }

        post("/login") {
            val browser = browserSession()
            val body = call.receive<LoginRequest>()
            enforceLimit(
                limiter = rateLimiter,
                key = "login-account:${body.email.trim().lowercase()}",
                limit = RateLimits.LOGIN_LIMIT,
                window = RateLimits.LOGIN_WINDOW,
            )
            enforceLimit(rateLimiter, "login-ip:${call.clientIp() ?: "unknown"}", 100, RateLimits.LOGIN_WINDOW)
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
            if (browser) setBrowserSession(result.refreshToken, result.refreshExpiresAt)
            call.respond(HttpStatusCode.OK, result.toResponse().let { if (browser) it.copy(refreshToken = "browser-cookie") else it })
        }

        post("/refresh") {
            val browser = browserSession()
            val body = call.receive<RefreshRequest>()
            val result = service.refresh(
                refreshToken = if (browser) browserRefresh() else body.refreshToken,
                ip = call.clientIp(),
                userAgent = call.request.userAgent(),
            )
            if (browser) setBrowserSession(result.refreshToken, result.refreshExpiresAt)
            call.respond(HttpStatusCode.OK, result.toResponse().let { if (browser) it.copy(refreshToken = "browser-cookie") else it })
        }

        post("/browser-clear") {
            if (!browserSession()) throw AppException.Forbidden("Browser header required")
            clearBrowserSession()
            call.respond(HttpStatusCode.NoContent)
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
            enforceLimit(
                limiter = rateLimiter,
                key = "code:${body.email.trim().lowercase()}",
                limit = RateLimits.CODE_LIMIT,
                window = RateLimits.CODE_WINDOW,
            )
            service.resetPassword(body.email, body.token, body.newPassword)
            call.respond(
                HttpStatusCode.OK,
                AcceptedResponse("Пароль изменён, все сессии завершены"),
            )
        }

        authenticate(JWT_AUTH) {

            post("/logout") {
                val browser = browserSession()
                val caller = callerIdentity()
                service.logout(caller.sessionId)
                if (browser) clearBrowserSession()
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
            patch("/me") {
                val caller = callerIdentity()
                service.updateProfile(caller.userId, call.receive<ProfileUpdate>())
                val user = database.readOnly { repository.findUserById(it, caller.userId) }
                    ?: throw AppException.NotFound("Пользователь")
                call.respond(user.toResponse())
            }
            get("/me") {
                val caller = callerIdentity()
                val user = database.readOnly { repository.findUserById(it, caller.userId) }
                    ?: throw AppException.NotFound("Пользователь")
                call.respond(user.toResponse())
            }
        }
    }
}

/** Only explicitly trusted literal peer IPs may supply forwarded addresses. */
internal fun io.ktor.server.application.ApplicationCall.clientIp(): String? =
    resolveClientIp(request.local.remoteAddress, request.headers[HttpHeaders.XForwardedFor],
        System.getenv("MANGO_TRUSTED_PROXY_IPS").orEmpty().split(',').mapNotNull { it.trim().toIpLiteralOrNull() }.toSet())

fun resolveClientIp(peer: String, forwarded: String?, trusted: Set<String>): String? {
    val direct = peer.toIpLiteralOrNull() ?: return null
    if (direct !in trusted || forwarded.isNullOrBlank()) return direct
    val hops = forwarded.split(',')
    if (hops.size > 16) return direct
    val parsed = hops.map { it.trim().toIpLiteralOrNull() ?: return direct }
    return (parsed + direct).asReversed().firstOrNull { it !in trusted } ?: direct
}

const val JWT_AUTH = "jwt"

@kotlinx.serialization.Serializable
data class ProfileUpdate(val displayName: String, val username: String, val bio: String = "")
