package com.mango.backend.auth

import com.mango.backend.common.instant
import com.mango.backend.common.instantOrNull
import com.mango.backend.common.query
import com.mango.backend.common.queryOne
import com.mango.backend.common.update
import com.mango.backend.common.uuid
import com.mango.backend.common.uuidOrNull
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class UserRecord(
    val id: UUID,
    val email: String,
    val emailVerified: Boolean,
    val passwordHash: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val bio: String?,
    val createdAt: Instant,
)

data class SessionRecord(
    val id: UUID,
    val userId: UUID,
    val deviceId: UUID?,
    val expiresAt: Instant,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val revokedAt: Instant?,
    val revokedReason: String?,
    val userAgent: String?,
) {
    val isActive: Boolean get() = revokedAt == null
}

data class DeviceRecord(
    val id: UUID,
    val platform: String,
    val model: String,
    val appVersion: String,
    val lastActiveAt: Instant,
)

enum class AuthTokenPurpose { EMAIL_VERIFICATION, PASSWORD_RESET }

/**
 * Доступ к данным аутентификации.
 *
 * Методы принимают [Connection] явно: транзакцией управляет сервис,
 * потому что только он знает, какие операции обязаны быть атомарными
 * (например, «отозвать старую сессию и создать новую» при ротации).
 */
class AuthRepository {

    // ─────────────────────────── пользователи ───────────────────────────

    /**
     * Создаёт пользователя. Возвращает `null`, если адрес уже занят.
     *
     * `ON CONFLICT DO NOTHING` вместо перехвата исключения принципиален:
     * в PostgreSQL любая ошибка ограничения ПРЕРЫВАЕТ транзакцию, и
     * последующий commit завершился бы отказом. Здесь конфликт — штатный
     * исход, а не ошибка, поэтому транзакция остаётся пригодной.
     *
     * Условие `WHERE deleted_at IS NULL` повторяет предикат частичного
     * индекса: без него PostgreSQL не сможет сопоставить конфликт с индексом.
     */
    fun insertUser(
        connection: Connection,
        id: UUID,
        email: String,
        username: String,
        displayName: String,
        passwordHash: String,
        now: Instant,
    ): UUID? = connection.queryOne(
        """
        INSERT INTO users (id, email, username, display_name, password_hash, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (email) WHERE deleted_at IS NULL DO NOTHING
        RETURNING id
        """.trimIndent(),
        id, email, username, displayName, passwordHash, now, now,
    ) { it.uuid("id") }

    fun findUserByEmail(connection: Connection, email: String): UserRecord? =
        connection.queryOne(
            "$USER_COLUMNS WHERE email = ? AND deleted_at IS NULL",
            email,
            map = ::mapUser,
        )

    fun findUserById(connection: Connection, id: UUID): UserRecord? =
        connection.queryOne(
            "$USER_COLUMNS WHERE id = ? AND deleted_at IS NULL",
            id,
            map = ::mapUser,
        )

    fun usernameExists(connection: Connection, username: String): Boolean =
        connection.queryOne(
            "SELECT 1 FROM users WHERE username = ? AND deleted_at IS NULL",
            username,
        ) { true } ?: false

    fun markEmailVerified(connection: Connection, userId: UUID, now: Instant): Int =
        connection.update(
            "UPDATE users SET email_verified = true, updated_at = ? WHERE id = ?",
            now, userId,
        )

    fun updatePasswordHash(
        connection: Connection,
        userId: UUID,
        passwordHash: String,
        now: Instant,
    ): Int = connection.update(
        "UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?",
        passwordHash, now, userId,
    )

    fun updatePresence(
        connection: Connection,
        userId: UUID,
        status: String,
        now: Instant,
    ): Int = connection.update(
        "UPDATE users SET presence_status = ?, last_seen_at = ?, updated_at = ? WHERE id = ?",
        status, now, now, userId,
    )

    // ─────────────────────────── устройства ───────────────────────────

