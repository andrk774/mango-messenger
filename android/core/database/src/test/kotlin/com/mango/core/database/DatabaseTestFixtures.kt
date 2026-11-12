package com.mango.core.database

import com.mango.core.database.entity.ConversationEntity
import com.mango.core.database.entity.ConversationMemberEntity
import com.mango.core.database.entity.MessageEntity
import com.mango.core.database.entity.OutboxOperationEntity
import com.mango.core.database.entity.OutboxStatus
import com.mango.core.database.entity.TaskEntity
import com.mango.core.database.entity.UserEntity
import com.mango.core.model.ConversationType
import com.mango.core.model.MemberRole
import com.mango.core.model.MessageDeliveryStatus
import com.mango.core.model.MessageType
import com.mango.core.model.PresenceStatus
import com.mango.core.model.SyncStatus
import com.mango.core.model.TaskPriority
import com.mango.core.model.TaskStatus

/** Фикстуры для тестов БД: минимум параметров в вызове, остальное — разумные значения. */
object Fixtures {

    const val ME = "user-me"
    const val OTHER = "user-other"
    const val CONVERSATION = "conv-1"

    fun user(
        id: String = OTHER,
        displayName: String = "Пользователь",
        username: String = id,
        isMe: Boolean = false,
    ) = UserEntity(
        id = id,
        username = username,
        displayName = displayName,
        avatarUrl = null,
        bio = null,
        presence = PresenceStatus.OFFLINE,
        lastSeenAt = null,
        isMe = isMe,
        updatedAt = 1_000,
    )

    fun conversation(
        id: String = CONVERSATION,
        type: ConversationType = ConversationType.DIRECT,
        title: String = "Чат",
        lastReadSeq: Long = 0,
        lastEventSeq: Long = 0,
        updatedAt: Long = 1_000,
        mutedUntil: Long? = null,
    ) = ConversationEntity(
        id = id,
        type = type,
        title = title,
        avatarUrl = null,
        memberCount = 2,
        pinnedMessageCount = 0,
        lastEventSeq = lastEventSeq,
        lastReadSeq = lastReadSeq,
        mutedUntil = mutedUntil,
        draft = null,
        myRole = MemberRole.MEMBER,
        isArchived = false,
        leftAt = null,
        updatedAt = updatedAt,
    )

    fun member(
        conversationId: String = CONVERSATION,
        userId: String = OTHER,
        role: MemberRole = MemberRole.MEMBER,
        leftAt: Long? = null,
    ) = ConversationMemberEntity(
        conversationId = conversationId,
        userId = userId,
        role = role,
        joinedAt = 1_000,
        lastReadSeq = 0,
        leftAt = leftAt,
    )

    fun message(
        id: String,
        conversationId: String = CONVERSATION,
        senderId: String = ME,
        clientMsgId: String = id,
        seq: Long? = null,
        body: String? = "текст",
        deliveryStatus: MessageDeliveryStatus = MessageDeliveryStatus.PENDING,
        syncStatus: SyncStatus = SyncStatus.PENDING,
        createdAt: Long = 1_000,
        deletedAt: Long? = null,
        pinnedAt: Long? = null,
    ) = MessageEntity(
        id = id,
        conversationId = conversationId,
        senderId = senderId,
        clientMsgId = clientMsgId,
        seq = seq,
        type = MessageType.TEXT,
        body = body,
        replyToId = null,
        replyToSenderName = null,
        replyToSnapshot = null,
        forwardedFromId = null,
        forwardedFromSenderName = null,
        deliveryStatus = deliveryStatus,
        syncStatus = syncStatus,
        pinnedAt = pinnedAt,
        editedAt = null,
        deletedAt = deletedAt,
        createdAt = createdAt,
    )

    fun task(
        id: String,
        title: String = "Задача",
        status: TaskStatus = TaskStatus.TODO,
        priority: TaskPriority = TaskPriority.MEDIUM,
        createdBy: String = ME,
        conversationId: String? = null,
        sourceMessageId: String? = null,
        sourceSnapshot: String? = null,
        dueAt: Long? = null,
        parentTaskId: String? = null,
        syncStatus: SyncStatus = SyncStatus.SYNCED,
        deletedAt: Long? = null,
        version: Long = 1,
        fieldVersionsJson: String = "{}",
    ) = TaskEntity(
        id = id,
        title = title,
        description = null,
        status = status,
        priority = priority,
        createdBy = createdBy,
        conversationId = conversationId,
        conversationTitle = null,
        sourceMessageId = sourceMessageId,
        sourceSenderName = if (sourceMessageId != null) "Автор" else null,
        sourceMessageSnapshot = sourceSnapshot,
        sourceCreatedAt = if (sourceMessageId != null) 1_000 else null,
        sourceUnavailable = false,
        parentTaskId = parentTaskId,
        dueAt = dueAt,
        remindAt = null,
        version = version,
        fieldVersionsJson = fieldVersionsJson,
        syncStatus = syncStatus,
        completedAt = null,
        deletedAt = deletedAt,
        createdAt = 1_000,
        updatedAt = 1_000,
    )

    fun outboxOperation(
        id: String,
        clientOpId: String = id,
        opType: String = "SEND_MESSAGE",
        entityType: String = "MESSAGE",
        entityId: String = "msg-1",
        status: OutboxStatus = OutboxStatus.PENDING,
        nextAttemptAt: Long = 0,
        attemptCount: Int = 0,
        dependsOnOpId: String? = null,
        createdAt: Long = 1_000,
    ) = OutboxOperationEntity(
        id = id,
        clientOpId = clientOpId,
        opType = opType,
        entityType = entityType,
        entityId = entityId,
        payloadJson = """{"body":"текст"}""",
        status = status,
        attemptCount = attemptCount,
        nextAttemptAt = nextAttemptAt,
        lastError = null,
        dependsOnOpId = dependsOnOpId,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
