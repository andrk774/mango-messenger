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
                listOf(
                    OutgoingEvent(
                        scope = EventScope.CONVERSATION,
                        scopeId = createdId,
                        type = EventTypes.CONVERSATION_CREATED,
                        payloadJson = ApiJson.encodeToString(ConversationCreatedPayload(created)),
                        recipients = recipients,
                    ),
                ),
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

    private suspend fun publish(events: List<StoredEvent>) {
        if (events.isNotEmpty()) eventBus.publish(events)
    }

    companion object {
        const val MAX_MEMBERS = 200
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
