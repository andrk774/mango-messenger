package com.mango.core.data.mapper

import com.mango.core.database.entity.TaskAssigneeEntity
import com.mango.core.database.entity.TaskCommentEntity
import com.mango.core.database.entity.TaskEntity
import com.mango.core.database.entity.UserEntity
import com.mango.core.model.PresenceStatus
import com.mango.core.model.SyncStatus
import com.mango.core.model.TaskPriority
import com.mango.core.model.TaskStatus
import com.mango.core.network.dto.AssigneeDto
import com.mango.core.network.dto.TaskCommentDto
import com.mango.core.network.dto.TaskDto
import java.time.Instant

/**
 * Серверная задача → локальная запись.
 *
 * Метки версий полей сохраняются вместе с задачей: при следующей правке
 * клиент отправит `baseVersion`, и сервер решит, были ли конкурирующие
 * изменения.
 */
fun TaskDto.toEntity(): TaskEntity = TaskEntity(
    id = id,
    title = title,
    description = description,
    status = runCatching { TaskStatus.valueOf(status) }.getOrDefault(TaskStatus.TODO),
    priority = runCatching { TaskPriority.valueOf(priority) }.getOrDefault(TaskPriority.MEDIUM),
    createdBy = createdBy,
    conversationId = conversationId,
    conversationTitle = conversationTitle,
    sourceMessageId = source?.messageId,
    sourceSenderName = source?.senderName,
    sourceMessageSnapshot = source?.textSnapshot,
    sourceCreatedAt = source?.sourceCreatedAt?.toEpochMilli(),
    sourceUnavailable = source?.isSourceUnavailable ?: false,
    parentTaskId = parentTaskId,
    dueAt = dueAt?.toEpochMilli(),
    remindAt = remindAt?.toEpochMilli(),
    version = version,
    fieldVersionsJson = fieldVersions.entries.joinToString(prefix = "{", postfix = "}") {
        "\"${it.key}\":${it.value}"
    },
    syncStatus = SyncStatus.SYNCED,
    completedAt = completedAt?.toEpochMilli(),
    deletedAt = null,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
)

fun AssigneeDto.toUserEntity(): UserEntity = UserEntity(
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

fun AssigneeDto.toAssigneeEntity(taskId: String, assignedAt: Long): TaskAssigneeEntity =
    TaskAssigneeEntity(
        taskId = taskId,
        userId = userId,
        assignedAt = assignedAt,
        removedAt = null,
        syncStatus = SyncStatus.SYNCED,
    )

fun TaskCommentDto.toEntity(): TaskCommentEntity = TaskCommentEntity(
    id = id,
    taskId = taskId,
    authorId = authorId,
    clientOpId = clientOpId,
    body = body,
    syncStatus = SyncStatus.SYNCED,
    editedAt = editedAt?.toEpochMilli(),
    deletedAt = null,
    createdAt = createdAt.toEpochMilli(),
)

/**
 * Наложение неотправленной правки поля на локальную запись.
 *
 * Имена полей совпадают с контрактом API (`due_at`, а не `dueAt`):
 * правки в очереди записаны именно в этом виде.
 *
 * Нераспознанное поле игнорируется, а не роняет применение: очередь
 * могла быть записана другой версией приложения.
 */
fun TaskEntity.withPendingField(field: String, value: String?): TaskEntity = when (field) {
    "title" -> if (value != null) copy(title = value) else this
    "description" -> copy(description = value)
    "status" -> value?.let { runCatching { TaskStatus.valueOf(it) }.getOrNull() }
        ?.let { copy(status = it) } ?: this
    "priority" -> value?.let { runCatching { TaskPriority.valueOf(it) }.getOrNull() }
        ?.let { copy(priority = it) } ?: this
    "due_at" -> copy(dueAt = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() })
    "remind_at" -> copy(remindAt = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() })
    else -> this
}
