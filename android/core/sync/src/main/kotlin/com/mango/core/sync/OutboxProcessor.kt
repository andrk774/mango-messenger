package com.mango.core.sync

import com.mango.core.common.AppError
import com.mango.core.common.Logger
import com.mango.core.common.Outcome
import com.mango.core.common.Redact
import com.mango.core.common.RetryPolicy
import com.mango.core.common.TimeProvider
import com.mango.core.data.outbox.DeleteMessagePayload
import com.mango.core.data.outbox.EditMessagePayload
import com.mango.core.data.outbox.MarkReadPayload
import com.mango.core.data.outbox.OpType
import com.mango.core.data.outbox.OutboxJson
import com.mango.core.data.outbox.SendMessagePayload
import com.mango.core.database.MangoDatabase
import com.mango.core.database.entity.OutboxOperationEntity
import com.mango.core.database.entity.OutboxStatus
import com.mango.core.model.MessageDeliveryStatus
import com.mango.core.model.SyncStatus
import com.mango.core.common.map
import com.mango.core.network.MessagingRemoteDataSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Итог одного прохода по очереди. */
data class OutboxRunResult(
    val processed: Int,
    val failed: Int,
    val retriesScheduled: Int,
) {
    val hasWork: Boolean get() = processed + failed + retriesScheduled > 0
}

/**
 * Отправка операций из очереди на сервер.
 *
 * Это недостающее звено между локальной записью и сервером: репозитории
 * уже кладут операции в очередь, схема уже гарантирует отсутствие
 * дубликатов — здесь операции наконец уезжают.
 *
 * Повтор при неизвестном исходе безопасен: ключом идемпотентности служит
 * `clientMsgId`, и сервер возвращает ранее созданное сообщение вместо
 * создания второго.
 */
