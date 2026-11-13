package com.mango.core.model

import java.time.Instant

data class Conversation(
    val id: ConversationId,
    val type: ConversationType,
    val title: String,
    val avatarUrl: String? = null,
    val lastMessage: MessagePreview? = null,
    val unreadCount: Int = 0,
    val mutedUntil: Instant? = null,
    val pinnedMessageCount: Int = 0,
    val memberCount: Int = 0,
    /** Последний seq, прочитанный текущим пользователем. */
    val lastReadSeq: Long = 0,
    /** Максимальный seq, известный клиенту. Разница с [lastReadSeq] даёт непрочитанное. */
    val lastEventSeq: Long = 0,
    val draft: String? = null,
    val updatedAt: Instant,
    /** Своя роль: от неё зависит, какие действия с группой показывать. */
    val myRole: MemberRole? = null,
) {
    val isMuted: Boolean
        get() = mutedUntil?.isAfter(Instant.now()) == true

    val hasUnread: Boolean get() = unreadCount > 0
}

/** Краткое представление последнего сообщения для списка чатов. */
data class MessagePreview(
    val id: MessageId,
    val senderId: UserId,
    val senderName: String,
    val type: MessageType,
    val text: String?,
    val createdAt: Instant,
    val deliveryStatus: MessageDeliveryStatus,
    val isDeleted: Boolean = false,
    val system: SystemEvent? = null,
)

data class ConversationMember(
    val user: User,
    val role: MemberRole,
    val joinedAt: Instant,
    val lastReadSeq: Long = 0,
)
