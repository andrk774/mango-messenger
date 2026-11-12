package com.mango.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mango.core.model.NotificationType

/**
 * Очередь исходящих операций.
 *
 * Это сердце offline-first: любое изменение сначала применяется локально
 * и кладётся сюда, а уже потом уезжает на сервер. Таблица персистентная,
 * поэтому очередь переживает принудительное закрытие приложения.
 *
 * [clientOpId] отправляется серверу как ключ идемпотентности: повторная
 * доставка той же операции не создаёт дубликат.
 *
 * [dependsOnOpId] задаёт порядок: комментарий к задаче не должен уехать
 * раньше, чем сама задача получит серверный id.
 */
@Entity(
    tableName = "outbox_operations",
    indices = [
        Index(value = ["client_op_id"], unique = true),
        Index(value = ["status", "next_attempt_at"]),
        Index("depends_on_op_id"),
        Index(value = ["entity_type", "entity_id"]),
    ],
)
data class OutboxOperationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "client_op_id") val clientOpId: String,

    /** Тип операции, например SEND_MESSAGE, PATCH_TASK, ADD_REACTION. */
    @ColumnInfo(name = "op_type") val opType: String,

    /** Сущность, к которой относится операция — для схлопывания и отмены. */
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,

    /** Сериализованная полезная нагрузка запроса. */
    @ColumnInfo(name = "payload_json") val payloadJson: String,

    val status: OutboxStatus,
    @ColumnInfo(name = "attempt_count", defaultValue = "0") val attemptCount: Int = 0,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long,
    @ColumnInfo(name = "last_error") val lastError: String?,
    @ColumnInfo(name = "depends_on_op_id") val dependsOnOpId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

enum class OutboxStatus {
    /** Готова к отправке. */
    PENDING,

    /** Отправка выполняется. При старте приложения переводится обратно в PENDING. */
    IN_FLIGHT,

    /** Ждёт выполнения операции-зависимости. */
    BLOCKED,

    /** Сервер отклонил операцию, нужно решение пользователя. */
    FAILED,
}

/**
 * Курсоры синхронизации.
 *
 * Хранятся в БД, а не в DataStore, чтобы продвижение курсора происходило
 * в той же транзакции, что и применение событий. Иначе возможен разрыв:
 * курсор продвинулся, а события не записались — и они потеряются навсегда.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    /** Область синхронизации: GLOBAL, CONVERSATION:<id>, TASKS и т. п. */
    @PrimaryKey val scope: String,

    /** Последний применённый идентификатор события. */
    @ColumnInfo(name = "last_event_id", defaultValue = "0") val lastEventId: Long = 0,

    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long?,

    /** Сервер попросил полную перезагрузку этой области. */
    @ColumnInfo(name = "needs_reset", defaultValue = "0") val needsReset: Boolean = false,
)

@Entity(
    tableName = "notifications",
    indices = [Index(value = ["read_at", "created_at"])],
)
data class NotificationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    val type: NotificationType,
    val title: String,
    val body: String?,
    @ColumnInfo(name = "conversation_id") val conversationId: String?,
    @ColumnInfo(name = "message_id") val messageId: String?,
    @ColumnInfo(name = "task_id") val taskId: String?,
    @ColumnInfo(name = "read_at") val readAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
