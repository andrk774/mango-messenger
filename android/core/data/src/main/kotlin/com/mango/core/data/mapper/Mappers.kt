package com.mango.core.data.mapper

import com.mango.core.database.dao.ConversationWithPreview
import com.mango.core.database.entity.MessageEntity
import com.mango.core.database.entity.TaskEntity
import com.mango.core.database.entity.UserEntity
import com.mango.core.model.Attachment
import com.mango.core.model.ClientOpId
import com.mango.core.model.Conversation
import com.mango.core.model.ConversationId
import com.mango.core.model.Message
import com.mango.core.model.MessageId
import com.mango.core.model.MessagePreview
import com.mango.core.model.MessageReference
import com.mango.core.model.MessageType
import com.mango.core.model.Reaction
import com.mango.core.model.Task
import com.mango.core.model.TaskId
import com.mango.core.model.TaskSource
import com.mango.core.model.User
import com.mango.core.model.UserId
import java.time.Instant

/**
 * Преобразование Room-сущностей в domain-модели.
 *
 * Слой данных — единственное место, где знают про entity. Presentation
 * получает только domain-модели: иначе изменение схемы БД тянет за собой
 * правку экранов.
 */

fun UserEntity.toDomain(): User = User(
    id = UserId(id),
    username = username,
    displayName = displayName,
    avatarUrl = avatarUrl,
    bio = bio,
    presence = presence,
    lastSeenAt = lastSeenAt?.let(Instant::ofEpochMilli),
)

fun MessageEntity.toDomain(
    reactions: List<Reaction> = emptyList(),
    attachments: List<Attachment> = emptyList(),
): Message = Message(
    id = MessageId(id),
    conversationId = ConversationId(conversationId),
    senderId = UserId(senderId),
    clientMsgId = ClientOpId(clientMsgId),
    seq = seq,
    type = type,
    // У системного сообщения в body лежит структура события, а не текст.
    text = body.takeUnless { type == MessageType.SYSTEM },
    system = if (type == MessageType.SYSTEM) SystemEventCodec.decode(body) else null,
    replyTo = replyToId?.let {
        MessageReference(
            messageId = MessageId(it),
            senderName = replyToSenderName.orEmpty(),
            textSnapshot = replyToSnapshot,
            type = type,
            // Снапшот отсутствует только если исходное сообщение так и не было
            // загружено — для пользователя это равносильно «недоступно».
            isUnavailable = replyToSnapshot == null,
        )
    },
    forwardedFrom = forwardedFromId?.let {
        MessageReference(
            messageId = MessageId(it),
            senderName = forwardedFromSenderName.orEmpty(),
            textSnapshot = null,
            type = type,
        )
    },
    attachments = attachments,
    reactions = reactions,
    deliveryStatus = deliveryStatus,
    syncStatus = syncStatus,
    isPinned = pinnedAt != null,
    editedAt = editedAt?.let(Instant::ofEpochMilli),
    deletedAt = deletedAt?.let(Instant::ofEpochMilli),
    createdAt = Instant.ofEpochMilli(createdAt),
)

fun ConversationWithPreview.toDomain(): Conversation = Conversation(
    id = ConversationId(conversation.id),
    type = conversation.type,
    title = conversation.title,
    avatarUrl = conversation.avatarUrl,
    lastMessage = lastMessage?.let { message ->
        MessagePreview(
            id = MessageId(message.id),
            senderId = UserId(message.senderId),
            senderName = lastMessageSender?.displayName.orEmpty(),
            type = message.type,
            text = message.body.takeUnless { message.type == MessageType.SYSTEM },
            system = if (message.type == MessageType.SYSTEM) SystemEventCodec.decode(message.body) else null,
            createdAt = Instant.ofEpochMilli(message.createdAt),
            deliveryStatus = message.deliveryStatus,
            isDeleted = message.deletedAt != null,
        )
    },
    unreadCount = unreadCount,
    mutedUntil = conversation.mutedUntil?.let(Instant::ofEpochMilli),
    pinnedMessageCount = conversation.pinnedMessageCount,
    memberCount = conversation.memberCount,
    lastReadSeq = conversation.lastReadSeq,
    lastEventSeq = conversation.lastEventSeq,
    draft = conversation.draft,
    updatedAt = Instant.ofEpochMilli(conversation.updatedAt),
    myRole = conversation.myRole,
)

fun TaskEntity.toDomain(
    assignees: List<User> = emptyList(),
    subtaskCount: Int = 0,
    completedSubtaskCount: Int = 0,
    commentCount: Int = 0,
    attachments: List<Attachment> = emptyList(),
): Task = Task(
    id = TaskId(id),
    title = title,
    description = description,
    status = status,
    priority = priority,
    createdBy = UserId(createdBy),
    assignees = assignees,
    conversationId = conversationId?.let(::ConversationId),
    conversationTitle = conversationTitle,
    source = sourceMessageId?.let {
        TaskSource(
            messageId = MessageId(it),
            conversationId = ConversationId(conversationId.orEmpty()),
            senderName = sourceSenderName.orEmpty(),
            textSnapshot = sourceMessageSnapshot,
            createdAt = Instant.ofEpochMilli(sourceCreatedAt ?: createdAt),
            isSourceUnavailable = sourceUnavailable,
        )
    },
    parentTaskId = parentTaskId?.let(::TaskId),
    subtaskCount = subtaskCount,
    completedSubtaskCount = completedSubtaskCount,
    commentCount = commentCount,
    attachments = attachments,
    dueAt = dueAt?.let(Instant::ofEpochMilli),
    remindAt = remindAt?.let(Instant::ofEpochMilli),
    version = version,
    syncStatus = syncStatus,
    completedAt = completedAt?.let(Instant::ofEpochMilli),
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
)

/** Группировка плоского списка реакций в агрегированный вид для UI. */
fun List<com.mango.core.database.entity.MessageReactionEntity>.toDomainReactions(
    currentUserId: String,
): List<Reaction> = groupBy { it.emoji }
    .map { (emoji, rows) ->
        Reaction(
            emoji = emoji,
            count = rows.size,
            reactedByMe = rows.any { it.userId == currentUserId },
            userIds = rows.map { UserId(it.userId) },
        )
    }
    .sortedByDescending { it.count }
