package com.mango.core.model

import java.time.Instant

data class Task(
    val id: TaskId,
    val title: String,
    val description: String? = null,
    val status: TaskStatus,
    val priority: TaskPriority,
    val createdBy: UserId,
    val assignees: List<User> = emptyList(),
    val conversationId: ConversationId? = null,
    val conversationTitle: String? = null,
    /** Ссылка на сообщение, из которого создана задача. */
    val source: TaskSource? = null,
    val parentTaskId: TaskId? = null,
    val subtaskCount: Int = 0,
    val completedSubtaskCount: Int = 0,
    val commentCount: Int = 0,
    val attachments: List<Attachment> = emptyList(),
    val dueAt: Instant? = null,
    val remindAt: Instant? = null,
    /** Версия объекта для оптимистичной блокировки при PATCH. */
    val version: Long = 1,
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
    val completedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val isOverdue: Boolean
        get() = dueAt != null && !status.isTerminal && dueAt.isBefore(Instant.now())

    val hasSubtasks: Boolean get() = subtaskCount > 0

    val subtaskProgress: Float
        get() = if (subtaskCount == 0) 0f else completedSubtaskCount.toFloat() / subtaskCount
}

/**
 * Связь задачи с исходным сообщением.
 *
 * [textSnapshot] и [senderName] сохраняются на момент создания задачи.
 * Если сообщение удалят, задача не теряет контекст — она лишь помечается
 * как [isSourceUnavailable], и переход к сообщению блокируется.
 */
data class TaskSource(
    val messageId: MessageId,
    val conversationId: ConversationId,
    val senderName: String,
    val textSnapshot: String?,
    val createdAt: Instant,
    val isSourceUnavailable: Boolean = false,
)

data class TaskComment(
    val id: CommentId,
    val taskId: TaskId,
    val author: User,
    val body: String,
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
    val editedAt: Instant? = null,
    val createdAt: Instant,
)

/** Запись в истории изменений задачи. */
data class TaskEvent(
    val id: Long,
    val taskId: TaskId,
    val actor: User?,
    val type: Type,
    val field: String? = null,
    val oldValue: String? = null,
    val newValue: String? = null,
    val createdAt: Instant,
) {
    enum class Type {
        CREATED,
        STATUS_CHANGED,
        PRIORITY_CHANGED,
        ASSIGNED,
        UNASSIGNED,
        DUE_DATE_CHANGED,
        TITLE_CHANGED,
        DESCRIPTION_CHANGED,
        COMMENT_ADDED,
        ATTACHMENT_ADDED,
        DELETED,
        RESTORED,
    }
}

/** Фильтр для экранов списка задач. */
data class TaskFilter(
    val scope: TaskScope = TaskScope.MINE,
    val statuses: Set<TaskStatus> = emptySet(),
    val priorities: Set<TaskPriority> = emptySet(),
    val assigneeIds: Set<UserId> = emptySet(),
    val conversationId: ConversationId? = null,
    val dueBefore: Instant? = null,
    val query: String = "",
    val includeCompleted: Boolean = false,
) {
    val isEmpty: Boolean
        get() = statuses.isEmpty() &&
            priorities.isEmpty() &&
            assigneeIds.isEmpty() &&
            conversationId == null &&
            dueBefore == null &&
            query.isBlank()
}
