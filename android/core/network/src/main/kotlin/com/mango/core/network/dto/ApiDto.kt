@file:UseSerializers(InstantSerializer::class)

package com.mango.core.network.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/**
 * Разбор меток времени из ISO-8601.
 *
 * Повреждённое значение не должно ронять разбор всего ответа: одно
 * битое поле сделало бы недоступной целую страницу сообщений. Вместо
 * исключения подставляется начало эпохи — это заметно в интерфейсе
 * и не блокирует работу.
 */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant =
        runCatching { Instant.parse(decoder.decodeString()) }.getOrDefault(Instant.EPOCH)
}

// ─────────────────────────── аутентификация ───────────────────────────

@Serializable
data class RegisterRequestDto(
    val email: String,
    val password: String,
    val displayName: String,
    val username: String,
)

@Serializable
data class LoginRequestDto(
    val email: String,
    val password: String,
    val device: DeviceInfoDto? = null,
)

@Serializable
data class DeviceInfoDto(
    val platform: String = "ANDROID",
    val model: String = "",
    val appVersion: String = "",
)

@Serializable
data class VerifyEmailRequestDto(val token: String)

@Serializable
data class RefreshRequestDto(val refreshToken: String)

@Serializable
data class ForgotPasswordRequestDto(val email: String)

@Serializable
data class ResetPasswordRequestDto(val token: String, val newPassword: String)

@Serializable
data class TokenPairDto(
    val accessToken: String,
    val accessExpiresAt: Instant,
    val refreshToken: String,
    val refreshExpiresAt: Instant,
)

@Serializable
data class CurrentUserDto(
    val id: String,
    val email: String,
    val emailVerified: Boolean,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val createdAt: Instant,
)

@Serializable
data class AcceptedDto(val message: String)

@Serializable
data class SessionDto(
    val id: String,
    val createdAt: Instant,
    val lastUsedAt: Instant? = null,
    val userAgent: String? = null,
    val isCurrent: Boolean,
)

// ─────────────────────────── переписка ───────────────────────────

@Serializable
data class CreateConversationRequestDto(
    val type: String,
    val memberIds: List<String> = emptyList(),
    val title: String? = null,
)

@Serializable
data class SendMessageRequestDto(
    val clientMsgId: String,
    val type: String = "TEXT",
    val body: String? = null,
    val replyToId: String? = null,
    val attachmentIds: List<String> = emptyList(),
)

@Serializable
data class UpdateConversationRequestDto(val title: String)

@Serializable
data class AddMembersRequestDto(val userIds: List<String>)

@Serializable
data class ChangeRoleRequestDto(val role: String)

@Serializable
data class AttachmentDto(
    val id: String,
    val mimeType: String,
    val sizeBytes: Long,
    val fileName: String = "",
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
)

@Serializable
data class SystemTargetDto(val userId: String, val name: String = "")

/** Структура системного сообщения; текст собирается на устройстве. */
@Serializable
data class SystemEventDto(
    val kind: String,
    val actorId: String,
    val actorName: String = "",
    val targets: List<SystemTargetDto> = emptyList(),
    val title: String? = null,
    val role: String? = null,
)

@Serializable
data class EditMessageRequestDto(val body: String)

@Serializable
data class MarkReadRequestDto(val seq: Long)

@Serializable
data class MessageReferenceDto(
    val messageId: String,
    val senderName: String,
    val textSnapshot: String? = null,
    val isUnavailable: Boolean = false,
)

@Serializable
data class MessageDto(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val senderName: String,
    val clientMsgId: String,
    val seq: Long,
    val type: String,
    val body: String? = null,
    val replyTo: MessageReferenceDto? = null,
    val editedAt: Instant? = null,
    val deletedAt: Instant? = null,
    val pinnedAt: Instant? = null,
    val createdAt: Instant,
    val attachments: List<AttachmentDto> = emptyList(),
    val system: SystemEventDto? = null,
)

@Serializable
data class ConversationDto(
    val id: String,
    val type: String,
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
    val userId: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val role: String,
    val joinedAt: Instant,
    val lastReadSeq: Long,
)

@Serializable
data class ConversationsResponseDto(
    val items: List<ConversationDto>,
    val lastEventId: Long,
)

@Serializable
data class MessagesResponseDto(val items: List<MessageDto>)

@Serializable
data class MembersResponseDto(val items: List<MemberDto>)

@Serializable
data class UserSummaryDto(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
)

@Serializable
data class UserSearchResponseDto(val items: List<UserSummaryDto>)

// ─────────────────────────── синхронизация ───────────────────────────

@Serializable
data class SyncEventDto(
    val id: Long,
    val scope: String,
    val scopeId: String? = null,
    val type: String,
    val payload: JsonElement,
    val createdAt: Instant,
)

@Serializable
data class SyncResponseDto(
    val events: List<SyncEventDto>,
    val lastEventId: Long,
    val hasMore: Boolean,
)

@Serializable
data class ResetRequiredDto(
    val code: String,
    val message: String,
    val resetScopes: List<String> = emptyList(),
)

// ─────────────────────────── ошибки ───────────────────────────

@Serializable
data class ErrorResponseDto(
    val code: String,
    val message: String,
    val traceId: String? = null,
    val fieldErrors: Map<String, String>? = null,
)

// ─────────────────── полезная нагрузка событий ───────────────────

@Serializable
data class MessageCreatedPayloadDto(val message: MessageDto)

@Serializable
data class MessageEditedPayloadDto(val message: MessageDto)

@Serializable
data class MessageDeletedPayloadDto(
    val conversationId: String,
    val messageId: String,
    val seq: Long,
    val deletedAt: Instant,
)

@Serializable
data class ConversationCreatedPayloadDto(val conversation: ConversationDto)

@Serializable
data class MembersAddedPayloadDto(val conversationId: String, val members: List<MemberDto>)

@Serializable
data class MemberRemovedPayloadDto(val conversationId: String, val userId: String)

@Serializable
data class MemberUpdatedPayloadDto(val conversationId: String, val member: MemberDto)

// ─────────────────────────── вложения ───────────────────────────

@Serializable
data class UploadTicketRequestDto(
    val attachmentId: String,
    val conversationId: String,
    val mimeType: String,
    val sizeBytes: Long,
    val checksumSha256: String,
    val fileName: String = "",
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
)

@Serializable
data class UploadTicketDto(
    val attachmentId: String,
    /** Относительная (от адреса API) или абсолютная ссылка для PUT. */
    val uploadUrl: String,
    val expiresAt: Instant,
    val uploaded: Boolean = false,
)

@Serializable
data class DownloadUrlDto(
    val url: String,
    val expiresAt: Instant,
    val mimeType: String = "",
    val sizeBytes: Long = 0,
    val fileName: String = "",
)

@Serializable
data class ReadMarkerPayloadDto(
    val conversationId: String,
    val userId: String,
    val seq: Long,
)
