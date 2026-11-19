package com.mango.backend.storage

import com.mango.backend.common.AppException
import com.mango.backend.common.Database
import com.mango.backend.common.TimeSource
import com.mango.backend.common.validate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Результат выдачи разрешения: создано ли оно сейчас. */
data class TicketResult(val response: UploadTicketResponse, val created: Boolean)

/** Файл, готовый к отдаче. Поток закрывает вызывающий. */
class DownloadableFile(
    val record: AttachmentRecord,
    val inline: Boolean,
    val content: InputStream,
)

/**
 * Вложения: от разрешения на загрузку до выдачи файла.
 *
 * ## Жизненный цикл
 *
 * 1. Клиент запрашивает разрешение, заявляя тип, размер и SHA-256.
 *    Сервер проверяет тип по белому списку, размер по пределам и
 *    участие в чате. Создаётся запись `PENDING`.
 * 2. Клиент отправляет содержимое по подписанной ссылке. Сервер
 *    принимает не больше заявленного, сверяет размер, хеш и сигнатуру
 *    содержимого с заявленным типом. Совпало — `CLEAN`, нет — файл
 *    удаляется.
 * 3. Клиент отправляет сообщение со ссылкой на вложение; привязка
 *    происходит в транзакции отправки (модуль `messaging`).
 * 4. Получатель запрашивает временную ссылку на скачивание; права
 *    проверяются в этот момент.
 */
