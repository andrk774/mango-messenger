package com.mango.core.sync

import com.mango.core.common.AppError
import com.mango.core.common.Logger
import com.mango.core.common.Outcome
import com.mango.core.common.Redact
import com.mango.core.common.RetryPolicy
import com.mango.core.common.TimeProvider
import com.mango.core.data.outbox.AddTaskCommentPayload
import com.mango.core.data.outbox.CreateTaskPayload
import com.mango.core.data.outbox.DeleteMessagePayload
import com.mango.core.data.outbox.DeleteTaskPayload
import com.mango.core.data.outbox.PatchTaskPayload
import com.mango.core.data.outbox.SetTaskAssigneesPayload
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
import com.mango.core.network.TasksRemoteDataSource
import com.mango.core.network.AttachmentsRemoteDataSource
import com.mango.core.network.dto.UploadTicketRequestDto
import com.mango.core.data.outbox.UploadAttachmentPayload
import java.io.File
import java.time.Instant
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
    private val tasksRemote: TasksRemoteDataSource,
    private val attachmentsRemote: AttachmentsRemoteDataSource,
    private val applier: EventApplier,
    private val conflicts: TaskConflictNotifier,
    private val time: TimeProvider,
    private val logger: Logger,
    private val retryPolicy: RetryPolicy = RetryPolicy.Default,
) {

    private val outboxDao = database.outboxDao()
    private val messageDao = database.messageDao()
    private val conversationDao = database.conversationDao()
    private val attachmentDao = database.attachmentDao()

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
                    // Ответ сервера применяется ПОСЛЕ удаления операции.
                    // Порядок важен для задач: при применении поверх
                    // серверной версии накладываются ещё не отправленные
                    // правки, и только что отправленная правка не должна
                    // попасть в их число — иначе отклонённое сервером
                    // значение вернулось бы в интерфейс.
                    outcome.value.invoke()
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
                        failDependents(operation)
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

    /**
     * Отправка операции.
     *
     * Успех несёт действие «применить ответ сервера», которое выполняется
     * после удаления операции из очереди (см. [runOnce]).
     */
    private suspend fun dispatch(operation: OutboxOperationEntity): Outcome<AfterCommit> =
        when (operation.opType) {
            OpType.SEND_MESSAGE -> sendMessage(operation)
            OpType.EDIT_MESSAGE -> editMessage(operation)
            OpType.DELETE_MESSAGE -> deleteMessage(operation)
            OpType.MARK_READ -> markRead(operation)
            OpType.UPLOAD_ATTACHMENT -> uploadAttachment(operation)

            // Черновик живёт только на устройстве: серверного хранения
            // для него нет, поэтому операция просто считается выполненной.
            OpType.SAVE_DRAFT -> Outcome.Success(NOTHING)

            OpType.CREATE_TASK -> createTask(operation)
            OpType.PATCH_TASK -> patchTask(operation)
            OpType.DELETE_TASK -> deleteTask(operation)
            OpType.SET_TASK_ASSIGNEES -> setAssignees(operation)
            OpType.ADD_TASK_COMMENT -> addComment(operation)

            else -> Outcome.Failure(
                AppError.Unknown(
                    IllegalStateException("Операция ${operation.opType} не поддерживается"),
                ),
            )
        }

    // ─────────────────────────── сообщения ───────────────────────────

    private suspend fun sendMessage(operation: OutboxOperationEntity): Outcome<AfterCommit> {
        val payload = decode<SendMessagePayload>(operation) ?: return malformed(operation)

        return remote.sendMessage(
            conversationId = payload.conversationId,
            clientMsgId = payload.clientMsgId,
            body = payload.text,
            replyToId = payload.replyToId,
            type = payload.type,
            attachmentIds = payload.attachmentIds,
        ).map { message ->
            // Ответ применяется тем же кодом, что и событие из сокета:
            // одно место, где серверное сообщение попадает в базу.
            AfterCommit { applier.applyServerMessage(message) }
        }
    }

    private suspend fun editMessage(operation: OutboxOperationEntity): Outcome<AfterCommit> {
        val payload = decode<EditMessagePayload>(operation) ?: return malformed(operation)
        return remote.editMessage(payload.messageId, payload.text)
            .map { message -> AfterCommit { applier.applyServerMessage(message) } }
    }

    private suspend fun deleteMessage(operation: OutboxOperationEntity): Outcome<AfterCommit> {
        val payload = decode<DeleteMessagePayload>(operation) ?: return malformed(operation)
        return remote.deleteMessage(payload.messageId).map { NOTHING }
    }

    private suspend fun markRead(operation: OutboxOperationEntity): Outcome<AfterCommit> {
        val payload = decode<MarkReadPayload>(operation) ?: return malformed(operation)
        return remote.markRead(payload.conversationId, payload.seq).map { NOTHING }
    }

    // ──────────────────────────── вложения ────────────────────────────

    /**
     * Загрузка файла: разрешение → передача содержимого.
     *
     * Разрешение запрашивается при каждой попытке: оно живёт 15 минут,
     * а между попытками может пройти больше. Повторный запрос с тем же
     * идентификатором безопасен; если файл уже принят (ответ на прошлую
     * попытку потерялся), сервер так и скажет, и передача пропускается.
     */
    private suspend fun uploadAttachment(operation: OutboxOperationEntity): Outcome<AfterCommit> {
        val payload = decode<UploadAttachmentPayload>(operation) ?: return malformed(operation)
        val file = File(payload.localPath)
        if (!file.isFile) {
            return Outcome.Failure(AppError.Validation(mapOf("file" to "Файл больше недоступен на устройстве")))
        }

        val ticket = attachmentsRemote.uploadTicket(
            UploadTicketRequestDto(
                attachmentId = payload.attachmentId,
                conversationId = payload.conversationId,
                mimeType = payload.mimeType,
                sizeBytes = payload.sizeBytes,
                checksumSha256 = payload.sha256,
                fileName = payload.fileName,
                width = payload.width,
                height = payload.height,
                durationMs = payload.durationMs,
            ),
        )
        val done = AfterCommit { attachmentDao.updateStatus(payload.attachmentId, SyncStatus.SYNCED, null) }

        return when (ticket) {
            is Outcome.Failure -> ticket
            is Outcome.Success ->
                if (ticket.value.uploaded) {
                    Outcome.Success(done)
                } else {
                    attachmentsRemote.upload(ticket.value.uploadUrl, file, payload.mimeType) { progress ->
                        attachmentDao.updateProgressBlocking(payload.attachmentId, progress)
                    }.map { done }
                }
        }
    }

    /**
     * Отказ операции, от которой зависят другие.
     *
     * Сообщение, ждущее загрузки файла, без неё не уйдёт никогда: оставлять
     * его в очереди «заблокированным» значило бы показывать пользователю
     * вечное «отправляется». Оно получает отказ вместе с загрузкой.
     */
    private suspend fun failDependents(operation: OutboxOperationEntity) {
        outboxDao.blockedDependentsOf(operation.clientOpId).forEach { dependent ->
            outboxDao.markFailed(dependent.id, error = "Не выполнена операция, от которой зависит эта", nowMillis = time.nowMillis())
            markEntityFailed(dependent)
            failDependents(dependent)
        }
    }

    // ───────────────────────────── задачи ─────────────────────────────

    private suspend fun createTask(operation: OutboxOperationEntity): Outcome<AfterCommit> {
        val payload = decode<CreateTaskPayload>(operation) ?: return malformed(operation)

        return tasksRemote.create(
            taskId = payload.taskId,
            title = payload.title,
            description = payload.description,
            priority = payload.priority,
            dueAt = payload.dueAtMillis?.let(Instant::ofEpochMilli),
            conversationId = payload.conversationId,
            parentTaskId = payload.parentTaskId,
            assigneeIds = payload.assigneeIds,
            sourceMessageId = payload.sourceMessageId,
        ).map { task -> AfterCommit { applier.applyServerTask(task) } }
    }

    /**
     * Частичное изменение задачи.
     *
     * Сервер разрешает конфликт по полям и возвращает итог вместе со
     * списком полей, где его версия победила. Итог записывается как
     * истина; о проигравших полях сообщается интерфейсу — пользователь
     * должен узнать, что его правка не прошла, а не обнаружить это сам.
     */
    private suspend fun patchTask(operation: OutboxOperationEntity): Outcome<AfterCommit> {
        val payload = decode<PatchTaskPayload>(operation) ?: return malformed(operation)

        return tasksRemote.patch(
            taskId = payload.taskId,
            baseVersion = payload.baseVersion,
            changedFields = payload.changedFields,
            fieldTimestamps = payload.fieldTimestamps,
        ).map { response ->
            AfterCommit {
                applier.applyServerTask(response.task)
                if (response.conflicts.isNotEmpty()) {
                    conflicts.report(TaskConflict(response.task.id, response.task.title, response.conflicts))
                }
            }
        }
    }

    private suspend fun deleteTask(operation: OutboxOperationEntity): Outcome<AfterCommit> {
        val payload = decode<DeleteTaskPayload>(operation) ?: return malformed(operation)
        return tasksRemote.delete(payload.taskId).map { NOTHING }
    }

    private suspend fun setAssignees(operation: OutboxOperationEntity): Outcome<AfterCommit> {
        val payload = decode<SetTaskAssigneesPayload>(operation) ?: return malformed(operation)
        return tasksRemote.assignees(payload.taskId, payload.add, payload.remove)
            .map { task -> AfterCommit { applier.applyServerTask(task) } }
    }

    private suspend fun addComment(operation: OutboxOperationEntity): Outcome<AfterCommit> {
        val payload = decode<AddTaskCommentPayload>(operation) ?: return malformed(operation)
        // Идентификатор локальной копии служит ключом дедупликации:
        // повтор после обрыва не создаст второй комментарий.
        return tasksRemote.addComment(payload.taskId, clientOpId = payload.commentId, body = payload.body)
            .map { comment -> AfterCommit { applier.applyServerComment(comment) } }
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

            "ATTACHMENT" -> attachmentDao.updateStatus(operation.entityId, SyncStatus.FAILED, null)
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
    private fun malformed(operation: OutboxOperationEntity): Outcome<Nothing> =
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
         * Выборка из очереди фильтруется по этому списку: операция, которую
         * сервер ещё не умеет принимать, не должна занимать порцию и мешать
         * отправке остальных.
         */
        val SUPPORTED_OP_TYPES = listOf(
            OpType.SEND_MESSAGE,
            OpType.EDIT_MESSAGE,
            OpType.DELETE_MESSAGE,
            OpType.MARK_READ,
            OpType.SAVE_DRAFT,
            OpType.CREATE_TASK,
            OpType.PATCH_TASK,
            OpType.DELETE_TASK,
            OpType.SET_TASK_ASSIGNEES,
            OpType.ADD_TASK_COMMENT,
            OpType.UPLOAD_ATTACHMENT,
        )

        private val NOTHING = AfterCommit { }
    }
}

/**
 * Действие, выполняемое после удаления операции из очереди.
 *
 * Обычно это запись ответа сервера в локальную базу.
 */
fun interface AfterCommit {
    suspend operator fun invoke()
}

/** Правка задачи, проигравшая конкурирующему изменению. */
data class TaskConflict(
    val taskId: String,
    val taskTitle: String,
    val fields: List<String>,
)

/**
 * Канал уведомлений о проигравших правках.
 *
 * Обработка очереди идёт в фоне, без экрана; интерфейс подписывается сюда
 * и показывает «задача изменена на другом устройстве». Канал не хранит
 * историю: уведомление нужно, только пока пользователь смотрит на экран,
 * а сама проигравшая правка сохранена в истории задачи на сервере.
 */
@Singleton
class TaskConflictNotifier @Inject constructor() {
    private val _conflicts = kotlinx.coroutines.flow.MutableSharedFlow<TaskConflict>(
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val conflicts: kotlinx.coroutines.flow.SharedFlow<TaskConflict> = _conflicts

    fun report(conflict: TaskConflict) {
        _conflicts.tryEmit(conflict)
    }
}