    fun insertDevice(
        connection: Connection,
        id: UUID,
        userId: UUID,
        platform: String,
        model: String,
        appVersion: String,
        now: Instant,
    ): Int = connection.update(
        """
        INSERT INTO devices (id, user_id, platform, model, app_version, last_active_at, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        id, userId, platform, model, appVersion, now, now,
    )

    /**
     * Регистрация push-токена.
     *
     * Старая привязка этого же токена к другому устройству снимается:
     * после переустановки приложения FCM может выдать тот же токен,
     * и уведомление ушло бы на чужое устройство.
     */
    fun setPushToken(
        connection: Connection,
        deviceId: UUID,
        provider: String,
        token: String,
        now: Instant,
    ) {
        connection.update(
            """
            UPDATE devices SET push_token = NULL
            WHERE push_provider = ? AND push_token = ? AND id <> ?
            """.trimIndent(),
            provider, token, deviceId,
        )
        connection.update(
            """
            UPDATE devices SET push_provider = ?, push_token = ?, last_active_at = ?
            WHERE id = ?
            """.trimIndent(),
            provider, token, now, deviceId,
        )
    }

    // ──────────────────────────── сессии ────────────────────────────

    fun insertSession(
        connection: Connection,
        id: UUID,
        userId: UUID,
        deviceId: UUID?,
        refreshTokenHash: String,
        previousSessionId: UUID?,
        expiresAt: Instant,
        now: Instant,
        ip: String?,
        userAgent: String?,
    ): Int = connection.update(
        """
        INSERT INTO sessions (
            id, user_id, device_id, refresh_token_hash, previous_session_id,
            expires_at, created_at, last_used_at, ip, user_agent
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS inet), ?)
        """.trimIndent(),
        id, userId, deviceId, refreshTokenHash, previousSessionId,
        expiresAt, now, now, ip, userAgent,
    )

    fun findSessionByRefreshHash(connection: Connection, hash: String): SessionRecord? =
        connection.queryOne(
            "$SESSION_COLUMNS WHERE refresh_token_hash = ?",
            hash,
            map = ::mapSession,
        )

    fun findSessionById(connection: Connection, id: UUID): SessionRecord? =
        connection.queryOne("$SESSION_COLUMNS WHERE id = ?", id, map = ::mapSession)

    fun listActiveSessions(connection: Connection, userId: UUID): List<SessionRecord> =
        connection.query(
            "$SESSION_COLUMNS WHERE user_id = ? AND revoked_at IS NULL ORDER BY created_at DESC",
            userId,
            map = ::mapSession,
        )

    fun revokeSession(
        connection: Connection,
        sessionId: UUID,
        reason: String,
        now: Instant,
    ): Int = connection.update(
        """
        UPDATE sessions SET revoked_at = ?, revoked_reason = ?
        WHERE id = ? AND revoked_at IS NULL
        """.trimIndent(),
        now, reason, sessionId,
    )

    /**
     * Идентификаторы сессий, отозванных этим вызовом.
     *
     * Нужны, чтобы внести их в список отзыва: без этого уже выданные
     * access token продолжали бы работать до истечения срока.
     */
    fun revokeAllSessionsReturningIds(
        connection: Connection,
        userId: UUID,
        reason: String,
        now: Instant,
    ): List<UUID> = connection.query(
        """
        UPDATE sessions SET revoked_at = ?, revoked_reason = ?
        WHERE user_id = ? AND revoked_at IS NULL
        RETURNING id
        """.trimIndent(),
        now, reason, userId,
    ) { it.uuid("id") }

    /**
     * Отзыв всех сессий пользователя.
     *
     * Вызывается при смене пароля и при обнаружении повторного
     * использования refresh token.
     */
    fun revokeAllSessions(
        connection: Connection,
        userId: UUID,
        reason: String,
        now: Instant,
    ): Int = connection.update(
        """
        UPDATE sessions SET revoked_at = ?, revoked_reason = ?
        WHERE user_id = ? AND revoked_at IS NULL
        """.trimIndent(),
        now, reason, userId,
    )

    fun touchSession(connection: Connection, sessionId: UUID, now: Instant): Int =
        connection.update("UPDATE sessions SET last_used_at = ? WHERE id = ?", now, sessionId)

    // ───────────────────── токены из писем ─────────────────────

    fun insertAuthToken(
        connection: Connection,
        id: UUID,
        userId: UUID,
        purpose: AuthTokenPurpose,
        tokenHash: String,
        expiresAt: Instant,
        now: Instant,
    ): Int = connection.update(
        """
        INSERT INTO auth_tokens (id, user_id, purpose, token_hash, expires_at, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        id, userId, purpose, tokenHash, expiresAt, now,
    )

