package com.mango.core.network.api

import com.mango.core.network.dto.AcceptedDto
import com.mango.core.network.dto.ConversationDto
import com.mango.core.network.dto.ConversationsResponseDto
import com.mango.core.network.dto.CreateConversationRequestDto
import com.mango.core.network.dto.CurrentUserDto
import com.mango.core.network.dto.EditMessageRequestDto
import com.mango.core.network.dto.ForgotPasswordRequestDto
import com.mango.core.network.dto.LoginRequestDto
import com.mango.core.network.dto.MarkReadRequestDto
import com.mango.core.network.dto.MembersResponseDto
import com.mango.core.network.dto.MessageDto
import com.mango.core.network.dto.MessagesResponseDto
import com.mango.core.network.dto.RefreshRequestDto
import com.mango.core.network.dto.RegisterRequestDto
import com.mango.core.network.dto.ResetPasswordRequestDto
import com.mango.core.network.dto.SendMessageRequestDto
import com.mango.core.network.dto.SessionDto
import com.mango.core.network.dto.SyncResponseDto
import com.mango.core.network.dto.TokenPairDto
import com.mango.core.network.dto.UserSearchResponseDto
import com.mango.core.network.dto.VerifyEmailRequestDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Аутентификация.
 *
 * Методы возвращают `Response<T>`, а не голый `T`: код ответа несёт смысл
 * (401 против 403, 200 против 201 при повторе операции), и терять его
 * нельзя. Перевод в доменный результат выполняет вызывающий слой.
 */
interface AuthApi {

    @POST("v1/auth/register")
    suspend fun register(@Body body: RegisterRequestDto): Response<AcceptedDto>

    @POST("v1/auth/verify-email")
    suspend fun verifyEmail(@Body body: VerifyEmailRequestDto): Response<AcceptedDto>

    @POST("v1/auth/login")
    suspend fun login(@Body body: LoginRequestDto): Response<TokenPairDto>

    @POST("v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequestDto): Response<TokenPairDto>

    @POST("v1/auth/logout")
    suspend fun logout(): Response<Unit>

    @POST("v1/auth/password/forgot")
    suspend fun forgotPassword(@Body body: ForgotPasswordRequestDto): Response<AcceptedDto>

    @POST("v1/auth/password/reset")
    suspend fun resetPassword(@Body body: ResetPasswordRequestDto): Response<AcceptedDto>

    @GET("v1/auth/sessions")
    suspend fun sessions(): Response<List<SessionDto>>

    @DELETE("v1/auth/sessions/{sessionId}")
    suspend fun revokeSession(@Path("sessionId") sessionId: String): Response<Unit>

    @GET("v1/users/me")
    suspend fun me(): Response<CurrentUserDto>
}

interface MessagingApi {

    @GET("v1/conversations")
    suspend fun conversations(
        @Query("limit") limit: Int = 50,
    ): Response<ConversationsResponseDto>

    @POST("v1/conversations")
    suspend fun createConversation(
        @Body body: CreateConversationRequestDto,
    ): Response<ConversationDto>

    @GET("v1/conversations/{id}")
    suspend fun conversation(@Path("id") id: String): Response<ConversationDto>

    @GET("v1/conversations/{id}/members")
    suspend fun members(@Path("id") id: String): Response<MembersResponseDto>

    @PUT("v1/conversations/{id}/read")
    suspend fun markRead(
        @Path("id") id: String,
        @Body body: MarkReadRequestDto,
    ): Response<Unit>

    @GET("v1/conversations/{id}/messages")
    suspend fun messages(
        @Path("id") id: String,
        @Query("beforeSeq") beforeSeq: Long? = null,
        @Query("afterSeq") afterSeq: Long? = null,
        @Query("limit") limit: Int = 50,
    ): Response<MessagesResponseDto>

    /**
     * Отправка сообщения.
     *
     * Ответ 201 означает «создано», 200 — «эта операция уже выполнялась».
     * Клиенту важно различать: во втором случае повторять ничего не нужно,
     * а сообщение уже лежит на сервере.
     */
    @POST("v1/conversations/{id}/messages")
    suspend fun sendMessage(
        @Path("id") id: String,
        @Body body: SendMessageRequestDto,
    ): Response<MessageDto>

    @PATCH("v1/messages/{id}")
    suspend fun editMessage(
        @Path("id") id: String,
        @Body body: EditMessageRequestDto,
    ): Response<MessageDto>

    @DELETE("v1/messages/{id}")
    suspend fun deleteMessage(@Path("id") id: String): Response<Unit>

    @GET("v1/users/search")
    suspend fun searchUsers(
        @Query("q") query: String,
        @Query("limit") limit: Int = 20,
    ): Response<UserSearchResponseDto>
}

interface SyncApi {

    /** Догонка пропущенных событий по курсору. */
    @GET("v1/sync")
    suspend fun since(
        @Query("since") since: Long,
        @Query("limit") limit: Int = 500,
    ): Response<SyncResponseDto>

    @GET("v1/sync/bootstrap")
    suspend fun bootstrap(): Response<ConversationsResponseDto>
}
