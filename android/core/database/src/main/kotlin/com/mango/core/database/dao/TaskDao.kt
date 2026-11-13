package com.mango.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.mango.core.database.entity.TaskAssigneeEntity
import com.mango.core.database.entity.TaskCommentEntity
import com.mango.core.database.entity.TaskEntity
import com.mango.core.database.entity.TaskEventEntity
import com.mango.core.model.SyncStatus
import com.mango.core.model.TaskStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Query("SELECT * FROM tasks WHERE id = :id AND deleted_at IS NULL")
    fun observeById(id: String): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun findById(id: String): TaskEntity?

    /** Мои задачи: созданные мной либо назначенные мне. */
    @Query(
        """
        SELECT DISTINCT t.* FROM tasks t
        LEFT JOIN task_assignees a ON a.task_id = t.id AND a.removed_at IS NULL
        WHERE t.deleted_at IS NULL
          AND (t.created_by = :currentUserId OR a.user_id = :currentUserId)
          AND (:includeCompleted = 1 OR t.status NOT IN ('DONE', 'CANCELLED'))
        ORDER BY
            CASE WHEN t.due_at IS NULL THEN 1 ELSE 0 END,
            t.due_at ASC,
            t.created_at DESC
        """,
    )
    fun observeMyTasks(currentUserId: String, includeCompleted: Boolean): Flow<List<TaskEntity>>

    /** Задачи команды: всё, что видно в чатах, где я участвую. */
    @Query(
        """
        SELECT t.* FROM tasks t
        WHERE t.deleted_at IS NULL
          AND t.conversation_id IS NOT NULL
          AND (:includeCompleted = 1 OR t.status NOT IN ('DONE', 'CANCELLED'))
        ORDER BY
            CASE WHEN t.due_at IS NULL THEN 1 ELSE 0 END,
            t.due_at ASC,
            t.created_at DESC
        """,
    )
    fun observeTeamTasks(includeCompleted: Boolean): Flow<List<TaskEntity>>

    /** Задачи, созданные из сообщений — отдельный экран «Задачи из чатов». */
    @Query(
        """
        SELECT * FROM tasks
        WHERE deleted_at IS NULL AND source_message_id IS NOT NULL
        ORDER BY created_at DESC
        """,
    )
    fun observeTasksFromChats(): Flow<List<TaskEntity>>

    @Query(
        """
        SELECT * FROM tasks
        WHERE deleted_at IS NULL AND conversation_id = :conversationId
        ORDER BY status, priority, created_at DESC
        """,
    )
    fun observeByConversation(conversationId: String): Flow<List<TaskEntity>>

    /** Срез для Kanban-доски: одна колонка. */
    @Query(
        """
        SELECT * FROM tasks
        WHERE deleted_at IS NULL AND status = :status
          AND (:conversationId IS NULL OR conversation_id = :conversationId)
        ORDER BY priority, created_at DESC
        """,
    )
    fun observeByStatus(status: TaskStatus, conversationId: String?): Flow<List<TaskEntity>>

    /** Задачи с дедлайном в диапазоне — для экрана календаря. */
    @Query(
        """
        SELECT * FROM tasks
        WHERE deleted_at IS NULL AND due_at IS NOT NULL
          AND due_at BETWEEN :fromMillis AND :toMillis
        ORDER BY due_at ASC
        """,
    )
    fun observeDueBetween(fromMillis: Long, toMillis: Long): Flow<List<TaskEntity>>

    @Query(
        """
        SELECT * FROM tasks
        WHERE deleted_at IS NULL
          AND (title LIKE '%' || :query || '%' OR description LIKE '%' || :query || '%')
        ORDER BY created_at DESC
        LIMIT :limit
        """,
    )
    fun observeSearch(query: String, limit: Int = 100): Flow<List<TaskEntity>>

    @Upsert
    suspend fun upsert(task: TaskEntity)

    @Upsert
    suspend fun upsertAll(tasks: List<TaskEntity>)

    @Query("UPDATE tasks SET status = :status, updated_at = :updatedAt, sync_status = :syncStatus, completed_at = :completedAt WHERE id = :id")
    suspend fun updateStatus(
        id: String,
        status: TaskStatus,
        updatedAt: Long,
        syncStatus: SyncStatus,
        completedAt: Long?,
    )

    @Query("UPDATE tasks SET sync_status = :syncStatus WHERE id = :id")
    suspend fun updateSyncStatus(id: String, syncStatus: SyncStatus)

    @Query("UPDATE tasks SET field_versions_json = :json WHERE id = :id")
    suspend fun updateFieldVersions(id: String, json: String)

    @Query("UPDATE tasks SET deleted_at = :deletedAt, sync_status = :syncStatus WHERE id = :id")
    suspend fun markDeleted(id: String, deletedAt: Long, syncStatus: SyncStatus)

    /**
     * Помечает задачи, чьё исходное сообщение удалено.
     *
     * Текст-снапшот при этом НЕ стирается: задача должна остаться читаемой,
     * просто переход к сообщению становится недоступен.
     */
    @Query("UPDATE tasks SET source_unavailable = 1 WHERE source_message_id = :messageId")
    suspend fun markSourceUnavailable(messageId: String)

    @Query("SELECT * FROM tasks WHERE sync_status IN ('PENDING', 'IN_FLIGHT')")
    suspend fun findUnsynced(): List<TaskEntity>

    @Query("SELECT COUNT(*) FROM tasks WHERE deleted_at IS NULL AND parent_task_id = :parentId")
    suspend fun countSubtasks(parentId: String): Int

    @Query("SELECT COUNT(*) FROM tasks WHERE deleted_at IS NULL AND parent_task_id = :parentId AND status = 'DONE'")
    suspend fun countCompletedSubtasks(parentId: String): Int

    @Query("SELECT * FROM tasks WHERE deleted_at IS NULL AND parent_task_id = :parentId ORDER BY created_at ASC")
    fun observeSubtasks(parentId: String): Flow<List<TaskEntity>>

    // --- исполнители (OR-Set) ---

    @Upsert
    suspend fun upsertAssignee(assignee: TaskAssigneeEntity)

    @Upsert
    suspend fun upsertAssignees(assignees: List<TaskAssigneeEntity>)

    @Query("SELECT * FROM task_assignees WHERE task_id = :taskId AND removed_at IS NULL")
    suspend fun assigneesOf(taskId: String): List<TaskAssigneeEntity>

    @Query(
        """
        SELECT u.* FROM users u
        INNER JOIN task_assignees a ON a.user_id = u.id
        WHERE a.task_id = :taskId AND a.removed_at IS NULL
        ORDER BY u.display_name
        """,
    )
    fun observeAssigneeUsers(taskId: String): Flow<List<com.mango.core.database.entity.UserEntity>>

    @Query("UPDATE task_assignees SET removed_at = :removedAt, sync_status = :syncStatus WHERE task_id = :taskId AND user_id = :userId")
    suspend fun markAssigneeRemoved(taskId: String, userId: String, removedAt: Long, syncStatus: SyncStatus)

    @Query("DELETE FROM task_assignees WHERE task_id = :taskId AND user_id = :userId")
    suspend fun deleteAssignee(taskId: String, userId: String)

    /** Все строки исполнителей задачи, включая снятых и неотправленных. */
    @Query("SELECT * FROM task_assignees WHERE task_id = :taskId")
    suspend fun allAssigneesOf(taskId: String): List<TaskAssigneeEntity>

    /**
     * Удаление локальной копии комментария перед записью серверной.
     *
     * Локальная и серверная копии имеют разные идентификаторы, но один
     * клиентский ключ, а ключ уникален в пределах задачи. Без удаления
     * запись серверной копии упала бы на ограничении уникальности.
     */
    @Query("DELETE FROM task_comments WHERE task_id = :taskId AND client_op_id = :clientOpId AND id != :keepId")
    suspend fun deleteLocalCommentCopy(taskId: String, clientOpId: String, keepId: String)

    // --- комментарии ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCommentIgnore(comment: TaskCommentEntity): Long

    @Upsert
    suspend fun upsertComment(comment: TaskCommentEntity)

    @Query("SELECT * FROM task_comments WHERE task_id = :taskId AND deleted_at IS NULL ORDER BY created_at ASC")
    fun observeComments(taskId: String): Flow<List<TaskCommentEntity>>

    @Query("SELECT COUNT(*) FROM task_comments WHERE task_id = :taskId AND deleted_at IS NULL")
    suspend fun countComments(taskId: String): Int

    @Query("SELECT * FROM task_comments WHERE task_id = :taskId AND client_op_id = :clientOpId")
    suspend fun findCommentByClientOpId(taskId: String, clientOpId: String): TaskCommentEntity?

    // --- история изменений ---

    @Insert
    suspend fun insertEvent(event: TaskEventEntity): Long

    @Insert
    suspend fun insertEvents(events: List<TaskEventEntity>)

    @Query("SELECT * FROM task_events WHERE task_id = :taskId ORDER BY created_at DESC, localId DESC")
    fun observeEvents(taskId: String): Flow<List<TaskEventEntity>>

    /**
     * Применение серверной версии задачи вместе с записью в историю — одной транзакцией.
     *
     * Если история пишется отдельно, то при падении между вызовами задача
     * изменится «молча», и конфликт станет неразбираемым.
     */
    @Transaction
    suspend fun applyServerTask(
        task: TaskEntity,
        assignees: List<TaskAssigneeEntity>,
        events: List<TaskEventEntity>,
    ) {
        upsert(task)
        if (assignees.isNotEmpty()) upsertAssignees(assignees)
        if (events.isNotEmpty()) insertEvents(events)
    }
}
