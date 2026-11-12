package com.mango.backend.app

import com.mango.backend.auth.ForgotPasswordRequest
import com.mango.backend.auth.LoginRequest
import com.mango.backend.auth.RegisterRequest
import com.mango.backend.common.InMemoryRateLimiter
import com.mango.backend.common.RateLimits
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Проверка ограничения частоты запросов.
 *
 * Перебор паролей по сети — основная практическая атака на вход, и
 * ограничение частоты закрывает её там, где стоимость хеширования
 * уже недостаточна.
 */
class RateLimitTest : IntegrationTestBase() {

    @BeforeEach
    fun cleanup() = resetDatabase()

    @Test
    fun `перебор пароля упирается в предел и получает Retry-After`() =
        withApp(InMemoryRateLimiter(time)) { client ->
            val email = "victim@example.com"

            // Предел считается по попыткам, а не по успехам: отсутствие
            // учётной записи не должно давать неограниченный перебор.
            repeat(RateLimits.LOGIN_LIMIT) { attempt ->
                val response = client.login(email, "Попытка-$attempt-2026")
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    response.status,
                    "Попытка ${attempt + 1} должна отклоняться как неверные данные",
                )
            }

            val blocked = client.login(email, "ЕщёОднаПопытка-2026")
            assertEquals(HttpStatusCode.TooManyRequests, blocked.status)

            val retryAfter = blocked.headers[HttpHeaders.RetryAfter]
            assertNotNull(retryAfter, "Клиенту нужно сообщить, когда повторять")
            assertTrue(
                retryAfter.toLong() in 1..RateLimits.LOGIN_WINDOW.seconds,
                "Неправдоподобная задержка: $retryAfter",
            )
        }

    @Test
    fun `ограничение входа не затрагивает другие учётные записи`() =
        withApp(InMemoryRateLimiter(time)) { client ->
            repeat(RateLimits.LOGIN_LIMIT + 1) {
                client.login("first@example.com", "Пароль-2026")
            }

            // Ключ включает адрес почты: иначе перебор одного аккаунта
            // блокировал бы вход всем остальным с того же адреса сети.
            val other = client.login("second@example.com", "Пароль-2026")
            assertEquals(
                HttpStatusCode.Unauthorized,
                other.status,
                "Другая учётная запись не должна блокироваться",
            )
        }

    @Test
    fun `ограничение регистрации считается по адресу клиента`() =
        withApp(InMemoryRateLimiter(time)) { client ->
            repeat(RateLimits.REGISTER_LIMIT) { index ->
                val response = client.post("/v1/auth/register") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.XForwardedFor, "203.0.113.7")
                    setBody(
                        RegisterRequest(
                            email = "flood$index@example.com",
                            password = "ДлинныйПароль-2026",
                            displayName = "Поток",
                            username = "flood$index",
                        ),
                    )
                }
                assertEquals(HttpStatusCode.Accepted, response.status)
            }

            val blocked = client.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.XForwardedFor, "203.0.113.7")
                setBody(
                    RegisterRequest(
                        email = "flood99@example.com",
                        password = "ДлинныйПароль-2026",
                        displayName = "Поток",
                        username = "flood99",
                    ),
                )
            }
            assertEquals(HttpStatusCode.TooManyRequests, blocked.status)

            // С другого адреса регистрация по-прежнему доступна.
            val fromElsewhere = client.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.XForwardedFor, "198.51.100.4")
                setBody(
                    RegisterRequest(
                        email = "elsewhere@example.com",
                        password = "ДлинныйПароль-2026",
                        displayName = "Другой",
                        username = "elsewhere",
                    ),
                )
            }
            assertEquals(HttpStatusCode.Accepted, fromElsewhere.status)
        }

    @Test
    fun `запросы восстановления пароля ограничены по адресу почты`() =
        withApp(InMemoryRateLimiter(time)) { client ->
            repeat(RateLimits.PASSWORD_RESET_LIMIT) {
                val response = client.post("/v1/auth/password/forgot") {
                    contentType(ContentType.Application.Json)
                    setBody(ForgotPasswordRequest("target@example.com"))
                }
                assertEquals(HttpStatusCode.Accepted, response.status)
            }

            val blocked = client.post("/v1/auth/password/forgot") {
                contentType(ContentType.Application.Json)
                setBody(ForgotPasswordRequest("target@example.com"))
            }

            // Без этого ограничения эндпоинт становится средством
            // забрасывать чужой почтовый ящик письмами.
            assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
        }

    @Test
    fun `некорректный X-Forwarded-For не ломает вход`() =
        withApp(InMemoryRateLimiter(time)) { client ->
            // Заголовок полностью управляется клиентом. Значение, не
            // являющееся адресом, раньше приводило к отказу 500 при записи
            // в колонку типа inet — то есть одним заголовком можно было
            // сломать аутентификацию.
            val response = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.XForwardedFor, "'; DROP TABLE users; --")
                setBody(LoginRequest(email = "anyone@example.com", password = "Пароль-2026"))
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    private suspend fun io.ktor.client.HttpClient.login(email: String, password: String) =
        post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = email, password = password))
        }
}
