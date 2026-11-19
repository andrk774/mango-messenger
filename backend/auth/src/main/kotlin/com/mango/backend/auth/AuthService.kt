package com.mango.backend.auth

import com.mango.backend.common.isBlocked
import com.mango.backend.common.queryOne
import com.mango.backend.common.update

import com.mango.backend.common.AppException
import com.mango.backend.common.Database
import com.mango.backend.common.IdGenerator
import com.mango.backend.common.Redact
import com.mango.backend.common.SessionRevocations
import com.mango.backend.common.TimeSource
import com.mango.backend.common.isUniqueViolation
import com.mango.backend.common.validate
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Прикладная логика аутентификации.
 *
 * Здесь сосредоточены решения, от которых зависит безопасность учётных
 * записей, поэтому каждое из них прокомментировано: правила легко
 * нарушить незаметно, а цена ошибки — чужой аккаунт.
 */
class AuthService(
    private val database: Database,
    private val repository: AuthRepository,
    private val passwordHasher: PasswordHasher,
    private val tokens: TokenService,
    private val ids: IdGenerator,
    private val time: TimeSource,
    private val mail: MailSender,
    /**
     * Список отозванных сессий.
     *
     * Без него закрытие сессии не прекращает действие уже выданного
     * access token: подписанный JWT проверяется без обращения к базе.
     */
    private val revocations: SessionRevocations = SessionRevocations.Disabled,
    private val accessTokenTtl: Duration = Duration.ofMinutes(15),
) {

    private val log = LoggerFactory.getLogger(AuthService::class.java)

    suspend fun updateProfile(owner: UUID, body: ProfileUpdate) {
        val name = body.displayName.trim()
        val username = body.username.trim()
        validate {
            requireNotBlank(name, "displayName", "Укажите имя")
            requireMatches(username, "username", USERNAME_REGEX, USERNAME_HINT)
        }
        if (name.length > 80 || body.bio.length > 280) throw AppException.BadRequest("Имя — до 80 символов, описание — до 280")
        try {
            database.transaction { c ->
                c.update("UPDATE users SET display_name=?,username=?,bio=?,updated_at=now() WHERE id=?", name, username, body.bio.trim(), owner)
            }
        } catch (e: SQLException) {
            if (e.isUniqueViolation()) throw AppException.Conflict("Это имя пользователя уже занято", listOf("username"))
            throw e
        }
    }

    // ──────────────────────────── регистрация ────────────────────────────

    /**
     * Регистрация.
     *
     * Ответ НЕ зависит от того, занят ли адрес: иначе эндпоинт становится
     * средством проверки «зарегистрирован ли такой человек». Занятый адрес
     * получает письмо о попытке регистрации — так настоящий владелец узнаёт
     * о происходящем, а посторонний не узнаёт ничего.
     *
     * Имя пользователя (username) — исключение: оно публично и участвует
     * в поиске, поэтому о его занятости сообщается прямо.
     */
    suspend fun register(command: RegisterCommand) {
        validate {
            requireNotBlank(command.email, "email", "Укажите адрес электронной почты")
            requireMatches(command.email, "email", EMAIL_REGEX, "Некорректный адрес")
            requireNotBlank(command.displayName, "displayName", "Укажите имя")
            requireLength(command.displayName, "displayName", max = 64, min = 1)
            check(
                command.password.length >= MIN_PASSWORD_LENGTH,
                "password",
                "Пароль должен быть не короче $MIN_PASSWORD_LENGTH символов",
            )
            requireLength(command.password, "password", max = 128)
            requireMatches(command.username, "username", USERNAME_REGEX, USERNAME_HINT)
        }

        val now = time.now()
        val email = command.email.trim().lowercase()
        val passwordHash = passwordHasher.hash(command.password)

        val outcome = try {
            database.transaction { connection ->
            if (repository.usernameExists(connection, command.username)) {
                throw AppException.Conflict("Это имя пользователя уже занято", listOf("username"))
            }

            val userId = repository.insertUser(
                connection = connection,
                id = ids.newId(),
                email = email,
                username = command.username,
                displayName = command.displayName.trim(),
                passwordHash = passwordHash,
                now = now,
            ) ?: return@transaction RegistrationOutcome.EmailTaken

            val token = tokens.issueOneTimeToken()
            repository.insertAuthToken(
                connection = connection,
                id = ids.newId(),
                userId = userId,
                purpose = AuthTokenPurpose.EMAIL_VERIFICATION,
                tokenHash = token.hash,
                expiresAt = now.plus(EMAIL_TOKEN_TTL),
                now = now,
            )
            RegistrationOutcome.Created(token.value)
            }
        } catch (e: SQLException) {
            // Единственный оставшийся конфликт — гонка по username между
            // проверкой и вставкой. Транзакция уже откачена, поэтому
            // ошибку можно безопасно перевести в ответ пользователю.
            if (e.isUniqueViolation()) {
                throw AppException.Conflict("Это имя пользователя уже занято", listOf("username"))
            }
            throw e
        }

        when (outcome) {
            is RegistrationOutcome.Created -> mail.send(
                to = email,
                subject = "Подтверждение адреса в Mango",
                body = "Код подтверждения: ${outcome.verificationToken}",
            )

            RegistrationOutcome.EmailTaken -> {
                log.info("повторная регистрация на занятый адрес {}", Redact.email(email))
                mail.send(
                    to = email,
                    subject = "Попытка регистрации в Mango",
                    body = "На этот адрес уже зарегистрирована учётная запись. " +
                        "Если это были не вы, менять ничего не нужно.",
                )
            }
        }
    }

    /** Подтверждение адреса по коду из письма, выданному этой почте. */
    suspend fun verifyEmail(email: String, token: String) {
        val now = time.now()
        val hash = tokens.hashOneTimeToken(token)

        database.transaction { connection ->
            val userId = repository.consumeAuthTokenFor(
                connection, hash, AuthTokenPurpose.EMAIL_VERIFICATION, normalizeEmail(email), now,
            ) ?: throw AppException.BadRequest("Код неверен или устарел")

            repository.markEmailVerified(connection, userId, now)
            // Same transaction as verification; repeated verification cannot reset progress.
            for (kind in listOf("AVATAR", "INVITE", "CHAT", "TASK")) {
                connection.update(
                    "INSERT INTO welcome_tasks(user_id,kind) VALUES (?,?) ON CONFLICT DO NOTHING",
                    userId, kind,
                )
            }
        }
    }

    // ──────────────────────────────── вход ────────────────────────────────

    /**
     * Вход по email и паролю.
     *
     * Для несуществующего адреса всё равно выполняется проверка пароля
     * против эталонного хеша: без этого запрос к незарегистрированному
     * адресу завершался бы заметно быстрее, и время ответа выдавало бы
     * наличие учётной записи.
     */
    suspend fun login(command: LoginCommand): TokenPairResult {
        val email = command.email.trim().lowercase()
        val now = time.now()

        val user = database.readOnly { repository.findUserByEmail(it, email) }

        if (user == null) {
            passwordHasher.verify(command.password, DUMMY_HASH)
            throw AppException.Unauthorized(INVALID_CREDENTIALS)
        }

        if (!passwordHasher.verify(command.password, user.passwordHash)) {
            throw AppException.Unauthorized(INVALID_CREDENTIALS)
        }

        // Проверка подтверждения адреса стоит ПОСЛЕ проверки пароля:
        // иначе она сообщала бы о существовании учётной записи тому,
        // кто пароля не знает.
        if (!user.emailVerified) {
            throw AppException.Forbidden("Адрес электронной почты не подтверждён")
        }

        // Параметры стоимости могли вырасти с момента регистрации —
        // удачный вход это единственный момент, когда пароль доступен
        // в открытом виде и его можно перехешировать.
        return database.transaction { connection ->
            val currentHash=connection.queryOne("SELECT password_hash FROM users WHERE id=? FOR UPDATE", user.id) { it.getString(1) }
            if(currentHash != user.passwordHash) throw AppException.Unauthorized(INVALID_CREDENTIALS)
            if (connection.isBlocked(user.id)) throw AppException.Forbidden("Аккаунт заблокирован")
            if (passwordHasher.needsRehash(user.passwordHash)) {
                repository.updatePasswordHash(connection,user.id,passwordHasher.hash(command.password),now)
            }
            val deviceId = command.device?.let { device ->
                ids.newId().also {
                    repository.insertDevice(
                        connection = connection,
                        id = it,
                        userId = user.id,
                        platform = device.platform,
                        model = device.model,
                        appVersion = device.appVersion,
                        now = now,
                    )
                }
            }

            repository.updatePresence(connection, user.id, "ONLINE", now)

            issueSession(
                connection = connection,
                userId = user.id,
                deviceId = deviceId,
                previousSessionId = null,
                ip = command.ip,
                userAgent = command.userAgent,
                now = now,
            )
        }
    }

    // ───────────────────────── обновление токенов ─────────────────────────

    /**
     * Обмен refresh token на новую пару с ротацией.
     *
     * Ключевая защита — обнаружение повторного использования. Если пришёл
     * refresh, который уже был обменян (сессия отозвана с причиной ROTATED),
     * это значит, что токен у кого-то ещё: либо украден, либо склонирован.
     * Различить «украли у пользователя» и «украли у злоумышленника»
     * невозможно, поэтому отзываются ВСЕ сессии пользователя.
     */
    suspend fun refresh(refreshToken: String, ip: String?, userAgent: String?): TokenPairResult {
        val now = time.now()
        val hash = tokens.hashRefreshToken(refreshToken)

        // Результат возвращается значением, а не исключением. Причина
        // принципиальная: исключение из тела транзакции откатывает её,
        // и отзыв сессий при обнаружении кражи был бы отменён вместе
        // с ним — защита не сработала бы вовсе. Отказ выбрасывается
        // ПОСЛЕ фиксации изменений.
        val outcome = database.transaction { connection ->
            var session = repository.findSessionByRefreshHash(connection, hash)
                ?: return@transaction RefreshOutcome.UnknownToken

            connection.queryOne("SELECT id FROM users WHERE id=? FOR UPDATE", session.userId) { it.getString("id") }
            session = repository.findSessionByRefreshHash(connection, hash) ?: return@transaction RefreshOutcome.UnknownToken
            if (connection.isBlocked(session.userId)) return@transaction RefreshOutcome.UnknownToken

            if (!session.isActive) {
                val revoked = repository.revokeAllSessionsReturningIds(
                    connection, session.userId, REASON_REUSE, now,
                )
                return@transaction RefreshOutcome.Reuse(
                    sessionId = session.id,
                    userId = session.userId,
                    revokedReason = session.revokedReason,
                    revokedSessionIds = revoked + session.id,
                )
            }

            if (session.expiresAt <= now) {
                repository.revokeSession(connection, session.id, REASON_EXPIRED, now)
                return@transaction RefreshOutcome.Expired
            }

            // Старая сессия закрывается до выдачи новой: даже при сбое
            // после этой точки обменянный токен уже не сработает повторно.
            repository.revokeSession(connection, session.id, REASON_ROTATED, now)

            RefreshOutcome.Rotated(
                issueSession(
                    connection = connection,
                    userId = session.userId,
                    deviceId = session.deviceId,
                    previousSessionId = session.id,
                    ip = ip,
                    userAgent = userAgent,
                    now = now,
                ),
            )
        }

        return when (outcome) {
            is RefreshOutcome.Rotated -> outcome.tokens

            RefreshOutcome.UnknownToken ->
                throw AppException.Unauthorized("Токен обновления недействителен")

            RefreshOutcome.Expired ->
                throw AppException.Unauthorized("Срок действия сессии истёк")

            is RefreshOutcome.Reuse -> {
                // Внесение в список выполняется ПОСЛЕ фиксации транзакции:
                // иначе откат оставил бы в списке сессии, которые на самом
                // деле действуют.
                outcome.revokedSessionIds.forEach { revocations.revoke(it, accessTokenTtl) }
                log.warn(
                    "повторное использование refresh token: сессия {} пользователя {} уже была отозвана ({}); " +
                        "все сессии пользователя завершены",
                    Redact.id(outcome.sessionId), Redact.id(outcome.userId), outcome.revokedReason,
                )
                throw AppException.Unauthorized("Сессия завершена по соображениям безопасности")
            }
        }
    }

    private sealed interface RefreshOutcome {
        data class Rotated(val tokens: TokenPairResult) : RefreshOutcome
        data object UnknownToken : RefreshOutcome
        data object Expired : RefreshOutcome
        data class Reuse(
            val sessionId: UUID,
            val userId: UUID,
            val revokedReason: String?,
            val revokedSessionIds: List<UUID>,
        ) : RefreshOutcome
    }

    suspend fun logout(sessionId: UUID) {
        val now = time.now()
        database.transaction { repository.revokeSession(it, sessionId, REASON_LOGOUT, now) }
        revocations.revoke(sessionId, accessTokenTtl)
    }

    suspend fun listSessions(userId: UUID, currentSessionId: UUID): List<SessionView> =
        database.readOnly { connection ->
            repository.listActiveSessions(connection, userId).map { session ->
                SessionView(
                    id = session.id,
                    createdAt = session.createdAt,
                    lastUsedAt = session.lastUsedAt,
                    userAgent = session.userAgent,
                    isCurrent = session.id == currentSessionId,
                )
            }
        }

    /** Отзыв чужой сессии: разрешён только владельцу. */
    suspend fun revokeSession(userId: UUID, sessionId: UUID) {
        val now = time.now()
        database.transaction { connection ->
            val session = repository.findSessionById(connection, sessionId)
                ?: throw AppException.NotFound("Сессия")

            // Проверка владения обязательна: идентификатор сессии нельзя
            // считать секретом, он виден в списке устройств.
            if (session.userId != userId) throw AppException.Forbidden()

            repository.revokeSession(connection, sessionId, REASON_REVOKED, now)
        }
        revocations.revoke(sessionId, accessTokenTtl)
    }

    // ─────────────────────── восстановление пароля ───────────────────────

    /**
     * Запрос на восстановление.
     *
     * Ответ одинаков для существующего и несуществующего адреса —
     * по той же причине, что и при регистрации.
     */
    suspend fun requestPasswordReset(email: String) {
        val normalized = email.trim().lowercase()
        val now = time.now()

        val token = database.transaction { connection ->
            val user = repository.findUserByEmail(connection, normalized)
                ?: return@transaction null

            // Предыдущие запросы аннулируются: иначе несколько писем
            // давали бы несколько действующих ссылок одновременно.
            repository.invalidateTokens(connection, user.id, AuthTokenPurpose.PASSWORD_RESET, now)

            tokens.issueOneTimeToken().also { issued ->
                repository.insertAuthToken(
                    connection = connection,
                    id = ids.newId(),
                    userId = user.id,
                    purpose = AuthTokenPurpose.PASSWORD_RESET,
                    tokenHash = issued.hash,
                    expiresAt = now.plus(PASSWORD_RESET_TTL),
                    now = now,
                )
            }
        }

        if (token != null) {
            mail.send(
                to = normalized,
                subject = "Восстановление доступа к Mango",
                body = "Код для смены пароля: ${token.value}",
            )
        } else {
            log.info("запрос восстановления для неизвестного адреса {}", Redact.email(normalized))
        }
    }

    /**
     * Установка нового пароля.
     *
     * Все сессии отзываются: если доступ восстанавливают после кражи,
     * у злоумышленника не должно остаться действующего входа.
     */
    suspend fun resetPassword(email: String, token: String, newPassword: String) {
        validate {
            check(
                newPassword.length >= MIN_PASSWORD_LENGTH,
                "newPassword",
                "Пароль должен быть не короче $MIN_PASSWORD_LENGTH символов",
            )
            requireLength(newPassword, "newPassword", max = 128)
        }

        val now = time.now()
        val hash = tokens.hashOneTimeToken(token)
        val newHash = passwordHasher.hash(newPassword)

        database.transaction { connection ->
            val userId = repository.consumeAuthTokenFor(
                connection, hash, AuthTokenPurpose.PASSWORD_RESET, normalizeEmail(email), now,
            ) ?: throw AppException.BadRequest("Код неверен или устарел")

            repository.updatePasswordHash(connection, userId, newHash, now)
            repository.revokeAllSessionsReturningIds(
                connection, userId, REASON_PASSWORD_CHANGED, now,
            )
        }.forEach { revocations.revoke(it, accessTokenTtl) }
    }

    // ──────────────────────────── внутреннее ────────────────────────────

    /** Почта хранится в нижнем регистре без пробелов — так же она и ищется. */
    private fun normalizeEmail(email: String): String = email.trim().lowercase()

    private fun issueSession(
        connection: java.sql.Connection,
        userId: UUID,
        deviceId: UUID?,
        previousSessionId: UUID?,
        ip: String?,
        userAgent: String?,
        now: Instant,
    ): TokenPairResult {
        val sessionId = ids.newId()
        val refresh = tokens.issueRefreshToken()

        repository.insertSession(
            connection = connection,
            id = sessionId,
            userId = userId,
            deviceId = deviceId,
            refreshTokenHash = refresh.hash,
            previousSessionId = previousSessionId,
            expiresAt = refresh.expiresAt,
            now = now,
            ip = ip,
            userAgent = userAgent,
        )

        val access = tokens.issueAccessToken(userId, sessionId)

        return TokenPairResult(
            accessToken = access.value,
            accessExpiresAt = access.expiresAt,
            refreshToken = refresh.value,
            refreshExpiresAt = refresh.expiresAt,
            sessionId = sessionId,
            userId = userId,
        )
    }

    private sealed interface RegistrationOutcome {
        data class Created(val verificationToken: String) : RegistrationOutcome
        data object EmailTaken : RegistrationOutcome
    }

    companion object {
        const val MIN_PASSWORD_LENGTH = 10

        private const val INVALID_CREDENTIALS = "Неверный адрес или пароль"
        private const val USERNAME_HINT =
            "Имя пользователя: латиница, цифры и подчёркивание, от 3 до 32 символов"

        private val EMAIL_REGEX = Regex("""^[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}$""")
        private val USERNAME_REGEX = Regex("""^[A-Za-z0-9_]{3,32}$""")

        private val EMAIL_TOKEN_TTL: Duration = Duration.ofHours(24)
        private val PASSWORD_RESET_TTL: Duration = Duration.ofHours(1)

        const val REASON_LOGOUT = "LOGOUT"
        const val REASON_ROTATED = "ROTATED"
        const val REASON_REUSE = "REFRESH_TOKEN_REUSE"
        const val REASON_EXPIRED = "EXPIRED"
        const val REASON_REVOKED = "REVOKED_BY_USER"
        const val REASON_PASSWORD_CHANGED = "PASSWORD_CHANGED"

        /**
         * Эталонный хеш для выравнивания времени ответа при неизвестном адресе.
         * Соответствует паролю, который невозможно ввести осмысленно.
         */
        private const val DUMMY_HASH =
            "\$argon2id\$v=19\$m=65536,t=3,p=1\$c29tZXNhbHRzb21lc2FsdA\$" +
                "Zm9yY29uc3RhbnR0aW1lY29tcGFyaXNvbm9ubHkxMg"
    }
}