    /**
     * Одноразовое использование токена.
     *
     * Пометка `used_at` и выборка выполняются ОДНИМ запросом с `RETURNING`:
     * два параллельных запроса с одним токеном не смогут оба получить
     * строку, поэтому ссылка из письма срабатывает ровно один раз.
     */
    fun consumeAuthToken(
        connection: Connection,
        tokenHash: String,
        purpose: AuthTokenPurpose,
        now: Instant,
    ): UUID? = connection.queryOne(
        """
        UPDATE auth_tokens
        SET used_at = ?
        WHERE token_hash = ?
          AND purpose = ?
          AND used_at IS NULL
          AND expires_at > ?
        RETURNING user_id
        """.trimIndent(),
        now, tokenHash, purpose, now,
    ) { it.uuid("user_id") }

    /**
     * То же, но код засчитывается только пользователю с этой почтой.
     *
     * Попытки ввода считаются по почте; без этой привязки код, выданный
     * одной почте, проверялся бы в счёт попыток другой.
     */
    fun consumeAuthTokenFor(
        connection: Connection,
        tokenHash: String,
        purpose: AuthTokenPurpose,
        email: String,
        now: Instant,
    ): UUID? = connection.queryOne(
        """
        UPDATE auth_tokens
        SET used_at = ?
        WHERE token_hash = ?
          AND purpose = ?
          AND used_at IS NULL
          AND expires_at > ?
          AND user_id = (SELECT id FROM users WHERE email = ? AND deleted_at IS NULL)
        RETURNING user_id
        """.trimIndent(),
        now, tokenHash, purpose, now, email,
    ) { it.uuid("user_id") }

    fun invalidateTokens(
        connection: Connection,
        userId: UUID,
        purpose: AuthTokenPurpose,
        now: Instant,
    ): Int = connection.update(
        """
        UPDATE auth_tokens SET used_at = ?
        WHERE user_id = ? AND purpose = ? AND used_at IS NULL
        """.trimIndent(),
        now, userId, purpose,
    )

    private companion object {
        const val USER_COLUMNS = """
            SELECT id, email, email_verified, password_hash, username, display_name,
                   avatar_url, bio, created_at
            FROM users
        """

        const val SESSION_COLUMNS = """
            SELECT id, user_id, device_id, expires_at, created_at, last_used_at,
                   revoked_at, revoked_reason, user_agent
            FROM sessions
        """

        fun mapUser(rs: ResultSet) = UserRecord(
            id = rs.uuid("id"),
            email = rs.getString("email"),
            emailVerified = rs.getBoolean("email_verified"),
            passwordHash = rs.getString("password_hash"),
            username = rs.getString("username"),
            displayName = rs.getString("display_name"),
            avatarUrl = rs.getString("avatar_url"),
            bio = rs.getString("bio"),
            createdAt = rs.instant("created_at"),
        )

        fun mapSession(rs: ResultSet) = SessionRecord(
            id = rs.uuid("id"),
            userId = rs.uuid("user_id"),
            deviceId = rs.uuidOrNull("device_id"),
            expiresAt = rs.instant("expires_at"),
            createdAt = rs.instant("created_at"),
            lastUsedAt = rs.instantOrNull("last_used_at"),
            revokedAt = rs.instantOrNull("revoked_at"),
            revokedReason = rs.getString("revoked_reason"),
            userAgent = rs.getString("user_agent"),
        )
    }
}
