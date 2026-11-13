package com.mango.core.data.repository

import androidx.room.withTransaction
import com.mango.core.common.AppError
import com.mango.core.common.DispatcherProvider
import com.mango.core.common.IdGenerator
import com.mango.core.common.Logger
import com.mango.core.common.Outcome
import com.mango.core.common.Redact
import com.mango.core.common.TimeProvider
import com.mango.core.data.mapper.senderEntity
import com.mango.core.data.mapper.storeServerAttachments
import com.mango.core.data.mapper.toDomain
import com.mango.core.data.mapper.toEntity
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
import com.mango.core.network.MessagingRemoteDataSource
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
class DefaultMessageRepository @Inject constructor(
    private val database: MangoDatabase,
    private val remote: MessagingRemoteDataSource,
    private val attachmentsRemote: com.mango.core.network.AttachmentsRemoteDataSource,
    private val files: com.mango.core.data.attachment.AttachmentFileStore,
    private val idGenerator: IdGenerator,
    private val time: TimeProvider,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) : MessageRepository {

    private val messageDao = database.messageDao()
    private val outboxDao = database.outboxDao()
    private val userDao = database.userDao()
    private val attachmentDao = database.attachmentDao()

    override fun observeRecentMessages(
        conversationId: ConversationId,
        limit: Int,
    ): Flow<List<Message>> =
        combine(
            messageDao.observeRecent(conversationId.value, limit),
            userDao.observeCurrentUser(),
            // Отдельный поток вложений: прогресс загрузки меняет только
            // таблицу вложений, и без него полоса прогресса стояла бы на месте.
            attachmentDao.observeForConversation(conversationId.value),
        ) { entities, me, attachments ->
            val currentUserId = me?.id.orEmpty()
            val reactions = messageDao.reactionsFor(entities.map { it.id })
                .groupBy { it.messageId }
            val byMessage = attachments.groupBy { it.messageId }
            entities.map { entity ->
                entity.toDomain(
                    reactions = reactions[entity.id]
                        ?.toDomainReactions(currentUserId)
                        .orEmpty(),
                    attachments = byMessage[entity.id].orEmpty().map { it.toDomain() },
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

    override suspend fun sendAttachment(
        conversationId: ConversationId,
        uri: android.net.Uri,
        caption: String?,
    ): Outcome<ClientOpId> = withContext(dispatchers.io) {
        val senderId = userDao.currentUserId()
            ?: return@withContext Outcome.Failure(AppError.Unauthorized)

        val attachmentId = idGenerator.newId()
        val imported = try {
            files.import(uri, attachmentId)
        } catch (e: com.mango.core.data.attachment.AttachmentRejected) {
            return@withContext Outcome.Failure(AppError.Validation(mapOf("file" to (e.message ?: "Файл не подходит"))))
        } catch (e: Exception) {
            logger.e(TAG, "не удалось подготовить файл: ${Redact.text(e.message)}", e)
            return@withContext Outcome.Failure(AppError.Storage(e))
        }

        val type = when {
            imported.mimeType.startsWith("image/") -> MessageType.IMAGE
            imported.mimeType.startsWith("video/") -> MessageType.VIDEO
            imported.mimeType.startsWith("audio/") -> MessageType.VOICE
            else -> MessageType.FILE
        }
        val text = caption?.trim()?.takeIf { it.isNotEmpty() }
        val clientOpId = idGenerator.newId()
        val uploadOpId = idGenerator.newId()
        val now = time.nowMillis()

        runCatching {
            database.withTransaction {
                messageDao.insertIgnore(
                    MessageEntity(
                        id = clientOpId,
                        conversationId = conversationId.value,
                        senderId = senderId,
                        clientMsgId = clientOpId,
                        seq = null,
                        type = type,
                        body = text,
                        replyToId = null,
                        replyToSenderName = null,
                        replyToSnapshot = null,
                        forwardedFromId = null,
                        forwardedFromSenderName = null,
                        deliveryStatus = MessageDeliveryStatus.PENDING,
                        syncStatus = SyncStatus.PENDING,
                        pinnedAt = null,
                        editedAt = null,
                        deletedAt = null,
                        createdAt = now,
                    ),
                )
                attachmentDao.upsert(
                    com.mango.core.database.entity.AttachmentEntity(
                        id = attachmentId,
                        messageId = clientOpId,
                        taskId = null,
                        mimeType = imported.mimeType,
                        sizeBytes = imported.sizeBytes,
                        fileName = imported.fileName,
                        remoteUrl = null,
                        localUri = imported.file.path,
                        width = imported.width,
                        height = imported.height,
                        durationMs = null,
                        uploadProgress = 0f,
                        syncStatus = SyncStatus.PENDING,
                        createdAt = now,
                    ),
                )
                outboxDao.enqueue(
                    outboxOperation(
                        clientOpId = uploadOpId,
                        opType = OpType.UPLOAD_ATTACHMENT,
                        entityType = EntityType.ATTACHMENT,
                        entityId = attachmentId,
                        payloadJson = OutboxJson.encodeToString(
                            com.mango.core.data.outbox.UploadAttachmentPayload(
                                attachmentId = attachmentId,
                                conversationId = conversationId.value,
                                localPath = imported.file.path,
                                mimeType = imported.mimeType,
                                sizeBytes = imported.sizeBytes,
                                sha256 = imported.sha256,
                                fileName = imported.fileName,
                                width = imported.width,
                                height = imported.height,
                            ),
                        ),
                        nowMillis = now,
                    ),
                )
                // Сообщение уходит только после загрузки файла: сервер
                // не примет ссылку на вложение, которого у него ещё нет.
                outboxDao.enqueue(
                    outboxOperation(
                        clientOpId = clientOpId,
                        opType = OpType.SEND_MESSAGE,
                        entityType = EntityType.MESSAGE,
                        entityId = clientOpId,
                        payloadJson = OutboxJson.encodeToString(
                            SendMessagePayload(
                                conversationId = conversationId.value,
                                clientMsgId = clientOpId,
                                text = text,
                                type = type.name,
                                attachmentIds = listOf(attachmentId),
                            ),
                        ),
                        nowMillis = now,
                        dependsOnOpId = uploadOpId,
                    ),
                )
            }
        }.fold(
            onSuccess = { Outcome.Success(ClientOpId(clientOpId)) },
            onFailure = { throwable ->
                imported.file.delete()
                logger.e(TAG, "не удалось поставить файл в очередь: ${Redact.text(throwable.message)}", throwable)
                Outcome.Failure(AppError.Storage(throwable))
            },
        )
    }

    override suspend fun attachmentFile(id: com.mango.core.model.AttachmentId): Outcome<java.io.File> =
        withContext(dispatchers.io) {
            val entity = attachmentDao.findById(id.value)
                ?: return@withContext Outcome.Failure(AppError.NotFound("attachment"))

            entity.localUri?.let { java.io.File(it) }?.takeIf { it.isFile }?.let {
                return@withContext Outcome.Success(it)
            }

            when (val link = attachmentsRemote.downloadUrl(id.value)) {
                is Outcome.Failure -> link
                is Outcome.Success -> {
                    val target = files.downloadTarget(id.value, entity.fileName)
                    when (val downloaded = attachmentsRemote.download(link.value.url, target)) {
                        is Outcome.Failure -> downloaded
                        is Outcome.Success -> {
                            attachmentDao.setLocalUri(id.value, downloaded.value.path)
                            downloaded
                        }
                    }
                }
            }
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

    /**
     * Догрузка истории с сервера.
     *
     * Записывается только пришедшее: локальные неотправленные сообщения
     * не трогаются, иначе прокрутка истории стирала бы то, что
     * пользователь ещё не успел отправить.
     */
    override suspend fun loadHistory(
        conversationId: ConversationId,
        beforeSeq: Long?,
        limit: Int,
    ): Outcome<Int> = withContext(dispatchers.io) {
        when (val response = remote.messages(conversationId.value, beforeSeq, null, limit)) {
            is Outcome.Failure -> response
            is Outcome.Success -> {
                runCatching {
                    database.withTransaction {
                        response.value.items.forEach { dto ->
                            if (userDao.findById(dto.senderId) == null) {
                                userDao.upsert(senderEntity(dto.senderId, dto.senderName, null))
                            }
                            messageDao.upsert(dto.toEntity())
                            database.storeServerAttachments(dto, time.nowMillis())
                        }
                    }
                    response.value.items.size
                }.fold({ Outcome.Success(it) }, { Outcome.Failure(AppError.Storage(it)) })
            }
        }
    }

    private companion object {
        const val TAG = "MessageRepo"
    }
}
