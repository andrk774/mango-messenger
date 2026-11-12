package com.mango.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mango.core.model.ConversationType
import com.mango.core.model.MemberRole
import com.mango.core.model.MessageDeliveryStatus
import com.mango.core.model.MessageType
import com.mango.core.model.PresenceStatus
import com.mango.core.model.SyncStatus

@Entity(
    tableName = "users",
    indices = [Index("username")],
)
data class UserEntity(
    @PrimaryKey val id: String,
    val username: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "avatar_url") val avatarUrl: String?,
    val bio: String?,
    val presence: PresenceStatus,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long?,
    /** Признак текущего пользователя — ровно одна строка должна иметь true. */
    @ColumnInfo(name = "is_me", defaultValue = "0") val isMe: Boolean = false,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "conversations",
    indices = [Index("updated_at"), Index("type")],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val type: ConversationType,
    val title: String,
    @ColumnInfo(name = "avatar_url") val avatarUrl: String?,
    @ColumnInfo(name = "member_count", defaultValue = "0") val memberCount: Int = 0,
    @ColumnInfo(name = "pinned_message_count", defaultValue = "0") val pinnedMessageCount: Int = 0,
    /** Максимальный seq, известный клиенту для этого чата. */
    @ColumnInfo(name = "last_event_seq", defaultValue = "0") val lastEventSeq: Long = 0,
    /** Последний seq, прочитанный текущим пользователем. */
    @ColumnInfo(name = "last_read_seq", defaultValue = "0") val lastReadSeq: Long = 0,
    @ColumnInfo(name = "muted_until") val mutedUntil: Long?,
    val draft: String?,
    @ColumnInfo(name = "my_role") val myRole: MemberRole?,
    @ColumnInfo(name = "is_archived", defaultValue = "0") val isArchived: Boolean = false,
    @ColumnInfo(name = "left_at") val leftAt: Long?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "conversation_members",
    primaryKeys = ["conversation_id", "user_id"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("user_id")],
)
data class ConversationMemberEntity(
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "user_id") val userId: String,
    val role: MemberRole,
    @ColumnInfo(name = "joined_at") val joinedAt: Long,
    @ColumnInfo(name = "last_read_seq", defaultValue = "0") val lastReadSeq: Long = 0,
    @ColumnInfo(name = "left_at") val leftAt: Long?,
)

/**
 * Сообщение.
 *
 * Ключевые инварианты, заданные на уровне схемы:
 *  - уникальность (conversation_id, client_msg_id) — защита от дубликатов
 *    при повторной отправке одной и той же операции;
 *  - уникальность (conversation_id, seq) для подтверждённых сообщений;
 *  - `seq = null` означает «ещё не подтверждено сервером».
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["conversation_id", "seq"]),
        Index(value = ["conversation_id", "created_at"]),
        Index(value = ["conversation_id", "client_msg_id"], unique = true),
        Index("sync_status"),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "sender_id") val senderId: String,
    @ColumnInfo(name = "client_msg_id") val clientMsgId: String,
    val seq: Long?,
    val type: MessageType,
    val body: String?,
    @ColumnInfo(name = "reply_to_id") val replyToId: String?,
    @ColumnInfo(name = "reply_to_sender_name") val replyToSenderName: String?,
    @ColumnInfo(name = "reply_to_snapshot") val replyToSnapshot: String?,
    @ColumnInfo(name = "forwarded_from_id") val forwardedFromId: String?,
    @ColumnInfo(name = "forwarded_from_sender_name") val forwardedFromSenderName: String?,
    @ColumnInfo(name = "delivery_status") val deliveryStatus: MessageDeliveryStatus,
    @ColumnInfo(name = "sync_status") val syncStatus: SyncStatus,
    @ColumnInfo(name = "pinned_at") val pinnedAt: Long?,
    @ColumnInfo(name = "edited_at") val editedAt: Long?,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "message_reactions",
    primaryKeys = ["message_id", "user_id", "emoji"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MessageReactionEntity(
    @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "user_id") val userId: String,
    val emoji: String,
    @ColumnInfo(name = "sync_status") val syncStatus: SyncStatus,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * Разрыв в локальной истории чата.
 *
 * Позволяет догружать только пропущенный диапазон вместо полной
 * перезагрузки переписки. Без этой таблицы единственный способ
 * «починить» историю — перезалить её целиком.
 */
@Entity(
    tableName = "message_gaps",
    primaryKeys = ["conversation_id", "from_seq"],
    indices = [Index("conversation_id")],
)
data class MessageGapEntity(
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "from_seq") val fromSeq: Long,
    @ColumnInfo(name = "to_seq") val toSeq: Long,
)

@Entity(
    tableName = "attachments",
    indices = [Index("message_id"), Index("task_id")],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "message_id") val messageId: String?,
    @ColumnInfo(name = "task_id") val taskId: String?,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "remote_url") val remoteUrl: String?,
    @ColumnInfo(name = "local_uri") val localUri: String?,
    val width: Int?,
    val height: Int?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "upload_progress") val uploadProgress: Float?,
    @ColumnInfo(name = "sync_status") val syncStatus: SyncStatus,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
