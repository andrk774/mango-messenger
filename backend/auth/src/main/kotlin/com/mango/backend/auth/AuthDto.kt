@file:UseSerializers(InstantSerializer::class, UuidSerializer::class)

package com.mango.backend.auth

import com.mango.backend.common.InstantSerializer
import com.mango.backend.common.UuidSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.util.UUID

// Контракт HTTP отделён от доменных типов: переименование поля в ответе
// не должно требовать правки сервиса, и наоборот.

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String,
    val username: String,
)

@Serializable
data class VerifyEmailRequest(val token: String)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    val device: DeviceInfoDto? = null,
)

@Serializable
data class DeviceInfoDto(
    val platform: String,
    val model: String = "",
    val appVersion: String = "",
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class ForgotPasswordRequest(val email: String)

@Serializable
data class ResetPasswordRequest(val token: String, val newPassword: String)

@Serializable
data class TokenPairResponse(
    val accessToken: String,
    val accessExpiresAt: Instant,
    val refreshToken: String,
    val refreshExpiresAt: Instant,
)

@Serializable
data class SessionResponse(
    val id: UUID,
    val createdAt: Instant,
    val lastUsedAt: Instant? = null,
    val userAgent: String? = null,
    val isCurrent: Boolean,
)

@Serializable
data class AcceptedResponse(val message: String)

@Serializable
data class CurrentUserResponse(
    val id: UUID,
    val email: String,
    val emailVerified: Boolean,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val createdAt: Instant,
)

fun TokenPairResult.toResponse() = TokenPairResponse(
    accessToken = accessToken,
    accessExpiresAt = accessExpiresAt,
    refreshToken = refreshToken,
    refreshExpiresAt = refreshExpiresAt,
)

fun SessionView.toResponse() = SessionResponse(
    id = id,
    createdAt = createdAt,
    lastUsedAt = lastUsedAt,
    userAgent = userAgent,
    isCurrent = isCurrent,
)

fun UserRecord.toResponse() = CurrentUserResponse(
    id = id,
    email = email,
    emailVerified = emailVerified,
    username = username,
    displayName = displayName,
    avatarUrl = avatarUrl,
    bio = bio,
    createdAt = createdAt,
)
