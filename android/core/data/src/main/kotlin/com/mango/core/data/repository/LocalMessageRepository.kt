package com.mango.core.data.repository

import androidx.room.withTransaction
import com.mango.core.common.AppError
import com.mango.core.common.DispatcherProvider
import com.mango.core.common.IdGenerator
import com.mango.core.common.Logger
import com.mango.core.common.Outcome
import com.mango.core.common.Redact
import com.mango.core.common.TimeProvider
import com.mango.core.data.mapper.toDomain
import com.mango.core.data.mapper.toDomainReactions
import com.mango.core.data.outbox.DeleteMessagePayload
import com.mango.core.data.outbox.EditMessagePayload
import com.mango.core.data.outbox.EntityType
import com.mango.core.data.outbox.OpType
import com.mango.core.data.outbox.OutboxJson
import com.mango.core.data.outbox.outboxOperation
import com.mango.core.data.outbox.SendMessagePayload
import com.mango.core.data.outbox.ToggleReactionPayload
import com.mango.core.database.MangoDatabase
import com.mango.core.database.entity.MessageEntity
import com.mango.core.database.entity.MessageReactionEntity
import com.mango.core.model.ClientOpId
import com.mango.core.model.ConversationId
import com.mango.core.model.Message
import com.mango.core.model.MessageDeliveryStatus
import com.mango.core.model.MessageId
import com.mango.core.model.MessageType
import com.mango.core.model.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Локальная реализация репозитория сообщений.
 *
 * На этом этапе сетевого слоя ещё нет, и это сделано осознанно: отправка
 * полностью работает офлайн — сообщение появляется в интерфейсе сразу,
 * а операция ложится в персистентную очередь. Когда на Этапе 4 появится
 * API-клиент, добавится только процессор очереди; контракт репозитория
 * и поведение интерфейса не изменятся.
 */
