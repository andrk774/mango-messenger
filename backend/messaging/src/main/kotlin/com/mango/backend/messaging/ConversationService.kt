package com.mango.backend.messaging

import com.mango.backend.common.ApiJson
import com.mango.backend.common.AppException
import com.mango.backend.common.Database
import com.mango.backend.common.IdGenerator
import com.mango.backend.common.TimeSource
import com.mango.backend.common.validate
import com.mango.backend.sync.EventBus
import com.mango.backend.sync.EventLog
import com.mango.backend.sync.EventScope
import com.mango.backend.sync.EventTypes
import com.mango.backend.sync.OutgoingEvent
import com.mango.backend.sync.StoredEvent
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * Чаты и участники.
 *
 * Каждая операция начинается с проверки принадлежности к чату. Проверка
 * выполняется на сервере в самом запросе, а не полагается на то, что
 * клиент покажет только «свои» чаты.
 */
class ConversationService(
    private val database: Database,
    private val repository: MessagingRepository,
    private val eventLog: EventLog,
    private val eventBus: EventBus,
    private val ids: IdGenerator,
    private val time: TimeSource,
) {

    suspend fun create(userId: UUID, request: CreateConversationRequest): ConversationDto {
        val type = request.type.uppercase()
        val otherIds = request.memberIds.filter { it != userId }.distinct()

        validate {
            check(type == "DIRECT" || type == "GROUP", "type", "Допустимо DIRECT или GROUP")
            check(otherIds.isNotEmpty(), "memberIds", "Нужен хотя бы один собеседник")
            if (type == "DIRECT") {
                check(otherIds.size == 1, "memberIds", "В личном чате ровно один собеседник")
            } else {
                requireNotBlank(request.title, "title", "Групповому чату нужно название")
                requireLength(request.title, "title", max = 128)
            }
            check(otherIds.size <= MAX_MEMBERS, "memberIds", "Не больше $MAX_MEMBERS участников")
        }

        val now = time.now()

        val (conversation, events) = database.transaction { connection ->
            val existing = repository.usersExist(connection, otherIds)
            val missing = otherIds - existing
            if (missing.isNotEmpty()) {
                // Сообщается сам факт, без перечисления: ответ не должен
                // работать как проверка существования чужих учётных записей.
                throw AppException.Validation(
                    mapOf("memberIds" to "Некоторые пользователи не найдены"),
                )
            }

            val directKey = if (type == "DIRECT") directKeyOf(userId, otherIds.single()) else null

            val createdId = repository.insertConversation(
                connection = connection,
                id = ids.newId(),
                type = type,
                title = request.title?.trim(),
                createdBy = userId,
                directKey = directKey,
                now = now,
            )

            if (createdId == null) {
                // Личный чат с этим человеком уже существует. Это штатный
                // исход: повторное открытие переписки обязано вернуть тот же
                // чат, а не создать второй.
                val existingId = directKey?.let { repository.findByDirectKey(connection, it) }
                    ?: throw AppException.Internal("Чат не создан и не найден")

                val found = repository.conversationFor(connection, userId, existingId)
                    ?: throw AppException.Forbidden()
                return@transaction found to emptyList<StoredEvent>()
            }

            repository.insertMember(connection, createdId, userId, ownerRoleFor(type), now)
            otherIds.forEach { member ->
                repository.insertMember(connection, createdId, member, "MEMBER", now)
            }

            val created = repository.conversationFor(connection, userId, createdId)
                ?: throw AppException.Internal("Созданный чат не читается")

            val recipients = repository.memberIds(connection, createdId)
            val stored = eventLog.append(
                connection,
                conversationViews(connection, createdId, recipients, EventTypes.CONVERSATION_CREATED),
            )
            created to stored
        }

        publish(events)
        return conversation
    }

    suspend fun list(userId: UUID, limit: Int): ConversationsResponse =
        database.readOnly { connection ->
            ConversationsResponse(
                items = repository.conversationsFor(connection, userId, limit),
                // Курсор журнала выдаётся вместе со списком: между первичной
                // загрузкой и подпиской на события не должно оставаться окна,
                // в котором изменения теряются.
                lastEventId = eventLog.lastEventId(connection, userId),
            )
        }

    suspend fun get(userId: UUID, conversationId: UUID): ConversationDto =
        database.readOnly { connection ->
            repository.conversationFor(connection, userId, conversationId)
                // 403, а не 404: ответ не должен различать «чата нет»
                // и «чат есть, но он не ваш».
                ?: throw AppException.Forbidden("Чат недоступен")
        }

    suspend fun members(userId: UUID, conversationId: UUID): MembersResponse =
        database.readOnly { connection ->
            repository.membership(connection, conversationId, userId)
                ?: throw AppException.Forbidden("Чат недоступен")
            MembersResponse(repository.members(connection, conversationId))
        }

    /**
     * Отметка о прочтении.
     *
     * Событие рассылается остальным участникам: по нему собеседник видит,
     * что сообщение прочитано.
     */
    suspend fun markRead(userId: UUID, conversationId: UUID, seq: Long): Long {
        if (seq < 0) throw AppException.BadRequest("Номер не может быть отрицательным")

        val (effective, events) = database.transaction { connection ->
            repository.membership(connection, conversationId, userId)
                ?: throw AppException.Forbidden("Чат недоступен")

            val applied = repository.advanceReadMarker(connection, conversationId, userId, seq)

            val others = repository.memberIds(connection, conversationId).filter { it != userId }
            val stored = if (others.isEmpty()) {
                emptyList()
            } else {
                eventLog.append(
                    connection,
                    listOf(
                        OutgoingEvent(
                            scope = EventScope.CONVERSATION,
                            scopeId = conversationId,
                            type = EventTypes.READ_MARKER_MOVED,
                            payloadJson = ApiJson.encodeToString(
                                ReadMarkerPayload(conversationId, userId, applied),
                            ),
                            recipients = others,
                        ),
                    ),
                )
            }
            applied to stored
        }

        publish(events)
        return effective
    }

    suspend fun searchUsers(userId: UUID, query: String, limit: Int): UserSearchResponse {
        val trimmed = query.trim()
        if (trimmed.length < MIN_SEARCH_LENGTH) {
            throw AppException.BadRequest("Запрос должен быть не короче $MIN_SEARCH_LENGTH символов")
        }
        return database.readOnly { connection ->
            UserSearchResponse(repository.searchUsers(connection, trimmed, userId, limit))
        }
    }

    // ─────────────────────── управление группой ───────────────────────

    /** Переименование группы. Администратор или владелец. */
    suspend fun rename(userId: UUID, conversationId: UUID, title: String): ConversationDto {
        val trimmed = title.trim()
        validate {
            requireNotBlank(trimmed, "title", "Название не может быть пустым")
            requireLength(trimmed, "title", max = MAX_TITLE_LENGTH)
        }
        val now = time.now()

        val (result, events) = database.transaction { connection ->
            val actor = requireGroupManager(connection, conversationId, userId)
            if (actor.currentTitle == trimmed) {
                return@transaction view(connection, userId, conversationId) to emptyList<StoredEvent>()
            }
            repository.updateTitle(connection, conversationId, trimmed, now)

            val system = appendSystemMessage(
                connection, conversationId, userId, now,
                SystemEventDto(kind = "TITLE_CHANGED", actorId = userId, actorName = actor.name, title = trimmed),
            )
            val recipients = repository.memberIds(connection, conversationId)
            val stored = eventLog.append(
                connection,
                conversationViews(connection, conversationId, recipients, EventTypes.CONVERSATION_UPDATED) +
                    messageEvent(conversationId, system, recipients),
            )
            view(connection, userId, conversationId) to stored
        }
        publish(events)
        return result
    }

    /**
     * Добавление участников. Администратор или владелец.
     *
     * Уже состоящие в группе пропускаются: повтор запроса после обрыва
     * связи не должен ни падать, ни порождать второе системное сообщение.
     */
    suspend fun addMembers(userId: UUID, conversationId: UUID, request: AddMembersRequest): MembersResponse {
        val requested = request.userIds.distinct().filter { it != userId }
        validate {
            check(requested.isNotEmpty(), "userIds", "Нужен хотя бы один пользователь")
            check(requested.size <= MAX_MEMBERS, "userIds", "Не больше $MAX_MEMBERS за раз")
        }
        val now = time.now()

        val (result, events) = database.transaction { connection ->
            val actor = requireGroupManager(connection, conversationId, userId)

            val existing = repository.usersExist(connection, requested)
            if (existing.size != requested.size) {
                throw AppException.Validation(mapOf("userIds" to "Некоторые пользователи не найдены"))
            }

            val current = repository.memberIds(connection, conversationId).toSet()
            val newcomers = requested.filter { it !in current }
            if (newcomers.isEmpty()) {
                return@transaction MembersResponse(repository.members(connection, conversationId)) to
                    emptyList<StoredEvent>()
            }
            if (current.size + newcomers.size > MAX_MEMBERS + 1) {
                throw AppException.Validation(
                    mapOf("userIds" to "В группе не может быть больше ${MAX_MEMBERS + 1} участников"),
                )
            }

            newcomers.forEach { repository.insertMember(connection, conversationId, it, "MEMBER", now) }

            val names = repository.displayNames(connection, newcomers)
            val system = appendSystemMessage(
                connection, conversationId, userId, now,
                SystemEventDto(
                    kind = "MEMBERS_ADDED",
                    actorId = userId,
                    actorName = actor.name,
                    targets = newcomers.map { SystemTargetDto(it, names[it] ?: "") },
                ),
            )

            val members = repository.members(connection, conversationId)
            val everyone = members.map { it.userId }
            val outgoing = buildList {
                // Новичкам чат приходит целиком — как будто он только что создан.
                addAll(conversationViews(connection, conversationId, newcomers, EventTypes.CONVERSATION_CREATED))
                // Остальным — обновлённый счётчик участников и сами участники.
                addAll(conversationViews(connection, conversationId, current.toList(), EventTypes.CONVERSATION_UPDATED))
                add(
                    OutgoingEvent(
                        scope = EventScope.CONVERSATION,
                        scopeId = conversationId,
                        type = EventTypes.MEMBER_ADDED,
                        payloadJson = ApiJson.encodeToString(
                            MembersAddedPayload(conversationId, members.filter { it.userId in newcomers }),
                        ),
                        recipients = everyone,
                    ),
                )
                add(messageEvent(conversationId, system, everyone))
            }
            MembersResponse(members) to eventLog.append(connection, outgoing)
        }
        publish(events)
        return result
    }

    /**
     * Удаление участника или выход из группы.
     *
     * Права: себя может убрать любой (это выход); владелец — любого;
     * администратор — только рядового участника. Иначе два администратора
     * могли бы исключить друг друга, а администратор — владельца.
     */
    suspend fun removeMember(userId: UUID, conversationId: UUID, targetId: UUID) {
        val now = time.now()

        val events = database.transaction { connection ->
            val type = repository.lockConversationForChange(connection, conversationId)
                ?: throw AppException.Forbidden("Чат недоступен")
            val actor = repository.membership(connection, conversationId, userId)
                ?: throw AppException.Forbidden("Чат недоступен")
            if (type != "GROUP") throw AppException.BadRequest("Состав личного чата не меняется")

            val leaving = targetId == userId
            val target = if (leaving) actor else repository.membership(connection, conversationId, targetId)
            // Повтор удаления уже выбывшего — успех без последствий.
            if (target == null) {
                if (!actor.canManageMembers) throw AppException.Forbidden("Недостаточно прав")
                return@transaction emptyList<StoredEvent>()
            }

            if (!leaving) {
                val allowed = when (actor.role) {
                    "OWNER" -> true
                    "ADMIN" -> target.role == "MEMBER"
                    else -> false
                }
                if (!allowed) throw AppException.Forbidden("Недостаточно прав")
            }

            repository.markLeft(connection, conversationId, targetId, now)

            // Владелец уходит — владение переходит, иначе группой никто
            // не сможет управлять.
            val successor = if (leaving && target.role == "OWNER") {
                repository.successorFor(connection, conversationId, targetId)
                    ?.also { repository.setRole(connection, conversationId, it, "OWNER") }
            } else {
                null
            }

            val remaining = repository.memberIds(connection, conversationId)
            val names = repository.displayNames(connection, listOfNotNull(userId, targetId, successor))

            val removedEvent = OutgoingEvent(
                scope = EventScope.CONVERSATION,
                scopeId = conversationId,
                type = EventTypes.MEMBER_REMOVED,
                payloadJson = ApiJson.encodeToString(MemberRemovedPayload(conversationId, targetId)),
                // Выбывший тоже получает событие: его устройство должно убрать чат.
                recipients = remaining + targetId,
            )

            if (remaining.isEmpty()) {
                // Последний участник ушёл — чат больше никому не виден.
                repository.softDeleteConversation(connection, conversationId, now)
                return@transaction eventLog.append(connection, listOf(removedEvent))
            }

            val system = appendSystemMessage(
                connection, conversationId, userId, now,
                SystemEventDto(
                    kind = if (leaving) "MEMBER_LEFT" else "MEMBER_REMOVED",
                    actorId = userId,
                    actorName = names[userId] ?: "",
                    targets = buildList {
                        if (!leaving) add(SystemTargetDto(targetId, names[targetId] ?: ""))
                        // Новый владелец указывается в том же сообщении:
                        // смена владельца — следствие выхода, а не отдельное действие.
                        successor?.let { add(SystemTargetDto(it, names[it] ?: "")) }
                    },
                    role = successor?.let { "OWNER" },
                ),
            )

            val outgoing = buildList {
                add(removedEvent)
                addAll(conversationViews(connection, conversationId, remaining, EventTypes.CONVERSATION_UPDATED))
                successor?.let { id ->
                    repository.member(connection, conversationId, id)?.let { member ->
                        add(memberUpdatedEvent(conversationId, member, remaining))
                    }
                }
                add(messageEvent(conversationId, system, remaining))
            }
            eventLog.append(connection, outgoing)
        }
        publish(events)
    }

    /**
     * Смена роли. Только владелец.
     *
     * Назначение другого владельцем — передача владения: прежний
     * владелец становится администратором. Двух владельцев не бывает.
     */
    suspend fun changeRole(userId: UUID, conversationId: UUID, targetId: UUID, role: String): MemberDto {
        val newRole = role.uppercase()
        validate { check(newRole in ROLES, "role", "Допустимо OWNER, ADMIN или MEMBER") }
        val now = time.now()

        val (result, events) = database.transaction { connection ->
            val type = repository.lockConversationForChange(connection, conversationId)
                ?: throw AppException.Forbidden("Чат недоступен")
            val actor = repository.membership(connection, conversationId, userId)
                ?: throw AppException.Forbidden("Чат недоступен")
            if (type != "GROUP") throw AppException.BadRequest("В личном чате нет ролей")
            if (actor.role != "OWNER") throw AppException.Forbidden("Роли назначает только владелец")
            if (targetId == userId) throw AppException.BadRequest("Свою роль владелец передаёт другому участнику")

            val target = repository.membership(connection, conversationId, targetId)
                ?: throw AppException.Validation(mapOf("userId" to "Пользователь не состоит в группе"))

            if (target.role == newRole) {
                val member = repository.member(connection, conversationId, targetId)
                    ?: throw AppException.Internal("Участник не читается")
                return@transaction member to emptyList<StoredEvent>()
            }

            repository.setRole(connection, conversationId, targetId, newRole)
            if (newRole == "OWNER") repository.setRole(connection, conversationId, userId, "ADMIN")

            val names = repository.displayNames(connection, listOf(userId, targetId))
            val system = appendSystemMessage(
                connection, conversationId, userId, now,
                SystemEventDto(
                    kind = if (newRole == "OWNER") "OWNER_CHANGED" else "ROLE_CHANGED",
                    actorId = userId,
                    actorName = names[userId] ?: "",
                    targets = listOf(SystemTargetDto(targetId, names[targetId] ?: "")),
                    role = newRole,
                ),
            )

            val recipients = repository.memberIds(connection, conversationId)
            val changed = listOfNotNull(
                repository.member(connection, conversationId, targetId),
                if (newRole == "OWNER") repository.member(connection, conversationId, userId) else null,
            )
            val changedIds = changed.map { it.userId }
            val outgoing = buildList {
                changed.forEach { add(memberUpdatedEvent(conversationId, it, recipients)) }
                // Собственная роль хранится и в карточке чата (`myRole`):
                // затронутым она обновляется их собственным видом чата.
                addAll(conversationViews(connection, conversationId, changedIds, EventTypes.CONVERSATION_UPDATED))
                add(messageEvent(conversationId, system, recipients))
            }
            changed.first() to eventLog.append(connection, outgoing)
        }
        publish(events)
        return result
    }

    // ─────────────────────────── внутреннее ───────────────────────────

    private data class Manager(val name: String, val currentTitle: String?)

    /** Проверка: чат — группа, вызывающий — администратор или владелец. Берёт блокировку чата. */
    private fun requireGroupManager(connection: Connection, conversationId: UUID, userId: UUID): Manager {
        val type = repository.lockConversationForChange(connection, conversationId)
            ?: throw AppException.Forbidden("Чат недоступен")
        val membership = repository.membership(connection, conversationId, userId)
            ?: throw AppException.Forbidden("Чат недоступен")
        if (type != "GROUP") throw AppException.BadRequest("Доступно только для групп")
        if (!membership.canManageMembers) throw AppException.Forbidden("Недостаточно прав")
        val view = repository.conversationFor(connection, userId, conversationId)
        return Manager(
            name = repository.displayNames(connection, listOf(userId))[userId] ?: "",
            currentTitle = view?.title,
        )
    }

    private fun view(connection: Connection, userId: UUID, conversationId: UUID): ConversationDto =
        repository.conversationFor(connection, userId, conversationId)
            ?: throw AppException.Forbidden("Чат недоступен")

    /**
     * Событие чата — отдельно для каждого получателя.
     *
     * Карточка чата зависит от того, кто на неё смотрит: у личного чата
     * заголовок — имя собеседника, у каждого своя роль и свой счётчик
     * непрочитанных. Общая на всех карточка, построенная для автора
     * изменения, показала бы получателю его собственное имя в заголовке
     * и чужую роль.
     */
    private fun conversationViews(
        connection: Connection,
        conversationId: UUID,
        recipients: Collection<UUID>,
        type: String,
    ): List<OutgoingEvent> = recipients.mapNotNull { recipient ->
        repository.conversationFor(connection, recipient, conversationId)?.let { view ->
            OutgoingEvent(
                scope = EventScope.CONVERSATION,
                scopeId = conversationId,
                type = type,
                payloadJson = ApiJson.encodeToString(ConversationCreatedPayload(view)),
                recipients = listOf(recipient),
            )
        }
    }

    private fun memberUpdatedEvent(conversationId: UUID, member: MemberDto, recipients: List<UUID>) =
        OutgoingEvent(
            scope = EventScope.CONVERSATION,
            scopeId = conversationId,
            type = EventTypes.MEMBER_UPDATED,
            payloadJson = ApiJson.encodeToString(MemberUpdatedPayload(conversationId, member)),
            recipients = recipients,
        )

    private fun messageEvent(conversationId: UUID, message: MessageDto, recipients: List<UUID>) =
        OutgoingEvent(
            scope = EventScope.CONVERSATION,
            scopeId = conversationId,
            type = EventTypes.MESSAGE_CREATED,
            payloadJson = ApiJson.encodeToString(MessageCreatedPayload(message)),
            recipients = recipients,
        )

    /**
     * Системное сообщение о смене состава или настроек группы.
     *
     * Попадает в общую нумерацию сообщений: так изменения состава видны
     * в истории на своём месте, а клиент, пропустивший событие, узнает
     * о них при обычной догрузке истории. Блокировка чата уже взята.
     */
    private fun appendSystemMessage(
        connection: Connection,
        conversationId: UUID,
        actorId: UUID,
        now: Instant,
        event: SystemEventDto,
    ): MessageDto {
        val seq = repository.nextSeq(connection, conversationId)
        val messageId = ids.newId()
        repository.insertMessage(
            connection = connection,
            id = messageId,
            conversationId = conversationId,
            senderId = actorId,
            clientMsgId = "system:$messageId",
            seq = seq,
            type = "SYSTEM",
            body = ApiJson.encodeToString(SystemEventDto.serializer(), event),
            replyToId = null,
            now = now,
        )
        repository.touchConversation(connection, conversationId, messageId, now)
        return repository.messageById(connection, messageId)
            ?: throw AppException.Internal("Системное сообщение не читается")
    }

    private suspend fun publish(events: List<StoredEvent>) {
        if (events.isNotEmpty()) eventBus.publish(events)
    }

    companion object {
        const val MAX_MEMBERS = 200
        const val MAX_TITLE_LENGTH = 128
        private val ROLES = setOf("OWNER", "ADMIN", "MEMBER")
        const val MIN_SEARCH_LENGTH = 2
        const val DEFAULT_LIMIT = 50

        /**
         * Нормализованный ключ личного чата.
         *
         * Пара участников упорядочивается, поэтому «я написал ему» и
         * «он написал мне» дают один и тот же ключ, а уникальный индекс
         * делает второй личный чат невозможным.
         */
        fun directKeyOf(a: UUID, b: UUID): String {
            val first = minOf(a.toString(), b.toString())
            val second = maxOf(a.toString(), b.toString())
            return "$first:$second"
        }

        /** В личном чате ролей нет, в групповом создатель становится владельцем. */
        private fun ownerRoleFor(type: String): String =
            if (type == "GROUP") "OWNER" else "MEMBER"
    }
}
