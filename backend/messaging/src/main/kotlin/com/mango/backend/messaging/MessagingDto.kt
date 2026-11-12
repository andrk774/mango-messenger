@file:UseSerializers(UuidSerializer::class, InstantSerializer::class)

package com.mango.backend.messaging

import com.mango.backend.common.InstantSerializer
import com.mango.backend.common.UuidSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.util.UUID

// ──────────────────────────── запросы ────────────────────────────

@Serializable
data class CreateConversationRequest(
    /** DIRECT или GROUP. */
    val type: String,
    val memberIds: List<UUID> = emptyList(),
    val title: String? = null,
)

@Serializable
data class SendMessageRequest(
    /**
     * Идентификатор, присвоенный клиентом ДО отправки (UUIDv7).
     * Одновременно ключ дедупликации на уровне схемы.
     */
    val clientMsgId: String,
    val type: String = "TEXT",
    val body: String? = null,
    val replyToId: UUID? = null,
)

@Serializable
data class EditMessageRequest(val body: String)

@Serializable
data class MarkReadRequest(val seq: Long)

// ──────────────────────────── ответы ────────────────────────────

@Serializable
data class MessageReferenceDto(
    val messageId: UUID,
    val senderName: String,
    /**
     * Снимок текста на момент ответа. Сохраняется, чтобы ответ оставался
     * читаемым после удаления исходного сообщения.
     */
    val textSnapshot: String? = null,
    val isUnavailable: Boolean = false,
)

@Serializable
data class MessageDto(
    val id: UUID,
    val conversationId: UUID,
    val senderId: UUID,
    val senderName: String,
    val clientMsgId: String,
    /** Порядковый номер внутри чата — источник истины для порядка. */
    val seq: Long,
    val type: String,
    val body: String? = null,
    val replyTo: MessageReferenceDto? = null,
    val editedAt: Instant? = null,
    val deletedAt: Instant? = null,
    val pinnedAt: Instant? = null,
    val createdAt: Instant,
)

@Serializable
data class ConversationDto(
    val id: UUID,
    val type: String,
    /** Для DIRECT подставляется имя собеседника. */
    val title: String,
    val avatarUrl: String? = null,
    val memberCount: Int,
    val myRole: String,
    val lastEventSeq: Long,
    val lastReadSeq: Long,
    val unreadCount: Int,
    val mutedUntil: Instant? = null,
    val lastMessage: MessageDto? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
data class MemberDto(
    val userId: UUID,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val role: String,
    val joinedAt: Instant,
    val lastReadSeq: Long,
)

@Serializable
data class ConversationsResponse(
    val items: List<ConversationDto>,
    /**
     * Положение журнала событий на момент выдачи.
     *
     * Клиент запоминает его как курсор: всё, что произошло позже,
     * он получит через `/v1/sync`, а что раньше — уже отражено в этом
     * ответе. Без этого поля между первичной загрузкой и подпиской
     * оставалось бы окно, в котором события терялись.
     */
    val lastEventId: Long,
)

@Serializable
data class MessagesResponse(val items: List<MessageDto>)

@Serializable
data class MembersResponse(val items: List<MemberDto>)

@Serializable
data class UserSummaryDto(
    val id: UUID,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
)

@Serializable
data class UserSearchResponse(val items: List<UserSummaryDto>)

// ─────────────────── полезная нагрузка событий ───────────────────

@Serializable
data class MessageCreatedPayload(val message: MessageDto)

@Serializable
data class MessageEditedPayload(val message: MessageDto)

@Serializable
data class MessageDeletedPayload(
    val conversationId: UUID,
    val messageId: UUID,
    val seq: Long,
    val deletedAt: Instant,
)

@Serializable
data class ConversationCreatedPayload(val conversation: ConversationDto)

@Serializable
data class ReadMarkerPayload(
    val conversationId: UUID,
    val userId: UUID,
    val seq: Long,
)
