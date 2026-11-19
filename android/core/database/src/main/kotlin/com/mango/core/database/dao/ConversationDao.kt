package com.mango.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.mango.core.database.entity.ConversationEntity
import com.mango.core.database.entity.ConversationMemberEntity
import com.mango.core.database.entity.MessageEntity
import com.mango.core.model.MemberRole
import kotlinx.coroutines.flow.Flow

/**
 * Строка списка чатов вместе с последним сообщением и числом непрочитанных.
 *
 * Считается одним запросом в SQLite, а не N+1 обращениями из Kotlin:
 * при 200 чатах разница между одним запросом и 400 — это разница между
 * мгновенным открытием экрана и заметным лагом.
 */
data class ConversationWithPreview(
    @Embedded val conversation: ConversationEntity,
    @Embedded(prefix = "msg_") val lastMessage: MessageEntity?,
    @Embedded(prefix = "sender_") val lastMessageSender: LastMessageSender?,
    val unreadCount: Int,
)

data class MemberWithUser(
    @androidx.room.Embedded val user: com.mango.core.database.entity.UserEntity,
    @androidx.room.ColumnInfo(name = "member_role") val role: MemberRole,
    @androidx.room.ColumnInfo(name = "member_joined_at") val joinedAt: Long,
)

data class LastMessageSender(
    val id: String?,
    @androidx.room.ColumnInfo(name = "display_name") val displayName: String?,
)

@Dao
interface ConversationDao {

