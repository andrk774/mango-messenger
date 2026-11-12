package com.mango.backend.common

import java.time.Duration

/**
 * Конфигурация приложения.
 *
 * Источник — переменные окружения: так требует двенадцатифакторный подход
 * и так работает Docker Compose. Значения по умолчанию заданы только для
 * локальной разработки; всё, что является секретом, обязано быть задано
 * явно, иначе запуск прерывается.
 */
data class AppConfig(
    val environment: Environment,
    val http: HttpConfig,
    val database: DatabaseConfig,
    val jwt: JwtConfig,
    val password: PasswordConfig,
    val redis: RedisConfig,
    val mail: MailConfig,
) {
    enum class Environment { LOCAL, TEST, STAGING, PRODUCTION;

        val isProduction: Boolean get() = this == PRODUCTION
    }

    companion object {
        /**
         * @param env источник переменных; подменяется в тестах.
         */
        fun load(env: (String) -> String? = System::getenv): AppConfig {
            val environment = env("MANGO_ENV")
                ?.uppercase()
                ?.let { runCatching { Environment.valueOf(it) }.getOrNull() }
                ?: Environment.LOCAL

            val jwtSecret = env("MANGO_JWT_SECRET")
                ?: if (environment.isProduction) {
                    error("MANGO_JWT_SECRET обязателен в production")
                } else {
                    // Заведомо непригодное для продакшна значение: оно видно
                    // в исходниках, и это именно то, что нужно — случайно
                    // «уехать» с ним в бой невозможно без правки кода.
                    "local-development-secret-not-for-production-use"
                }

            require(jwtSecret.length >= 32) {
                "MANGO_JWT_SECRET должен быть не короче 32 символов"
            }

            return AppConfig(
                environment = environment,
                http = HttpConfig(
                    host = env("MANGO_HTTP_HOST") ?: "0.0.0.0",
                    port = env("MANGO_HTTP_PORT")?.toIntOrNull() ?: 8080,
                ),
                database = DatabaseConfig(
                    jdbcUrl = env("MANGO_DB_URL")
                        ?: "jdbc:postgresql://localhost:5433/mango",
                    username = env("MANGO_DB_USER") ?: "mango",
                    password = env("MANGO_DB_PASSWORD")
                        ?: if (environment.isProduction) {
                            error("MANGO_DB_PASSWORD обязателен в production")
                        } else {
                            "mango"
                        },
                    poolSize = env("MANGO_DB_POOL_SIZE")?.toIntOrNull() ?: 10,
                    runMigrations = env("MANGO_DB_MIGRATE")?.toBooleanStrictOrNull() ?: true,
                ),
                jwt = JwtConfig(
                    secret = jwtSecret,
                    issuer = env("MANGO_JWT_ISSUER") ?: "mango-messenger",
                    audience = env("MANGO_JWT_AUDIENCE") ?: "mango-app",
                    accessTokenTtl = env("MANGO_ACCESS_TTL_MINUTES")?.toLongOrNull()
                        ?.let(Duration::ofMinutes) ?: Duration.ofMinutes(15),
                    refreshTokenTtl = env("MANGO_REFRESH_TTL_DAYS")?.toLongOrNull()
                        ?.let(Duration::ofDays) ?: Duration.ofDays(30),
                ),
                password = PasswordConfig(
                    // Параметры Argon2id. Память — основной защитный ресурс:
                    // она мешает распараллеливанию перебора на GPU.
                    memoryKib = env("MANGO_ARGON_MEMORY_KIB")?.toIntOrNull() ?: 65_536,
                    iterations = env("MANGO_ARGON_ITERATIONS")?.toIntOrNull() ?: 3,
                    parallelism = env("MANGO_ARGON_PARALLELISM")?.toIntOrNull() ?: 1,
                ),
                redis = RedisConfig(
                    url = env("MANGO_REDIS_URL") ?: "redis://localhost:6380",
                    enabled = env("MANGO_REDIS_ENABLED")?.toBooleanStrictOrNull() ?: true,
                ),
                mail = MailConfig(
                    host = env("MANGO_SMTP_HOST") ?: "localhost",
                    port = env("MANGO_SMTP_PORT")?.toIntOrNull() ?: 1026,
                    from = env("MANGO_MAIL_FROM") ?: "noreply@mango.local",
                    enabled = env("MANGO_MAIL_ENABLED")?.toBooleanStrictOrNull() ?: true,
                ),
            )
        }
    }
}

data class HttpConfig(val host: String, val port: Int)

data class DatabaseConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val poolSize: Int,
    val runMigrations: Boolean,
)

data class JwtConfig(
    val secret: String,
    val issuer: String,
    val audience: String,
    /** Короткий срок жизни: украденный access token быстро протухает. */
    val accessTokenTtl: Duration,
    val refreshTokenTtl: Duration,
)

data class PasswordConfig(
    val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int,
)

data class RedisConfig(val url: String, val enabled: Boolean)

data class MailConfig(
    val host: String,
    val port: Int,
    val from: String,
    val enabled: Boolean,
)
