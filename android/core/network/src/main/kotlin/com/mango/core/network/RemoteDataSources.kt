package com.mango.core.network

import com.mango.core.common.Outcome
import com.mango.core.common.map
import com.mango.core.network.api.AuthApi
import com.mango.core.network.api.MessagingApi
import com.mango.core.network.api.SyncApi
import com.mango.core.network.dto.ConversationDto
import com.mango.core.network.dto.ConversationsResponseDto
import com.mango.core.network.dto.CreateConversationRequestDto
import com.mango.core.network.dto.CurrentUserDto
import com.mango.core.network.dto.DeviceInfoDto
import com.mango.core.network.dto.EditMessageRequestDto
import com.mango.core.network.dto.ForgotPasswordRequestDto
import com.mango.core.network.dto.LoginRequestDto
import com.mango.core.network.dto.MarkReadRequestDto
import com.mango.core.network.dto.MembersResponseDto
import com.mango.core.network.dto.MemberDto
import com.mango.core.network.dto.AddMembersRequestDto
import com.mango.core.network.dto.ChangeRoleRequestDto
import com.mango.core.network.dto.UpdateConversationRequestDto
import com.mango.core.network.dto.MessageDto
import com.mango.core.network.dto.MessagesResponseDto
import com.mango.core.network.dto.RegisterRequestDto
import com.mango.core.network.dto.ResetPasswordRequestDto
import com.mango.core.network.dto.SendMessageRequestDto
import com.mango.core.network.dto.SyncResponseDto
import com.mango.core.network.dto.TokenPairDto
import com.mango.core.network.dto.UserSearchResponseDto
import com.mango.core.network.dto.VerifyEmailRequestDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Источники удалённых данных.
 *
 * Граница модуля: наружу уходят [Outcome] и DTO, но НЕ типы Retrofit.
 * Без этого слоя `retrofit2.Response` протекал бы в сигнатуры
 * вызывающих модулей, и те были бы вынуждены зависеть от HTTP-клиента —
 * то есть заявленная изоляция сетевого слоя существовала бы только
 * на словах.
 */
@Singleton
class AuthRemoteDataSource @Inject constructor(private val api: AuthApi) {

    suspend fun register(
        email: String,
        password: String,
        displayName: String,
        username: String,
    ): Outcome<Unit> = apiCall {
        api.register(RegisterRequestDto(email, password, displayName, username))
    }.map { }

    suspend fun verifyEmail(email: String, token: String): Outcome<Unit> =
        apiCall { api.verifyEmail(VerifyEmailRequestDto(token = token, email = email)) }.map { }

    suspend fun login(
        email: String,
        password: String,
        deviceModel: String,
        appVersion: String,
    ): Outcome<TokenPairDto> = apiCall {
        api.login(
            LoginRequestDto(
                email = email,
                password = password,
                device = DeviceInfoDto(model = deviceModel, appVersion = appVersion),
            ),
        )
    }

    suspend fun logout(): Outcome<Unit> = apiCall { api.logout() }.map { }

    suspend fun forgotPassword(email: String): Outcome<Unit> =
        apiCall { api.forgotPassword(ForgotPasswordRequestDto(email)) }.map { }

    suspend fun resetPassword(email: String, token: String, newPassword: String): Outcome<Unit> =
        apiCall { api.resetPassword(ResetPasswordRequestDto(token, newPassword, email)) }.map { }

    suspend fun me(): Outcome<CurrentUserDto> = apiCall { api.me() }

    suspend fun registerPush(token: String): Outcome<Unit> =
        apiCall { api.registerPush(com.mango.core.network.dto.RegisterPushTokenRequestDto("FCM", token)) }.map { }

    suspend fun unregisterPush(): Outcome<Unit> = apiCall { api.unregisterPush() }.map { }
}

@Singleton
class MessagingRemoteDataSource @Inject constructor(private val api: MessagingApi) {

    suspend fun conversations(limit: Int = 50): Outcome<ConversationsResponseDto> =
        apiCall { api.conversations(limit) }

    suspend fun createDirect(userId: String): Outcome<ConversationDto> = apiCall {
        api.createConversation(
            CreateConversationRequestDto(type = "DIRECT", memberIds = listOf(userId)),
        )
    }

    suspend fun createGroup(title: String, memberIds: List<String>): Outcome<ConversationDto> =
        apiCall {
            api.createConversation(
                CreateConversationRequestDto(
                    type = "GROUP",
                    memberIds = memberIds,
                    title = title,
                ),
            )
        }

    suspend fun conversation(id: String): Outcome<ConversationDto> =
        apiCall { api.conversation(id) }

    suspend fun members(id: String): Outcome<MembersResponseDto> = apiCall { api.members(id) }

    suspend fun rename(id: String, title: String): Outcome<ConversationDto> =
        apiCall { api.renameConversation(id, UpdateConversationRequestDto(title)) }

    suspend fun addMembers(id: String, userIds: List<String>): Outcome<MembersResponseDto> =
        apiCall { api.addMembers(id, AddMembersRequestDto(userIds)) }

    suspend fun removeMember(id: String, userId: String): Outcome<Unit> =
        apiCall { api.removeMember(id, userId) }.map { }

    suspend fun changeRole(id: String, userId: String, role: String): Outcome<MemberDto> =
        apiCall { api.changeRole(id, userId, ChangeRoleRequestDto(role)) }

    suspend fun messages(
        conversationId: String,
        beforeSeq: Long? = null,
        afterSeq: Long? = null,
        limit: Int = 50,
    ): Outcome<MessagesResponseDto> =
        apiCall { api.messages(conversationId, beforeSeq, afterSeq, limit) }

    suspend fun sendMessage(
        conversationId: String,
        clientMsgId: String,
        body: String?,
        replyToId: String?,
        type: String = "TEXT",
        attachmentIds: List<String> = emptyList(),
    ): Outcome<MessageDto> = apiCall {
        api.sendMessage(
            id = conversationId,
            body = SendMessageRequestDto(
                clientMsgId = clientMsgId,
                type = type,
                body = body,
                replyToId = replyToId,
                attachmentIds = attachmentIds,
            ),
        )
    }

    suspend fun editMessage(messageId: String, body: String): Outcome<MessageDto> =
        apiCall { api.editMessage(messageId, EditMessageRequestDto(body)) }

    suspend fun deleteMessage(messageId: String): Outcome<Unit> =
        apiCall { api.deleteMessage(messageId) }.map { }

    suspend fun markRead(conversationId: String, seq: Long): Outcome<Unit> =
        apiCall { api.markRead(conversationId, MarkReadRequestDto(seq)) }.map { }

    suspend fun searchUsers(query: String, limit: Int = 20): Outcome<UserSearchResponseDto> =
        apiCall { api.searchUsers(query, limit) }
}

@Singleton
class SyncRemoteDataSource @Inject constructor(private val api: SyncApi) {

    suspend fun since(cursor: Long, limit: Int = 500): Outcome<SyncResponseDto> =
        apiCall { api.since(cursor, limit) }

    suspend fun bootstrap(): Outcome<ConversationsResponseDto> = apiCall { api.bootstrap() }
}
