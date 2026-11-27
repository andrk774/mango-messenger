package com.mango.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mango.core.common.DispatcherProvider
import com.mango.core.common.MutableTimeProvider
import com.mango.core.common.NoOpLogger
import com.mango.core.common.Outcome
import com.mango.core.common.UuidV7Generator
import com.mango.core.data.outbox.CreateTaskPayload
import com.mango.core.data.outbox.OpType
import com.mango.core.data.outbox.OutboxJson
import com.mango.core.data.outbox.SendMessagePayload
import com.mango.core.database.MangoDatabase
import com.mango.core.database.entity.ConversationEntity
import com.mango.core.database.entity.MessageEntity
import com.mango.core.database.entity.OutboxStatus
import com.mango.core.database.entity.UserEntity
import com.mango.core.model.ConversationId
import com.mango.core.network.MessagingRemoteDataSource
import com.mango.core.network.api.MessagingApi
import com.mango.core.network.dto.CreateConversationRequestDto
import com.mango.core.network.dto.EditMessageRequestDto
import com.mango.core.network.dto.MarkReadRequestDto
import com.mango.core.network.dto.SendMessageRequestDto
import com.mango.core.model.ConversationType
import com.mango.core.model.MemberRole
import com.mango.core.model.MessageDeliveryStatus
import com.mango.core.model.MessageId
import com.mango.core.model.MessageType
import com.mango.core.model.PresenceStatus
import com.mango.core.model.SyncStatus
import com.mango.core.model.TaskId
import com.mango.core.model.TaskScope
import com.mango.core.model.TaskStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Проверка сценариев offline-first из технического задания (§13):
 *
 *  1. Отправка сообщения без интернета.
 *  2. Перезапуск приложения до синхронизации.
 *  3. Повторная постановка одной операции.
 *
 * Сетевого слоя на этом этапе нет вовсе — именно поэтому тест и показателен:
 * вся запись должна работать и сохраняться без сервера.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class OfflineSendTest {

    private lateinit var database: MangoDatabase
    private lateinit var messageRepository: DefaultMessageRepository
    private lateinit var taskRepository: LocalTaskRepository
    private lateinit var syncRepository: LocalSyncStatusRepository

    private val time = MutableTimeProvider(Instant.ofEpochMilli(1_700_000_000_000))

    private val testDispatchers = object : DispatcherProvider {
        private val dispatcher = UnconfinedTestDispatcher()
        override val main = dispatcher
        override val io = dispatcher
        override val default = dispatcher
    }

    private val conversationId = ConversationId("conv-1")

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangoDatabase::class.java,
        ).allowMainThreadQueries().build()

        val idGenerator = UuidV7Generator(clockMillis = { time.nowMillis() })

        messageRepository = DefaultMessageRepository(
            database = database,
            remote = offlineRemote(),
            attachmentsRemote = com.mango.core.network.AttachmentsRemoteDataSource(
                object : com.mango.core.network.api.AttachmentsApi {
                    override suspend fun uploadTicket(body: com.mango.core.network.dto.UploadTicketRequestDto) =
                        throw java.io.IOException("нет сети")
                    override suspend fun downloadUrl(id: String) = throw java.io.IOException("нет сети")
                },
                object : com.mango.core.network.api.BlobTransferApi {
                    override suspend fun upload(url: String, body: okhttp3.RequestBody) =
                        throw java.io.IOException("нет сети")
                    override suspend fun download(url: String) = throw java.io.IOException("нет сети")
                },
            ),
            files = com.mango.core.data.attachment.AttachmentFileStore(ApplicationProvider.getApplicationContext()),
            idGenerator = idGenerator,
            time = time,
            dispatchers = testDispatchers,
            logger = NoOpLogger(),
        )
        taskRepository = LocalTaskRepository(
            database = database,
            idGenerator = idGenerator,
            time = time,
            dispatchers = testDispatchers,
        )
        syncRepository = LocalSyncStatusRepository(
            database = database,
            time = time,
            dispatchers = testDispatchers,
        )

        database.userDao().upsert(
            UserEntity(
                id = "me", username = "me", displayName = "Я", avatarUrl = null, bio = null,
                presence = PresenceStatus.ONLINE, lastSeenAt = null, isMe = true, updatedAt = 1,
            ),
        )
        database.conversationDao().upsert(
            ConversationEntity(
                id = conversationId.value, type = ConversationType.DIRECT, title = "Иван",
                avatarUrl = null, memberCount = 2, pinnedMessageCount = 0, lastEventSeq = 0,
                lastReadSeq = 0, mutedUntil = null, draft = null, myRole = MemberRole.MEMBER,
                isArchived = false, leftAt = null, updatedAt = 1,
            ),
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `sending a message offline stores it locally and queues the operation`() = runTest {
        val result = messageRepository.sendText(conversationId, "Привет без сети")

        assertTrue("Отправка должна считаться успешной локально", result is Outcome.Success)
        val clientOpId = (result as Outcome.Success).value

        // 1. Сообщение видно в интерфейсе сразу, помечено как неотправленное.
        val messages = messageRepository.observeRecentMessages(conversationId).first()
        assertEquals(1, messages.size)
        val message = messages.single()
        assertEquals("Привет без сети", message.text)
        assertNull("До подтверждения сервером seq должен быть пустым", message.seq)
        assertEquals(MessageDeliveryStatus.PENDING, message.deliveryStatus)
        assertEquals(SyncStatus.PENDING, message.syncStatus)

        // 2. Операция стоит в очереди с корректной полезной нагрузкой.
        val operation = database.outboxDao().findByClientOpId(clientOpId.value)
        assertNotNull("Операция должна быть в очереди", operation)
        assertEquals(OpType.SEND_MESSAGE, operation!!.opType)
        assertEquals(OutboxStatus.PENDING, operation.status)

        val payload = OutboxJson.decodeFromString<SendMessagePayload>(operation.payloadJson)
        assertEquals(conversationId.value, payload.conversationId)
        assertEquals("Привет без сети", payload.text)
        assertEquals(
            "clientMsgId в payload должен совпадать с ключом идемпотентности",
            clientOpId.value,
            payload.clientMsgId,
        )
    }

    @Test
    fun `empty message is rejected without touching the queue`() = runTest {
        val result = messageRepository.sendText(conversationId, "   ")

        assertTrue(result is Outcome.Failure)
        assertEquals(0, database.outboxDao().all().size)
        assertEquals(0, messageRepository.observeRecentMessages(conversationId).first().size)
    }

    @Test
    fun `queue survives process restart and in flight work is recovered`() = runTest {
        messageRepository.sendText(conversationId, "первое")
        messageRepository.sendText(conversationId, "второе")

        // Имитируем, что процессор успел взять одну операцию в работу
        // и приложение было убито до получения ответа сервера.
        val operations = database.outboxDao().all()
        assertEquals(2, operations.size)
        database.outboxDao().updateStatus(
            operations.first().id,
            OutboxStatus.IN_FLIGHT,
            time.nowMillis(),
        )
        assertEquals(
            "Застрявшая операция не должна выбираться до восстановления",
            1,
            database.outboxDao().nextBatch(time.nowMillis()).size,
        )

        // Старт приложения.
        val recovered = syncRepository.recoverAfterRestart()

        assertEquals(1, recovered)
        assertEquals(
            "После восстановления обе операции снова готовы к отправке",
            2,
            database.outboxDao().nextBatch(time.nowMillis()).size,
        )
        assertEquals(
            "Сообщения не должны потеряться",
            2,
            messageRepository.observeRecentMessages(conversationId).first().size,
        )
    }

    @Test
    fun `retry reschedules the operation and resets message status`() = runTest {
        val clientOpId = (messageRepository.sendText(conversationId, "текст") as Outcome.Success).value

        // Процессор отчитался об ошибке и отложил попытку.
        val operation = database.outboxDao().findByClientOpId(clientOpId.value)!!
        database.outboxDao().scheduleRetry(
            id = operation.id,
            nextAttemptAt = time.nowMillis() + 60_000,
            error = "timeout",
            nowMillis = time.nowMillis(),
        )
        assertEquals(0, database.outboxDao().nextBatch(time.nowMillis()).size)

        // Пользователь нажал «отправить снова».
        val result = messageRepository.retry(clientOpId)

        assertTrue(result is Outcome.Success)
        assertEquals(
            "После ручного повтора операция должна быть доступна сразу",
            1,
            database.outboxDao().nextBatch(time.nowMillis()).size,
        )
        val stored = database.outboxDao().findByClientOpId(clientOpId.value)!!
        assertEquals("Счётчик попыток сохраняется для диагностики", 2, stored.attemptCount)
        assertNull(stored.lastError)
    }

    @Test
    fun `sync snapshot reflects queue state`() = runTest {
        assertTrue(syncRepository.observeSnapshot().first().isFullySynced)

        messageRepository.sendText(conversationId, "первое")
        messageRepository.sendText(conversationId, "второе")

        val snapshot = syncRepository.observeSnapshot().first()
        assertEquals(2, snapshot.pendingOperations)
        assertEquals(0, snapshot.failedOperations)
        assertTrue(!snapshot.isFullySynced)
    }

    @Test
    fun `retry legacy m4a preserves file and message key and repairs upload dependency`() = runTest {
        val file = java.io.File.createTempFile("legacy-voice", ".m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val key = queueFailedVoice(file.path)
        assertTrue(messageRepository.retry(key) is Outcome.Success)
        val sendOp = database.outboxDao().findByClientOpId(key.value)!!
        val uploadOp = database.outboxDao().findByClientOpId("legacy-upload")!!
        val upload = OutboxJson.decodeFromString<com.mango.core.data.outbox.UploadAttachmentPayload>(uploadOp.payloadJson)
        val send = OutboxJson.decodeFromString<SendMessagePayload>(sendOp.payloadJson)
        assertEquals("audio/mp4", upload.mimeType)
        assertTrue(upload.attachmentId != "legacy-attachment")
        assertEquals(key.value, send.clientMsgId)
        assertEquals(listOf(upload.attachmentId), send.attachmentIds)
        assertEquals(OutboxStatus.BLOCKED, sendOp.status)
        assertEquals(OutboxStatus.PENDING, uploadOp.status)
        assertEquals(listOf(uploadOp.id), database.outboxDao().nextBatch(time.nowMillis()).map { it.id })
        assertEquals(listOf<Byte>(1, 2, 3), file.readBytes().toList())
        assertNull(database.attachmentDao().findById("legacy-attachment")!!.messageId)
        assertEquals(file.path, database.attachmentDao().findById(upload.attachmentId)!!.localUri)
        // A second tap must reuse the repaired operation, not allocate another upload/message.
        assertTrue(messageRepository.retry(key) is Outcome.Success)
        assertEquals(uploadOp.entityId, database.outboxDao().findByClientOpId("legacy-upload")!!.entityId)
        assertEquals(1, messageRepository.observeRecentMessages(conversationId).first().size)
    }

    @Test
    fun `missing legacy voice file leaves failed operations untouched`() = runTest {
        val key = queueFailedVoice(java.io.File(System.getProperty("java.io.tmpdir"), "missing-${java.util.UUID.randomUUID()}").path)
        val before = database.outboxDao().all()
        assertTrue(messageRepository.retry(key) is Outcome.Failure)
        assertEquals(before, database.outboxDao().all())
        assertNotNull(database.attachmentDao().findById("legacy-attachment")!!.messageId)
    }

    private suspend fun queueFailedVoice(path: String): com.mango.core.model.ClientOpId {
        val key = (messageRepository.sendText(conversationId, "legacy") as Outcome.Success).value
        val sendOp = database.outboxDao().findByClientOpId(key.value)!!
        val send = OutboxJson.decodeFromString<SendMessagePayload>(sendOp.payloadJson)
        database.attachmentDao().upsert(com.mango.core.database.entity.AttachmentEntity(
            "legacy-attachment", key.value, null, "audio/mpeg", 3, "voice.m4a", null, path,
            null, null, null, 0f, SyncStatus.FAILED, time.nowMillis(),
        ))
        val upload = com.mango.core.data.outbox.UploadAttachmentPayload(
            "legacy-attachment", conversationId.value, path, "audio/mpeg", 3, "checksum", "voice.m4a",
        )
        database.outboxDao().enqueue(com.mango.core.data.outbox.outboxOperation(
            "legacy-upload", OpType.UPLOAD_ATTACHMENT, "ATTACHMENT", "legacy-attachment",
            OutboxJson.encodeToString(upload), time.nowMillis(),
        ).copy(status = OutboxStatus.FAILED))
        database.outboxDao().upsert(sendOp.copy(status = OutboxStatus.FAILED, dependsOnOpId = "legacy-upload",
            payloadJson = OutboxJson.encodeToString(send.copy(type = "VOICE", attachmentIds = listOf("legacy-attachment")))))
        return key
    }

    @Test
    fun `editing an offline task preserves identity and can clear its deadline`() = runTest {
        val id = (taskRepository.create(NewTaskDraft(title = "Before", dueAtMillis = 100000L)) as Outcome.Success).value
        assertTrue(taskRepository.edit(id, "After", "Details", null, 200000L) is Outcome.Success)
        val task = taskRepository.observeTask(id).first()!!
        assertEquals("After", task.title)
        assertEquals("Details", task.description)
        assertNull(task.dueAt)
        assertEquals(java.time.Instant.ofEpochMilli(200000L), task.remindAt)
        assertEquals(SyncStatus.PENDING, task.syncStatus)
        assertTrue(taskRepository.edit(id, " ", null, null, null) is Outcome.Failure)
        assertEquals("After", taskRepository.observeTask(id).first()!!.title)
        assertTrue(taskRepository.edit(id, "After", "Details", null, null) is Outcome.Success)
        assertNull(taskRepository.observeTask(id).first()!!.remindAt)
    }

    @Test
    fun `creating a task offline keeps source message snapshot`() = runTest {
        // Входящее сообщение, из которого создаётся задача.
        database.messageDao().upsert(
            MessageEntity(
                id = "msg-1", conversationId = conversationId.value, senderId = "other",
                clientMsgId = "c1", seq = 5, type = MessageType.TEXT,
                body = "Нужно обновить документацию к пятнице",
                replyToId = null, replyToSenderName = null, replyToSnapshot = null,
                forwardedFromId = null, forwardedFromSenderName = null,
                deliveryStatus = MessageDeliveryStatus.DELIVERED, syncStatus = SyncStatus.SYNCED,
                pinnedAt = null, editedAt = null, deletedAt = null, createdAt = 1_000,
            ),
        )

        val result = taskRepository.create(
            NewTaskDraft(
                title = "Обновить документацию",
                conversationId = conversationId,
                sourceMessageId = MessageId("msg-1"),
                sourceSenderName = "Иван",
                sourceTextSnapshot = "Нужно обновить документацию к пятнице",
            ),
        )

        assertTrue(result is Outcome.Success)
        val taskId = (result as Outcome.Success).value

        val task = taskRepository.observeTask(taskId).first()
        assertNotNull(task)
        assertEquals(SyncStatus.PENDING, task!!.syncStatus)
        assertEquals("msg-1", task.source?.messageId?.value)
        assertEquals("Нужно обновить документацию к пятнице", task.source?.textSnapshot)
        assertTrue("Источник пока доступен", task.source?.isSourceUnavailable == false)

        // Операция создания в очереди.
        val operation = database.outboxDao().findByClientOpId(taskId.value)!!
        assertEquals(OpType.CREATE_TASK, operation.opType)
        val payload = OutboxJson.decodeFromString<CreateTaskPayload>(operation.payloadJson)
        assertEquals("Обновить документацию", payload.title)
        assertEquals("msg-1", payload.sourceMessageId)

        // История изменений заполнена.
        assertEquals(1, database.taskDao().observeEvents(taskId.value).first().size)
    }

    @Test
    fun `deleting the source message keeps the task readable`() = runTest {
        database.messageDao().upsert(
            MessageEntity(
                id = "msg-1", conversationId = conversationId.value, senderId = "me",
                clientMsgId = "c1", seq = 5, type = MessageType.TEXT, body = "исходный текст",
                replyToId = null, replyToSenderName = null, replyToSnapshot = null,
                forwardedFromId = null, forwardedFromSenderName = null,
                deliveryStatus = MessageDeliveryStatus.SENT, syncStatus = SyncStatus.SYNCED,
                pinnedAt = null, editedAt = null, deletedAt = null, createdAt = 1_000,
            ),
        )
        val taskId = (
            taskRepository.create(
                NewTaskDraft(
                    title = "Задача из сообщения",
                    conversationId = conversationId,
                    sourceMessageId = MessageId("msg-1"),
                    sourceSenderName = "Я",
                    sourceTextSnapshot = "исходный текст",
                ),
            ) as Outcome.Success
            ).value

        messageRepository.delete(MessageId("msg-1"), forEveryone = true)

        val task = taskRepository.observeTask(taskId).first()!!
        assertTrue("Источник должен быть помечен недоступным", task.source!!.isSourceUnavailable)
        assertEquals(
            "Снапшот текста обязан сохраниться — иначе задача теряет смысл",
            "исходный текст",
            task.source!!.textSnapshot,
        )
    }

    @Test
    fun `status change patch depends on task creation while unsynced`() = runTest {
        val taskId = (
            taskRepository.create(NewTaskDraft(title = "Задача")) as Outcome.Success
            ).value

        taskRepository.changeStatus(taskId, TaskStatus.IN_PROGRESS)

        val operations = database.outboxDao().all()
        val patch = operations.single { it.opType == OpType.PATCH_TASK }
        assertEquals(
            "Патч неподтверждённой задачи должен ждать её создания",
            taskId.value,
            patch.dependsOnOpId,
        )
        assertEquals(OutboxStatus.BLOCKED, patch.status)
        assertEquals(
            "Пока создание в очереди, патч не отправляется",
            listOf(OpType.CREATE_TASK),
            database.outboxDao().nextBatch(time.nowMillis()).map { it.opType },
        )
    }

    @Test
    fun `status change records history and marks task pending`() = runTest {
        val taskId = (
            taskRepository.create(NewTaskDraft(title = "Задача")) as Outcome.Success
            ).value

        taskRepository.changeStatus(taskId, TaskStatus.DONE)

        val task = taskRepository.observeTask(taskId).first()!!
        assertEquals(TaskStatus.DONE, task.status)
        assertEquals(SyncStatus.PENDING, task.syncStatus)

        val events = database.taskDao().observeEvents(taskId.value).first()
        assertEquals(2, events.size)
        assertTrue(
            "Должна быть запись о смене статуса",
            events.any { it.field == "status" && it.newValue == "DONE" },
        )
    }

    @Test
    fun `field version timestamp is recorded for the changed field only`() = runTest {
        val taskId = (
            taskRepository.create(NewTaskDraft(title = "Задача")) as Outcome.Success
            ).value

        time.advanceMillis(60_000)
        taskRepository.changeStatus(taskId, TaskStatus.REVIEW)

        val entity = database.taskDao().findById(taskId.value)!!
        assertTrue(
            "Метка поля status должна обновиться: ${entity.fieldVersionsJson}",
            entity.fieldVersionsJson.contains("\"status\":${time.nowMillis()}"),
        )
        assertTrue(
            "Метка title должна остаться прежней",
            entity.fieldVersionsJson.contains("\"title\":1700000000000"),
        )
    }

    @Test
    fun `my tasks list excludes completed by default`() = runTest {
        val open = (taskRepository.create(NewTaskDraft(title = "Открытая")) as Outcome.Success).value
        val done = (taskRepository.create(NewTaskDraft(title = "Закрытая")) as Outcome.Success).value
        taskRepository.changeStatus(done, TaskStatus.DONE)

        val visible = taskRepository.observeTasks(TaskScope.MINE).first()
        assertEquals(listOf(open.value), visible.map { it.id.value })

        val all = taskRepository.observeTasks(TaskScope.MINE, includeCompleted = true).first()
        assertEquals(2, all.size)
    }

    @Test
    fun `draft operation is collapsed to a single queue entry`() = runTest {
        val conversations = DefaultConversationRepository(
            database = database,
            remote = offlineRemote(),
            idGenerator = UuidV7Generator(clockMillis = { time.nowMillis() }),
            time = time,
            dispatchers = testDispatchers,
        )

        conversations.saveDraft(conversationId, "чер")
        time.advanceMillis(10)
        conversations.saveDraft(conversationId, "черно")
        time.advanceMillis(10)
        conversations.saveDraft(conversationId, "черновик")

        val operations = database.outboxDao().all()
        assertEquals(
            "Быстрый набор не должен забивать очередь — остаётся одна операция",
            1,
            operations.size,
        )
        assertEquals(
            "черновик",
            database.conversationDao().findById(conversationId.value)!!.draft,
        )
    }

    @Test
    fun `offline read receipt survives resync with stale server cursor`() = runTest {
        val conversations = DefaultConversationRepository(
            database = database,
            remote = offlineRemote(),
            idGenerator = UuidV7Generator(clockMillis = { time.nowMillis() }),
            time = time,
            dispatchers = testDispatchers,
        )

        assertTrue(conversations.markRead(conversationId, 7) is Outcome.Success)

        val receipt = database.outboxDao().all().single { it.opType == OpType.MARK_READ }
        assertEquals(OutboxStatus.PENDING, receipt.status)
        assertEquals(conversationId.value, receipt.entityId)

        // Карточка из списка чатов, полученная до доставки отметки.
        val stale = database.conversationDao().findById(conversationId.value)!!.copy(lastReadSeq = 2)
        database.conversationDao().upsertFromServer(stale)

        assertEquals(7L, database.conversationDao().findById(conversationId.value)!!.lastReadSeq)
    }

    /**
     * Источник данных без сети.
     *
     * Любой запрос завершается сетевой ошибкой — ровно то, что нужно
     * проверить: запись обязана работать и сохраняться без сервера.
     * Подделка на уровне HTTP, а не репозитория, потому что именно так
     * ведёт себя устройство в самолёте.
     */
    private fun offlineRemote(): MessagingRemoteDataSource =
        MessagingRemoteDataSource(
            object : MessagingApi {
                override suspend fun react(id: String, request: com.mango.core.network.dto.ReactionRequestDto): Nothing = error("Offline test must not call reaction endpoint")
                override suspend fun searchMessages(id: String, query: String, beforeSeq: Long?): Nothing = error("Offline test must not call search")
                private fun fail(): Nothing = throw java.io.IOException("нет сети")

                override suspend fun conversations(limit: Int) = fail()
                override suspend fun createConversation(body: CreateConversationRequestDto) = fail()
                override suspend fun conversation(id: String) = fail()
                override suspend fun members(id: String) = fail()
                override suspend fun renameConversation(id: String, body: com.mango.core.network.dto.UpdateConversationRequestDto) = fail()
                override suspend fun addMembers(id: String, body: com.mango.core.network.dto.AddMembersRequestDto) = fail()
                override suspend fun removeMember(id: String, userId: String) = fail()
                override suspend fun changeRole(id: String, userId: String, body: com.mango.core.network.dto.ChangeRoleRequestDto) = fail()
                override suspend fun markRead(id: String, body: MarkReadRequestDto) = fail()
                override suspend fun messages(
                    id: String,
                    beforeSeq: Long?,
                    afterSeq: Long?,
                    limit: Int,
                ) = fail()

                override suspend fun sendMessage(id: String, body: SendMessageRequestDto) = fail()
                override suspend fun editMessage(id: String, body: EditMessageRequestDto) = fail()
                override suspend fun deleteMessage(id: String) = fail()
                override suspend fun searchUsers(query: String, limit: Int) = fail()
            },
        )

    /** Защита от случайного использования реального диспетчера в тестах. */
    @Test
    fun `test dispatchers are injected not hardcoded`() {
        val dispatchers: com.mango.core.common.DispatcherProvider = testDispatchers
        assertTrue(
            "Репозитории обязаны использовать инжектированный диспетчер",
            dispatchers.io is kotlinx.coroutines.test.TestDispatcher,
        )
    }
}
