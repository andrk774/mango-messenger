package com.mango.core.sync

import androidx.room.withTransaction
import com.mango.core.common.Logger
import com.mango.core.common.Redact
import com.mango.core.data.mapper.senderEntity
import com.mango.core.data.mapper.toEntity
import com.mango.core.database.MangoDatabase
import com.mango.core.database.entity.ConversationEntity
import com.mango.core.database.entity.MessageEntity
import com.mango.core.database.entity.SyncStateEntity
import com.mango.core.model.ConversationType
import com.mango.core.model.MemberRole
import com.mango.core.model.MessageDeliveryStatus
import com.mango.core.model.SyncStatus
import com.mango.core.network.dto.ConversationCreatedPayloadDto
import com.mango.core.network.dto.ConversationDto
import com.mango.core.network.dto.MessageCreatedPayloadDto
import com.mango.core.network.dto.MessageDeletedPayloadDto
import com.mango.core.network.dto.MessageDto
import com.mango.core.network.dto.MessageEditedPayloadDto
import com.mango.core.network.dto.ReadMarkerPayloadDto
import com.mango.core.network.dto.SyncEventDto
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Применение серверных событий к локальной базе.
 *
 * Единственное место, где событие превращается в изменение данных.
 * Это важно: события приходят двумя путями — через WebSocket и через
 * догонку по курсору, — и если бы каждый путь применял их по-своему,
 * состояние базы зависело бы от того, каким каналом пришло изменение.
 */
