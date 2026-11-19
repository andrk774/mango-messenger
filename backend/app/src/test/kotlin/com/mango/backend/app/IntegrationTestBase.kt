package com.mango.backend.app

import com.mango.backend.auth.LoginRequest
import com.mango.backend.auth.MailSender
import com.mango.backend.auth.RegisterRequest
import com.mango.backend.auth.TokenPairResponse
import com.mango.backend.auth.VerifyEmailRequest
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import com.mango.backend.common.AppConfig
import com.mango.backend.common.ApiJson
import com.mango.backend.common.Database
import com.mango.backend.common.MutableTimeSource
import com.mango.backend.common.NoopRateLimiter
import com.mango.backend.common.RateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Основа интеграционных тестов.
 *
 * Запускается НАСТОЯЩИЙ PostgreSQL в контейнере, а не встроенная база:
 * схема опирается на citext, JSONB, частичные индексы и `ON CONFLICT`
 * с предикатом — всё это H2 либо не поддерживает, либо поддерживает иначе.
 * Тест на поддельной базе проверял бы не ту систему, которая поедет в бой.
 *
 * Контейнер один на весь прогон: старт PostgreSQL занимает секунды,
 * и поднимать его на каждый класс тестов было бы расточительно.
 * Изоляция обеспечивается очисткой таблиц перед каждым тестом.
 */
abstract class IntegrationTestBase {

    protected val time = MutableTimeSource(Instant.now())

    /** Перехватчик писем: токены подтверждения достаются отсюда. */
    protected class RecordingMailSender : MailSender {
        data class Letter(val to: String, val subject: String, val body: String)

        private val letters = mutableListOf<Letter>()

        override suspend fun send(to: String, subject: String, body: String) {
            synchronized(letters) { letters += Letter(to, subject, body) }
        }

        fun all(): List<Letter> = synchronized(letters) { letters.toList() }

        fun lastTo(email: String): Letter? =
            synchronized(letters) { letters.lastOrNull { it.to.equals(email, ignoreCase = true) } }

        fun clear() = synchronized(letters) { letters.clear() }

        /** Токен из письма: сервис кладёт его последним словом. */
        fun tokenFor(email: String): String =
            lastTo(email)?.body?.substringAfterLast(": ")
                ?: error("Письмо для $email не отправлено")
    }

    protected val mail = RecordingMailSender()

    /** Очистка данных между тестами. */
    protected fun resetDatabase() {
        postgres.createConnection("").use { connection ->
            connection.createStatement().use { statement ->
                // TRUNCATE с CASCADE быстрее, чем DELETE, и разом сбрасывает
                // последовательности — тесты не зависят от порядка запуска.
                statement.execute(
                    """
                    TRUNCATE TABLE
                        outbox_operations, sync_events, notifications,
                        task_events, task_comments, task_assignees, tasks,
                        attachments, message_reactions, messages,
                        conversation_members, conversations,
                        auth_tokens, sessions, devices, users
                    RESTART IDENTITY CASCADE
                    """.trimIndent(),
                )
                statement.execute("UPDATE server_settings SET server_name='Mango Messenger',registration_open=true,smtp_profile_id=NULL WHERE id=1")
                statement.execute("TRUNCATE smtp_profiles CASCADE")
                statement.execute("INSERT INTO server_settings(id) VALUES (1) ON CONFLICT DO NOTHING")
            }
        }
        mail.clear()
    }

    /**
     * Поднимает приложение целиком: плагины, маршруты, обработку ошибок.
     *
     * Ограничение частоты по умолчанию отключено: иначе тесты, выполняющие
     * несколько входов подряд, упирались бы в предел и падали по причине,
     * не связанной с проверяемым поведением. Само ограничение проверяется
     * отдельным классом тестов.
     */
    protected fun withApp(
        rateLimiter: RateLimiter = NoopRateLimiter,
        pushProvider: com.mango.backend.notifications.PushProvider =
            com.mango.backend.notifications.LoggingPushProvider(),
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) = testApplication {
            application {
                // Короткий тайм-аут аутентификации: проверяется само
                // поведение, а не терпение сервера.
                mangoModule(
                    config = config,
                    database = database,
                    time = time,
                    mailSender = mail,
                    rateLimiter = rateLimiter,
                    wsAuthTimeoutMillis = 1_000,
                    pushProvider = pushProvider,
                )
            }

            val client = createClient {
                install(ContentNegotiation) { json(ApiJson) }
                // Ответы 4xx — ожидаемая часть проверок, исключений быть не должно.
                expectSuccess = false
            }

            block(client)
        }

    /**
     * Создаёт подтверждённого пользователя и выполняет вход.
     *
     * Почти каждый сценарий начинается с «есть два пользователя»,
     * поэтому подготовка вынесена сюда: иначе половина каждого теста
     * состояла бы из повторения регистрации.
     */
    protected suspend fun HttpClient.newUser(
        email: String,
        username: String,
        password: String = "ТестовыйПароль-2026",
        displayName: String = username,
    ): TokenPairResponse {
        post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    email = email,
                    password = password,
                    displayName = displayName,
                    username = username,
                ),
            )
        }
        post("/v1/auth/verify-email") {
            contentType(ContentType.Application.Json)
            setBody(VerifyEmailRequest(mail.tokenFor(email), email))
        }
        return post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = email, password = password))
        }.body()
    }

    companion object {
        private val started = AtomicBoolean(false)

        /**
         * Образ зафиксирован по мажорной версии: тест обязан проверять
         * ту же СУБД, что стоит в docker-compose.
         */
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("mango_test")
            .withUsername("mango")
            .withPassword("mango")

        lateinit var config: AppConfig
            private set

        lateinit var database: Database
            private set

        @JvmStatic
        @BeforeAll
        fun startInfrastructure() {
            if (!started.compareAndSet(false, true)) return

            postgres.start()

            config = AppConfig.load { key ->
                when (key) {
                    "MANGO_ENV" -> "TEST"
                    "MANGO_DB_URL" -> postgres.jdbcUrl
                    "MANGO_DB_USER" -> postgres.username
                    "MANGO_DB_PASSWORD" -> postgres.password
                    "MANGO_JWT_SECRET" -> "integration-test-secret-long-enough-for-hmac256"
                    // Стоимость Argon2 снижена: тесты проверяют логику,
                    // а не подбор параметров. С боевыми 64 МБ каждый вход
                    // добавлял бы заметную задержку к каждому тесту.
                    "MANGO_ARGON_MEMORY_KIB" -> "1024"
                    "MANGO_ARGON_ITERATIONS" -> "1"
                    "MANGO_DB_POOL_SIZE" -> "4"
                    else -> null
                }
            }

            Migrations.run(config.database)

            database = Database(
                jdbcUrl = config.database.jdbcUrl,
                username = config.database.username,
                password = config.database.password,
                poolSize = config.database.poolSize,
            )
        }

        @JvmStatic
        @AfterAll
        fun stopInfrastructure() {
            // Контейнер останавливает Ryuk при завершении JVM; явное закрытие
            // пула нужно, чтобы не держать соединения между классами тестов.
        }
    }
}
