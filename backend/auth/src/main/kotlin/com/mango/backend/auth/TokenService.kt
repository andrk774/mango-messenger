package com.mango.backend.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.mango.backend.common.JwtConfig
import com.mango.backend.common.TimeSource
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Выпуск и проверка токенов.
 *
 * Разделение ролей:
 *  * **access** — JWT, подписанный HMAC-SHA256, живёт минуты. Проверяется
 *    без обращения к базе, поэтому отзыв до истечения невозможен — отсюда
 *    короткий срок жизни;
 *  * **refresh** — непредсказуемая случайная строка, живёт неделями.
 *    В базе хранится ТОЛЬКО её SHA-256: дамп базы не даёт доступа к аккаунтам.
 *    Каждый обмен порождает новый refresh (ротация).
 */
class TokenService(
    private val config: JwtConfig,
    private val time: TimeSource,
    private val random: SecureRandom = SecureRandom(),
) {

    private val algorithm: Algorithm = Algorithm.HMAC256(config.secret)

    /**
     * Проверяющий токены.
     *
     * ВАЖНО: срок действия (`exp`) проверяется по СИСТЕМНЫМ часам —
     * java-jwt 4.6 не позволяет подменить источник времени. В продакшне
     * это ровно те же часы, что и [time], поэтому расхождения нет, но
     * тест на истечение срока обязан строиться на реальном времени
     * (например, через отрицательный TTL), а не на сдвиге [time].
     */
    val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(config.issuer)
        .withAudience(config.audience)
        .withClaimPresence(CLAIM_SESSION)
        .build()

    val issuer: String get() = config.issuer
    val audience: String get() = config.audience
    val algorithmForVerification: Algorithm get() = algorithm

    /**
     * Выпускает access token.
     *
     * Внутрь кладётся идентификатор сессии: он позволяет связать запрос
     * с конкретным устройством в журнале и не считать валидным токен,
     * выписанный под уже отозванную сессию, когда появится чёрный список.
     */
    fun issueAccessToken(userId: UUID, sessionId: UUID): AccessToken {
        val issuedAt = time.now()
        val expiresAt = issuedAt.plus(config.accessTokenTtl)

        val token = JWT.create()
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withSubject(userId.toString())
            .withClaim(CLAIM_SESSION, sessionId.toString())
            .withIssuedAt(issuedAt)
            .withExpiresAt(expiresAt)
            .withJWTId(UUID.randomUUID().toString())
            .sign(algorithm)

        return AccessToken(token, expiresAt)
    }

    /**
     * Создаёт refresh token.
     *
     * 256 бит из криптографически стойкого источника: перебор невозможен,
     * поэтому отдельная «сложность» как у пароля не нужна, а значит
     * не нужен и медленный хеш — SHA-256 здесь достаточно и не тормозит
     * обновление токена.
     */
    fun issueRefreshToken(): RefreshToken {
        val bytes = ByteArray(REFRESH_TOKEN_BYTES).also(random::nextBytes)
        val value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return RefreshToken(
            value = value,
            hash = hashRefreshToken(value),
            expiresAt = time.now().plus(config.refreshTokenTtl),
        )
    }

    fun hashRefreshToken(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /**
     * Одноразовый код для письма: подтверждение адреса, сброс пароля.
     *
     * Код перепечатывают руками с экрана компьютера на телефон, поэтому:
     * десять символов вместо 43, только заглавные буквы и цифры, без
     * похожих пар (0/O, 1/I/L), в письме — двумя группами через дефис.
     * 31^10 ≈ 8·10^14 вариантов (~50 бит); подбор отсекают срок жизни,
     * одноразовость и ограничение числа попыток (RateLimits.CODE_*).
     */
    fun issueOneTimeToken(): OneTimeToken {
        val raw = CharArray(OneTimeCode.CODE_LENGTH) {
            OneTimeCode.CODE_ALPHABET[random.nextInt(OneTimeCode.CODE_ALPHABET.length)]
        }.concatToString()
        val display = raw.chunked(OneTimeCode.CODE_LENGTH / 2).joinToString("-")
        return OneTimeToken(value = display, hash = hashRefreshToken(raw))
    }

    /** Хеш введённого кода: без учёта регистра, пробелов и дефисов. */
    fun hashOneTimeToken(input: String): String = hashRefreshToken(OneTimeCode.normalizeCode(input))

    object OneTimeCode {
        const val CODE_LENGTH = 10

        /** Цифры 2–9 и латиница без I, L, O — 31 символ, которые не спутать. */
        const val CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

        /**
         * Приведение введённого кода к каноническому виду.
         *
         * Длинные коды из писем, отправленных до перехода на короткие,
         * регистрозависимы и оставляются как есть: они ещё действуют
         * до истечения срока.
         */
        fun normalizeCode(input: String): String {
            val compact = input.filterNot { it.isWhitespace() || it == '-' }
            return if (compact.length == CODE_LENGTH) compact.uppercase() else input.trim()
        }
    }

    /** Возвращает (userId, sessionId) или null, если токен недействителен. */
    fun verifyAccessToken(token: String): AccessClaims? = try {
        val decoded = verifier.verify(token)
        val userId = decoded.subject?.let(UUID::fromString)
        val sessionId = decoded.getClaim(CLAIM_SESSION).asString()?.let(UUID::fromString)
        if (userId != null && sessionId != null) AccessClaims(userId, sessionId) else null
    } catch (_: JWTVerificationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        const val CLAIM_SESSION = "sid"
        private const val REFRESH_TOKEN_BYTES = 32
    }
}

data class AccessToken(val value: String, val expiresAt: Instant)

data class RefreshToken(val value: String, val hash: String, val expiresAt: Instant)

data class OneTimeToken(val value: String, val hash: String)

data class AccessClaims(val userId: UUID, val sessionId: UUID)
