package com.mango.core.data.repository

import androidx.room.withTransaction
import com.mango.core.common.AppError
import com.mango.core.common.DispatcherProvider
import com.mango.core.common.IdGenerator
import com.mango.core.common.Outcome
import com.mango.core.common.TimeProvider
import com.mango.core.data.mapper.senderEntity
import com.mango.core.data.mapper.toDomain
import com.mango.core.data.mapper.toEntity
import com.mango.core.data.outbox.CreateTaskPayload
import com.mango.core.data.outbox.DeleteTaskPayload
import com.mango.core.data.outbox.EntityType
import com.mango.core.data.outbox.MarkReadPayload
import com.mango.core.data.outbox.OpType
import com.mango.core.data.outbox.OutboxJson
import com.mango.core.data.outbox.outboxOperation
import com.mango.core.data.outbox.PatchTaskPayload
import com.mango.core.data.outbox.SaveDraftPayload
import com.mango.core.database.MangoDatabase
import com.mango.core.database.entity.ConversationMemberEntity
import com.mango.core.database.entity.TaskAssigneeEntity
import com.mango.core.database.entity.TaskEntity
import com.mango.core.database.entity.TaskEventEntity
import com.mango.core.network.MessagingRemoteDataSource
import com.mango.core.model.Conversation
import com.mango.core.model.ConversationId
import com.mango.core.model.ConversationMember
import com.mango.core.model.MemberRole
import com.mango.core.model.MessageId
import com.mango.core.model.SyncStatus
import com.mango.core.model.Task
import com.mango.core.model.TaskEvent
import com.mango.core.model.TaskId
import com.mango.core.model.TaskScope
import com.mango.core.model.TaskStatus
import com.mango.core.model.User
import com.mango.core.model.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultConversationRepository @Inject constructor(
    private val database: MangoDatabase,
    private val remote: MessagingRemoteDataSource,
    private val idGenerator: IdGenerator,
    private val time: TimeProvider,
    private val dispatchers: DispatcherProvider,
) : ConversationRepository {

    private val conversationDao = database.conversationDao()
    private val outboxDao = database.outboxDao()
    private val userDao = database.userDao()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observeConversations(): Flow<List<Conversation>> =
        userDao.observeCurrentUser().flatMapLatest { me ->
            conversationDao.observeConversationList(me?.id.orEmpty())
                .map { rows -> rows.map { it.toDomain() } }
        }

    override fun observeConversation(id: ConversationId): Flow<Conversation?> =
        conversationDao.observeById(id.value).combine(userDao.observeCurrentUser()) { entity, _ ->
            entity?.let {
                Conversation(
                    id = ConversationId(it.id),
                    type = it.type,
                    title = it.title,
                    avatarUrl = it.avatarUrl,
                    unreadCount = 0,
                    mutedUntil = it.mutedUntil?.let(java.time.Instant::ofEpochMilli),
                    pinnedMessageCount = it.pinnedMessageCount,
                    memberCount = it.memberCount,
                    lastReadSeq = it.lastReadSeq,
                    lastEventSeq = it.lastEventSeq,
                    draft = it.draft,
                    updatedAt = java.time.Instant.ofEpochMilli(it.updatedAt),
                    myRole = it.myRole,
                )
            }
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observeTotalUnread(): Flow<Int> =
        userDao.observeCurrentUser().flatMapLatest { me ->
            conversationDao.observeTotalUnread(me?.id.orEmpty())
        }

    override fun observeMembers(id: ConversationId): Flow<List<User>> =
        conversationDao.observeMemberUsers(id.value).map { list -> list.map { it.toDomain() } }

    /**
     * Черновик сохраняется локально сразу, а в очередь ставится одна операция
     * на чат: предыдущая нерелевантна, поэтому старые операции по этой
     * сущности удаляются. Иначе быстрый набор текста забил бы очередь.
     */
    override suspend fun saveDraft(id: ConversationId, draft: String?): Outcome<Unit> =
        withContext(dispatchers.io) {
            val now = time.nowMillis()
            val clientOpId = idGenerator.newId()
            runCatching {
                database.withTransaction {
                    conversationDao.updateDraft(id.value, draft, now)
                    outboxDao.deleteForEntity(EntityType.CONVERSATION, id.value)
                    outboxDao.enqueue(
                        outboxOperation(
                            clientOpId = clientOpId,
                            opType = OpType.SAVE_DRAFT,
                            entityType = EntityType.CONVERSATION,
                            entityId = id.value,
                            payloadJson = OutboxJson.encodeToString(
                                SaveDraftPayload(id.value, draft),
                            ),
                            nowMillis = now,
                            dependsOnOpId = null,
                        ),
                    )
                }
            }.fold({ Outcome.Success(Unit) }, { Outcome.Failure(AppError.Storage(it)) })
        }

    override suspend fun markRead(id: ConversationId, seq: Long): Outcome<Unit> =
        withContext(dispatchers.io) {
            val now = time.nowMillis()
            val clientOpId = idGenerator.newId()
            runCatching {
                database.withTransaction {
                    conversationDao.advanceLastReadSeq(id.value, seq)
                    outboxDao.enqueue(
                        outboxOperation(
                            clientOpId = clientOpId,
                            opType = OpType.MARK_READ,
                            entityType = EntityType.CONVERSATION,
                            entityId = id.value,
                            payloadJson = OutboxJson.encodeToString(MarkReadPayload(id.value, seq)),
                            nowMillis = now,
                            dependsOnOpId = null,
                        ),
                    )
                }
            }.fold({ Outcome.Success(Unit) }, { Outcome.Failure(AppError.Storage(it)) })
        }

    override suspend fun setMuted(id: ConversationId, mutedUntilMillis: Long?): Outcome<Unit> =
        withContext(dispatchers.io) {
            runCatching { conversationDao.setMuted(id.value, mutedUntilMillis) }
                .fold({ Outcome.Success(Unit) }, { Outcome.Failure(AppError.Storage(it)) })
        }

    override suspend fun refresh(): Outcome<Unit> = withContext(dispatchers.io) {
        when (val response = remote.conversations()) {
            is Outcome.Failure -> response
            is Outcome.Success -> {
                runCatching {
                    database.withTransaction {
                        response.value.items.forEach { dto ->
                            conversationDao.upsertFromServer(dto.toEntity())
                            dto.lastMessage?.let { message ->
                                rememberSender(message.senderId, message.senderName)
                                database.messageDao().upsert(message.toEntity())
                            }
                        }
                    }
                }.fold({ Outcome.Success(Unit) }, { Outcome.Failure(AppError.Storage(it)) })
            }
        }
    }

    override suspend fun openDirect(userId: UserId): Outcome<ConversationId> =
        withContext(dispatchers.io) {
            when (val response = remote.createDirect(userId.value)) {
                is Outcome.Failure -> response
                is Outcome.Success -> {
                    runCatching {
                        conversationDao.upsertFromServer(response.value.toEntity())
                    }.fold(
                        { Outcome.Success(ConversationId(response.value.id)) },
                        { Outcome.Failure(AppError.Storage(it)) },
                    )
                }
            }
        }

    override suspend fun loadMembers(id: ConversationId): Outcome<Unit> =
        withContext(dispatchers.io) {
            when (val response = remote.members(id.value)) {
                is Outcome.Failure -> response
                is Outcome.Success -> storeMembers(id, response.value.items)
            }
        }

    override fun observeMembersWithRoles(id: ConversationId): Flow<List<ConversationMember>> =
        conversationDao.observeMembers(id.value).map { rows ->
            rows.map { row ->
                ConversationMember(
                    user = row.user.toDomain(),
                    role = row.role,
                    joinedAt = java.time.Instant.ofEpochMilli(row.joinedAt),
                )
            }
        }

    override suspend fun createGroup(title: String, memberIds: List<UserId>): Outcome<ConversationId> =
        withContext(dispatchers.io) {
            val trimmed = title.trim()
            if (trimmed.isEmpty()) {
                return@withContext Outcome.Failure(AppError.Validation(mapOf("title" to "Нужно название группы")))
            }
            if (memberIds.isEmpty()) {
                return@withContext Outcome.Failure(AppError.Validation(mapOf("members" to "Добавьте хотя бы одного участника")))
            }
            when (val response = remote.createGroup(trimmed, memberIds.map { it.value })) {
                is Outcome.Failure -> response
                is Outcome.Success -> runCatching {
                    conversationDao.upsertFromServer(response.value.toEntity())
                }.fold(
                    { Outcome.Success(ConversationId(response.value.id)) },
                    { Outcome.Failure(AppError.Storage(it)) },
                )
            }
        }

    override suspend fun renameGroup(id: ConversationId, title: String): Outcome<Unit> =
        withContext(dispatchers.io) {
            val trimmed = title.trim()
            if (trimmed.isEmpty()) {
                return@withContext Outcome.Failure(AppError.Validation(mapOf("title" to "Название не может быть пустым")))
            }
            when (val response = remote.rename(id.value, trimmed)) {
                is Outcome.Failure -> response
                is Outcome.Success -> runCatching { conversationDao.upsertFromServer(response.value.toEntity()) }
                    .fold({ Outcome.Success(Unit) }, { Outcome.Failure(AppError.Storage(it)) })
            }
        }

    override suspend fun addMembers(id: ConversationId, userIds: List<UserId>): Outcome<Unit> =
        withContext(dispatchers.io) {
            if (userIds.isEmpty()) return@withContext Outcome.Success(Unit)
            when (val response = remote.addMembers(id.value, userIds.map { it.value })) {
                is Outcome.Failure -> response
                is Outcome.Success -> storeMembers(id, response.value.items)
            }
        }

    override suspend fun removeMember(id: ConversationId, userId: UserId): Outcome<Unit> =
        withContext(dispatchers.io) {
            when (val response = remote.removeMember(id.value, userId.value)) {
                is Outcome.Failure -> response
                is Outcome.Success -> runCatching { conversationDao.deleteMember(id.value, userId.value) }
                    .fold({ Outcome.Success(Unit) }, { Outcome.Failure(AppError.Storage(it)) })
            }
        }

    override suspend fun changeRole(id: ConversationId, userId: UserId, role: MemberRole): Outcome<Unit> =
        withContext(dispatchers.io) {
            when (val response = remote.changeRole(id.value, userId.value, role.name)) {
                is Outcome.Failure -> response
                // Передача владения меняет и свою роль; состав и карточка
                // перечитываются целиком, чтобы не собирать это по частям.
                is Outcome.Success -> {
                    loadMembers(id)
                    when (val card = remote.conversation(id.value)) {
                        is Outcome.Success -> runCatching { conversationDao.upsertFromServer(card.value.toEntity()) }
                        is Outcome.Failure -> Unit
                    }
                    Outcome.Success(Unit)
                }
            }
        }

    override suspend fun leaveGroup(id: ConversationId): Outcome<Unit> = withContext(dispatchers.io) {
        val me = userDao.currentUserId() ?: return@withContext Outcome.Failure(AppError.Unauthorized)
        when (val response = remote.removeMember(id.value, me)) {
            is Outcome.Failure -> response
            // Чат удаляется с устройства вместе с перепиской (каскад по
            // внешнему ключу): доступа к ней у ушедшего больше нет.
            is Outcome.Success -> runCatching { conversationDao.delete(id.value) }
                .fold({ Outcome.Success(Unit) }, { Outcome.Failure(AppError.Storage(it)) })
        }
    }

    /** Состав с сервера заменяет локальный: выбывшие удаляются. */
    private suspend fun storeMembers(
        id: ConversationId,
        members: List<com.mango.core.network.dto.MemberDto>,
    ): Outcome<Unit> = runCatching {
        database.withTransaction {
            members.forEach { member -> userDao.upsert(member.toEntity()) }
            conversationDao.upsertMembers(
                members.map { member ->
                    ConversationMemberEntity(
                        conversationId = id.value,
                        userId = member.userId,
                        role = runCatching { MemberRole.valueOf(member.role) }.getOrDefault(MemberRole.MEMBER),
                        joinedAt = member.joinedAt.toEpochMilli(),
                        lastReadSeq = member.lastReadSeq,
                        leftAt = null,
                    )
                },
            )
            conversationDao.deleteMembersExcept(id.value, members.map { it.userId })
        }
    }.fold({ Outcome.Success(Unit) }, { Outcome.Failure(AppError.Storage(it)) })

    /**
     * Поиск людей.
     *
     * Найденные записываются в локальную базу: после начала переписки
     * имя собеседника должно отображаться даже без сети.
     */
    override suspend fun searchPeople(query: String): Outcome<List<User>> =
        withContext(dispatchers.io) {
            when (val response = remote.searchUsers(query)) {
                is Outcome.Failure -> response
                is Outcome.Success -> {
                    runCatching {
                        response.value.items.forEach { userDao.upsert(it.toEntity()) }
                        response.value.items.map { dto ->
                            User(
                                id = UserId(dto.id),
                                username = dto.username,
                                displayName = dto.displayName,
                                avatarUrl = dto.avatarUrl,
                            )
                        }
                    }.fold({ Outcome.Success(it) }, { Outcome.Failure(AppError.Storage(it)) })
                }
            }
        }

    private suspend fun rememberSender(id: String, displayName: String) {
        if (userDao.findById(id) == null) {
            userDao.upsert(senderEntity(id, displayName, null))
        }
    }
}

@Singleton
class LocalTaskRepository @Inject constructor(
    private val database: MangoDatabase,
    private val idGenerator: IdGenerator,
    private val time: TimeProvider,
    private val dispatchers: DispatcherProvider,
) : TaskRepository {

    private val taskDao = database.taskDao()
    private val outboxDao = database.outboxDao()
    private val userDao = database.userDao()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observeTasks(scope: TaskScope, includeCompleted: Boolean): Flow<List<Task>> =
        when (scope) {
            TaskScope.MINE -> userDao.observeCurrentUser().flatMapLatest { me ->
                taskDao.observeMyTasks(me?.id.orEmpty(), includeCompleted)
            }
            TaskScope.TEAM -> taskDao.observeTeamTasks(includeCompleted)
            TaskScope.FROM_CHATS -> taskDao.observeTasksFromChats()
        }.map { list -> list.map { it.toDomain() } }

    override fun observeTask(id: TaskId): Flow<Task?> =
        taskDao.observeById(id.value).combine(taskDao.observeAssigneeUsers(id.value)) { task, users ->
            task?.toDomain(assignees = users.map { it.toDomain() })
        }

    override fun observeByConversation(id: ConversationId): Flow<List<Task>> =
        taskDao.observeByConversation(id.value).map { list -> list.map { it.toDomain() } }

    override fun observeBoardColumn(
        status: TaskStatus,
        conversationId: ConversationId?,
    ): Flow<List<Task>> =
        taskDao.observeByStatus(status, conversationId?.value)
            .map { list -> list.map { it.toDomain() } }

    override fun observeDueBetween(fromMillis: Long, toMillis: Long): Flow<List<Task>> =
        taskDao.observeDueBetween(fromMillis, toMillis).map { list -> list.map { it.toDomain() } }

    override fun observeSubtasks(parentId: TaskId): Flow<List<Task>> =
        taskDao.observeSubtasks(parentId.value).map { list -> list.map { it.toDomain() } }

    /**
     * Создание задачи — полностью работает офлайн.
     *
     * Снапшот исходного сообщения записывается в саму задачу, поэтому
     * последующее удаление сообщения не лишает задачу контекста.
     */
    override suspend fun create(draft: NewTaskDraft): Outcome<TaskId> =
        withContext(dispatchers.io) {
            if (draft.title.isBlank()) {
                return@withContext Outcome.Failure(
                    AppError.Validation(mapOf("title" to "Название обязательно")),
                )
            }
            val creatorId = userDao.currentUserId()
                ?: return@withContext Outcome.Failure(AppError.Unauthorized)

            val taskId = idGenerator.newId()
            val now = time.nowMillis()

            val entity = TaskEntity(
                id = taskId,
                title = draft.title.trim(),
                description = draft.description?.trim()?.takeIf { it.isNotEmpty() },
                status = TaskStatus.TODO,
                priority = draft.priority,
                createdBy = creatorId,
                conversationId = draft.conversationId?.value,
                conversationTitle = draft.conversationId?.let {
                    database.conversationDao().findById(it.value)?.title
                },
                sourceMessageId = draft.sourceMessageId?.value,
                sourceSenderName = draft.sourceSenderName,
                sourceMessageSnapshot = draft.sourceTextSnapshot,
                sourceCreatedAt = draft.sourceMessageId?.let { now },
                sourceUnavailable = false,
                parentTaskId = draft.parentTaskId?.value,
                dueAt = draft.dueAtMillis,
                remindAt = null,
                version = 1,
                fieldVersionsJson = initialFieldVersions(now),
                syncStatus = SyncStatus.PENDING,
                completedAt = null,
                deletedAt = null,
                createdAt = now,
                updatedAt = now,
            )

            runCatching {
                database.withTransaction {
                    taskDao.upsert(entity)
                    if (draft.assigneeIds.isNotEmpty()) {
                        taskDao.upsertAssignees(
                            draft.assigneeIds.map { userId ->
                                TaskAssigneeEntity(
                                    taskId = taskId,
                                    userId = userId.value,
                                    assignedAt = now,
                                    removedAt = null,
                                    syncStatus = SyncStatus.PENDING,
                                )
                            },
                        )
                    }
                    taskDao.insertEvent(
                        TaskEventEntity(
                            serverId = null,
                            taskId = taskId,
                            actorId = creatorId,
                            type = TaskEvent.Type.CREATED,
                            field = null,
                            oldValue = null,
                            newValue = entity.title,
                            createdAt = now,
                        ),
                    )
                    outboxDao.enqueue(
                        outboxOperation(
                            clientOpId = taskId,
                            opType = OpType.CREATE_TASK,
                            entityType = EntityType.TASK,
                            entityId = taskId,
                            payloadJson = OutboxJson.encodeToString(
                                CreateTaskPayload(
                                    taskId = taskId,
                                    title = entity.title,
                                    description = entity.description,
                                    priority = entity.priority.name,
                                    dueAtMillis = entity.dueAt,
                                    conversationId = entity.conversationId,
                                    assigneeIds = draft.assigneeIds.map { it.value },
                                    parentTaskId = entity.parentTaskId,
                                    sourceMessageId = entity.sourceMessageId,
                                ),
                            ),
                            nowMillis = now,
                            dependsOnOpId = null,
                        ),
                    )
                }
            }.fold(
                { Outcome.Success(TaskId(taskId)) },
                { Outcome.Failure(AppError.Storage(it)) },
            )
        }

    /**
     * Смена статуса отправляется как частичный PATCH с baseVersion и меткой
     * времени поля — это даёт серверу разрешить конфликт по полю, не теряя
     * параллельных изменений других полей.
     */
    override suspend fun edit(id: TaskId, title: String, description: String?, dueAtMillis: Long?): Outcome<Unit> =
        withContext(dispatchers.io) {
            if (title.isBlank() || title.trim().length > 256) return@withContext Outcome.Failure(
                AppError.Validation(mapOf("title" to "Название должно содержать от 1 до 256 символов")),
            )
            runCatching {
                database.withTransaction {
                    val existing = taskDao.findById(id.value) ?: error("Задача не найдена")
                    val now = time.nowMillis()
                    val fields = mutableMapOf<String, String?>()
                    if (existing.title != title.trim()) fields["title"] = title.trim()
                    if (existing.description != description?.trim()?.takeIf { it.isNotEmpty() }) fields["description"] = description?.trim()?.takeIf { it.isNotEmpty() }
                    if (existing.dueAt != dueAtMillis) fields["due_at"] = dueAtMillis?.let { java.time.Instant.ofEpochMilli(it).toString() }
                    if (fields.isEmpty()) return@withTransaction
                    val versions = fields.keys.fold(existing.fieldVersionsJson) { json, field -> mergeFieldVersion(json, field, now) }
                    taskDao.upsert(existing.copy(title = title.trim(), description = description?.trim()?.takeIf { it.isNotEmpty() }, dueAt = dueAtMillis,
                        updatedAt = now, syncStatus = SyncStatus.PENDING, fieldVersionsJson = versions))
                    outboxDao.enqueue(outboxOperation(
                        clientOpId = idGenerator.newId(), opType = OpType.PATCH_TASK, entityType = EntityType.TASK,
                        entityId = id.value, payloadJson = OutboxJson.encodeToString(PatchTaskPayload(
                            taskId = id.value, baseVersion = existing.version, changedFields = fields, fieldTimestamps = fields.keys.associateWith { now },
                        )), nowMillis = now, dependsOnOpId = id.value.takeIf { existing.syncStatus != SyncStatus.SYNCED },
                    ))
                }
            }.fold({ Outcome.Success(Unit) }, { Outcome.Failure(AppError.Storage(it)) })
        }

    override suspend fun changeStatus(id: TaskId, status: TaskStatus): Outcome<Unit> =
        withContext(dispatchers.io) {
            val existing = taskDao.findById(id.value)
                ?: return@withContext Outcome.Failure(AppError.NotFound("task"))
            val actorId = userDao.currentUserId()

            val now = time.nowMillis()
            val clientOpId = idGenerator.newId()
            val completedAt = if (status == TaskStatus.DONE) now else null

            runCatching {
                database.withTransaction {
                    taskDao.updateStatus(
                        id = id.value,
                        status = status,
                        updatedAt = now,
                        syncStatus = SyncStatus.PENDING,
                        completedAt = completedAt,
                    )
                    taskDao.updateFieldVersions(
                        id.value,
                        mergeFieldVersion(existing.fieldVersionsJson, "status", now),
                    )
                    taskDao.insertEvent(
                        TaskEventEntity(
                            serverId = null,
                            taskId = id.value,
                            actorId = actorId,
                            type = TaskEvent.Type.STATUS_CHANGED,
                            field = "status",
                            oldValue = existing.status.name,
                            newValue = status.name,
                            createdAt = now,
                        ),
                    )
                    outboxDao.enqueue(
                        outboxOperation(
                            clientOpId = clientOpId,
                            opType = OpType.PATCH_TASK,
                            entityType = EntityType.TASK,
                            entityId = id.value,
                            payloadJson = OutboxJson.encodeToString(
                                PatchTaskPayload(
                                    taskId = id.value,
                                    baseVersion = existing.version,
                                    changedFields = mapOf("status" to status.name),
                                    fieldTimestamps = mapOf("status" to now),
                                ),
                            ),
                            nowMillis = now,
                            dependsOnOpId = id.value.takeIf { existing.syncStatus != SyncStatus.SYNCED },
                        ),
                    )
                }
            }.fold({ Outcome.Success(Unit) }, { Outcome.Failure(AppError.Storage(it)) })
        }

    override suspend fun delete(id: TaskId): Outcome<Unit> = withContext(dispatchers.io) {
        val now = time.nowMillis()
        val clientOpId = idGenerator.newId()
        runCatching {
            database.withTransaction {
                taskDao.markDeleted(id.value, now, SyncStatus.PENDING)
                outboxDao.enqueue(
                    outboxOperation(
                            clientOpId = clientOpId,
                            opType = OpType.DELETE_TASK,
                            entityType = EntityType.TASK,
                            entityId = id.value,
                            payloadJson = OutboxJson.encodeToString(DeleteTaskPayload(id.value)),
                            nowMillis = now,
                            dependsOnOpId = null,
                        ),
                )
            }
        }.fold({ Outcome.Success(Unit) }, { Outcome.Failure(AppError.Storage(it)) })
    }

    override suspend fun onSourceMessageDeleted(messageId: MessageId): Outcome<Unit> =
        withContext(dispatchers.io) {
            runCatching { taskDao.markSourceUnavailable(messageId.value) }
                .fold({ Outcome.Success(Unit) }, { Outcome.Failure(AppError.Storage(it)) })
        }

    private fun initialFieldVersions(now: Long): String =
        """{"title":$now,"description":$now,"status":$now,"priority":$now,"due_at":$now}"""

    /**
     * Обновляет метку времени одного поля в JSON-карте версий.
     *
     * Разбор примитивный и намеренно устойчивый: при любой проблеме
     * возвращается карта с единственным актуальным полем, а не исключение —
     * сломанный JSON не должен блокировать пользователю смену статуса.
     */
    private fun mergeFieldVersion(currentJson: String, field: String, timestamp: Long): String =
        runCatching {
            val element = OutboxJson.parseToJsonElement(currentJson)
            val map = element.let { it as kotlinx.serialization.json.JsonObject }
                .mapValues { (_, value) ->
                    (value as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                }
                .toMutableMap()
            map[field] = timestamp
            map.entries.joinToString(prefix = "{", postfix = "}") { "\"${it.key}\":${it.value}" }
        }.getOrElse { """{"$field":$timestamp}""" }
}

@Singleton
class LocalUserRepository @Inject constructor(
    private val database: MangoDatabase,
    private val dispatchers: DispatcherProvider,
) : UserRepository {

    private val userDao = database.userDao()

    override fun observeCurrentUser(): Flow<User?> =
        userDao.observeCurrentUser().map { it?.toDomain() }

    override fun observeContacts(): Flow<List<User>> =
        userDao.observeContacts().map { list -> list.map { it.toDomain() } }

    override fun observeSearch(query: String): Flow<List<User>> =
        userDao.observeSearch(query).map { list -> list.map { it.toDomain() } }

    override suspend fun currentUserId(): UserId? = withContext(dispatchers.io) {
        userDao.currentUserId()?.let(::UserId)
    }
}

@Singleton
class LocalSyncStatusRepository @Inject constructor(
    private val database: MangoDatabase,
    private val time: TimeProvider,
    private val dispatchers: DispatcherProvider,
) : SyncStatusRepository {

    private val outboxDao = database.outboxDao()

    override fun observeSnapshot(): Flow<SyncSnapshot> = combine(
        outboxDao.observePendingCount(),
        outboxDao.observeFailedCount(),
        outboxDao.observeSyncState(GLOBAL_SCOPE),
    ) { pending, failed, state ->
        SyncSnapshot(
            pendingOperations = pending,
            failedOperations = failed,
            lastSyncedAtMillis = state?.lastSyncedAt,
        )
    }

    override suspend fun recoverAfterRestart(): Int = withContext(dispatchers.io) {
        val now = time.nowMillis()
        val recovered = outboxDao.recoverInFlight(now)
        outboxDao.unblockResolved(now)
        recovered
    }

    private companion object {
        const val GLOBAL_SCOPE = "GLOBAL"
    }
}
