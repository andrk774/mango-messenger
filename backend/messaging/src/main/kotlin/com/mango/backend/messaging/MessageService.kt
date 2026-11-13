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

/** Результат отправки: сообщение и признак того, что оно создано сейчас. */
data class SendResult(val message: MessageDto, val created: Boolean)

/**
 * Отправка, чтение и изменение сообщений.
 *
 * ## Идемпотентность
 *
 * Ключом служит `clientMsgId`, присвоенный устройством до отправки.
 * Отдельная запись в журнале идемпотентности для сообщений НЕ нужна:
 * уникальный индекс `(conversation_id, sender_id, client_msg_id)` уже
 * делает повторную вставку невозможной, а повтор возвращает ранее
 * созданное сообщение. Дублировать ту же гарантию во втором месте
 * означало бы иметь два источника истины для одного инварианта.
 */
class MessageService(
    private val database: Database,
    private val repository: MessagingRepository,
    private val eventLog: EventLog,
    private val eventBus: EventBus,
    private val ids: IdGenerator,
    private val time: TimeSource,
) {

    suspend fun send(
        userId: UUID,
        conversationId: UUID,
        request: SendMessageRequest,
    ): SendResult {
        val type = request.type.uppercase()
        val body = request.body?.trim()

        validate {
            requireNotBlank(request.clientMsgId, "clientMsgId", "Идентификатор операции обязателен")
            requireLength(request.clientMsgId, "clientMsgId", max = 64, min = 1)
            check(type in ALLOWED_TYPES, "type", "Недопустимый тип сообщения")
            if (type == "TEXT") {
                check(!body.isNullOrEmpty(), "body", "Текстовое сообщение не может быть пустым")
                check(request.attachmentIds.isEmpty(), "type", "Сообщение с вложениями не может иметь тип TEXT")
            } else {
                check(request.attachmentIds.isNotEmpty(), "attachmentIds", "Нужно хотя бы одно вложение")
            }
            check(
                request.attachmentIds.size <= MAX_ATTACHMENTS,
                "attachmentIds",
                "Не больше $MAX_ATTACHMENTS вложений в сообщении",
            )
            check(
                request.attachmentIds.distinct().size == request.attachmentIds.size,
                "attachmentIds",
                "Вложения повторяются",
            )
            requireLength(body, "body", max = MAX_BODY_LENGTH)
        }

        val now = time.now()

        val (result, events) = database.transaction { connection ->
            repository.membership(connection, conversationId, userId)
                ?: throw AppException.Forbidden("Чат недоступен")

            // Блокировка берётся ДО проверки на повтор. Порядок важен:
            // иначе два одновременных повтора одной операции прошли бы
            // проверку оба и столкнулись на уникальном индексе, прервав
            // транзакцию вместо того, чтобы вернуть одно и то же сообщение.
            if (!repository.lockConversation(connection, conversationId)) {
                throw AppException.Forbidden("Чат недоступен")
            }

            repository.findByClientMsgId(connection, conversationId, userId, request.clientMsgId)
                ?.let { return@transaction SendResult(it, created = false) to emptyList<StoredEvent>() }

            if (request.replyToId != null) {
                val replyTo = repository.messageById(connection, request.replyToId)
                // Ответ на сообщение из другого чата означает либо ошибку
                // клиента, либо попытку вытянуть чужой текст в свой чат.
                if (replyTo == null || replyTo.conversationId != conversationId) {
                    throw AppException.Validation(
                        mapOf("replyToId" to "Сообщение не найдено в этом чате"),
                    )
                }
            }

            val seq = repository.nextSeq(connection, conversationId)
            val messageId = ids.newId()

            repository.insertMessage(
                connection = connection,
                id = messageId,
                conversationId = conversationId,
                senderId = userId,
                clientMsgId = request.clientMsgId,
                seq = seq,
                type = type,
                body = body,
                replyToId = request.replyToId,
                now = now,
            )

            // Привязка вложений в той же транзакции: сообщение без своих
            // файлов или файлы, привязанные к несуществующему сообщению,
            // невозможны. Не привязалось хоть одно — откат всего.
            val claimed = repository.claimAttachments(
                connection, request.attachmentIds, userId, conversationId, messageId,
            )
            if (claimed.size != request.attachmentIds.size) {
                throw AppException.Validation(
                    mapOf(
                        "attachmentIds" to
                            "Вложение не найдено, ещё не загружено или уже использовано",
                    ),
                )
            }

            repository.touchConversation(connection, conversationId, messageId, now)

            val message = repository.messageById(connection, messageId)
                ?: throw AppException.Internal("Сообщение не читается после вставки")

            val stored = eventLog.append(
                connection,
                listOf(
                    OutgoingEvent(
                        scope = EventScope.CONVERSATION,
                        scopeId = conversationId,
                        type = EventTypes.MESSAGE_CREATED,
                        payloadJson = ApiJson.encodeToString(MessageCreatedPayload(message)),
                        recipients = repository.memberIds(connection, conversationId),
                    ),
                ),
            )

            SendResult(message, created = true) to stored
        }

        publish(events)
        return result
    }

    suspend fun history(
        userId: UUID,
        conversationId: UUID,
        beforeSeq: Long?,
        afterSeq: Long?,
        limit: Int,
    ): MessagesResponse = database.readOnly { connection ->
        repository.membership(connection, conversationId, userId)
            ?: throw AppException.Forbidden("Чат недоступен")

        MessagesResponse(
            repository.history(connection, conversationId, beforeSeq, afterSeq, limit),
        )
    }

    suspend fun edit(userId: UUID, messageId: UUID, newBody: String): MessageDto {
        val body = newBody.trim()
        validate {
            requireNotBlank(body, "body", "Сообщение не может быть пустым")
            requireLength(body, "body", max = MAX_BODY_LENGTH)
        }

        val now = time.now()

        val (message, events) = database.transaction { connection ->
            val existing = repository.messageById(connection, messageId)
                ?: throw AppException.NotFound("Сообщение")

            repository.membership(connection, existing.conversationId, userId)
                ?: throw AppException.Forbidden("Чат недоступен")

            // Править можно только свои сообщения. Права администратора
            // чата на это не распространяются: подмена чужих слов —
            // не модерация.
            if (existing.senderId != userId) {
                throw AppException.Forbidden("Можно изменять только свои сообщения")
            }
            if (existing.deletedAt != null) {
                throw AppException.Conflict("Сообщение удалено")
            }

            repository.editMessage(connection, messageId, body, now)

            val updated = repository.messageById(connection, messageId)
                ?: throw AppException.Internal("Сообщение не читается после изменения")

            val stored = eventLog.append(
                connection,
                listOf(
                    OutgoingEvent(
                        scope = EventScope.CONVERSATION,
                        scopeId = existing.conversationId,
                        type = EventTypes.MESSAGE_EDITED,
                        payloadJson = ApiJson.encodeToString(MessageEditedPayload(updated)),
                        recipients = repository.memberIds(connection, existing.conversationId),
                    ),
                ),
            )
            updated to stored
        }

        publish(events)
        return message
    }

    suspend fun delete(userId: UUID, messageId: UUID) {
        val now = time.now()

        val events = database.transaction { connection ->
            val existing = repository.messageById(connection, messageId)
                ?: throw AppException.NotFound("Сообщение")

            val membership = repository.membership(connection, existing.conversationId, userId)
                ?: throw AppException.Forbidden("Чат недоступен")

            // Своё сообщение удаляет автор; чужое — только администратор
            // или владелец группы, и это уже модерация.
            if (existing.senderId != userId && !membership.canManageMembers) {
                throw AppException.Forbidden("Недостаточно прав для удаления")
            }
            if (existing.deletedAt != null) return@transaction emptyList<StoredEvent>()

            repository.deleteMessage(connection, messageId, now)

            eventLog.append(
                connection,
                listOf(
                    OutgoingEvent(
                        scope = EventScope.CONVERSATION,
                        scopeId = existing.conversationId,
                        type = EventTypes.MESSAGE_DELETED,
                        payloadJson = ApiJson.encodeToString(
                            MessageDeletedPayload(
                                conversationId = existing.conversationId,
                                messageId = messageId,
                                seq = existing.seq,
                                deletedAt = now,
                            ),
                        ),
                        recipients = repository.memberIds(connection, existing.conversationId),
                    ),
                ),
            )
        }

        publish(events)
    }

    private suspend fun publish(events: List<StoredEvent>) {
        if (events.isNotEmpty()) eventBus.publish(events)
    }

    companion object {
        const val MAX_BODY_LENGTH = 8192
        const val DEFAULT_PAGE_SIZE = 50
        const val MAX_PAGE_SIZE = 100
        const val MAX_ATTACHMENTS = 10

        private val ALLOWED_TYPES = setOf("TEXT", "IMAGE", "VIDEO", "VOICE", "FILE")
    }
}
