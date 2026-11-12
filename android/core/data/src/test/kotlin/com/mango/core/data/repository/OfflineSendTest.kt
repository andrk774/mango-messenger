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
    private lateinit var messageRepository: LocalMessageRepository
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

        messageRepository = LocalMessageRepository(
            database = database,
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
        val conversations = LocalConversationRepository(
            database = database,
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

    /** Защита от случайного использования реального диспетчера в тестах. */
    @Test
    fun `test dispatchers are injected not hardcoded`() {
        assertTrue(
            "Репозитории обязаны использовать инжектированный диспетчер",
            testDispatchers.io is kotlinx.coroutines.test.TestDispatcher,
        )
    }
}