    @Query(
        """
        SELECT
            c.*,
            m.id                 AS msg_id,
            m.conversation_id    AS msg_conversation_id,
            m.sender_id          AS msg_sender_id,
            m.client_msg_id      AS msg_client_msg_id,
            m.seq                AS msg_seq,
            m.type               AS msg_type,
            m.body               AS msg_body,
            m.reply_to_id        AS msg_reply_to_id,
            m.reply_to_sender_name AS msg_reply_to_sender_name,
            m.reply_to_snapshot  AS msg_reply_to_snapshot,
            m.forwarded_from_id  AS msg_forwarded_from_id,
            m.forwarded_from_sender_name AS msg_forwarded_from_sender_name,
            m.delivery_status    AS msg_delivery_status,
            m.sync_status        AS msg_sync_status,
            m.pinned_at          AS msg_pinned_at,
            m.edited_at          AS msg_edited_at,
            m.deleted_at         AS msg_deleted_at,
            m.created_at         AS msg_created_at,
            u.id                 AS sender_id,
            u.display_name       AS sender_display_name,
            (
                SELECT COUNT(*) FROM messages um
                WHERE um.conversation_id = c.id
                  AND um.seq > c.last_read_seq
                  AND um.deleted_at IS NULL
                  AND um.type != 'SYSTEM'
                  AND um.sender_id != :currentUserId
            ) AS unreadCount
        FROM conversations c
        LEFT JOIN messages m ON m.id = (
            SELECT id FROM messages
            WHERE conversation_id = c.id AND deleted_at IS NULL
            ORDER BY (seq IS NULL) DESC, seq DESC, created_at DESC
            LIMIT 1
        )
        LEFT JOIN users u ON u.id = m.sender_id
        WHERE c.left_at IS NULL AND c.is_archived = 0
        ORDER BY COALESCE(m.created_at, c.updated_at) DESC
        """,
    )
    fun observeConversationList(currentUserId: String): Flow<List<ConversationWithPreview>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeById(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun findById(id: String): ConversationEntity?

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Upsert
    suspend fun upsertAll(conversations: List<ConversationEntity>)

    @Query("UPDATE conversations SET draft = :draft, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateDraft(id: String, draft: String?, updatedAt: Long)

    @Query("UPDATE conversations SET last_read_seq = MAX(last_read_seq, :seq) WHERE id = :id")
    suspend fun advanceLastReadSeq(id: String, seq: Long)

    @Query("UPDATE conversations SET last_event_seq = MAX(last_event_seq, :seq) WHERE id = :id")
    suspend fun advanceLastEventSeq(id: String, seq: Long)

    @Query("UPDATE conversations SET muted_until = :mutedUntil WHERE id = :id")
    suspend fun setMuted(id: String, mutedUntil: Long?)

    @Query("UPDATE conversations SET left_at = :leftAt WHERE id = :id")
    suspend fun markLeft(id: String, leftAt: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT SUM(CASE WHEN muted_until IS NULL THEN 1 ELSE 0 END) FROM conversations WHERE left_at IS NULL")
    fun observeActiveCount(): Flow<Int?>

    /** Суммарное число непрочитанных для бейджа в нижней навигации. */
    @Query(
        """
        SELECT COALESCE(SUM(cnt), 0) FROM (
            SELECT (
                SELECT COUNT(*) FROM messages m
                WHERE m.conversation_id = c.id
                  AND m.seq > c.last_read_seq
                  AND m.deleted_at IS NULL
                  AND m.type != 'SYSTEM'
                  AND m.sender_id != :currentUserId
            ) AS cnt
            FROM conversations c
            WHERE c.left_at IS NULL AND c.muted_until IS NULL
        )
        """,
    )
    fun observeTotalUnread(currentUserId: String): Flow<Int>

    // --- участники ---

    @Upsert
    suspend fun upsertMembers(members: List<ConversationMemberEntity>)

    @Query(
        """
        SELECT u.* FROM users u
        INNER JOIN conversation_members cm ON cm.user_id = u.id
        WHERE cm.conversation_id = :conversationId AND cm.left_at IS NULL
        ORDER BY cm.role, u.display_name
        """,
    )
    fun observeMemberUsers(conversationId: String): Flow<List<com.mango.core.database.entity.UserEntity>>

    @Query("SELECT * FROM conversation_members WHERE conversation_id = :conversationId AND left_at IS NULL")
    suspend fun membersOf(conversationId: String): List<ConversationMemberEntity>

    /** Участники с ролями — для экрана группы. Владелец и администраторы первыми. */
    @Query(
        """
        SELECT u.*, cm.role AS member_role, cm.joined_at AS member_joined_at
        FROM users u
        INNER JOIN conversation_members cm ON cm.user_id = u.id
        WHERE cm.conversation_id = :conversationId AND cm.left_at IS NULL
        ORDER BY CASE cm.role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END, u.display_name
        """,
    )
    fun observeMembers(conversationId: String): Flow<List<MemberWithUser>>

    /** Состав с сервера заменяет локальный целиком: выбывшие удаляются. */
    @Query("DELETE FROM conversation_members WHERE conversation_id = :conversationId AND user_id NOT IN (:keep)")
    suspend fun deleteMembersExcept(conversationId: String, keep: List<String>)

    @Query("DELETE FROM conversation_members WHERE conversation_id = :conversationId AND user_id = :userId")
    suspend fun deleteMember(conversationId: String, userId: String)

    @Query("SELECT role FROM conversation_members WHERE conversation_id = :conversationId AND user_id = :userId AND left_at IS NULL")
    suspend fun roleOf(conversationId: String, userId: String): MemberRole?

    @Query("UPDATE conversation_members SET left_at = :leftAt WHERE conversation_id = :conversationId AND user_id = :userId")
    suspend fun markMemberLeft(conversationId: String, userId: String, leftAt: Long)

    @Query("UPDATE conversation_members SET role = :role WHERE conversation_id = :conversationId AND user_id = :userId")
    suspend fun updateMemberRole(conversationId: String, userId: String, role: MemberRole)

    /**
     * Отметка прочтения конкретного участника.
     *
     * Нужна, чтобы показать «прочитано» под своим сообщением. Значение
     * только растёт: запоздавшее событие не должно откатывать отметку.
     */
    @Query(
        """
        UPDATE conversation_members
        SET last_read_seq = MAX(last_read_seq, :seq)
        WHERE conversation_id = :conversationId AND user_id = :userId
        """,
    )
    suspend fun advanceMemberReadSeq(conversationId: String, userId: String, seq: Long)

    /**
     * Поиск существующего личного чата с пользователем.
     *
     * Нужен, чтобы повторное открытие переписки не создавало второй DIRECT-чат.
     */
    @Query(
        """
        SELECT c.* FROM conversations c
        INNER JOIN conversation_members me    ON me.conversation_id = c.id AND me.user_id = :currentUserId
        INNER JOIN conversation_members other ON other.conversation_id = c.id AND other.user_id = :otherUserId
        WHERE c.type = 'DIRECT' AND me.left_at IS NULL AND other.left_at IS NULL
        LIMIT 1
        """,
    )
    suspend fun findDirectConversation(currentUserId: String, otherUserId: String): ConversationEntity?

    /** Есть ли по чату отметка прочтения, которую сервер ещё не получил. */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM outbox_operations
            WHERE op_type = 'MARK_READ' AND entity_id = :conversationId
              AND status IN ('PENDING', 'IN_FLIGHT', 'BLOCKED')
        )
        """,
    )
    suspend fun hasUnsentReadReceipt(conversationId: String): Boolean

    /**
     * Запись серверной карточки чата с сохранением локальных полей.
     *
     * Черновик, архив и счётчик закреплённых живут только на устройстве.
     * Запись карточки целиком затирала бы их при каждом обновлении списка
     * чатов и каждом событии об изменении группы.
     */
    @Transaction
    suspend fun upsertFromServer(conversation: ConversationEntity) {
        val existing = findById(conversation.id)
        upsert(
            if (existing == null) {
                conversation
            } else {
                conversation.copy(
                    draft = existing.draft,
                    isArchived = existing.isArchived,
                    pinnedMessageCount = existing.pinnedMessageCount,
                    // Локальный курсор опережает серверный, пока отметка
                    // прочтения ждёт в очереди: карточка, полученная до её
                    // доставки, не должна возвращать прочитанное в непрочитанное.
                    // Без такой операции источник истины — сервер: иначе после
                    // смены аккаунта без очистки базы остался бы чужой курсор.
                    lastReadSeq = if (hasUnsentReadReceipt(conversation.id)) {
                        maxOf(existing.lastReadSeq, conversation.lastReadSeq)
                    } else {
                        conversation.lastReadSeq
                    },
                )
            },
        )
    }

    /** Запись чата вместе с участниками одной транзакцией. */
    @Transaction
    suspend fun upsertWithMembers(
        conversation: ConversationEntity,
        members: List<ConversationMemberEntity>,
    ) {
        upsert(conversation)
        upsertMembers(members)
    }
}
