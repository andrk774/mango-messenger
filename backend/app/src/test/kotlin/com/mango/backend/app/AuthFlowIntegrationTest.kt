package com.mango.backend.app

import com.mango.backend.auth.CurrentUserResponse
import com.mango.backend.auth.ForgotPasswordRequest
import com.mango.backend.auth.LoginRequest
import com.mango.backend.auth.RefreshRequest
import com.mango.backend.auth.RegisterRequest
import com.mango.backend.auth.ResetPasswordRequest
import com.mango.backend.auth.SessionResponse
import com.mango.backend.auth.TokenPairResponse
import com.mango.backend.auth.VerifyEmailRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import com.mango.backend.common.ApiJson
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Сквозные проверки аутентификации на настоящих PostgreSQL и HTTP-слое.
 *
 * Проверяется не «метод вернул объект», а наблюдаемое поведение системы:
 * коды ответов, содержимое базы, инварианты безопасности.
 */
class AuthFlowIntegrationTest : IntegrationTestBase() {

    @BeforeEach
    fun cleanup() = resetDatabase()

    private val email = "anna@example.com"
    private val password = "ОченьДлинныйПароль-2026"

    // ──────────────────────────── регистрация ────────────────────────────

    @Test
    fun `полный путь регистрация подтверждение вход`() = withApp { client ->
        val registered = client.register(email, password, "anna")
        assertEquals(HttpStatusCode.Accepted, registered.status)

        // До подтверждения адреса вход запрещён.
        val tooEarly = client.login(email, password)
        assertEquals(
            HttpStatusCode.Forbidden,
            tooEarly.status,
            "Неподтверждённый адрес не должен пускать в систему",
        )

        val token = mail.tokenFor(email)
        val verified = client.post("/v1/auth/verify-email") {
            contentType(ContentType.Application.Json)
            setBody(VerifyEmailRequest(token))
        }
        assertEquals(HttpStatusCode.OK, verified.status)

        val loggedIn = client.login(email, password)
        assertEquals(HttpStatusCode.OK, loggedIn.status)

        val tokens: TokenPairResponse = loggedIn.body()
        assertTrue(tokens.accessToken.isNotBlank())
        assertTrue(tokens.refreshToken.isNotBlank())
        assertTrue(tokens.accessExpiresAt.isBefore(tokens.refreshExpiresAt))
    }

    @Test
    fun `повторная регистрация на занятый адрес не раскрывает его занятость`() =
        withApp { client ->
            client.register(email, password, "anna")
            mail.clear()

            val second = client.register(email, "ДругойПароль-2026", "boris")

            assertEquals(
                HttpStatusCode.Accepted,
                second.status,
                "Ответ обязан совпадать с ответом для свободного адреса",
            )

            // Владельцу адреса уходит предупреждение, а не ссылка подтверждения.
            val letter = mail.lastTo(email)
            assertTrue(letter != null && letter.subject.contains("Попытка"))

            // Вторая учётная запись не создана.
            assertEquals(1, countUsers())
        }

    @Test
    fun `занятое имя пользователя сообщается прямо`() = withApp { client ->
        client.register(email, password, "anna")

        val conflict = client.register("boris@example.com", password, "anna")

        // username публичен и участвует в поиске, поэтому его занятость
        // не является секретом — в отличие от адреса почты.
        assertEquals(HttpStatusCode.Conflict, conflict.status)
    }

    @Test
    fun `короткий пароль и кривой адрес отклоняются с перечнем полей`() = withApp { client ->
        val response = client.register("не-почта", "123", "a")

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)

