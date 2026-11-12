package com.mango.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.mango.core.database.entity.OutboxOperationEntity
import com.mango.core.database.entity.OutboxStatus
import com.mango.core.database.entity.SyncStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {

    /**
     * Вставка операции с игнорированием дубликата по client_op_id.
     *
     * Повторный вызов с тем же clientOpId не создаёт вторую операцию —
     * это первая линия защиты от двойной отправки (вторая — на сервере).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(operation: OutboxOperationEntity): Long

    @Upsert
    suspend fun upsert(operation: OutboxOperationEntity)

    /**
     * Следующая порция операций, готовых к отправке.
     *
     * Условия: статус PENDING, время следующей попытки наступило, и операция
     * не ждёт зависимость. Порядок — по created_at: операции над одной
     * сущностью должны уезжать в том порядке, в котором их сделал пользователь.
     */
    @Query(
        """
        SELECT * FROM outbox_operations
        WHERE status = 'PENDING'
          AND next_attempt_at <= :nowMillis
          AND (
              depends_on_op_id IS NULL
              OR NOT EXISTS (
                  SELECT 1 FROM outbox_operations dep
                  WHERE dep.client_op_id = outbox_operations.depends_on_op_id
              )
          )
        ORDER BY created_at ASC
        LIMIT :limit
        """,
    )
    suspend fun nextBatch(nowMillis: Long, limit: Int = 20): List<OutboxOperationEntity>

    @Query("SELECT * FROM outbox_operations WHERE client_op_id = :clientOpId")
    suspend fun findByClientOpId(clientOpId: String): OutboxOperationEntity?

    @Query("SELECT COUNT(*) FROM outbox_operations WHERE status IN ('PENDING', 'IN_FLIGHT', 'BLOCKED')")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox_operations WHERE status = 'FAILED'")
    fun observeFailedCount(): Flow<Int>

    @Query("SELECT * FROM outbox_operations WHERE status = 'FAILED' ORDER BY created_at ASC")
    fun observeFailed(): Flow<List<OutboxOperationEntity>>

    @Query("UPDATE outbox_operations SET status = :status, updated_at = :nowMillis WHERE id = :id")
    suspend fun updateStatus(id: String, status: OutboxStatus, nowMillis: Long)

    @Query(
        """
        UPDATE outbox_operations
        SET attempt_count = attempt_count + 1,
            next_attempt_at = :nextAttemptAt,
            last_error = :error,
            status = 'PENDING',
            updated_at = :nowMillis
        WHERE id = :id
        """,
    )
    suspend fun scheduleRetry(id: String, nextAttemptAt: Long, error: String?, nowMillis: Long)

    @Query(
        """
        UPDATE outbox_operations
        SET status = 'FAILED', last_error = :error, updated_at = :nowMillis
        WHERE id = :id
        """,
    )
    suspend fun markFailed(id: String, error: String?, nowMillis: Long)

    @Query("DELETE FROM outbox_operations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM outbox_operations WHERE client_op_id = :clientOpId")
    suspend fun deleteByClientOpId(clientOpId: String)

    @Query("DELETE FROM outbox_operations WHERE entity_type = :entityType AND entity_id = :entityId")
    suspend fun deleteForEntity(entityType: String, entityId: String)

    /**
     * Восстановление очереди после перезапуска приложения.
     *
     * Операции, застрявшие в IN_FLIGHT, переводятся обратно в PENDING: процесс
     * был убит на полпути, и неизвестно, дошёл ли запрос. Повтор безопасен,
     * потому что сервер идемпотентен по client_op_id.
     */
    @Query(
        """
        UPDATE outbox_operations
        SET status = 'PENDING', next_attempt_at = :nowMillis, updated_at = :nowMillis
        WHERE status = 'IN_FLIGHT'
        """,
    )
    suspend fun recoverInFlight(nowMillis: Long): Int

    /** Разблокировка операций, чья зависимость выполнена. */
    @Query(
        """
        UPDATE outbox_operations
        SET status = 'PENDING', updated_at = :nowMillis
        WHERE status = 'BLOCKED'
          AND depends_on_op_id IS NOT NULL
          AND NOT EXISTS (
              SELECT 1 FROM outbox_operations dep
              WHERE dep.client_op_id = outbox_operations.depends_on_op_id
          )
        """,
    )
    suspend fun unblockResolved(nowMillis: Long): Int

    @Query("SELECT * FROM outbox_operations ORDER BY created_at ASC")
    suspend fun all(): List<OutboxOperationEntity>

    // --- курсоры синхронизации ---

    @Upsert
    suspend fun upsertSyncState(state: SyncStateEntity)

    @Query("SELECT * FROM sync_state WHERE scope = :scope")
    suspend fun syncState(scope: String): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE scope = :scope")
    fun observeSyncState(scope: String): Flow<SyncStateEntity?>

    @Query("UPDATE sync_state SET last_event_id = :eventId, last_synced_at = :nowMillis WHERE scope = :scope AND last_event_id < :eventId")
    suspend fun advanceCursor(scope: String, eventId: Long, nowMillis: Long)

    @Query("UPDATE sync_state SET needs_reset = :needsReset WHERE scope = :scope")
    suspend fun setNeedsReset(scope: String, needsReset: Boolean)

    /**
     * Успешное завершение операции: удаляем её из очереди и продвигаем курсор
     * одной транзакцией.
     *
     * Порядок важен. Если сначала продвинуть курсор и упасть, операция
     * останется в очереди и уедет второй раз — но это безопасно благодаря
     * идемпотентности. Обратный случай (операция удалена, курсор не продвинут)
     * привёл бы к повторной обработке уже применённого события.
     */
    @Transaction
    suspend fun completeOperation(
        operationId: String,
        scope: String,
        eventId: Long?,
        nowMillis: Long,
    ) {
        delete(operationId)
        if (eventId != null) {
            advanceCursor(scope, eventId, nowMillis)
        }
        unblockResolved(nowMillis)
    }
}
