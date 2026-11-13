package com.mango.core.data.repository

import com.mango.core.common.Outcome
import com.mango.core.model.ClientOpId
import com.mango.core.model.Conversation
import com.mango.core.model.ConversationId
import com.mango.core.model.Message
import com.mango.core.model.MessageId
import com.mango.core.model.Task
import com.mango.core.model.TaskId
import com.mango.core.model.TaskScope
import com.mango.core.model.TaskStatus
import com.mango.core.model.User
import com.mango.core.model.UserId
import kotlinx.coroutines.flow.Flow

/**
 * Контракты репозиториев.
 *
 * Все методы чтения возвращают [Flow] из локальной базы — это и есть
 * offline-first: экран подписывается на Room и получает данные сразу,
 * а синхронизация обновляет ту же базу в фоне.
 *
 * Все методы записи возвращают [Outcome] и считаются успешными, если
 * изменение сохранено ЛОКАЛЬНО и поставлено в очередь. Доставка на сервер —
 * отдельный, асинхронный процесс, и экран не должен её ждать.
 */
interface ConversationRepository {
    fun observeConversations(): Flow<List<Conversation>>
    fun observeConversation(id: ConversationId): Flow<Conversation?>
    fun observeTotalUnread(): Flow<Int>
    fun observeMembers(id: ConversationId): Flow<List<User>>

    suspend fun saveDraft(id: ConversationId, draft: String?): Outcome<Unit>
    suspend fun markRead(id: ConversationId, seq: Long): Outcome<Unit>
    suspend fun setMuted(id: ConversationId, mutedUntilMillis: Long?): Outcome<Unit>

    /**
     * Загружает список чатов с сервера в локальную базу.
     *
     * Интерфейс при этом не ждёт ответа: он подписан на Room и обновится
     * сам, когда данные приедут. Поэтому метод возвращает только исход
     * загрузки, а не сами чаты.
     */
    suspend fun refresh(): Outcome<Unit>

    /**
     * Открывает личную переписку с пользователем.
     *
     * Повторный вызов возвращает тот же чат: сервер не создаёт второй
     * личный чат между теми же людьми.
     */
    suspend fun openDirect(userId: UserId): Outcome<ConversationId>

    suspend fun loadMembers(id: ConversationId): Outcome<Unit>

    /** Участники с ролями — для экрана группы. */
    fun observeMembersWithRoles(id: ConversationId): Flow<List<com.mango.core.model.ConversationMember>>

    // Управление группой выполняется только при наличии сети: решение
    // о правах принимает сервер, и оптимистичный показ «участник удалён»
    // с последующим откатом вводил бы в заблуждение.
    suspend fun createGroup(title: String, memberIds: List<UserId>): Outcome<ConversationId>
    suspend fun renameGroup(id: ConversationId, title: String): Outcome<Unit>
    suspend fun addMembers(id: ConversationId, userIds: List<UserId>): Outcome<Unit>
    suspend fun removeMember(id: ConversationId, userId: UserId): Outcome<Unit>
    suspend fun changeRole(id: ConversationId, userId: UserId, role: com.mango.core.model.MemberRole): Outcome<Unit>

    /** Выход из группы; чат удаляется с устройства. */
    suspend fun leaveGroup(id: ConversationId): Outcome<Unit>

    /** Поиск людей для начала переписки. */
    suspend fun searchPeople(query: String): Outcome<List<User>>
}

interface MessageRepository {
    fun observeRecentMessages(conversationId: ConversationId, limit: Int = 50): Flow<List<Message>>
    fun observePinnedMessages(conversationId: ConversationId): Flow<List<Message>>

    /**
     * Создаёт сообщение локально и ставит отправку в очередь.
     *
     * @return идентификатор клиентской операции — по нему можно отследить
     *   состояние отправки и повторить её.
     */
    suspend fun sendText(
        conversationId: ConversationId,
        text: String,
        replyTo: MessageId? = null,
    ): Outcome<ClientOpId>

    /**
     * Отправка файла.
     *
     * Файл копируется в личный каталог приложения, сообщение сразу
     * появляется в переписке, а загрузка и отправка уходят в очередь:
     * сначала файл, затем сообщение, зависящее от него.
     */
    suspend fun sendAttachment(
        conversationId: ConversationId,
        uri: android.net.Uri,
        caption: String? = null,
    ): Outcome<ClientOpId>

