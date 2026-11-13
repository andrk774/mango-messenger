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
    val storage: StorageConfig,
    val push: PushConfig = PushConfig(credentialsFile = null),
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
                    username = env("MANGO_SMTP_USERNAME")?.takeIf { it.isNotBlank() },
                    password = env("MANGO_SMTP_PASSWORD")?.takeIf { it.isNotBlank() },
                    security = env("MANGO_SMTP_SECURITY")?.uppercase()?.let(MailSecurity::valueOf)
                        ?: MailSecurity.NONE,
                ),
                push = PushConfig(
                    // Ключ сервисного аккаунта Firebase. Не задан или файла
                    // нет — push только записываются в журнал.
                    credentialsFile = env("MANGO_FCM_CREDENTIALS_FILE")?.takeIf { it.isNotBlank() },
                ),
                storage = StorageConfig(
                    directory = env("MANGO_STORAGE_DIR") ?: "build/attachments",
                    // Отдельный ключ подписи ссылок можно задать явно; иначе
                    // он выводится из секрета JWT с другим назначением, чтобы
                    // подпись ссылки нельзя было выдать за подпись токена.
                    signingKey = env("MANGO_STORAGE_SIGNING_KEY") ?: "storage-url-signing:$jwtSecret",
                    maxFileBytes = env("MANGO_STORAGE_MAX_FILE_MB")?.toLongOrNull()
                        ?.let { it * 1024 * 1024 } ?: (100L * 1024 * 1024),
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

/** Push-уведомления (ADR-0010). */
data class PushConfig(val credentialsFile: String?)

/**
 * Хранилище вложений.
 *
 * [directory] — каталог файловой реализации (см. ADR-0019); в контейнере
 * это подключённый том.
 */
data class StorageConfig(
    val directory: String,
    val signingKey: String,
    val maxFileBytes: Long,
)

data class MailConfig(
    val host: String,
    val port: Int,
    val from: String,
    val enabled: Boolean,
    val username: String? = null,
    val password: String? = null,
    val security: MailSecurity = MailSecurity.NONE,
) {
    init {
        require((username == null) == (password == null)) { "SMTP username and password must be configured together" }
        require(username == null || security != MailSecurity.NONE) { "SMTP authentication requires STARTTLS or TLS" }
    }
}

enum class MailSecurity { NONE, STARTTLS, TLS }
