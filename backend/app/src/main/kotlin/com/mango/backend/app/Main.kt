package com.mango.backend.app

import com.mango.backend.storage.AttachmentService
import com.mango.backend.storage.AttachmentsRepository
import com.mango.backend.storage.FileSystemBlobStorage
import com.mango.backend.storage.UrlSigner
import com.mango.backend.storage.attachmentsRoutes
import java.nio.file.Path
import com.mango.backend.auth.AuthRepository
import com.mango.backend.auth.AuthService
import com.mango.backend.auth.JWT_AUTH
import com.mango.backend.auth.LoggingMailSender
import com.mango.backend.auth.MailSender
import com.mango.backend.auth.PasswordHasher
import com.mango.backend.auth.SmtpMailSender
import com.mango.backend.auth.TokenService
import com.mango.backend.auth.authRoutes
import com.mango.backend.common.ApiJson
import com.mango.backend.common.AppConfig
import com.mango.backend.common.Database
import com.mango.backend.common.InMemoryRateLimiter
import com.mango.backend.common.RedisRateLimiter
import com.mango.backend.common.InMemorySessionRevocations
import com.mango.backend.common.RedisSessionRevocations
import com.mango.backend.common.SessionRevocations
import com.mango.backend.common.RateLimiter
import com.mango.backend.common.SystemTime
import com.mango.backend.common.TimeSource
import com.mango.backend.common.UuidV7Generator
import com.mango.backend.messaging.ConversationService
import com.mango.backend.messaging.MessageService
import com.mango.backend.messaging.MessagingRepository
import com.mango.backend.messaging.messagingRoutes
import com.mango.backend.realtime.DEFAULT_AUTH_TIMEOUT_MILLIS
import com.mango.backend.realtime.HEARTBEAT_SECONDS
import com.mango.backend.realtime.LocalEventBus
import com.mango.backend.realtime.RedisEventBus
import com.mango.backend.realtime.WebSocketHub
import com.mango.backend.realtime.realtimeRoutes
import com.mango.backend.sync.EventBus
import com.mango.backend.sync.EventLog
import com.mango.backend.sync.SyncService
import com.mango.backend.sync.syncRoutes
import com.mango.backend.tasks.TaskService
import com.mango.backend.tasks.TasksRepository
import com.mango.backend.tasks.tasksRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlin.time.Duration.Companion.seconds
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.UUID

private val log = LoggerFactory.getLogger("MangoBackend")

fun main() {
    val config = AppConfig.load()
    log.info("запуск Mango backend, окружение {}", config.environment)

    if (config.database.runMigrations) {
        Migrations.run(config.database)
    }

    val database = Database(
        jdbcUrl = config.database.jdbcUrl,
        username = config.database.username,
        password = config.database.password,
        poolSize = config.database.poolSize,
    )

    // Почта включается настройкой: в окружении без SMTP письма уходят
    // только в журнал, и регистрация всё равно работает.
    val mailSender: MailSender = if (config.mail.enabled) {
        SmtpMailSender(config.mail)
    } else {
        LoggingMailSender()
    }

    // Реестр подключений этого процесса и способ доставки событий.
    // Redis нужен только для нескольких экземпляров; при одном достаточно
    // локальной доставки, и лишняя зависимость в разработке не требуется.
    // Счётчики ограничения частоты: общие при наличии Redis, иначе локальные.
    val rateLimiter = if (config.redis.enabled) {
        RedisRateLimiter(config.redis.url)
    } else {
        InMemoryRateLimiter(SystemTime)
    }

    // Список отозванных сессий. Закрывает окно, в котором уже выданный
    // access token продолжал бы работать после закрытия сессии.
    val revocations = if (config.redis.enabled) {
        RedisSessionRevocations(config.redis.url)
    } else {
        InMemorySessionRevocations(SystemTime)
    }

    val hub = WebSocketHub()
    val eventBus = if (config.redis.enabled) {
        RedisEventBus(config.redis.url, hub)
    } else {
        LocalEventBus(hub)
    }

    val server = embeddedServer(
        factory = Netty,
        port = config.http.port,
        host = config.http.host,
    ) {
        mangoModule(
            config = config,
            database = database,
            mailSender = mailSender,
            rateLimiter = rateLimiter,
            hub = hub,
            eventBus = eventBus,
            revocations = revocations,
        )
    }

    // Корректная остановка: Netty дорабатывает принятые запросы, после чего
    // закрывается пул соединений. Без этого контейнер при деплое обрывал бы
    // запросы на полпути, а незакрытый пул держал бы соединения в PostgreSQL.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            log.info("остановка сервера")
            server.stop(gracePeriodMillis = 3_000, timeoutMillis = 10_000)
            (eventBus as? AutoCloseable)?.let { runCatching(it::close) }
            (rateLimiter as? AutoCloseable)?.let { runCatching(it::close) }
            (revocations as? AutoCloseable)?.let { runCatching(it::close) }
            database.close()
        },
    )

    server.start(wait = true)
}