        val error: ErrorResponse = response.body()
        assertEquals("VALIDATION_FAILED", error.code)
        val fields = error.fieldErrors.orEmpty().keys
        assertTrue("email" in fields, "ожидалась ошибка по email, получено $fields")
        assertTrue("password" in fields, "ожидалась ошибка по password, получено $fields")
        assertTrue("username" in fields, "ожидалась ошибка по username, получено $fields")
    }

    @Test
    fun `ссылка подтверждения срабатывает один раз`() = withApp { client ->
        client.register(email, password, "anna")
        val token = mail.tokenFor(email)

        val first = client.verifyEmail(token)
        val second = client.verifyEmail(token)

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(
            HttpStatusCode.BadRequest,
            second.status,
            "Повторное использование ссылки должно отклоняться",
        )
    }

    // ──────────────────────────────── вход ────────────────────────────────

    @Test
    fun `неверный пароль и неизвестный адрес дают одинаковый ответ`() = withApp { client ->
        client.registerAndVerify(email, password, "anna")

        val wrongPassword = client.login(email, "НеправильныйПароль-2026")
        val unknownEmail = client.login("nobody@example.com", password)

        assertEquals(HttpStatusCode.Unauthorized, wrongPassword.status)
        assertEquals(HttpStatusCode.Unauthorized, unknownEmail.status)

        val a: ErrorResponse = wrongPassword.body()
        val b: ErrorResponse = unknownEmail.body()
        assertEquals(
            a.message,
            b.message,
            "Различие в тексте позволило бы перечислять зарегистрированные адреса",
        )
    }

    @Test
    fun `вход создаёт сессию, видимую в списке устройств`() = withApp { client ->
        val tokens = client.registerVerifyLogin(email, password, "anna")

        val response = client.get("/v1/auth/sessions") { bearerAuth(tokens.accessToken) }
        assertEquals(HttpStatusCode.OK, response.status)

        val sessions: List<SessionResponse> = response.body()
        assertEquals(1, sessions.size)
        assertTrue(sessions.single().isCurrent)
    }

    // ─────────────────────── обновление токенов ───────────────────────

    @Test
    fun `обновление выдаёт новую пару и делает старый refresh недействительным`() =
        withApp { client ->
            val first = client.registerVerifyLogin(email, password, "anna")

            val refreshed = client.refresh(first.refreshToken)
            assertEquals(HttpStatusCode.OK, refreshed.status)

            val second: TokenPairResponse = refreshed.body()
            assertNotEquals(first.refreshToken, second.refreshToken, "refresh обязан ротироваться")
            assertNotEquals(first.accessToken, second.accessToken)

            // Новый токен работает.
            val me = client.get("/v1/users/me") { bearerAuth(second.accessToken) }
            assertEquals(HttpStatusCode.OK, me.status)
        }

    @Test
    fun `повторное использование refresh token завершает все сессии`() = withApp { client ->
        val first = client.registerVerifyLogin(email, password, "anna")

        // Законный обмен.
        val second: TokenPairResponse = client.refresh(first.refreshToken).body()

        // Тот же refresh приходит второй раз: значит, он есть у кого-то ещё.
        val reused = client.refresh(first.refreshToken)
        assertEquals(HttpStatusCode.Unauthorized, reused.status)

        // Отзываются ВСЕ сессии, включая выданную законному владельцу:
        // отличить «украли у пользователя» от «украли у злоумышленника»
        // невозможно, поэтому безопасный выбор — прервать обе.
        val afterReuse = client.refresh(second.refreshToken)
        assertEquals(
            HttpStatusCode.Unauthorized,
            afterReuse.status,
            "После обнаружения повторного использования не должно остаться действующих сессий",
        )
    }

    @Test
    fun `выход прекращает действие и access token`() = withApp { client ->
        val tokens = client.registerVerifyLogin(email, password, "anna")

        assertEquals(
            HttpStatusCode.OK,
            client.get("/v1/users/me") { bearerAuth(tokens.accessToken) }.status,
        )

        client.post("/v1/auth/logout") { bearerAuth(tokens.accessToken) }

        // Без списка отозванных сессий подписанный токен продолжал бы
        // работать до истечения срока, и выход не защищал бы ничего.
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v1/users/me") { bearerAuth(tokens.accessToken) }.status,
            "Выход обязан прекращать действие уже выданного токена",
        )
    }

    @Test
    fun `обнаружение кражи прекращает действие выданных токенов`() = withApp { client ->
        val first = client.registerVerifyLogin(email, password, "anna")
        val second: TokenPairResponse = client.refresh(first.refreshToken).body()

        // Проверяем, что токен второй сессии действует до обнаружения кражи.
        assertEquals(
            HttpStatusCode.OK,
            client.get("/v1/users/me") { bearerAuth(second.accessToken) }.status,
        )

        // Предъявление уже обменянного refresh — признак кражи.
        client.refresh(first.refreshToken)

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v1/users/me") { bearerAuth(second.accessToken) }.status,
            "Иначе у злоумышленника оставался бы доступ до конца срока токена",
        )
    }

    @Test
    fun `смена пароля прекращает действие выданных токенов`() = withApp { client ->
        val tokens = client.registerVerifyLogin(email, password, "anna")
        mail.clear()

        client.post("/v1/auth/password/forgot") {
            contentType(ContentType.Application.Json)
            setBody(ForgotPasswordRequest(email))
        }
        client.post("/v1/auth/password/reset") {
            contentType(ContentType.Application.Json)
            setBody(ResetPasswordRequest(mail.tokenFor(email), "ЕщёОдинДлинныйПароль-2026"))
        }

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v1/users/me") { bearerAuth(tokens.accessToken) }.status,
            "Восстановление доступа после кражи не должно оставлять входа",
        )
    }

    @Test
    fun `выход делает refresh недействительным`() = withApp { client ->
        val tokens = client.registerVerifyLogin(email, password, "anna")

        val loggedOut = client.post("/v1/auth/logout") { bearerAuth(tokens.accessToken) }
        assertEquals(HttpStatusCode.NoContent, loggedOut.status)

        assertEquals(HttpStatusCode.Unauthorized, client.refresh(tokens.refreshToken).status)
    }

    // ───────────────────────── доступ к ресурсам ─────────────────────────

    @Test
    fun `защищённый ресурс требует токен`() = withApp { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/users/me").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v1/users/me") { bearerAuth("not.a.valid.token") }.status,
        )
    }

    @Test
    fun `профиль не содержит хеша пароля`() = withApp { client ->
        val tokens = client.registerVerifyLogin(email, password, "anna")

        val response = client.get("/v1/users/me") { bearerAuth(tokens.accessToken) }
        val raw = response.bodyAsText()
        val body = ApiJson.decodeFromString<CurrentUserResponse>(raw)

        assertEquals(email, body.email)
        assertEquals("anna", body.username)
        assertTrue(body.emailVerified)

        assertTrue(
            !raw.contains("passwordHash") && !raw.contains("argon2"),
            "В ответ не должен попадать хеш пароля: $raw",
        )
    }

    // ─────────────────────── восстановление пароля ───────────────────────

    @Test
    fun `смена пароля завершает все сессии и включает новый пароль`() = withApp { client ->
        val tokens = client.registerVerifyLogin(email, password, "anna")
        mail.clear()

        val requested = client.post("/v1/auth/password/forgot") {
            contentType(ContentType.Application.Json)
            setBody(ForgotPasswordRequest(email))
        }
        assertEquals(HttpStatusCode.Accepted, requested.status)

        val resetToken = mail.tokenFor(email)
        val newPassword = "СовсемДругойПароль-2026"

        val reset = client.post("/v1/auth/password/reset") {
            contentType(ContentType.Application.Json)
            setBody(ResetPasswordRequest(resetToken, newPassword))
        }
        assertEquals(HttpStatusCode.OK, reset.status)

        // Старая сессия больше не действует: если доступ восстанавливают
        // после кражи, у злоумышленника не должно остаться входа.
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(tokens.refreshToken).status)

        assertEquals(HttpStatusCode.Unauthorized, client.login(email, password).status)
        assertEquals(HttpStatusCode.OK, client.login(email, newPassword).status)
    }

    @Test
    fun `запрос восстановления для неизвестного адреса выглядит так же`() = withApp { client ->
        val response = client.post("/v1/auth/password/forgot") {
            contentType(ContentType.Application.Json)
            setBody(ForgotPasswordRequest("nobody@example.com"))
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertNull(mail.lastTo("nobody@example.com"), "Письмо отправлять некому")
    }

    // ──────────────────────────── вспомогательное ────────────────────────────

    private suspend fun HttpClient.register(email: String, password: String, username: String) =
        post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    email = email,
                    password = password,
                    displayName = "Тестовый Пользователь",
                    username = username,
                ),
            )
        }

    private suspend fun HttpClient.verifyEmail(token: String) =
        post("/v1/auth/verify-email") {
            contentType(ContentType.Application.Json)
            setBody(VerifyEmailRequest(token))
        }

    private suspend fun HttpClient.login(email: String, password: String): HttpResponse =
        post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = email, password = password))
        }

    private suspend fun HttpClient.refresh(token: String): HttpResponse =
        post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(token))
        }

    private suspend fun HttpClient.registerAndVerify(
        email: String,
        password: String,
        username: String,
    ) {
        register(email, password, username)
        verifyEmail(mail.tokenFor(email))
    }

    private suspend fun HttpClient.registerVerifyLogin(
        email: String,
        password: String,
        username: String,
    ): TokenPairResponse {
        registerAndVerify(email, password, username)
        return login(email, password).body()
    }

    private fun countUsers(): Int =
        postgres.createConnection("").use { connection ->
            connection.prepareStatement("SELECT count(*) FROM users").use { statement ->
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
}