@Singleton
class LocalMessageRepository @Inject constructor(
    private val database: MangoDatabase,
    private val idGenerator: IdGenerator,
    private val time: TimeProvider,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) : MessageRepository {

    private val messageDao = database.messageDao()
    private val outboxDao = database.outboxDao()
    private val userDao = database.userDao()

    override fun observeRecentMessages(
        conversationId: ConversationId,
        limit: Int,
    ): Flow<List<Message>> =
        messageDao.observeRecent(conversationId.value, limit)
            .combine(userDao.observeCurrentUser()) { entities, me ->
                val currentUserId = me?.id.orEmpty()
                val reactions = messageDao.reactionsFor(entities.map { it.id })
                    .groupBy { it.messageId }
                entities.map { entity ->
                    entity.toDomain(
                        reactions = reactions[entity.id]
                            ?.toDomainReactions(currentUserId)
                            .orEmpty(),
                    )
                }
            }

    override fun observePinnedMessages(conversationId: ConversationId): Flow<List<Message>> =
        messageDao.observePinned(conversationId.value).map { list -> list.map { it.toDomain() } }

    override suspend fun sendText(
        conversationId: ConversationId,
        text: String,
        replyTo: MessageId?,
    ): Outcome<ClientOpId> = withContext(dispatchers.io) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return@withContext Outcome.Failure(
                AppError.Validation(mapOf("text" to "Сообщение не может быть пустым")),
            )
        }

        val senderId = userDao.currentUserId()
            ?: return@withContext Outcome.Failure(AppError.Unauthorized)

        // clientOpId генерируется ДО записи и переживает перезапуск приложения:
        // он же служит ключом идемпотентности на сервере.
        val clientOpId = idGenerator.newId()
        val now = time.nowMillis()

        val replySnapshot = replyTo?.let { messageDao.findById(it.value) }

        val message = MessageEntity(
            // Локальный id совпадает с clientOpId — после подтверждения
            // сервером строка переедет на серверный id (см. confirmSent).
            id = clientOpId,
            conversationId = conversationId.value,
            senderId = senderId,
            clientMsgId = clientOpId,
            seq = null,
            type = MessageType.TEXT,
            body = trimmed,
            replyToId = replyTo?.value,
            replyToSenderName = replySnapshot?.let {
                userDao.findById(it.senderId)?.displayName
            },
            replyToSnapshot = replySnapshot?.body,
            forwardedFromId = null,
            forwardedFromSenderName = null,
            deliveryStatus = MessageDeliveryStatus.PENDING,
            syncStatus = SyncStatus.PENDING,
            pinnedAt = null,
            editedAt = null,
            deletedAt = null,
            createdAt = now,
        )

        val payload = SendMessagePayload(
            conversationId = conversationId.value,
            clientMsgId = clientOpId,
            text = trimmed,
            replyToId = replyTo?.value,
        )

        runCatching {
            // Оптимистичная запись и постановка в очередь — одной транзакцией.
            // Иначе возможно состояние «сообщение видно, но никогда не уедет»
            // или наоборот — операция есть, а сообщения в интерфейсе нет.
            database.withTransaction {
                messageDao.insertIgnore(message)
                outboxDao.enqueue(
                    outboxOperation(
                        clientOpId = clientOpId,
                        opType = OpType.SEND_MESSAGE,
                        entityType = EntityType.MESSAGE,
                        entityId = clientOpId,
                        payloadJson = OutboxJson.encodeToString(payload),
                        nowMillis = now,
                    ),
                )
            }
        }.fold(
            onSuccess = {
                logger.d(
                    TAG,
                    "queued message op=${Redact.id(clientOpId)} " +
                        "conv=${Redact.id(conversationId.value)} ${Redact.messageBody(trimmed)}",
                )
                Outcome.Success(ClientOpId(clientOpId))
            },
            onFailure = { throwable ->
                logger.e(TAG, "failed to queue message: ${Redact.text(throwable.message)}", throwable)
                Outcome.Failure(AppError.Storage(throwable))
            },
        )
    }

    override suspend fun retry(clientOpId: ClientOpId): Outcome<Unit> =
        withContext(dispatchers.io) {
            val operation = outboxDao.findByClientOpId(clientOpId.value)
                ?: return@withContext Outcome.Failure(AppError.NotFound("operation"))

            val now = time.nowMillis()
            runCatching {
                database.withTransaction {
                    // Повтор вручную: сбрасываем расписание, но НЕ счётчик попыток —
                    // он нужен для диагностики «залипших» операций.
                    outboxDao.scheduleRetry(
                        id = operation.id,
                        nextAttemptAt = now,
                        error = null,
                        nowMillis = now,
                    )
                    messageDao.updateStatus(
                        id = operation.entityId,
                        status = MessageDeliveryStatus.PENDING,
                        syncStatus = SyncStatus.PENDING,
                    )
                }
            }.fold(
                onSuccess = { Outcome.Success(Unit) },
                onFailure = { Outcome.Failure(AppError.Storage(it)) },
            )
        }

    override suspend fun editText(id: MessageId, newText: String): Outcome<Unit> =
        withContext(dispatchers.io) {
            val trimmed = newText.trim()
            if (trimmed.isEmpty()) {
                return@withContext Outcome.Failure(
                    AppError.Validation(mapOf("text" to "Сообщение не может быть пустым")),
                )
            }
            val existing = messageDao.findById(id.value)
                ?: return@withContext Outcome.Failure(AppError.NotFound("message"))
            if (existing.deletedAt != null) {
                return@withContext Outcome.Failure(AppError.NotFound("message"))
            }

            val now = time.nowMillis()
            val clientOpId = idGenerator.newId()

            runCatching {
                database.withTransaction {
                    messageDao.applyEdit(id.value, trimmed, now, SyncStatus.PENDING)
                    outboxDao.enqueue(
                        outboxOperation(
                            clientOpId = clientOpId,
                            opType = OpType.EDIT_MESSAGE,
                            entityType = EntityType.MESSAGE,
                            entityId = id.value,
                            payloadJson = OutboxJson.encodeToString(
                                EditMessagePayload(id.value, trimmed),
                            ),
                            nowMillis = now,
                            // Правка неподтверждённого сообщения должна уехать
                            // только после его отправки.
                            dependsOnOpId = existing.clientMsgId.takeIf { existing.seq == null },
                        ),
                    )
                }
            }.fold({ Outcome.Success(Unit) }, { Outcome.Failure(AppError.Storage(it)) })
        }

    override suspend fun delete(id: MessageId, forEveryone: Boolean): Outcome<Unit> =
        withContext(dispatchers.io) {
            val existing = messageDao.findById(id.value)
                ?: return@withContext Outcome.Failure(AppError.NotFound("message"))

            val now = time.nowMillis()
            val clientOpId = idGenerator.newId()

            runCatching {
                database.withTransaction {
                    messageDao.markDeleted(id.value, now, SyncStatus.PENDING)
                    // Задачи, созданные из этого сообщения, теряют связь с источником,
                    // но сохраняют снапшот текста.
                    database.taskDao().markSourceUnavailable(id.value)
                    outboxDao.enqueue(
                        outboxOperation(
                            clientOpId = clientOpId,
                            opType = OpType.DELETE_MESSAGE,
                            entityType = EntityType.MESSAGE,
                            entityId = id.value,
                            payloadJson = OutboxJson.encodeToString(
                                DeleteMessagePayload(id.value, forEveryone),
                            ),
                            nowMillis = now,
                        ),
                    )
                }
            }.fold({ Outcome.Success(Unit) }, { Outcome.Failure(AppError.Storage(it)) })
        }

    override suspend fun toggleReaction(id: MessageId, emoji: String): Outcome<Unit> =
        withContext(dispatchers.io) {
            val currentUserId = userDao.currentUserId()
                ?: return@withContext Outcome.Failure(AppError.Unauthorized)

            val existing = messageDao.reactionsFor(listOf(id.value))
                .firstOrNull { it.userId == currentUserId && it.emoji == emoji }
            val add = existing == null
            val now = time.nowMillis()
            val clientOpId = idGenerator.newId()

            runCatching {
                database.withTransaction {
                    if (add) {
                        messageDao.upsertReaction(
                            MessageReactionEntity(
                                messageId = id.value,
                                userId = currentUserId,
                                emoji = emoji,
                                syncStatus = SyncStatus.PENDING,
                                createdAt = now,
                            ),
                        )
                    } else {
                        messageDao.deleteReaction(id.value, currentUserId, emoji)
                    }
                    outboxDao.enqueue(
                        outboxOperation(
                            clientOpId = clientOpId,
                            opType = OpType.TOGGLE_REACTION,
                            entityType = EntityType.MESSAGE,
                            entityId = id.value,
                            payloadJson = OutboxJson.encodeToString(
                                ToggleReactionPayload(id.value, emoji, add),
                            ),
                            nowMillis = now,
                        ),
                    )
                }
            }.fold({ Outcome.Success(Unit) }, { Outcome.Failure(AppError.Storage(it)) })
        }

    override suspend fun search(
        query: String,
        conversationId: ConversationId?,
    ): Outcome<List<Message>> = withContext(dispatchers.io) {
        if (query.isBlank()) return@withContext Outcome.Success(emptyList())
        runCatching {
            messageDao.search(query.trim(), conversationId?.value).map { it.toDomain() }
        }.fold(
            { Outcome.Success(it) },
            { Outcome.Failure(AppError.Storage(it)) },
        )
    }

    private companion object {
        const val TAG = "MessageRepo"
    }
}
