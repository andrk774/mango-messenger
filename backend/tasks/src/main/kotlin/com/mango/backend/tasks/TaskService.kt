package com.mango.backend.tasks

import com.mango.backend.common.ApiJson
import com.mango.backend.common.AppException
import com.mango.backend.common.Database
import com.mango.backend.common.IdGenerator
import com.mango.backend.common.TimeSource
import com.mango.backend.common.validate
import com.mango.backend.sync.EventBus
import com.mango.backend.sync.EventLog
import com.mango.backend.sync.EventScope
import com.mango.backend.sync.OutgoingEvent
import com.mango.backend.sync.StoredEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.sql.Connection
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/** Типы событий журнала для задач. */
object TaskEventTypes {
    const val TASK_CREATED = "TASK_CREATED"
    const val TASK_UPDATED = "TASK_UPDATED"
    const val TASK_DELETED = "TASK_DELETED"
    const val TASK_COMMENT_ADDED = "TASK_COMMENT_ADDED"
}

data class CreateResult(val task: TaskDto, val created: Boolean)
data class CommentResult(val comment: TaskCommentDto, val created: Boolean)

/**
 * Задачи.
 *
 * ## Кто что может
 *
 * | Действие | Кто |
 * |---|---|
 * | Видеть | создатель, действующие исполнители, участники чата задачи |
 * | Менять поля, исполнителей, комментировать | все, кто видит |
 * | Удалять | только создатель |
 *
 * ## Кого можно назначить
 *
 * Назначение выдаёт право видеть задачу. Поэтому исполнителем задачи чата
 * может быть только участник этого чата, а личную задачу — только сам
 * создатель. Без этого правила назначение стало бы способом показать
 * содержимое чата постороннему.
 */
