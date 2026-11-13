package com.mango.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.mango.core.database.entity.MessageEntity
import com.mango.core.database.entity.MessageGapEntity
import com.mango.core.database.entity.MessageReactionEntity
import com.mango.core.model.MessageDeliveryStatus
import com.mango.core.model.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    /**
     * Страница истории для Paging 3.
     *
     * Сортировка — по `seq DESC`, а неподтверждённые сообщения (`seq IS NULL`)
     * идут первыми: они только что отправлены и должны быть внизу переписки
     * (список рисуется реверсивно).
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversation_id = :conversationId AND deleted_at IS NULL
        ORDER BY (seq IS NULL) DESC, seq DESC, created_at DESC
        """,
    )
    fun pagingSource(conversationId: String): PagingSource<Int, MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE conversation_id = :conversationId
        ORDER BY (seq IS NULL) DESC, seq DESC, created_at DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(conversationId: String, limit: Int = 50): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun findById(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId AND client_msg_id = :clientMsgId")
    suspend fun findByClientMsgId(conversationId: String, clientMsgId: String): MessageEntity?

    /**
     * Вставка без перезаписи.
     *
     * IGNORE, а не REPLACE: REPLACE удалил бы строку и вставил новую, что
     * каскадно снесло бы реакции (ON DELETE CASCADE) и сбросило бы локальные
     * поля вроде sync_status.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(message: MessageEntity): Long

    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Upsert
    suspend fun upsertAll(messages: List<MessageEntity>)

    /**
     * Применение серверного подтверждения к локально созданному сообщению.
     *
     * Сопоставление идёт по client_msg_id, поэтому повторный ответ сервера
     * на ту же операцию не создаёт второе сообщение.
     */
    @Query(
        """
        UPDATE messages
        SET id = :serverId,
            seq = :seq,
            delivery_status = :deliveryStatus,
            sync_status = :syncStatus,
            created_at = :serverCreatedAt
        WHERE conversation_id = :conversationId AND client_msg_id = :clientMsgId
        """,
    )
    suspend fun confirmSent(
        conversationId: String,
        clientMsgId: String,
        serverId: String,
        seq: Long,
        serverCreatedAt: Long,
        deliveryStatus: MessageDeliveryStatus = MessageDeliveryStatus.SENT,
        syncStatus: SyncStatus = SyncStatus.SYNCED,
    ): Int

    @Query("UPDATE messages SET delivery_status = :status, sync_status = :syncStatus WHERE id = :id")
    suspend fun updateStatus(id: String, status: MessageDeliveryStatus, syncStatus: SyncStatus)

    @Query("UPDATE messages SET body = :body, edited_at = :editedAt, sync_status = :syncStatus WHERE id = :id")
    suspend fun applyEdit(id: String, body: String, editedAt: Long, syncStatus: SyncStatus)

    @Query("UPDATE messages SET deleted_at = :deletedAt, body = NULL, sync_status = :syncStatus WHERE id = :id")
    suspend fun markDeleted(id: String, deletedAt: Long, syncStatus: SyncStatus)

    @Query("UPDATE messages SET pinned_at = :pinnedAt WHERE id = :id")
    suspend fun setPinned(id: String, pinnedAt: Long?)

    @Query("SELECT MAX(seq) FROM messages WHERE conversation_id = :conversationId")
    suspend fun maxSeq(conversationId: String): Long?

    @Query("SELECT MIN(seq) FROM messages WHERE conversation_id = :conversationId AND seq IS NOT NULL")
    suspend fun minSeq(conversationId: String): Long?

    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE conversation_id = :conversationId
          AND seq > :lastReadSeq
          AND deleted_at IS NULL
          AND type != 'SYSTEM'
          AND sender_id != :currentUserId
        """,
    )
    suspend fun countUnread(conversationId: String, lastReadSeq: Long, currentUserId: String): Int

    /** Сообщения, которые не доехали до сервера — восстанавливаются при старте. */
    @Query("SELECT * FROM messages WHERE sync_status IN ('PENDING', 'IN_FLIGHT') ORDER BY created_at ASC")
    suspend fun findUnsent(): List<MessageEntity>

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId AND pinned_at IS NOT NULL ORDER BY pinned_at DESC")
    fun observePinned(conversationId: String): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT * FROM messages
        WHERE deleted_at IS NULL AND body LIKE '%' || :query || '%'
          AND (:conversationId IS NULL OR conversation_id = :conversationId)
        ORDER BY created_at DESC
        LIMIT :limit
        """,
    )
    suspend fun search(query: String, conversationId: String?, limit: Int = 100): List<MessageEntity>

    // --- реакции ---

    @Upsert
    suspend fun upsertReaction(reaction: MessageReactionEntity)

    @Query("DELETE FROM message_reactions WHERE message_id = :messageId AND user_id = :userId AND emoji = :emoji")
    suspend fun deleteReaction(messageId: String, userId: String, emoji: String)

    @Query("SELECT * FROM message_reactions WHERE message_id IN (:messageIds)")
    suspend fun reactionsFor(messageIds: List<String>): List<MessageReactionEntity>

    @Query("SELECT * FROM message_reactions WHERE message_id = :messageId")
    fun observeReactions(messageId: String): Flow<List<MessageReactionEntity>>

    // --- разрывы истории ---

    @Upsert
    suspend fun upsertGap(gap: MessageGapEntity)

    @Query("SELECT * FROM message_gaps WHERE conversation_id = :conversationId ORDER BY from_seq DESC")
    suspend fun gapsFor(conversationId: String): List<MessageGapEntity>

    @Query("DELETE FROM message_gaps WHERE conversation_id = :conversationId AND from_seq = :fromSeq")
    suspend fun deleteGap(conversationId: String, fromSeq: Long)

    /**
     * Запись пачки серверных сообщений вместе с закрытием разрыва — одной транзакцией.
     *
     * Если записать сообщения и упасть до удаления разрыва, при следующем запуске
     * диапазон просто загрузится ещё раз (операция идемпотентна). Обратный порядок
     * был бы опасен: разрыв закрыт, а сообщений нет.
     */
    @Transaction
    suspend fun applyServerPage(
        messages: List<MessageEntity>,
        closedGap: MessageGapEntity?,
    ) {
        upsertAll(messages)
        if (closedGap != null) {
            deleteGap(closedGap.conversationId, closedGap.fromSeq)
        }
    }
}
