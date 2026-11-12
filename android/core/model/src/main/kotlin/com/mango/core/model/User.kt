package com.mango.core.model

import java.time.Instant

data class User(
    val id: UserId,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val presence: PresenceStatus = PresenceStatus.OFFLINE,
    val lastSeenAt: Instant? = null,
) {
    /** Инициалы для аватара-заглушки, когда изображение отсутствует. */
    val initials: String
        get() = displayName.trim()
            .split(' ')
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifEmpty { username.take(1).uppercase() }
}

/** Профиль текущего пользователя — отличается от [User] наличием приватных полей. */
data class CurrentUser(
    val user: User,
    val email: String,
    val emailVerified: Boolean,
    val privacy: PrivacySettings = PrivacySettings(),
)

data class PrivacySettings(
    val lastSeenVisibility: Visibility = Visibility.CONTACTS,
    val avatarVisibility: Visibility = Visibility.EVERYONE,
    val readReceiptsEnabled: Boolean = true,
    val typingIndicatorEnabled: Boolean = true,
) {
    enum class Visibility { EVERYONE, CONTACTS, NOBODY }
}

data class Device(
    val id: DeviceId,
    val platform: String,
    val model: String,
    val appVersion: String,
    val lastActiveAt: Instant,
    val isCurrent: Boolean,
)