class TaskService(
    private val database: Database,
    private val repository: TasksRepository,
    private val eventLog: EventLog,
    private val eventBus: EventBus,
    private val ids: IdGenerator,
    private val time: TimeSource,
) {

    // ─────────────────────────── создание ───────────────────────────

    suspend fun create(userId: UUID, request: CreateTaskRequest): CreateResult {
        validateNewTask(request.title, request.description, request.priority)
        return createInternal(
            userId = userId,
            taskId = request.taskId,
            title = request.title,
            description = request.description,
            priority = request.priority,
            dueAt = request.dueAt,
            remindAt = request.remindAt,
            conversationId = request.conversationId,
            parentTaskId = request.parentTaskId,
            assigneeIds = request.assigneeIds,
            source = null,
        )
    }

    /**
     * Задача из сообщения.
     *
     * Сохраняется ссылка на сообщение и СНИМОК его текста. Снимок переживает
     * удаление сообщения — иначе задача, созданная из переписки, потеряла
     * бы контекст, ради которого её создавали.
     */
    suspend fun createFromMessage(
        userId: UUID,
        messageId: UUID,
        request: CreateTaskFromMessageRequest,
    ): CreateResult {
        validateNewTask(request.title, request.description, request.priority)

        val snapshot = database.readOnly { connection ->
            val message = repository.messageSnapshot(connection, messageId)
                ?: throw AppException.Forbidden("Сообщение недоступно")
            // Проверка участия обязательна: иначе по идентификатору чужого
            // сообщения можно было бы вытянуть его текст в свою задачу.
            if (!repository.isConversationMember(connection, message.conversationId, userId)) {
                throw AppException.Forbidden("Сообщение недоступно")
            }
            if (message.deleted) throw AppException.Conflict("Сообщение удалено")
            message
        }

        return createInternal(
            userId = userId,
            taskId = request.taskId,
            title = request.title,
            description = request.description,
            priority = request.priority,
            dueAt = request.dueAt,
            remindAt = null,
            conversationId = snapshot.conversationId,
            parentTaskId = null,
            assigneeIds = request.assigneeIds,
            source = snapshot,
        )
    }

    private suspend fun createInternal(
        userId: UUID,
        taskId: UUID,
        title: String,
        description: String?,
        priority: String,
        dueAt: Instant?,
        remindAt: Instant?,
        conversationId: UUID?,
        parentTaskId: UUID?,
        assigneeIds: List<UUID>,
        source: MessageSnapshot?,
    ): CreateResult {
        val now = time.now()
        val nowMillis = now.toEpochMilli()

        val (result, events) = database.transaction { connection ->
            // Повтор операции создания: задача уже есть. Возвращаем её,
            // если вызывающему она видна; иначе — тот же отказ, что и для
            // несуществующей, чтобы чужой id нельзя было «прощупать».
            repository.taskOwner(connection, taskId)?.let {
                if (!repository.isVisible(connection, taskId, userId)) {
                    throw AppException.Forbidden("Задача недоступна")
                }
                val existing = repository.load(connection, listOf(taskId)).single()
                return@transaction CreateResult(existing, created = false) to emptyList<StoredEvent>()
            }

            if (conversationId != null && !repository.isConversationMember(connection, conversationId, userId)) {
                throw AppException.Forbidden("Чат недоступен")
            }
            if (parentTaskId != null && !repository.isVisible(connection, parentTaskId, userId)) {
                throw AppException.Forbidden("Родительская задача недоступна")
            }
            checkAssignees(connection, conversationId, userId, assigneeIds)

            val snapshotJson = source?.let {
                ApiJson.encodeToString(
                    JsonObject.serializer(),
                    JsonObject(
                        buildMap {
                            put("conversationId", JsonPrimitive(it.conversationId.toString()))
                            put("senderName", JsonPrimitive(it.senderName))
                            it.text?.let { text -> put("text", JsonPrimitive(text)) }
                            put("createdAt", JsonPrimitive(it.createdAt.toString()))
                        },
                    ),
                )
            }

            repository.insertTask(
                connection = connection,
                id = taskId,
                createdBy = userId,
                conversationId = conversationId,
                title = title.trim(),
                description = description?.trim()?.takeIf { it.isNotEmpty() },
                priority = priority,
                dueAt = dueAt,
                remindAt = remindAt,
                parentTaskId = parentTaskId,
                sourceMessageId = source?.messageId,
                sourceSnapshot = snapshotJson,
                fieldVersions = ConflictResolver.EDITABLE_FIELDS.associateWith { nowMillis },
                now = now,
            ) ?: throw AppException.Conflict("Задача с таким идентификатором уже существует")

            assigneeIds.distinct().forEach { repository.addAssignee(connection, taskId, it, userId, now) }

            repository.insertEvent(connection, taskId, userId, "CREATED", null, null, title.trim(), now)
            assigneeIds.distinct().forEach {
                repository.insertEvent(connection, taskId, userId, "ASSIGNED", "assignees", null, it.toString(), now)
            }

            val task = repository.load(connection, listOf(taskId)).single()
            val stored = appendTaskEvent(connection, task, TaskEventTypes.TASK_CREATED)
            CreateResult(task, created = true) to stored
        }

        publish(events)
        return result
    }

    // ─────────────────────────── чтение ───────────────────────────

    suspend fun get(userId: UUID, taskId: UUID): TaskDto = database.readOnly { connection ->
        if (!repository.isVisible(connection, taskId, userId)) {
            throw AppException.Forbidden("Задача недоступна")
        }
        repository.load(connection, listOf(taskId)).single()
    }

    suspend fun list(
        userId: UUID,
        scope: TaskScope,
        conversationId: UUID?,
        statuses: Set<String>,
        includeCompleted: Boolean,
        limit: Int,
    ): TasksResponse = database.readOnly { connection ->
        if (scope == TaskScope.CHAT) {
            val chat = conversationId ?: throw AppException.BadRequest("Для области chat нужен conversationId")
            if (!repository.isConversationMember(connection, chat, userId)) {
                throw AppException.Forbidden("Чат недоступен")
            }
        }
        val unknown = statuses - STATUSES
        if (unknown.isNotEmpty()) throw AppException.BadRequest("Неизвестный статус: ${unknown.first()}")

        val ids = repository.listIds(connection, userId, scope, conversationId, statuses, includeCompleted, limit)
        TasksResponse(repository.load(connection, ids))
    }

    // ─────────────────────────── изменение ───────────────────────────

    /**
     * Частичное изменение с разрешением конфликтов по полям.
     *
     * Каждое изменение — и принятое, и отклонённое — записывается в историю.
     * Отклонённая правка не исчезает молча: её можно увидеть и восстановить
     * вручную. Это и есть страховка от тихой потери данных.
     */
    suspend fun patch(userId: UUID, taskId: UUID, request: PatchTaskRequest): PatchTaskResponse {
        validate {
            check(request.changedFields.isNotEmpty(), "changedFields", "Нет изменений")
            request.changedFields.keys.forEach { field ->
                check(field in ConflictResolver.EDITABLE_FIELDS, field, "Поле не изменяется")
            }
        }
        request.changedFields.forEach { (field, value) -> validateFieldValue(field, value) }

        val now = time.now()
        val nowMillis = now.toEpochMilli()

        val (response, events) = database.transaction { connection ->
            if (!repository.isVisible(connection, taskId, userId)) {
                throw AppException.Forbidden("Задача недоступна")
            }
            // Блокировка строки: два одновременных изменения одной задачи
            // должны разрешаться последовательно, иначе оба прочитали бы
            // одни и те же метки и оба решили бы, что выиграли.
            val state = repository.lockState(connection, taskId)
                ?: throw AppException.Forbidden("Задача недоступна")

            val changes = request.changedFields.map { (field, value) ->
                FieldChange(
                    field = field,
                    value = normalize(field, value),
                    // Метка отсутствует — клиент старый или ошибся. Считаем
                    // правку сделанной сейчас: это честнее, чем отклонить её.
                    timestamp = request.fieldTimestamps[field] ?: nowMillis,
                )
            }

            val resolution = ConflictResolver.resolve(
                current = state.fields,
                currentVersions = state.fieldVersions,
                changes = changes,
                serverNow = nowMillis,
                clientSawLatest = request.baseVersion == state.version,
            )

            if (resolution.hasChanges) {
                repository.applyFields(connection, taskId, resolution.applied, resolution.fieldVersions, now)
            }

            resolution.applied.forEach { (field, value) ->
                repository.insertEvent(
                    connection, taskId, userId, historyType(field), field, state.fields[field], value, now,
                )
            }
            resolution.rejected.forEach { field ->
                val proposed = changes.first { it.field == field }.value
                repository.insertEvent(
                    connection, taskId, userId, "EDIT_REJECTED", field, state.fields[field], proposed, now,
                )
            }

            val task = repository.load(connection, listOf(taskId)).single()
            val stored = if (resolution.hasChanges) {
                appendTaskEvent(connection, task, TaskEventTypes.TASK_UPDATED)
            } else {
                emptyList()
            }
            PatchTaskResponse(task, resolution.rejected) to stored
        }

        publish(events)
        return response
    }

    /**
     * Изменение исполнителей.
     *
     * Передаются добавления и удаления, а не итоговый набор: так правки
     * с двух устройств, назначивших разных людей, складываются, а не
     * затирают друг друга.
     */
    suspend fun changeAssignees(userId: UUID, taskId: UUID, request: AssigneesRequest): TaskDto {
        val now = time.now()

        val (task, events) = database.transaction { connection ->
            if (!repository.isVisible(connection, taskId, userId)) {
                throw AppException.Forbidden("Задача недоступна")
            }
            val state = repository.lockState(connection, taskId)
                ?: throw AppException.Forbidden("Задача недоступна")

            checkAssignees(connection, state.conversationId, state.createdBy, request.add)

            // Получатели до изменения: удалённый исполнитель теряет право
            // видеть задачу, но обязан получить событие, чтобы его
            // устройство убрало её из списка.
            val before = repository.recipients(connection, taskId)

            request.add.distinct().forEach {
                repository.addAssignee(connection, taskId, it, userId, now)
                repository.insertEvent(connection, taskId, userId, "ASSIGNED", "assignees", null, it.toString(), now)
            }
            request.remove.distinct().forEach {
                repository.removeAssignee(connection, taskId, it, now)
                repository.insertEvent(connection, taskId, userId, "UNASSIGNED", "assignees", it.toString(), null, now)
            }

            val updated = repository.load(connection, listOf(taskId)).single()
            val stored = appendTaskEvent(connection, updated, TaskEventTypes.TASK_UPDATED, extraRecipients = before)
            updated to stored
        }

        publish(events)
        return task
    }

    suspend fun delete(userId: UUID, taskId: UUID) {
        val now = time.now()

        val events = database.transaction { connection ->
            // Повтор удаления автором — успех без нового события.
            // Ответ на первое удаление мог потеряться в сети, и очередь
            // клиента повторит запрос; ответ 403 на повтор выглядел бы
            // для неё как окончательный отказ, и задача на устройстве
            // осталась бы в состоянии «не удалось удалить».
            if (repository.isDeletedByAuthor(connection, taskId, userId)) {
                return@transaction emptyList<StoredEvent>()
            }
            if (!repository.isVisible(connection, taskId, userId)) {
                throw AppException.Forbidden("Задача недоступна")
            }
            val state = repository.lockState(connection, taskId)
                ?: return@transaction emptyList<StoredEvent>()
            if (state.createdBy != userId) {
                throw AppException.Forbidden("Удалить задачу может только её автор")
            }

            // Получатели определяются ДО удаления: после него правило
            // видимости уже не выполняется ни для кого.
            val recipients = repository.recipients(connection, taskId)

            repository.softDelete(connection, taskId, now)
            repository.insertEvent(connection, taskId, userId, "DELETED", null, null, null, now)

            eventLog.append(
                connection,
                listOf(
                    OutgoingEvent(
                        scope = EventScope.TASK,
                        scopeId = taskId,
                        type = TaskEventTypes.TASK_DELETED,
                        payloadJson = ApiJson.encodeToString(TaskDeletedPayload(taskId, now)),
                        recipients = recipients,
                    ),
                ),
            )
        }

        publish(events)
    }

    // ─────────────────────────── комментарии ───────────────────────────

    suspend fun addComment(userId: UUID, taskId: UUID, request: AddCommentRequest): CommentResult {
        val body = request.body.trim()
        validate {
            requireNotBlank(body, "body", "Комментарий не может быть пустым")
            requireLength(body, "body", max = MAX_COMMENT_LENGTH)
            requireNotBlank(request.clientOpId, "clientOpId", "Идентификатор операции обязателен")
            requireLength(request.clientOpId, "clientOpId", max = 64)
        }
        val now = time.now()

        val (result, events) = database.transaction { connection ->
            if (!repository.isVisible(connection, taskId, userId)) {
                throw AppException.Forbidden("Задача недоступна")
            }

            val created = repository.insertComment(
                connection, ids.newId(), taskId, userId, request.clientOpId, body, now,
            )
            val comment = repository.commentByClientOp(connection, taskId, userId, request.clientOpId)
                ?: throw AppException.Internal("Комментарий не читается после вставки")

            if (!created) {
                // Повтор: комментарий уже есть, событие уже разослано.
                return@transaction CommentResult(comment, created = false) to emptyList<StoredEvent>()
            }

            repository.insertEvent(connection, taskId, userId, "COMMENT_ADDED", null, null, comment.id.toString(), now)

            val stored = eventLog.append(
                connection,
                listOf(
                    OutgoingEvent(
                        scope = EventScope.TASK,
                        scopeId = taskId,
                        type = TaskEventTypes.TASK_COMMENT_ADDED,
                        payloadJson = ApiJson.encodeToString(TaskCommentAddedPayload(comment)),
                        recipients = repository.recipients(connection, taskId),
                    ),
                ),
            )
            CommentResult(comment, created = true) to stored
        }

        publish(events)
        return result
    }

    suspend fun comments(userId: UUID, taskId: UUID): CommentsResponse = database.readOnly { connection ->
        if (!repository.isVisible(connection, taskId, userId)) throw AppException.Forbidden("Задача недоступна")
        CommentsResponse(repository.comments(connection, taskId))
    }

    suspend fun history(userId: UUID, taskId: UUID): TaskEventsResponse = database.readOnly { connection ->
        if (!repository.isVisible(connection, taskId, userId)) throw AppException.Forbidden("Задача недоступна")
        TaskEventsResponse(repository.events(connection, taskId))
    }

    // ─────────────────────────── внутреннее ───────────────────────────

    private fun checkAssignees(
        connection: Connection,
        conversationId: UUID?,
        creatorId: UUID,
        assigneeIds: List<UUID>,
    ) {
        if (assigneeIds.isEmpty()) return

        val missing = assigneeIds.toSet() - repository.existingUsers(connection, assigneeIds)
        if (missing.isNotEmpty()) {
            throw AppException.Validation(mapOf("assigneeIds" to "Некоторые пользователи не найдены"))
        }

        if (conversationId == null) {
            // Личную задачу нельзя назначить другому: назначение выдало бы
            // ему доступ, а общего контекста, в котором это уместно, нет.
            if (assigneeIds.any { it != creatorId }) {
                throw AppException.Validation(
                    mapOf("assigneeIds" to "Личную задачу можно назначить только себе"),
                )
            }
            return
        }

        val outsiders = assigneeIds.filterNot { repository.isConversationMember(connection, conversationId, it) }
        if (outsiders.isNotEmpty()) {
            throw AppException.Validation(
                mapOf("assigneeIds" to "Исполнитель должен быть участником чата"),
            )
        }
    }

    private fun appendTaskEvent(
        connection: Connection,
        task: TaskDto,
        type: String,
        extraRecipients: Collection<UUID> = emptyList(),
    ): List<StoredEvent> = eventLog.append(
        connection,
        listOf(
            OutgoingEvent(
                scope = EventScope.TASK,
                scopeId = task.id,
                type = type,
                payloadJson = ApiJson.encodeToString(TaskChangedPayload(task)),
                recipients = (repository.recipients(connection, task.id) + extraRecipients).distinct(),
            ),
        ),
    )

    private suspend fun publish(events: List<StoredEvent>) {
        if (events.isNotEmpty()) eventBus.publish(events)
    }

    private fun validateNewTask(title: String, description: String?, priority: String) {
        validate {
            requireNotBlank(title, "title", "Название обязательно")
            requireLength(title.trim(), "title", max = MAX_TITLE_LENGTH, min = 1)
            requireLength(description, "description", max = MAX_DESCRIPTION_LENGTH)
            check(priority in PRIORITIES, "priority", "Допустимо: ${PRIORITIES.joinToString()}")
        }
    }

    private fun validateFieldValue(field: String, value: String?) {
        validate {
            when (field) {
                "title" -> {
                    requireNotBlank(value, "title", "Название обязательно")
                    requireLength(value?.trim(), "title", max = MAX_TITLE_LENGTH, min = 1)
                }
                "description" -> requireLength(value, "description", max = MAX_DESCRIPTION_LENGTH)
                "status" -> check(value in STATUSES, "status", "Допустимо: ${STATUSES.joinToString()}")
                "priority" -> check(value in PRIORITIES, "priority", "Допустимо: ${PRIORITIES.joinToString()}")
                "due_at", "remind_at" -> check(
                    value == null || parsesAsInstant(value),
                    field,
                    "Ожидается время в формате ISO-8601",
                )
            }
        }
    }

    private fun normalize(field: String, value: String?): String? = when (field) {
        "title" -> value?.trim()
        "description" -> value?.trim()?.takeIf { it.isNotEmpty() }
        // Время приводится к каноническому виду: иначе одинаковые моменты,
        // записанные по-разному, считались бы разными значениями.
        "due_at", "remind_at" -> value?.let { Instant.parse(it).toString() }
        else -> value
    }

    private fun parsesAsInstant(value: String): Boolean =
        try {
            Instant.parse(value)
            true
        } catch (_: DateTimeParseException) {
            false
        }

    private fun historyType(field: String): String = when (field) {
        "title" -> "TITLE_CHANGED"
        "description" -> "DESCRIPTION_CHANGED"
        "status" -> "STATUS_CHANGED"
        "priority" -> "PRIORITY_CHANGED"
        "due_at" -> "DUE_DATE_CHANGED"
        "remind_at" -> "REMINDER_CHANGED"
        else -> "FIELD_CHANGED"
    }

    companion object {
        const val MAX_TITLE_LENGTH = 256
        const val MAX_DESCRIPTION_LENGTH = 8192
        const val MAX_COMMENT_LENGTH = 4096
        const val DEFAULT_LIMIT = 100

        val STATUSES = setOf("TODO", "IN_PROGRESS", "REVIEW", "DONE", "CANCELLED")
        val PRIORITIES = setOf("LOW", "MEDIUM", "HIGH", "URGENT")
    }
}
