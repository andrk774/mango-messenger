package com.mango.core.network

import com.mango.core.common.Outcome
import com.mango.core.common.map
import com.mango.core.network.api.TasksApi
import com.mango.core.network.dto.AddCommentRequestDto
import com.mango.core.network.dto.AssigneesRequestDto
import com.mango.core.network.dto.CreateTaskFromMessageRequestDto
import com.mango.core.network.dto.CreateTaskRequestDto
import com.mango.core.network.dto.PatchTaskRequestDto
import com.mango.core.network.dto.PatchTaskResponseDto
import com.mango.core.network.dto.TaskCommentDto
import com.mango.core.network.dto.TaskDto
import com.mango.core.network.dto.TasksResponseDto
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Удалённые операции с задачами.
 *
 * Как и остальные источники данных, отдаёт наружу [Outcome] и DTO,
 * но не типы Retrofit.
 */
@Singleton
class TasksRemoteDataSource @Inject constructor(private val api: TasksApi) {

    suspend fun tasks(scope: String, includeCompleted: Boolean = true): Outcome<TasksResponseDto> =
        apiCall { api.tasks(scope = scope, includeCompleted = includeCompleted) }

    /**
     * Создание задачи.
     *
     * Задача из сообщения создаётся отдельным эндпоинтом: только сервер
     * может проверить доступ к сообщению и снять с него снимок текста.
     * Клиентскому снимку доверять нельзя — его можно подделать.
     */
    suspend fun create(
        taskId: String,
        title: String,
        description: String?,
        priority: String,
        dueAt: Instant?,
        conversationId: String?,
        parentTaskId: String?,
        assigneeIds: List<String>,
        sourceMessageId: String?,
    ): Outcome<TaskDto> = if (sourceMessageId != null) {
        apiCall {
            api.createFromMessage(
                sourceMessageId,
                CreateTaskFromMessageRequestDto(
                    taskId = taskId,
                    title = title,
                    description = description,
                    priority = priority,
                    dueAt = dueAt,
                    assigneeIds = assigneeIds,
                ),
            )
        }
    } else {
        apiCall {
            api.create(
                CreateTaskRequestDto(
                    taskId = taskId,
                    title = title,
                    description = description,
                    priority = priority,
                    dueAt = dueAt,
                    conversationId = conversationId,
                    parentTaskId = parentTaskId,
                    assigneeIds = assigneeIds,
                ),
            )
        }
    }

    suspend fun patch(
        taskId: String,
        baseVersion: Long,
        changedFields: Map<String, String?>,
        fieldTimestamps: Map<String, Long>,
    ): Outcome<PatchTaskResponseDto> = apiCall {
        api.patch(taskId, PatchTaskRequestDto(baseVersion, changedFields, fieldTimestamps))
    }

    suspend fun delete(taskId: String): Outcome<Unit> = apiCall { api.delete(taskId) }.map { }

    suspend fun assignees(taskId: String, add: List<String>, remove: List<String>): Outcome<TaskDto> =
        apiCall { api.assignees(taskId, AssigneesRequestDto(add, remove)) }

    suspend fun addComment(taskId: String, clientOpId: String, body: String): Outcome<TaskCommentDto> =
        apiCall { api.addComment(taskId, AddCommentRequestDto(clientOpId, body)) }
}
