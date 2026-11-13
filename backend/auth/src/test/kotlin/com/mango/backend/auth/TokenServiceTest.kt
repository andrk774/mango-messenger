package com.mango.backend.auth

import com.mango.backend.common.JwtConfig
import com.mango.backend.common.MutableTimeSource
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenServiceTest {

    /**
     * Время выпуска. Привязано к реальным часам, потому что проверку `exp`
     * выполняет java-jwt по системному времени — подменить его нельзя
     * (см. комментарий к TokenService.verifier). Поэтому «истечение срока»
     * проверяется отрицательным TTL, а не сдвигом этого источника.
     */
    private val time = MutableTimeSource(Instant.now())

    private val config = JwtConfig(
        secret = "test-secret-long-enough-for-hmac-sha256-algorithm",
        issuer = "mango-test",
        audience = "mango-app",
        accessTokenTtl = Duration.ofMinutes(15),
        refreshTokenTtl = Duration.ofDays(30),
    )

    private val service = TokenService(config, time)

    @Test
    fun `access token содержит пользователя и сессию`() {
        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()

        val token = service.issueAccessToken(userId, sessionId)
        val claims = service.verifyAccessToken(token.value)

        assertNotNull(claims)
        assertEquals(userId, claims.userId)
        assertEquals(sessionId, claims.sessionId)
    }

    @Test
    fun `срок действия access token соответствует настройке`() {
        val token = service.issueAccessToken(UUID.randomUUID(), UUID.randomUUID())

        assertEquals(time.now().plus(Duration.ofMinutes(15)).epochSecond, token.expiresAt.epochSecond)
    }

    @Test
    fun `истёкший access token отвергается`() {
        // Токен, срок которого истёк минуту назад относительно реальных часов.
        val expiring = TokenService(config.copy(accessTokenTtl = Duration.ofMinutes(-1)), time)
        val token = expiring.issueAccessToken(UUID.randomUUID(), UUID.randomUUID())

        assertNull(
            service.verifyAccessToken(token.value),
            "Токен с истёкшим сроком не должен приниматься",
        )
    }

    @Test
    fun `действующий access token принимается`() {
        val token = service.issueAccessToken(UUID.randomUUID(), UUID.randomUUID())

        assertNotNull(service.verifyAccessToken(token.value))
    }

    @Test
    fun `токен, подписанный другим ключом, отвергается`() {
        val other = TokenService(
            config.copy(secret = "another-secret-also-long-enough-for-hmac-256"),
            time,
        )
        val foreign = other.issueAccessToken(UUID.randomUUID(), UUID.randomUUID())

        assertNull(service.verifyAccessToken(foreign.value))
    }

    @Test
    fun `токен с чужим издателем отвергается`() {
        val other = TokenService(config.copy(issuer = "evil-issuer"), time)
        val foreign = other.issueAccessToken(UUID.randomUUID(), UUID.randomUUID())

        assertNull(service.verifyAccessToken(foreign.value))
    }

    @Test
    fun `повреждённый токен не вызывает исключения`() {
        assertNull(service.verifyAccessToken("мусор"))
        assertNull(service.verifyAccessToken(""))
        assertNull(service.verifyAccessToken("a.b.c"))
    }

    @Test
    fun `refresh token каждый раз новый`() {
        val first = service.issueRefreshToken()
        val second = service.issueRefreshToken()

        assertNotEquals(first.value, second.value)
        assertNotEquals(first.hash, second.hash)
    }

    @Test
    fun `refresh token достаточно длинный для защиты от перебора`() {
        val token = service.issueRefreshToken()

        // 32 байта в base64url без выравнивания — 43 символа.
        assertEquals(43, token.value.length, "Ожидается 256 бит энтропии")
    }

    @Test
    fun `хеш refresh token детерминирован и необратим`() {
        val token = service.issueRefreshToken()

        assertEquals(token.hash, service.hashRefreshToken(token.value))
        assertNotEquals(token.value, token.hash)
        assertFalse(
            token.hash.contains(token.value),
            "Хеш не должен содержать исходный токен",
        )
    }

    @Test
    fun `срок refresh token соответствует настройке`() {
        val token = service.issueRefreshToken()

        assertEquals(
            time.now().plus(Duration.ofDays(30)).epochSecond,
            token.expiresAt.epochSecond,
        )
    }

    @Test
    fun `одноразовый токен для письма уникален`() {
        val first = service.issueOneTimeToken()
        val second = service.issueOneTimeToken()

        assertNotEquals(first.value, second.value)
        assertEquals(first.hash, service.hashOneTimeToken(first.value))
    }

    @Test
    fun `два access token подряд различаются`() {
        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()

        val first = service.issueAccessToken(userId, sessionId)
        val second = service.issueAccessToken(userId, sessionId)

        // jti делает токены различимыми, даже если выписаны в одну секунду
        // с одинаковыми утверждениями. Это нужно для будущего чёрного списка.
        assertNotEquals(first.value, second.value)
    }

    @Test
    fun `токен без идентификатора сессии отвергается`() {
        // Собираем токен вручную без утверждения sid.
        val algorithm = com.auth0.jwt.algorithms.Algorithm.HMAC256(config.secret)
        val token = com.auth0.jwt.JWT.create()
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withSubject(UUID.randomUUID().toString())
            .withExpiresAt(time.now().plus(Duration.ofMinutes(10)))
            .sign(algorithm)

        assertNull(
            service.verifyAccessToken(token),
            "Без sid нельзя связать запрос с сессией — такой токен бесполезен",
        )
    }

    // ─────────────────────── код из письма ───────────────────────

    @Test
    fun `код из письма короткий и без похожих символов`() {
        repeat(200) {
            val code = service.issueOneTimeToken().value
            assertTrue(Regex("^[2-9A-HJKMNP-Z]{5}-[2-9A-HJKMNP-Z]{5}$").matches(code), code)
            assertTrue(code.none { it in "01ILO" }, "Похожие символы в коде: $code")
        }
    }

    @Test
    fun `код принимается в любом регистре, с пробелами и без дефиса`() {
        val issued = service.issueOneTimeToken()
        val compact = issued.value.replace("-", "")

        listOf(
            issued.value,
            compact,
            compact.lowercase(),
            " ${issued.value.lowercase()} ",
            compact.chunked(2).joinToString(" "),
        ).forEach { typed ->
            assertEquals(issued.hash, service.hashOneTimeToken(typed), "Не принят ввод «$typed»")
        }
    }

    @Test
    fun `длинный код из старого письма по-прежнему проверяется точно`() {
        val legacy = "HVSW2w3j76y3rSheR4q8yLG6XEe79o2M8YNJR7XSlVM"
        assertEquals(service.hashRefreshToken(legacy), service.hashOneTimeToken(legacy))
        assertNotEquals(service.hashRefreshToken(legacy), service.hashOneTimeToken(legacy.uppercase()))
    }
}
