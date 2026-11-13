@file:UseSerializers(UuidSerializer::class, InstantSerializer::class)

package com.mango.backend.tasks

import com.mango.backend.common.InstantSerializer
import com.mango.backend.common.UuidSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.util.UUID

// ──────────────────────────── запросы ────────────────────────────

@Serializable
data class CreateTaskRequest(
    /**
     * Идентификатор, присвоенный клиентом (UUIDv7).
     *
     * Задача создаётся офлайн, и на неё сразу могут ссылаться другие
     * операции очереди — комментарии, смена статуса. Поэтому id выдаёт
     * клиент, а повторное создание с тем же id возвращает уже созданную.
     */
    val taskId: UUID,
    val title: String,
    val description: String? = null,
    val priority: String = "MEDIUM",
    val dueAt: Instant? = null,
    val remindAt: Instant? = null,
    val conversationId: UUID? = null,
    val parentTaskId: UUID? = null,
    val assigneeIds: List<UUID> = emptyList(),
)

@Serializable
data class CreateTaskFromMessageRequest(
    val taskId: UUID,
    val title: String,
    val description: String? = null,
    val priority: String = "MEDIUM",
    val dueAt: Instant? = null,
    val assigneeIds: List<UUID> = emptyList(),
)

/**
 * Частичное изменение.
 *
 * Передаются только изменённые поля и время, когда пользователь их
 * изменил. Значения — строками: `null` означает «снять значение»,
 * а время — в формате ISO-8601.
 */
@Serializable
data class PatchTaskRequest(
    val baseVersion: Long,
    val changedFields: Map<String, String?>,
    val fieldTimestamps: Map<String, Long> = emptyMap(),
)

@Serializable
data class AssigneesRequest(
    val add: List<UUID> = emptyList(),
    val remove: List<UUID> = emptyList(),
)

@Serializable
data class AddCommentRequest(
    /** Ключ дедупликации: повтор не создаёт второй комментарий. */
    val clientOpId: String,
    val body: String,
)

// ──────────────────────────── ответы ────────────────────────────

@Serializable
data class AssigneeDto(
    val userId: UUID,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
)

@Serializable
data class TaskSourceDto(
    val messageId: UUID? = null,
    val conversationId: UUID? = null,
    val senderName: String,
    /**
     * Текст сообщения на момент создания задачи.
     *
     * Хранится в самой задаче, поэтому переживает удаление сообщения:
     * без него задача «из сообщения» теряла бы смысл.
     */
    val textSnapshot: String? = null,
    val sourceCreatedAt: Instant? = null,
    /** Исходное сообщение удалено — переход к нему невозможен. */
    val isSourceUnavailable: Boolean,
)

@Serializable
data class TaskDto(
    val id: UUID,
    val title: String,
    val description: String? = null,
    val status: String,
    val priority: String,
    val createdBy: UUID,
    val assignees: List<AssigneeDto> = emptyList(),
    val conversationId: UUID? = null,
    val conversationTitle: String? = null,
    val source: TaskSourceDto? = null,
    val parentTaskId: UUID? = null,
    val subtaskCount: Int = 0,
    val completedSubtaskCount: Int = 0,
    val commentCount: Int = 0,
    val dueAt: Instant? = null,
    val remindAt: Instant? = null,
    /** Передаётся обратно как `baseVersion` при следующем изменении. */
    val version: Long,
    /** Метки времени по полям — клиенту они нужны для собственного слияния. */
    val fieldVersions: Map<String, Long> = emptyMap(),
    val completedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
data class PatchTaskResponse(
    val task: TaskDto,
    /**
     * Поля, в которых победила версия сервера.
     *
     * По ним клиент показывает «изменено на другом устройстве».
     * Пустой список — все правки приняты.
     */
    val conflicts: List<String>,
)

@Serializable
data class TasksResponse(val items: List<TaskDto>)

@Serializable
data class TaskCommentDto(
    val id: UUID,
    val taskId: UUID,
    val authorId: UUID,
    val authorName: String,
    val clientOpId: String,
    val body: String,
    val createdAt: Instant,
    val editedAt: Instant? = null,
)

@Serializable
data class CommentsResponse(val items: List<TaskCommentDto>)

@Serializable
data class TaskEventDto(
    val id: Long,
    val taskId: UUID,
    val actorId: UUID? = null,
    val actorName: String? = null,
    val type: String,
    val field: String? = null,
    val oldValue: JsonElement? = null,
    val newValue: JsonElement? = null,
    val createdAt: Instant,
)

@Serializable
data class TaskEventsResponse(val items: List<TaskEventDto>)

// ─────────────────── полезная нагрузка событий журнала ───────────────────

@Serializable
data class TaskChangedPayload(val task: TaskDto)

@Serializable
data class TaskDeletedPayload(val taskId: UUID, val deletedAt: Instant)

@Serializable
data class TaskCommentAddedPayload(val comment: TaskCommentDto)
