package com.mango.core.data.mapper

import com.mango.core.database.entity.AttachmentEntity
import com.mango.core.model.Attachment
import com.mango.core.model.AttachmentId
import com.mango.core.model.MemberRole
import com.mango.core.model.SyncStatus
import com.mango.core.model.SystemEvent
import com.mango.core.model.SystemEventKind
import com.mango.core.model.SystemTarget
import com.mango.core.model.UserId
import com.mango.core.database.MangoDatabase
import com.mango.core.network.dto.AttachmentDto
import com.mango.core.network.dto.MessageDto
import com.mango.core.network.dto.SystemEventDto
import kotlinx.serialization.json.Json

/**
 * Хранение структуры системного сообщения.
 *
 * Локально она лежит в `body` сообщения как JSON: отдельная таблица
 * ради шести видов событий потребовала бы миграции схемы, а читается
 * структура только при показе.
 */
object SystemEventCodec {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun encode(dto: SystemEventDto): String = json.encodeToString(SystemEventDto.serializer(), dto)

    /** Нечитаемое содержимое — не ошибка показа: событие будет показано как служебное. */
    fun decode(body: String?): SystemEvent? {
        val dto = body?.let { runCatching { json.decodeFromString(SystemEventDto.serializer(), it) }.getOrNull() }
            ?: return null
        return SystemEvent(
            kind = runCatching { SystemEventKind.valueOf(dto.kind) }.getOrDefault(SystemEventKind.UNKNOWN),
            actorId = UserId(dto.actorId),
            actorName = dto.actorName,
            targets = dto.targets.map { SystemTarget(UserId(it.userId), it.name) },
            title = dto.title,
            role = dto.role?.let { runCatching { MemberRole.valueOf(it) }.getOrNull() },
        )
    }
}

fun AttachmentEntity.toDomain(): Attachment = Attachment(
    id = AttachmentId(id),
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    fileName = fileName,
    remoteUrl = remoteUrl,
    localUri = localUri,
    width = width,
    height = height,
    durationMs = durationMs,
    uploadProgress = uploadProgress,
    syncStatus = syncStatus,
)

/**
 * Серверное вложение → локальная запись.
 *
 * Путь к локальной копии сохраняется: у собственного вложения файл уже
 * лежит на устройстве, и скачивать его заново незачем.
 */
fun AttachmentDto.toEntity(messageId: String, existing: AttachmentEntity?, nowMillis: Long): AttachmentEntity =
    AttachmentEntity(
        id = id,
        messageId = messageId,
        taskId = null,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        fileName = fileName,
        remoteUrl = null,
        localUri = existing?.localUri,
        width = width,
        height = height,
        durationMs = durationMs,
        uploadProgress = null,
        syncStatus = SyncStatus.SYNCED,
        createdAt = existing?.createdAt ?: nowMillis,
    )

/**
 * Запись вложений серверного сообщения.
 *
 * Общая для событий журнала, ответа на отправку и догрузки истории —
 * чтобы все три пути одинаково сохраняли локальную копию файла.
 * Вызывается внутри транзакции записи сообщения.
 */
suspend fun MangoDatabase.storeServerAttachments(message: MessageDto, nowMillis: Long) {
    val dao = attachmentDao()
    // Собственное сообщение до подтверждения жило под локальным id
    // (= clientMsgId); вложения переезжают вместе с ним.
    if (message.clientMsgId != message.id) dao.moveToMessage(message.clientMsgId, message.id)
    message.attachments.forEach { dto ->
        dao.upsert(dto.toEntity(message.id, dao.findById(dto.id), nowMillis))
    }
}