@Singleton
class OutboxProcessor @Inject constructor(
    private val database: MangoDatabase,
    private val remote: MessagingRemoteDataSource,
    private val applier: EventApplier,
    private val time: TimeProvider,
    private val logger: Logger,
    private val retryPolicy: RetryPolicy = RetryPolicy.Default,
) {

    private val outboxDao = database.outboxDao()
    private val messageDao = database.messageDao()
    private val conversationDao = database.conversationDao()

    /**
     * Один проход выполняется в единственном экземпляре.
     *
     * Запуск по расписанию, по появлению сети и вручную может совпасть.
     * Без замка две копии прохода взяли бы одни и те же операции
     * и отправили каждую дважды — от дубликата спасла бы только
     * серверная идемпотентность, но лишние запросы ни к чему.
     */
    private val runMutex = Mutex()

    suspend fun runOnce(limit: Int = BATCH_SIZE): OutboxRunResult = runMutex.withLock {
        val now = time.nowMillis()

        // Восстановление после обрыва: операции, оставшиеся в полёте,
        // возвращаются в очередь. Повтор безопасен по построению.
        outboxDao.recoverInFlight(now)
        outboxDao.unblockResolved(now)

        val batch = outboxDao.nextBatchOfTypes(now, SUPPORTED_OP_TYPES, limit)
        if (batch.isEmpty()) return OutboxRunResult(0, 0, 0)

        var processed = 0
        var failed = 0
        var retries = 0

        for (operation in batch) {
            outboxDao.updateStatus(operation.id, OutboxStatus.IN_FLIGHT, time.nowMillis())

            when (val outcome = dispatch(operation)) {
                is Outcome.Success -> {
                    outboxDao.delete(operation.id)
                    outboxDao.unblockResolved(time.nowMillis())
                    processed++
                }

                is Outcome.Failure -> {
                    val error = outcome.error
                    if (retryPolicy.shouldRetry(error, operation.attemptCount)) {
                        val delay = retryPolicy.effectiveDelayMillis(error, operation.attemptCount)
                        outboxDao.scheduleRetry(
                            id = operation.id,
                            nextAttemptAt = time.nowMillis() + delay,
                            error = error::class.simpleName,
                            nowMillis = time.nowMillis(),
                        )
                        retries++
                    } else {
                        // Повтор не изменит исход: операция остаётся
                        // в очереди со статусом отказа, и решение
                        // принимает пользователь.
                        outboxDao.markFailed(
                            operation.id,
                            error = describe(error),
                            nowMillis = time.nowMillis(),
                        )
                        markEntityFailed(operation)
                        failed++
                    }
                }
            }
        }

        logger.i(
            TAG,
            "очередь: отправлено $processed, отложено $retries, отклонено $failed",
        )
        return OutboxRunResult(processed, failed, retries)
    }

    private suspend fun dispatch(operation: OutboxOperationEntity): Outcome<Unit> =
        when (operation.opType) {
            OpType.SEND_MESSAGE -> sendMessage(operation)
            OpType.EDIT_MESSAGE -> editMessage(operation)
            OpType.DELETE_MESSAGE -> deleteMessage(operation)
            OpType.MARK_READ -> markRead(operation)

            // Черновик живёт только на устройстве: серверного хранения
            // для него нет, поэтому операция просто считается выполненной.
            OpType.SAVE_DRAFT -> Outcome.Success(Unit)

            else -> Outcome.Failure(
                AppError.Unknown(
                    IllegalStateException("Операция ${operation.opType} не поддерживается"),
                ),
            )
        }

    private suspend fun sendMessage(operation: OutboxOperationEntity): Outcome<Unit> {
        val payload = decode<SendMessagePayload>(operation) ?: return malformed(operation)

        val result = remote.sendMessage(
            conversationId = payload.conversationId,
            clientMsgId = payload.clientMsgId,
            body = payload.text,
            replyToId = payload.replyToId,
        )

        return when (result) {
            is Outcome.Success -> {
                // Ответ применяется тем же кодом, что и событие из сокета:
                // одно место, где серверное сообщение попадает в базу.
                applier.applyServerMessage(result.value)
                Outcome.Success(Unit)
            }

            is Outcome.Failure -> result
        }
    }

    private suspend fun editMessage(operation: OutboxOperationEntity): Outcome<Unit> {
        val payload = decode<EditMessagePayload>(operation) ?: return malformed(operation)

        val result = remote.editMessage(payload.messageId, payload.text)
        return when (result) {
            is Outcome.Success -> {
                applier.applyServerMessage(result.value)
                Outcome.Success(Unit)
            }

            is Outcome.Failure -> result
        }
    }

    private suspend fun deleteMessage(operation: OutboxOperationEntity): Outcome<Unit> {
        val payload = decode<DeleteMessagePayload>(operation) ?: return malformed(operation)
        return remote.deleteMessage(payload.messageId)
    }

    private suspend fun markRead(operation: OutboxOperationEntity): Outcome<Unit> {
        val payload = decode<MarkReadPayload>(operation) ?: return malformed(operation)
        return remote.markRead(payload.conversationId, payload.seq)
    }

    /**
     * Помечает связанную запись как не отправленную.
     *
     * Без этого сообщение осталось бы в состоянии «отправляется» навсегда,
     * и пользователь не узнал бы, что оно не ушло.
     */
    private suspend fun markEntityFailed(operation: OutboxOperationEntity) {
        when (operation.entityType) {
            "MESSAGE" -> messageDao.updateStatus(
                id = operation.entityId,
                status = MessageDeliveryStatus.FAILED,
                syncStatus = SyncStatus.FAILED,
            )

            "TASK" -> database.taskDao().updateSyncStatus(operation.entityId, SyncStatus.FAILED)
        }
    }

    private inline fun <reified T> decode(operation: OutboxOperationEntity): T? =
        runCatching { OutboxJson.decodeFromString<T>(operation.payloadJson) }
            .onFailure {
                logger.e(
                    TAG,
                    "не удалось разобрать операцию ${Redact.id(operation.clientOpId)}",
                    it,
                )
            }
            .getOrNull()

    /**
     * Операция с нечитаемым содержимым.
     *
     * Повторять бессмысленно: содержимое не станет корректным само собой.
     * Такое возможно после обновления приложения, изменившего формат.
     */
    private fun malformed(operation: OutboxOperationEntity): Outcome<Unit> =
        Outcome.Failure(
            AppError.Validation(
                mapOf("payload" to "Операция ${operation.opType} записана в неизвестном формате"),
            ),
        )

    private fun describe(error: AppError): String = when (error) {
        is AppError.Validation -> error.fieldErrors.values.firstOrNull() ?: "Данные отклонены"
        AppError.Forbidden -> "Нет прав на операцию"
        is AppError.NotFound -> "Объект не найден"
        is AppError.Conflict -> "Конфликт изменений"
        else -> error::class.simpleName ?: "Ошибка"
    }

    companion object {
        const val BATCH_SIZE = 20
        private const val TAG = "OutboxProcessor"

        /**
         * Типы операций, которые принимает текущая версия сервера.
         *
         * Операции задач появятся здесь на Этапе 5. Пока они остаются
         * в очереди и не мешают отправке сообщений — именно ради этого
         * выборка фильтруется по типу.
         */
        val SUPPORTED_OP_TYPES = listOf(
            OpType.SEND_MESSAGE,
            OpType.EDIT_MESSAGE,
            OpType.DELETE_MESSAGE,
            OpType.MARK_READ,
            OpType.SAVE_DRAFT,
        )
    }
}
