package com.mango.core.data.mapper

import com.mango.core.database.entity.ConversationEntity
import com.mango.core.database.entity.MessageEntity
import com.mango.core.database.entity.UserEntity
import com.mango.core.model.ConversationType
import com.mango.core.model.MemberRole
import com.mango.core.model.MessageDeliveryStatus
import com.mango.core.model.MessageType
import com.mango.core.model.PresenceStatus
import com.mango.core.model.SyncStatus
import com.mango.core.network.dto.ConversationDto
import com.mango.core.network.dto.MemberDto
import com.mango.core.network.dto.MessageDto
import com.mango.core.network.dto.UserSummaryDto

/**
 * Преобразование серверных DTO в записи локальной базы.
 *
 * Живёт в одном месте намеренно: серверные данные попадают в Room двумя
 * путями — применением событий и прямой загрузкой истории. Если бы каждый
 * путь преобразовывал их по-своему, содержимое базы зависело бы от того,
 * каким способом данные пришли.
 *
 * Неизвестное значение перечисления не считается ошибкой: сервер может
 * ввести новый тип сообщения раньше, чем обновится приложение, и это
 * не повод терять сообщение целиком.
 */
fun MessageDto.toEntity(): MessageEntity = MessageEntity(
    id = id,
    conversationId = conversationId,
    senderId = senderId,
    clientMsgId = clientMsgId,
    seq = seq,
    type = runCatching { MessageType.valueOf(type) }.getOrDefault(MessageType.TEXT),
    body = body,
    replyToId = replyTo?.messageId,
    replyToSenderName = replyTo?.senderName,
    replyToSnapshot = replyTo?.textSnapshot,
    forwardedFromId = null,
    forwardedFromSenderName = null,
    deliveryStatus = MessageDeliveryStatus.SENT,
    syncStatus = SyncStatus.SYNCED,
    pinnedAt = pinnedAt?.toEpochMilli(),
    editedAt = editedAt?.toEpochMilli(),
    deletedAt = deletedAt?.toEpochMilli(),
    createdAt = createdAt.toEpochMilli(),
)

fun ConversationDto.toEntity(): ConversationEntity = ConversationEntity(
    id = id,
    type = runCatching { ConversationType.valueOf(type) }.getOrDefault(ConversationType.DIRECT),
    title = title,
    avatarUrl = avatarUrl,
    memberCount = memberCount,
    pinnedMessageCount = 0,
    lastEventSeq = lastEventSeq,
    lastReadSeq = lastReadSeq,
    mutedUntil = mutedUntil?.toEpochMilli(),
    // Черновик живёт только на устройстве, сервер его не хранит,
    // поэтому серверный ответ не должен его затирать.
    draft = null,
    myRole = runCatching { MemberRole.valueOf(myRole) }.getOrDefault(MemberRole.MEMBER),
    isArchived = false,
    leftAt = null,
    updatedAt = updatedAt.toEpochMilli(),
)

/**
 * Сведения об авторе из сообщения.
 *
 * Сообщение приносит имя отправителя, и его нужно сохранить: иначе
 * в переписке будут видны идентификаторы вместо имён, пока не загрузится
 * список участников.
 */
fun senderEntity(id: String, displayName: String, avatarUrl: String?): UserEntity = UserEntity(
    id = id,
    // Настоящее имя пользователя придёт со списком участников;
    // до тех пор подставляется отображаемое.
    username = displayName,
    displayName = displayName,
    avatarUrl = avatarUrl,
    bio = null,
    presence = PresenceStatus.OFFLINE,
    lastSeenAt = null,
    isMe = false,
    updatedAt = System.currentTimeMillis(),
)

fun MemberDto.toEntity(): UserEntity = UserEntity(
    id = userId,
    username = username,
    displayName = displayName,
    avatarUrl = avatarUrl,
    bio = null,
    presence = PresenceStatus.OFFLINE,
    lastSeenAt = null,
    isMe = false,
    updatedAt = System.currentTimeMillis(),
)

fun UserSummaryDto.toEntity(): UserEntity = UserEntity(
    id = id,
    username = username,
    displayName = displayName,
    avatarUrl = avatarUrl,
    bio = null,
    presence = PresenceStatus.OFFLINE,
    lastSeenAt = null,
    isMe = false,
    updatedAt = System.currentTimeMillis(),
)
