package com.mango.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mango.core.model.SyncStatus
import com.mango.core.model.TaskEvent
import com.mango.core.model.TaskPriority
import com.mango.core.model.TaskStatus

/**
 * Задача.
 *
 * [fieldVersionsJson] хранит метки времени последнего изменения по каждому полю
 * (`{"title": 1731412000000, "status": 1731412500000}`). Это основа разрешения
 * конфликтов на уровне полей: при расхождении версий побеждает не весь объект,
 * а каждое поле по отдельности. См. docs/architecture/sync-model.md.
 *
 * [sourceMessageSnapshot] сохраняет текст исходного сообщения на момент создания
 * задачи, поэтому удаление сообщения не лишает задачу контекста.
 */
@Entity(
    tableName = "tasks",
    indices = [
        Index("conversation_id"),
        Index("status"),
        Index("due_at"),
        Index("parent_task_id"),
        Index("sync_status"),
        Index("source_message_id"),
    ],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String?,
    val status: TaskStatus,
    val priority: TaskPriority,
    @ColumnInfo(name = "created_by") val createdBy: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String?,
    @ColumnInfo(name = "conversation_title") val conversationTitle: String?,

    // --- связь с исходным сообщением ---
    @ColumnInfo(name = "source_message_id") val sourceMessageId: String?,
    @ColumnInfo(name = "source_sender_name") val sourceSenderName: String?,
    @ColumnInfo(name = "source_message_snapshot") val sourceMessageSnapshot: String?,
    @ColumnInfo(name = "source_created_at") val sourceCreatedAt: Long?,
    @ColumnInfo(name = "source_unavailable", defaultValue = "0") val sourceUnavailable: Boolean = false,

    @ColumnInfo(name = "parent_task_id") val parentTaskId: String?,
    @ColumnInfo(name = "due_at") val dueAt: Long?,
    @ColumnInfo(name = "remind_at") val remindAt: Long?,

    /** Версия объекта для оптимистичной блокировки (baseVersion в PATCH). */
    @ColumnInfo(name = "version", defaultValue = "1") val version: Long = 1,

    /** JSON: имя поля → метка времени последнего локального изменения. */
    @ColumnInfo(name = "field_versions_json", defaultValue = "'{}'")
    val fieldVersionsJson: String = "{}",

    @ColumnInfo(name = "sync_status") val syncStatus: SyncStatus,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * Исполнители задачи.
 *
 * Изменения исполнителей сливаются как OR-Set (см. модель синхронизации),
 * поэтому хранятся отдельной таблицей, а не списком внутри задачи:
 * иначе два устройства, добавивших разных людей, затирали бы друг друга.
 */
@Entity(
    tableName = "task_assignees",
    primaryKeys = ["task_id", "user_id"],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("user_id")],
)
data class TaskAssigneeEntity(
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "assigned_at") val assignedAt: Long,
    /** Локальное удаление из набора до подтверждения сервером. */
    @ColumnInfo(name = "removed_at") val removedAt: Long?,
    @ColumnInfo(name = "sync_status") val syncStatus: SyncStatus,
)

@Entity(
    tableName = "task_comments",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["task_id", "created_at"]),
        Index(value = ["task_id", "client_op_id"], unique = true),
    ],
)
data class TaskCommentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "author_id") val authorId: String,
    @ColumnInfo(name = "client_op_id") val clientOpId: String,
    val body: String,
    @ColumnInfo(name = "sync_status") val syncStatus: SyncStatus,
    @ColumnInfo(name = "edited_at") val editedAt: Long?,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** История изменений задачи — делает любой конфликт разбираемым постфактум. */
@Entity(
    tableName = "task_events",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["task_id", "created_at"])],
)
data class TaskEventEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    @ColumnInfo(name = "server_id") val serverId: Long?,
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "actor_id") val actorId: String?,
    val type: TaskEvent.Type,
    val field: String?,
    @ColumnInfo(name = "old_value") val oldValue: String?,
    @ColumnInfo(name = "new_value") val newValue: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
