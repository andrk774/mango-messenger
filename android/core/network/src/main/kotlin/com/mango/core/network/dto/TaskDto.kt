@file:UseSerializers(InstantSerializer::class)

package com.mango.core.network.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

// Контракт задач. Зеркало серверного описания в backend/tasks/TasksDto.kt.

@Serializable
data class CreateTaskRequestDto(
    val taskId: String,
    val title: String,
    val description: String? = null,
    val priority: String = "MEDIUM",
    val dueAt: Instant? = null,
    val remindAt: Instant? = null,
    val conversationId: String? = null,
    val parentTaskId: String? = null,
    val assigneeIds: List<String> = emptyList(),
)

@Serializable
data class CreateTaskFromMessageRequestDto(
    val taskId: String,
    val title: String,
    val description: String? = null,
    val priority: String = "MEDIUM",
    val dueAt: Instant? = null,
    val assigneeIds: List<String> = emptyList(),
)

@Serializable
data class PatchTaskRequestDto(
    val baseVersion: Long,
    val changedFields: Map<String, String?>,
    val fieldTimestamps: Map<String, Long> = emptyMap(),
)

@Serializable
data class AssigneesRequestDto(
    val add: List<String> = emptyList(),
    val remove: List<String> = emptyList(),
)

@Serializable
data class AddCommentRequestDto(val clientOpId: String, val body: String)

@Serializable
data class AssigneeDto(
    val userId: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
)

@Serializable
data class TaskSourceDto(
    val messageId: String? = null,
    val conversationId: String? = null,
    val senderName: String,
    val textSnapshot: String? = null,
    val sourceCreatedAt: Instant? = null,
    val isSourceUnavailable: Boolean,
)

@Serializable
data class TaskDto(
    val id: String,
    val title: String,
    val description: String? = null,
    val status: String,
    val priority: String,
    val createdBy: String,
    val assignees: List<AssigneeDto> = emptyList(),
    val conversationId: String? = null,
    val conversationTitle: String? = null,
    val source: TaskSourceDto? = null,
    val parentTaskId: String? = null,
    val subtaskCount: Int = 0,
    val completedSubtaskCount: Int = 0,
    val commentCount: Int = 0,
    val dueAt: Instant? = null,
    val remindAt: Instant? = null,
    val version: Long,
    val fieldVersions: Map<String, Long> = emptyMap(),
    val completedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
data class PatchTaskResponseDto(
    val task: TaskDto,
    /** Поля, где победила версия сервера: «изменено на другом устройстве». */
    val conflicts: List<String> = emptyList(),
)

@Serializable
data class TasksResponseDto(val items: List<TaskDto>)

@Serializable
data class TaskCommentDto(
    val id: String,
    val taskId: String,
    val authorId: String,
    val authorName: String,
    val clientOpId: String,
    val body: String,
    val createdAt: Instant,
    val editedAt: Instant? = null,
)

@Serializable
data class CommentsResponseDto(val items: List<TaskCommentDto>)

// Полезная нагрузка событий журнала.

@Serializable
data class TaskChangedPayloadDto(val task: TaskDto)

@Serializable
data class TaskDeletedPayloadDto(val taskId: String, val deletedAt: Instant)

@Serializable
data class TaskCommentAddedPayloadDto(val comment: TaskCommentDto)