data class RegisterCommand(
    val email: String,
    val password: String,
    val displayName: String,
    val username: String,
)

data class LoginCommand(
    val email: String,
    val password: String,
    val device: DeviceInfo?,
    val ip: String?,
    val userAgent: String?,
)

data class DeviceInfo(val platform: String, val model: String, val appVersion: String)

data class TokenPairResult(
    val accessToken: String,
    val accessExpiresAt: Instant,
    val refreshToken: String,
    val refreshExpiresAt: Instant,
    val sessionId: UUID,
    val userId: UUID,
)

data class SessionView(
    val id: UUID,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val userAgent: String?,
    val isCurrent: Boolean,
)

/**
 * Отправка писем.
 *
 * Абстракция, а не прямой вызов SMTP: в тестах письма перехватываются,
 * а в продакшне провайдер меняется без правки логики регистрации.
 */
interface MailSender {
    suspend fun send(to: String, subject: String, body: String)
}

/** Реализация для окружений без почты: письмо попадает только в журнал. */
class LoggingMailSender : MailSender {
    private val log = LoggerFactory.getLogger(LoggingMailSender::class.java)

    override suspend fun send(to: String, subject: String, body: String) {
        // Тело письма содержит одноразовый токен, поэтому в журнал
        // уходит только факт отправки.
        log.info("письмо «{}» для {}", subject, Redact.email(to))
    }
}
