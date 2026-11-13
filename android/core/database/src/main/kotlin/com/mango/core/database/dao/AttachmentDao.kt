package com.mango.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mango.core.database.entity.AttachmentEntity
import com.mango.core.model.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {

    @Upsert
    suspend fun upsert(attachment: AttachmentEntity)

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun findById(id: String): AttachmentEntity?

    /**
     * Вложения сообщений чата.
     *
     * Соединение с сообщениями нужно не только для фильтра: Room следит
     * за обеими таблицами, и поток обновляется и при новом сообщении,
     * и при изменении прогресса загрузки.
     */
    @Query(
        """
        SELECT a.* FROM attachments a
        INNER JOIN messages m ON m.id = a.message_id
        WHERE m.conversation_id = :conversationId
        ORDER BY a.created_at, a.id
        """,
    )
    fun observeForConversation(conversationId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE message_id IN (:messageIds) ORDER BY created_at, id")
    suspend fun forMessages(messageIds: List<String>): List<AttachmentEntity>

    /**
     * Перенос вложений на серверный идентификатор сообщения.
     *
     * Локальное сообщение после подтверждения получает серверный id
     * (см. MessageDao.confirmSent); без переноса его вложения потеряли бы
     * связь с ним.
     */
    @Query("UPDATE attachments SET message_id = :toMessageId WHERE message_id = :fromMessageId")
    suspend fun moveToMessage(fromMessageId: String, toMessageId: String)

    @Query("UPDATE attachments SET upload_progress = :progress WHERE id = :id")
    suspend fun updateProgress(id: String, progress: Float?)

    /**
     * Прогресс из потока передачи OkHttp.
     *
     * Блокирующий вариант: отчёт приходит из потока записи тела запроса,
     * где нет сопрограмм. Это поток ввода-вывода, не главный.
     */
    @Query("UPDATE attachments SET upload_progress = :progress WHERE id = :id")
    fun updateProgressBlocking(id: String, progress: Float?)

    @Query("UPDATE attachments SET sync_status = :status, upload_progress = :progress WHERE id = :id")
    suspend fun updateStatus(id: String, status: SyncStatus, progress: Float?)

    /** Скачанная копия: при следующем показе файл берётся с устройства. */
    @Query("UPDATE attachments SET local_uri = :localUri WHERE id = :id")
    suspend fun setLocalUri(id: String, localUri: String?)
}