@Singleton
class EventApplier @Inject constructor(
    private val database: MangoDatabase,
    private val json: Json,
    private val logger: Logger,
) {

    private val messageDao = database.messageDao()
    private val conversationDao = database.conversationDao()
    private val userDao = database.userDao()
    private val outboxDao = database.outboxDao()

    /**
     * Применяет пачку событий и продвигает курсор одной транзакцией.
     *
     * Транзакция обязательна: иначе возможен разрыв, при котором курсор
     * продвинулся, а события не записались, и они потеряются навсегда —
     * повторно сервер их не пришлёт.
     */
    suspend fun applyBatch(events: List<SyncEventDto>, scope: String = GLOBAL_SCOPE) {
        if (events.isEmpty()) return

        database.withTransaction {
            events.forEach { event -> applyOne(event) }

            val lastId = events.maxOf { it.id }
            outboxDao.upsertSyncState(
                SyncStateEntity(
                    scope = scope,
                    lastEventId = lastId,
                    lastSyncedAt = System.currentTimeMillis(),
                ),
            )
            outboxDao.advanceCursor(scope, lastId, System.currentTimeMillis())
        }
    }

    /**
     * Применяет одно событие, пришедшее через WebSocket.
     *
     * Курсор продвигается так же, как при догонке: сокет не является
     * отдельным источником истины, он лишь доставляет то же самое раньше.
     */
    suspend fun applyRealtime(event: SyncEventDto) = applyBatch(listOf(event))

    /**
     * Применение сообщения, полученного прямым ответом сервера.
     *
     * Ответ на отправку и событие из журнала описывают одно и то же,
     * поэтому и применяются одинаково — иначе состояние базы зависело бы
     * от того, каким путём пришло подтверждение.
     */
    suspend fun applyServerMessage(dto: MessageDto) {
        database.withTransaction { applyMessage(dto) }
    }

    private suspend fun applyOne(event: SyncEventDto) {
        runCatching {
            when (event.type) {
                "MESSAGE_CREATED" -> applyMessage(
                    json.decodeFromJsonElement(
                        MessageCreatedPayloadDto.serializer(), event.payload,
                    ).message,
                )

                "MESSAGE_EDITED" -> applyMessage(
                    json.decodeFromJsonElement(
                        MessageEditedPayloadDto.serializer(), event.payload,
                    ).message,
                )

                "MESSAGE_DELETED" -> applyDeletion(
                    json.decodeFromJsonElement(
                        MessageDeletedPayloadDto.serializer(), event.payload,
                    ),
                )

                "CONVERSATION_CREATED", "CONVERSATION_UPDATED" -> applyConversation(
                    json.decodeFromJsonElement(
                        ConversationCreatedPayloadDto.serializer(), event.payload,
                    ).conversation,
                )

                "READ_MARKER_MOVED" -> applyReadMarker(
                    json.decodeFromJsonElement(
                        ReadMarkerPayloadDto.serializer(), event.payload,
                    ),
                )

                else -> logger.d(TAG, "событие ${event.type} пока не применяется")
            }
        }.onFailure { error ->
            // Одно нераспознанное событие не должно останавливать поток:
            // иначе клиент навсегда застрянет на этом месте курсора
            // и перестанет получать всё последующее.
            logger.w(
                TAG,
                "не удалось применить событие ${event.id} (${event.type}): " +
                    Redact.text(error.message),
            )
        }
    }

    /**
     * Запись сообщения, пришедшего с сервера.
     *
     * Сначала выполняется попытка сопоставить его с локальным
     * неподтверждённым сообщением по `client_msg_id`. Без этого
     * собственное отправленное сообщение появилось бы в переписке
     * дважды: один раз как локальное, второй — как серверное.
     */
    private suspend fun applyMessage(dto: MessageDto) {
        ensureConversationExists(dto.conversationId)

        rememberUser(dto.senderId, dto.senderName, null)

        // Сопоставление с локальной строкой: она переезжает на серверный
        // идентификатор, после чего обновляется содержимым с сервера.
        // Если совпадения нет, upsert просто вставит новое сообщение.
        messageDao.confirmSent(
            conversationId = dto.conversationId,
            clientMsgId = dto.clientMsgId,
            serverId = dto.id,
            seq = dto.seq,
            serverCreatedAt = dto.createdAt.toEpochMilli(),
            deliveryStatus = MessageDeliveryStatus.SENT,
            syncStatus = SyncStatus.SYNCED,
        )
        messageDao.upsert(dto.toEntity())

        conversationDao.advanceLastEventSeq(dto.conversationId, dto.seq)
    }

    private suspend fun applyDeletion(payload: MessageDeletedPayloadDto) {
        messageDao.markDeleted(
            id = payload.messageId,
            deletedAt = payload.deletedAt.toEpochMilli(),
            syncStatus = SyncStatus.SYNCED,
        )
        // Задача, созданная из этого сообщения, теряет связь с источником,
        // но сохраняет снимок текста.
        database.taskDao().markSourceUnavailable(payload.messageId)
    }

    private suspend fun applyConversation(dto: ConversationDto) {
        conversationDao.upsert(dto.toEntity())
    }

    private suspend fun applyReadMarker(payload: ReadMarkerPayloadDto) {
        // Чужая отметка не меняет наш счётчик непрочитанных — она
        // касается собеседника. Обновляется только его строка участия,
        // чтобы интерфейс мог показать «прочитано» под своим сообщением.
        conversationDao.advanceMemberReadSeq(
            conversationId = payload.conversationId,
            userId = payload.userId,
            seq = payload.seq,
        )
    }

    /**
     * Создаёт заглушку чата, если события пришли раньше самого чата.
     *
     * Порядок событий гарантирован, но первичная загрузка могла
     * произойти до создания чата. Внешний ключ сообщений не должен
     * блокировать применение события.
     */
    private suspend fun ensureConversationExists(conversationId: String) {
        if (conversationDao.findById(conversationId) != null) return

        conversationDao.upsert(
            ConversationEntity(
                id = conversationId,
                type = ConversationType.DIRECT,
                title = "",
                avatarUrl = null,
                memberCount = 0,
                pinnedMessageCount = 0,
                lastEventSeq = 0,
                lastReadSeq = 0,
                mutedUntil = null,
                draft = null,
                myRole = MemberRole.MEMBER,
                isArchived = false,
                leftAt = null,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Сохранение сведений об авторе.
     *
     * Существующая запись не перезаписывается: у неё могут быть данные
     * полнее тех, что приносит сообщение, — настоящее имя пользователя,
     * аватар, признак «это я».
     */
    suspend fun rememberUser(id: String, displayName: String, avatarUrl: String?) {
        if (userDao.findById(id) != null) return
        userDao.upsert(senderEntity(id, displayName, avatarUrl))
    }

    companion object {
        const val GLOBAL_SCOPE = "GLOBAL"
        private const val TAG = "EventApplier"
    }
}
