package com.mango.core.model

import java.time.Instant

data class Message(
    val id: MessageId,
    val conversationId: ConversationId,
    val senderId: UserId,
    /**
     * Клиентский идентификатор, присвоенный при создании.
     * Нужен для дедупликации: сервер уникализирует по (conversation, sender, clientMsgId).
     */
    val clientMsgId: ClientOpId,
    /**
     * Серверный порядковый номер внутри чата. `null` — сообщение ещё не подтверждено
     * сервером, поэтому сортируется в хвост списка по [createdAt].
     */
    val seq: Long?,
    val type: MessageType,
    val text: String?,
    val replyTo: MessageReference? = null,
    val forwardedFrom: MessageReference? = null,
    val attachments: List<Attachment> = emptyList(),
    val reactions: List<Reaction> = emptyList(),
    val deliveryStatus: MessageDeliveryStatus,
    val syncStatus: SyncStatus,
    val isPinned: Boolean = false,
    val editedAt: Instant? = null,
    val deletedAt: Instant? = null,
    val createdAt: Instant,
) {
    val isDeleted: Boolean get() = deletedAt != null
    val isEdited: Boolean get() = editedAt != null
    val isConfirmed: Boolean get() = seq != null

    /** Можно ли повторить отправку — показывает кнопку «отправить снова». */
    val canRetry: Boolean get() = deliveryStatus == MessageDeliveryStatus.FAILED
}

/**
 * Ссылка на другое сообщение (ответ или пересылка).
 *
 * Содержит снапшот текста и автора: исходное сообщение может быть удалено,
 * и тогда ответ всё равно должен остаться читаемым.
 */
data class MessageReference(
    val messageId: MessageId,
    val senderName: String,
    val textSnapshot: String?,
    val type: MessageType,
    val isUnavailable: Boolean = false,
)

data class Reaction(
    val emoji: String,
    val count: Int,
    val reactedByMe: Boolean,
    val userIds: List<UserId> = emptyList(),
)

data class Attachment(
    val id: AttachmentId,
    val mimeType: String,
    val sizeBytes: Long,
    val fileName: String,
    val remoteUrl: String? = null,
    val localUri: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val uploadProgress: Float? = null,
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isAudio: Boolean get() = mimeType.startsWith("audio/")
    val isUploading: Boolean get() = uploadProgress != null && uploadProgress < 1f
}

/**
 * Разрыв в локальной истории сообщений.
 *
 * Клиент хранит разрывы явно, чтобы при скролле догружать именно пропущенный
 * диапазон, а не перезаливать весь чат. См. docs/architecture/sync-model.md.
 */
data class MessageGap(
    val conversationId: ConversationId,
    val fromSeq: Long,
    val toSeq: Long,
)