/**
 * Композиционный корень.
 *
 * Зависимости собираются вручную, без DI-контейнера: граф небольшой,
 * виден целиком, а отсутствие рефлексии означает, что ошибка связывания
 * обнаруживается компилятором, а не в рантайме при первом запросе.
 */
fun Application.mangoModule(
    config: AppConfig,
    database: Database,
    time: TimeSource = SystemTime,
    mailSender: MailSender = LoggingMailSender(),
    rateLimiter: RateLimiter = InMemoryRateLimiter(time),
    hub: WebSocketHub = WebSocketHub(),
    eventBus: EventBus = LocalEventBus(hub),
    wsAuthTimeoutMillis: Long = DEFAULT_AUTH_TIMEOUT_MILLIS,
    revocations: SessionRevocations = InMemorySessionRevocations(time),
) {
    val ids = UuidV7Generator()
    val tokenService = TokenService(config.jwt, time)
    val passwordHasher = PasswordHasher(config.password)
    val authRepository = AuthRepository()

    // Журнал событий — основа синхронизации. Рассылка в реальном времени
    // подключается отдельно: её отсутствие не теряет события, клиент
    // получит их при сверке по курсору.
    val eventLog = EventLog(time)
    val syncService = SyncService(database, eventLog)

    val messagingRepository = MessagingRepository()
    val conversationService = ConversationService(
        database = database,
        repository = messagingRepository,
        eventLog = eventLog,
        eventBus = eventBus,
        ids = ids,
        time = time,
    )
    val messageService = MessageService(
        database = database,
        repository = messagingRepository,
        eventLog = eventLog,
        eventBus = eventBus,
        ids = ids,
        time = time,
    )

    val taskService = TaskService(
        database = database,
        repository = TasksRepository(),
        eventLog = eventLog,
        eventBus = eventBus,
        ids = ids,
        time = time,
    )

    // Вложения: файлы в каталоге за интерфейсом BlobStorage (ADR-0019).
    val attachmentService = AttachmentService(
        database = database,
        repository = AttachmentsRepository(),
        storage = FileSystemBlobStorage(Path.of(config.storage.directory)),
        signer = UrlSigner(config.storage.signingKey),
        time = time,
        maxFileBytes = config.storage.maxFileBytes,
    )

    val authService = AuthService(
        database = database,
        repository = authRepository,
        passwordHasher = passwordHasher,
        tokens = tokenService,
        ids = ids,
        time = time,
        mail = mailSender,
        revocations = revocations,
        accessTokenTtl = config.jwt.accessTokenTtl,
    )

    install(DefaultHeaders) {
        // Браузер не должен угадывать тип содержимого: это защита от
        // исполнения загруженного файла как скрипта.
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("Referrer-Policy", "no-referrer")
    }

    install(CallId) {
        // Идентификатор запроса: клиент может прислать свой, иначе
        // генерируется новый. Он возвращается в ошибке как traceId,
        // поэтому обращение пользователя сопоставимо с записью в журнале.
        header("X-Request-Id")
        generate { UUID.randomUUID().toString() }
        verify { it.isNotBlank() && it.length <= 128 }
        replyToHeader("X-Request-Id")
    }

    install(CallLogging) {
        level = Level.INFO
        callIdMdc("requestId")
        // Пути с токенами в запросе не логируются целиком: токен
        // восстановления приходит в теле, но ссылки могут нести его и в пути.
        filter { call -> !call.request.local.uri.startsWith("/v1/health") }
    }

    install(ContentNegotiation) {
        json(ApiJson)
    }

    install(WebSockets) {
        // Ping на уровне протокола: без него соединение молча умирает
        // за NAT и у мобильного оператора, а клиент продолжает считать,
        // что получает события.
        pingPeriod = HEARTBEAT_SECONDS.seconds
        timeout = (HEARTBEAT_SECONDS * 2).seconds
        maxFrameSize = 64L * 1024
        masking = false
    }

    install(Authentication) {
        jwt(JWT_AUTH) {
            realm = "mango"
            verifier(tokenService.verifier)
            validate { credential ->
                // Проверка подписи и срока уже выполнена verifier-ом.
                // Здесь проверяется наличие обязательных утверждений:
                // токен без идентификатора сессии бесполезен.
                val subject = credential.payload.subject
                val sessionId = credential.payload
                    .getClaim(TokenService.CLAIM_SESSION)
                    .asString()
                when {
                    subject.isNullOrBlank() || sessionId.isNullOrBlank() -> null

                    // Сессия могла быть закрыта раньше, чем истёк токен:
                    // при выходе, смене пароля или обнаружении кражи.
                    // Без этой проверки украденный access продолжал бы
                    // работать до конца своего срока.
                    revocations.isRevoked(UUID.fromString(sessionId)) -> null

                    else -> JWTPrincipal(credential.payload)
                }
            }
        }
    }

    configureErrorHandling(config)

    routing {
        healthRoutes(database)
        authRoutes(authService, database, authRepository, rateLimiter)
        syncRoutes(syncService)
        messagingRoutes(conversationService, messageService)
        realtimeRoutes(hub, tokenService, syncService, wsAuthTimeoutMillis)
        tasksRoutes(taskService)
        attachmentsRoutes(attachmentService)
    }
}