    /**
     * Файл вложения на устройстве.
     *
     * Своё вложение уже лежит локально; чужое скачивается по временной
     * ссылке и запоминается, чтобы не качать повторно.
     */
    suspend fun attachmentFile(id: com.mango.core.model.AttachmentId): Outcome<java.io.File>

    suspend fun retry(clientOpId: ClientOpId): Outcome<Unit>
    suspend fun editText(id: MessageId, newText: String): Outcome<Unit>
    suspend fun delete(id: MessageId, forEveryone: Boolean): Outcome<Unit>
    suspend fun toggleReaction(id: MessageId, emoji: String): Outcome<Unit>
    suspend fun search(query: String, conversationId: ConversationId? = null): Outcome<List<Message>>

    /**
     * Догружает историю переписки с сервера.
     *
     * @param beforeSeq граница прокрутки: загружаются сообщения с меньшим
     *   номером. `null` — последняя страница.
     * @return сколько сообщений записано локально.
     */
    suspend fun loadHistory(
        conversationId: ConversationId,
        beforeSeq: Long? = null,
        limit: Int = 50,
    ): Outcome<Int>
}

interface TaskRepository {
    fun observeTasks(scope: TaskScope, includeCompleted: Boolean = false): Flow<List<Task>>
    fun observeTask(id: TaskId): Flow<Task?>
    fun observeByConversation(id: ConversationId): Flow<List<Task>>
    fun observeBoardColumn(status: TaskStatus, conversationId: ConversationId?): Flow<List<Task>>
    fun observeDueBetween(fromMillis: Long, toMillis: Long): Flow<List<Task>>
    fun observeSubtasks(parentId: TaskId): Flow<List<Task>>

    suspend fun create(draft: NewTaskDraft): Outcome<TaskId>
    suspend fun changeStatus(id: TaskId, status: TaskStatus): Outcome<Unit>
    suspend fun edit(id: TaskId, title: String, description: String?, dueAtMillis: Long?): Outcome<Unit>
    suspend fun delete(id: TaskId): Outcome<Unit>

    /**
     * Реакция на удаление сообщения: задача сохраняет снапшот текста,
     * но помечается как потерявшая связь с источником.
     */
    suspend fun onSourceMessageDeleted(messageId: MessageId): Outcome<Unit>
}

/** Данные для создания задачи, в том числе из сообщения. */
data class NewTaskDraft(
    val title: String,
    val description: String? = null,
    val priority: com.mango.core.model.TaskPriority = com.mango.core.model.TaskPriority.MEDIUM,
    val dueAtMillis: Long? = null,
    val conversationId: ConversationId? = null,
    val assigneeIds: List<com.mango.core.model.UserId> = emptyList(),
    val parentTaskId: TaskId? = null,
    /** Сообщение-источник: сохраняются id и снапшот текста. */
    val sourceMessageId: MessageId? = null,
    val sourceSenderName: String? = null,
    val sourceTextSnapshot: String? = null,
)

interface UserRepository {
    fun observeCurrentUser(): Flow<User?>
    fun observeContacts(): Flow<List<User>>
    fun observeSearch(query: String): Flow<List<User>>
    suspend fun currentUserId(): com.mango.core.model.UserId?
}

/** Сводное состояние синхронизации для индикатора в интерфейсе. */
data class SyncSnapshot(
    val pendingOperations: Int,
    val failedOperations: Int,
    val lastSyncedAtMillis: Long?,
) {
    val isFullySynced: Boolean get() = pendingOperations == 0 && failedOperations == 0
    val hasProblems: Boolean get() = failedOperations > 0
}

interface SyncStatusRepository {
    fun observeSnapshot(): Flow<SyncSnapshot>

    /**
     * Восстановление очереди после перезапуска процесса.
     *
     * Вызывается один раз при старте приложения: операции, застрявшие
     * в IN_FLIGHT, возвращаются в PENDING, потому что неизвестно, дошёл
     * ли запрос до сервера. Повтор безопасен благодаря идемпотентности.
     */
    suspend fun recoverAfterRestart(): Int
}
