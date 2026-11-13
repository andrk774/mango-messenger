package com.mango.core.network.api

import com.mango.core.network.dto.AddCommentRequestDto
import com.mango.core.network.dto.AssigneesRequestDto
import com.mango.core.network.dto.CommentsResponseDto
import com.mango.core.network.dto.CreateTaskFromMessageRequestDto
import com.mango.core.network.dto.CreateTaskRequestDto
import com.mango.core.network.dto.PatchTaskRequestDto
import com.mango.core.network.dto.PatchTaskResponseDto
import com.mango.core.network.dto.TaskCommentDto
import com.mango.core.network.dto.TaskDto
import com.mango.core.network.dto.TasksResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Задачи.
 *
 * Изменение задачи возвращает объект вместе со списком полей, где
 * победила версия сервера: по нему интерфейс показывает «изменено
 * на другом устройстве».
 */
interface TasksApi {

    @GET("v1/tasks")
    suspend fun tasks(
        @Query("scope") scope: String,
        @Query("includeCompleted") includeCompleted: Boolean = false,
        @Query("conversationId") conversationId: String? = null,
        @Query("limit") limit: Int = 100,
    ): Response<TasksResponseDto>

    @POST("v1/tasks")
    suspend fun create(@Body body: CreateTaskRequestDto): Response<TaskDto>

    @POST("v1/messages/{messageId}/to-task")
    suspend fun createFromMessage(
        @Path("messageId") messageId: String,
        @Body body: CreateTaskFromMessageRequestDto,
    ): Response<TaskDto>

    @GET("v1/tasks/{id}")
    suspend fun task(@Path("id") id: String): Response<TaskDto>

    @PATCH("v1/tasks/{id}")
    suspend fun patch(
        @Path("id") id: String,
        @Body body: PatchTaskRequestDto,
    ): Response<PatchTaskResponseDto>

    @DELETE("v1/tasks/{id}")
    suspend fun delete(@Path("id") id: String): Response<Unit>

    @POST("v1/tasks/{id}/assignees")
    suspend fun assignees(
        @Path("id") id: String,
        @Body body: AssigneesRequestDto,
    ): Response<TaskDto>

    @GET("v1/tasks/{id}/comments")
    suspend fun comments(@Path("id") id: String): Response<CommentsResponseDto>

    @POST("v1/tasks/{id}/comments")
    suspend fun addComment(
        @Path("id") id: String,
        @Body body: AddCommentRequestDto,
    ): Response<TaskCommentDto>
}