class AttachmentService(
    private val database: Database,
    private val repository: AttachmentsRepository,
    private val storage: BlobStorage,
    private val signer: UrlSigner,
    private val time: TimeSource,
    private val maxFileBytes: Long,
) {
    private val log = LoggerFactory.getLogger(AttachmentService::class.java)

    suspend fun createTicket(userId: UUID, request: UploadTicketRequest): TicketResult {
        val mimeType = AttachmentPolicy.normalize(request.mimeType)
        val rule = AttachmentPolicy.ruleFor(mimeType)
            ?: throw AppException.UnsupportedMediaType("Тип файла $mimeType не поддерживается")
        val limit = minOf(rule.maxBytes ?: maxFileBytes, maxFileBytes)
        if (request.sizeBytes > limit) {
            throw AppException.PayloadTooLarge("Файл больше допустимых ${limit / (1024 * 1024)} МБ")
        }
        val checksum = request.checksumSha256.lowercase()
        val fileName = sanitizeFileName(request.fileName)

        validate {
            check(request.sizeBytes > 0, "sizeBytes", "Пустой файл")
            check(SHA256_PATTERN.matches(checksum), "checksumSha256", "Ожидается SHA-256 в шестнадцатеричном виде")
            request.width?.let { check(it in 1..MAX_DIMENSION, "width", "Недопустимая ширина") }
            request.height?.let { check(it in 1..MAX_DIMENSION, "height", "Недопустимая высота") }
            request.durationMs?.let { check(it >= 0, "durationMs", "Недопустимая длительность") }
        }

        val now = time.now()

        return database.transaction { connection ->
            if (!repository.isMember(connection, request.conversationId, userId)) {
                throw AppException.Forbidden("Чат недоступен")
            }

            val existing = repository.find(connection, request.attachmentId)
            if (existing != null) {
                return@transaction TicketResult(repeatTicket(existing, userId, request, mimeType, checksum, now), created = false)
            }

            // Предел висящих разрешений: без него можно бесплатно занять
            // место в хранилище, запрашивая разрешения и не отправляя сообщений.
            val pending = repository.pendingCount(connection, userId, now.minus(PENDING_WINDOW))
            if (pending >= MAX_PENDING) {
                throw AppException.RateLimited(retryAfterSeconds = PENDING_WINDOW.seconds)
            }

            val inserted = repository.insertPending(
                connection = connection,
                id = request.attachmentId,
                ownerId = userId,
                conversationId = request.conversationId,
                storageKey = storageKeyOf(request.attachmentId),
                mimeType = mimeType,
                sizeBytes = request.sizeBytes,
                fileName = fileName,
                width = request.width,
                height = request.height,
                durationMs = request.durationMs,
                checksumSha256 = checksum,
                now = now,
            )
            if (!inserted) {
                // Одновременный запрос с тем же идентификатором успел первым.
                val raced = repository.find(connection, request.attachmentId)
                    ?: throw AppException.Internal("Вложение не создано и не найдено")
                return@transaction TicketResult(repeatTicket(raced, userId, request, mimeType, checksum, now), created = false)
            }
            TicketResult(uploadTicket(request.attachmentId, uploaded = false, now), created = true)
        }
    }

    /**
     * Приём содержимого по подписанной ссылке.
     *
     * Аутентификация — подписью ссылки, а не токеном: при переходе на
     * внешнее хранилище клиент будет отправлять файл туда, и токен
     * нашего API там бы не помог. Поведение клиента одинаково в обоих случаях.
     */
    suspend fun upload(
        attachmentId: UUID,
        expires: Long,
        signature: String,
        declaredLength: Long?,
        content: InputStream,
    ) {
        val now = time.now()
        if (!signer.verify(UrlSigner.UPLOAD, attachmentId, expires, signature, now)) {
            throw AppException.Forbidden("Ссылка недействительна или истекла")
        }

        val record = database.readOnly { repository.find(it, attachmentId) }
            ?: throw AppException.Forbidden("Ссылка недействительна или истекла")
        when {
            record.deletedAt != null -> throw AppException.Forbidden("Вложение удалено")
            // Повтор после потерянного ответа: файл уже принят.
            record.scanStatus == "CLEAN" -> return
            record.scanStatus == "REJECTED" ->
                throw AppException.Conflict("Файл отклонён проверкой; запросите новое разрешение")
        }
        if (declaredLength != null && declaredLength > record.sizeBytes) {
            throw AppException.PayloadTooLarge("Размер больше заявленного")
        }

        val stored = try {
            withContext(Dispatchers.IO) { storage.write(record.storageKey, content, record.sizeBytes) }
        } catch (_: BlobTooLargeException) {
            throw AppException.PayloadTooLarge("Размер больше заявленного")
        }

        // Неполный файл или повреждение в пути — ошибка передачи, а не
        // подлог: разрешение остаётся действительным, клиент повторит.
        if (stored.sizeBytes != record.sizeBytes) {
            discard(record)
            throw AppException.BadRequest("Получено ${stored.sizeBytes} байт из ${record.sizeBytes}")
        }
        if (stored.sha256Hex != record.checksumSha256) {
            discard(record)
            throw AppException.BadRequest("Контрольная сумма не совпадает")
        }

        // Содержимое не совпало с заявленным типом — это уже не сбой
        // сети, и повтор с тем же разрешением не поможет.
        val rule = AttachmentPolicy.ruleFor(record.mimeType)
        if (rule == null || !rule.matches(stored.head)) {
            discard(record)
            database.transaction { repository.markRejected(it, attachmentId) }
            log.info("вложение {} отклонено: содержимое не соответствует типу {}", attachmentId, record.mimeType)
            throw AppException.UnsupportedMediaType("Содержимое файла не соответствует типу ${record.mimeType}")
        }

        database.transaction { repository.markClean(it, attachmentId, now) }
    }

    /** Временная ссылка на скачивание. Права проверяются здесь, а не при скачивании. */
    suspend fun downloadUrl(userId: UUID, attachmentId: UUID): DownloadUrlResponse {
        val now = time.now()
        val record = database.readOnly { connection ->
            if (!repository.canRead(connection, attachmentId, userId)) {
                throw AppException.Forbidden("Вложение недоступно")
            }
            repository.find(connection, attachmentId)
        } ?: throw AppException.Forbidden("Вложение недоступно")

        if (record.scanStatus != "CLEAN") {
            throw AppException.Conflict("Файл ещё не загружен или не прошёл проверку")
        }

        val expiresAt = now.plus(DOWNLOAD_TTL)
        val signature = signer.sign(UrlSigner.DOWNLOAD, attachmentId, expiresAt)
        return DownloadUrlResponse(
            url = "/v1/attachments/$attachmentId/content?expires=${expiresAt.epochSecond}&signature=$signature",
            expiresAt = expiresAt,
            mimeType = record.mimeType,
            sizeBytes = record.sizeBytes,
            fileName = record.fileName,
        )
    }

    /** Only readable, clean audio may be sent to the private transcription worker. */
    suspend fun audioForTranscription(userId:UUID,attachmentId:UUID): java.io.InputStream {
        val access=downloadUrl(userId,attachmentId)
        if(!access.mimeType.startsWith("audio/")) throw AppException.Validation(mapOf("audio" to "Нужно голосовое сообщение"))
        if(access.sizeBytes>20*1024*1024) throw AppException.Validation(mapOf("audio" to "Для расшифровки файл должен быть не больше 20 МБ"))
        val record=database.readOnly { repository.find(it,attachmentId) } ?: throw AppException.Forbidden("Вложение недоступно")
        return withContext(Dispatchers.IO) { storage.open(record.storageKey) } ?: throw AppException.Unavailable("Файл отсутствует")
    }

    suspend fun open(attachmentId: UUID, expires: Long, signature: String): DownloadableFile {
        if (!signer.verify(UrlSigner.DOWNLOAD, attachmentId, expires, signature, time.now())) {
            throw AppException.Forbidden("Ссылка недействительна или истекла")
        }
        val record = database.readOnly { repository.find(it, attachmentId) }
        if (record == null || record.deletedAt != null || record.scanStatus != "CLEAN") {
            throw AppException.Forbidden("Вложение недоступно")
        }
        val stream = withContext(Dispatchers.IO) { storage.open(record.storageKey) }
            ?: throw AppException.Unavailable("Файл отсутствует в хранилище")
        return DownloadableFile(
            record = record,
            inline = AttachmentPolicy.ruleFor(record.mimeType)?.inline ?: false,
            content = stream,
        )
    }

    // ─────────────────────────── внутреннее ───────────────────────────

    private fun repeatTicket(
        existing: AttachmentRecord,
        userId: UUID,
        request: UploadTicketRequest,
        mimeType: String,
        checksum: String,
        now: Instant,
    ): UploadTicketResponse {
        // Чужой идентификатор: ответ не должен подтверждать, что
        // такое вложение существует.
        if (existing.ownerId != userId) throw AppException.Forbidden("Вложение недоступно")
        val same = existing.conversationId == request.conversationId &&
            existing.mimeType == mimeType &&
            existing.sizeBytes == request.sizeBytes &&
            existing.checksumSha256 == checksum
        if (!same) {
            throw AppException.Conflict("Идентификатор уже использован для другого файла")
        }
        if (existing.scanStatus == "REJECTED") {
            throw AppException.Conflict("Файл отклонён проверкой; запросите новое разрешение")
        }
        return uploadTicket(existing.id, uploaded = existing.scanStatus == "CLEAN", now)
    }

    private fun uploadTicket(attachmentId: UUID, uploaded: Boolean, now: Instant): UploadTicketResponse {
        val expiresAt = now.plus(UPLOAD_TTL)
        val signature = signer.sign(UrlSigner.UPLOAD, attachmentId, expiresAt)
        return UploadTicketResponse(
            attachmentId = attachmentId,
            uploadUrl = "/v1/attachments/$attachmentId/content?expires=${expiresAt.epochSecond}&signature=$signature",
            expiresAt = expiresAt,
            uploaded = uploaded,
        )
    }

    private suspend fun discard(record: AttachmentRecord) {
        withContext(Dispatchers.IO) { runCatching { storage.delete(record.storageKey) } }
    }

    companion object {
        val UPLOAD_TTL: Duration = Duration.ofMinutes(15)
        val DOWNLOAD_TTL: Duration = Duration.ofMinutes(5)
        val PENDING_WINDOW: Duration = Duration.ofHours(24)
        const val MAX_PENDING = 50
        const val MAX_DIMENSION = 20_000
        private const val MAX_FILE_NAME = 255
        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

        fun storageKeyOf(id: UUID): String = "att/$id"

        /**
         * Имя файла — только для показа и для заголовка скачивания.
         *
         * Убираются пути, управляющие символы и кавычки: имя попадает
         * в `Content-Disposition`, и перевод строки в нём позволил бы
         * дописать в ответ произвольный заголовок.
         */
        fun sanitizeFileName(raw: String): String {
            val base = raw.substringAfterLast('/').substringAfterLast('\\')
            val cleaned = base.filterNot { it.isISOControl() || it == '"' }.trim()
            return cleaned.take(MAX_FILE_NAME).ifEmpty { "file" }
        }
    }
}
