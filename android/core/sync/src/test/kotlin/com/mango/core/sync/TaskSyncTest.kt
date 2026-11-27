package com.mango.core.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mango.core.common.MutableTimeProvider
import com.mango.core.common.NoOpLogger
import com.mango.core.common.RetryPolicy
import com.mango.core.data.outbox.AddTaskCommentPayload
import com.mango.core.data.outbox.CreateTaskPayload
import com.mango.core.data.outbox.OpType
import com.mango.core.data.outbox.OutboxJson
import com.mango.core.data.outbox.PatchTaskPayload
import com.mango.core.database.MangoDatabase
import com.mango.core.database.entity.OutboxOperationEntity
import com.mango.core.database.entity.OutboxStatus
import com.mango.core.database.entity.TaskAssigneeEntity
import com.mango.core.database.entity.TaskCommentEntity
import com.mango.core.database.entity.TaskEntity
import com.mango.core.database.entity.UserEntity
import com.mango.core.model.PresenceStatus
import com.mango.core.model.SyncStatus
import com.mango.core.model.TaskPriority
import com.mango.core.model.TaskStatus
import com.mango.core.network.MessagingRemoteDataSource
import com.mango.core.network.TasksRemoteDataSource
import com.mango.core.network.AttachmentsRemoteDataSource
import com.mango.core.network.api.AttachmentsApi
import com.mango.core.network.api.BlobTransferApi
import com.mango.core.network.api.MessagingApi
import com.mango.core.network.api.TasksApi
import com.mango.core.network.dto.SyncEventDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Синхронизация задач на клиенте.
 *
 * Центральная проверка — неотправленные правки пользователя не должны
 * визуально откатываться, когда приходит серверная версия задачи,
 * а правка, отклонённая сервером, не должна «возвращаться» в интерфейс.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TaskSyncTest {

    private lateinit var server: MockWebServer
    private lateinit var database: MangoDatabase
    private lateinit var session: TestSession
    private lateinit var applier: EventApplier
    private lateinit var processor: OutboxProcessor
    private lateinit var notifier: TaskConflictNotifier
    private lateinit var engine: SyncEngine

    private val time = MutableTimeProvider(Instant.ofEpochMilli(1_700_000_000_000))
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val me = "user-me"
    private val taskId = "task-1"

    @Before
    fun setUp() = runTest {
        server = MockWebServer()
        server.start()

        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangoDatabase::class.java,
        ).allowMainThreadQueries().build()
        session = TestSession(database)
        session.signIn(me)

        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        applier = EventApplier(database, json, NoOpLogger())
        engine = SyncEngine(database,
            com.mango.core.network.SyncRemoteDataSource(retrofit.create(com.mango.core.network.api.SyncApi::class.java)),
            TasksRemoteDataSource(retrofit.create(TasksApi::class.java)), applier, NoOpLogger(), session.guard)
        notifier = TaskConflictNotifier()
        processor = OutboxProcessor(
            database = database,
            remote = MessagingRemoteDataSource(retrofit.create(MessagingApi::class.java)),
            tasksRemote = TasksRemoteDataSource(retrofit.create(TasksApi::class.java)),
            attachmentsRemote = AttachmentsRemoteDataSource(
                retrofit.create(AttachmentsApi::class.java),
                retrofit.create(BlobTransferApi::class.java),
            ),
            applier = applier,
            conflicts = notifier,
            time = time,
            logger = NoOpLogger(),
            accountGuard = session.guard,
            retryPolicy = RetryPolicy.Default,
        )

        database.userDao().upsert(
            UserEntity(me, "me", "Я", null, null, PresenceStatus.ONLINE, null, isMe = true, updatedAt = 1),
        )
    }

    @After
    fun tearDown() {
        database.close()
        server.close()
    }

    // ─────────────────────────── отправка ───────────────────────────

    @Test
    fun `созданная офлайн задача уходит и становится подтверждённой`() = runTest {
        localTask(status = TaskStatus.TODO, syncStatus = SyncStatus.PENDING)
        enqueue(
            OpType.CREATE_TASK,
            OutboxJson.encodeToString(
                CreateTaskPayload(taskId = taskId, title = "Задача", priority = "MEDIUM"),
            ),
            clientOpId = taskId,
        )
        respond(201, serverTask(title = "Задача", version = 1))

        val result = processor.runOnce()

        assertEquals(1, result.processed)
        val stored = database.taskDao().findById(taskId)!!
        assertEquals(SyncStatus.SYNCED, stored.syncStatus)
        assertEquals(1L, stored.version)
        assertNull(database.outboxDao().findByClientOpId(taskId))
    }

    @Test
    fun `проигравшая правка не возвращается в интерфейс и о ней сообщается`() = runTest {
        // Пользователь сменил название офлайн; локально видно его значение.
        localTask(title = "Моё название", syncStatus = SyncStatus.PENDING)
        enqueuePatch("op-1", mapOf("title" to "Моё название"))

        // Сервер разрешил конфликт в пользу другого устройства.
        respond(
            200,
            """{"task": ${serverTask(title = "Чужое название", version = 3)}, "conflicts": ["title"]}""",
        )

        val conflicts = mutableListOf<TaskConflict>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            notifier.conflicts.collect { conflicts += it }
        }

        processor.runOnce()
        collector.cancel()

        val stored = database.taskDao().findById(taskId)!!
        assertEquals(
            "Чужое название",
            stored.title,
            "Отклонённое сервером значение не должно вернуться из очереди",
        )
        assertEquals(SyncStatus.SYNCED, stored.syncStatus)
        assertEquals(listOf("title"), conflicts.single().fields, "Пользователь должен узнать о проигрыше")
    }

    @Test
    fun `ответ на одну правку не затирает следующую неотправленную`() = runTest {
        localTask(title = "Новое", status = TaskStatus.DONE, syncStatus = SyncStatus.PENDING)
        enqueuePatch("op-1", mapOf("title" to "Новое"), createdAt = 1_000)
        // Вторая правка ещё не отправлена: её время повтора в будущем.
        enqueuePatch("op-2", mapOf("status" to "DONE"), createdAt = 2_000, nextAttemptAt = Long.MAX_VALUE)

        // Сервер принял первую правку; статус в его ответе ещё старый.
        respond(200, """{"task": ${serverTask(title = "Новое", status = "TODO", version = 2)}, "conflicts": []}""")

        processor.runOnce()

        val stored = database.taskDao().findById(taskId)!!
        assertEquals("Новое", stored.title)
        assertEquals(
            TaskStatus.DONE,
            stored.status,
            "Статус из неотправленной правки не должен откатиться к серверному",
        )
        assertEquals(SyncStatus.PENDING, stored.syncStatus, "Задача ещё ждёт отправки второй правки")
    }

    @Test
    fun `комментарий уходит с ключом дедупликации`() = runTest {
        localTask(syncStatus = SyncStatus.SYNCED)
        enqueue(
            OpType.ADD_TASK_COMMENT,
            OutboxJson.encodeToString(AddTaskCommentPayload(taskId, commentId = "c-local", body = "Готово")),
            clientOpId = "op-comment",
            entityId = taskId,
        )
        respond(201, serverComment(id = "c-server", clientOpId = "c-local"))

        processor.runOnce()

        val request = server.takeRequest()
        assertTrue(
            request.body!!.utf8().contains("\"clientOpId\":\"c-local\""),
            "Повтор после обрыва не должен создать второй комментарий",
        )
    }

    // ─────────────────────── события журнала ───────────────────────

    @Test
    fun `assignment event shows another authors task in recipients mine list`() = runTest {
        val assigned = serverTask(version = 2,
            assignees = """[{"userId":"$me","username":"me","displayName":"Я"}]""")
            .replace("\"createdBy\":\"$me\"", "\"createdBy\":\"another-user\"")
        applier.applyBatch(listOf(taskEvent(1, "TASK_UPDATED", assigned)))
        assertEquals(listOf(taskId), database.taskDao().observeMyTasks(me, false).first().map { it.id })
        assertEquals(me, database.userDao().currentUserId())
        val unassigned = serverTask(version = 3)
            .replace("\"createdBy\":\"$me\"", "\"createdBy\":\"another-user\"")
        applier.applyBatch(listOf(taskEvent(2, "TASK_UPDATED", unassigned)))
        assertTrue(database.taskDao().observeMyTasks(me, false).first().isEmpty())
    }

    @Test
    fun `joining a chat downloads tasks created before membership`() = runTest {
        respond(200, """{"events":[{"id":1,"scope":"CONVERSATION","scopeId":"chat-1",
          "type":"CONVERSATION_MEMBER_ADDED","payload":{"conversationId":"chat-1","members":[]},
          "createdAt":"2026-11-12T10:00:00Z"}],"lastEventId":1,"hasMore":false}""")
        respond(200, """{"items":[]}""")
        val older = serverTask(version = 1).replace("\"createdBy\":\"$me\"",
            "\"createdBy\":\"another-user\",\"conversationId\":\"chat-1\"")
        respond(200, """{"items":[$older]}""")
        assertTrue(engine.catchUp() is com.mango.core.common.Outcome.Success)
        assertEquals(listOf(taskId), database.taskDao().observeTeamTasks(false).first().map { it.id })
        assertTrue(database.taskDao().observeMyTasks(me, false).first().isEmpty())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `событие из журнала не откатывает неотправленную правку`() = runTest {
        localTask(status = TaskStatus.IN_PROGRESS, syncStatus = SyncStatus.PENDING)
        enqueuePatch("op-1", mapOf("status" to "IN_PROGRESS"), nextAttemptAt = Long.MAX_VALUE)

        // Тем временем пришло событие от другого участника: сервер ещё
        // не знает о нашей правке и присылает старый статус.
        applier.applyBatch(listOf(taskEvent(1, "TASK_UPDATED", serverTask(status = "TODO", priority = "HIGH", version = 2))))

        val stored = database.taskDao().findById(taskId)!!
        assertEquals(TaskStatus.IN_PROGRESS, stored.status, "Наша неотправленная правка видна до отправки")
        assertEquals(TaskPriority.HIGH, stored.priority, "Чужая правка другого поля применена")
        assertEquals(SyncStatus.PENDING, stored.syncStatus)
        assertEquals(2L, stored.version)
    }

    @Test
    fun `удаление задачи из журнала помечает её удалённой`() = runTest {
        localTask(syncStatus = SyncStatus.SYNCED)

        applier.applyBatch(
            listOf(taskEvent(1, "TASK_DELETED", """{"taskId":"$taskId","deletedAt":"2026-11-12T10:00:00Z"}""")),
        )

        assertNotNull(database.taskDao().findById(taskId)!!.deletedAt)
        assertNull(database.taskDao().observeById(taskId).first(), "Удалённая задача не показывается")
    }

    @Test
    fun `серверный комментарий заменяет локальную копию без конфликта ключей`() = runTest {
        localTask(syncStatus = SyncStatus.SYNCED)
        database.taskDao().insertCommentIgnore(
            TaskCommentEntity(
                id = "c-local", taskId = taskId, authorId = me, clientOpId = "c-local",
                body = "Готово", syncStatus = SyncStatus.PENDING, editedAt = null,
                deletedAt = null, createdAt = 1_000,
            ),
        )

        applier.applyBatch(
            listOf(taskEvent(1, "TASK_COMMENT_ADDED", """{"comment": ${serverComment("c-server", "c-local")}}""")),
        )

        val comments = database.taskDao().observeComments(taskId).first()
        assertEquals(1, comments.size, "Локальная и серверная копии — один комментарий")
        assertEquals("c-server", comments.single().id)
        assertEquals(SyncStatus.SYNCED, comments.single().syncStatus)
    }

    @Test
    fun `снятый на сервере исполнитель снимается локально, неотправленный остаётся`() = runTest {
        localTask(syncStatus = SyncStatus.SYNCED)
        database.taskDao().upsertAssignees(
            listOf(
                TaskAssigneeEntity(taskId, "user-old", 1_000, null, SyncStatus.SYNCED),
                TaskAssigneeEntity(taskId, "user-pending", 1_000, null, SyncStatus.PENDING),
            ),
        )

        // На сервере исполнителей нет: старого сняли, о новом сервер не знает.
        applier.applyBatch(listOf(taskEvent(1, "TASK_UPDATED", serverTask(version = 2))))

        val remaining = database.taskDao().allAssigneesOf(taskId).map { it.userId }.toSet()
        assertEquals(setOf("user-pending"), remaining)
    }

    @Test
    fun `исполнитель из ответа сервера запоминается для отображения имени`() = runTest {
        applier.applyBatch(
            listOf(
                taskEvent(
                    1,
                    "TASK_CREATED",
                    serverTask(
                        version = 1,
                        assignees = """[{"userId":"user-b","username":"boris","displayName":"Борис"}]""",
                    ),
                ),
            ),
        )

        assertEquals("Борис", database.userDao().findById("user-b")?.displayName)
        assertEquals(
            listOf("user-b"),
            database.taskDao().allAssigneesOf(taskId).map { it.userId },
        )
    }

    // ─────────────────────────── вспомогательное ───────────────────────────

    private suspend fun localTask(
        title: String = "Задача",
        status: TaskStatus = TaskStatus.TODO,
        syncStatus: SyncStatus,
    ) {
        database.taskDao().upsert(
            TaskEntity(
                id = taskId, title = title, description = null, status = status,
                priority = TaskPriority.MEDIUM, createdBy = me, conversationId = null,
                conversationTitle = null, sourceMessageId = null, sourceSenderName = null,
                sourceMessageSnapshot = null, sourceCreatedAt = null, sourceUnavailable = false,
                parentTaskId = null, dueAt = null, remindAt = null, version = 1,
                fieldVersionsJson = "{}", syncStatus = syncStatus, completedAt = null,
                deletedAt = null, createdAt = 1_000, updatedAt = 1_000,
            ),
        )
    }

    private suspend fun enqueuePatch(
        opId: String,
        fields: Map<String, String?>,
        createdAt: Long = 1_000,
        nextAttemptAt: Long = 0,
    ) = enqueue(
        OpType.PATCH_TASK,
        OutboxJson.encodeToString(
            PatchTaskPayload(taskId, baseVersion = 1, changedFields = fields, fieldTimestamps = fields.mapValues { createdAt }),
        ),
        clientOpId = opId,
        createdAt = createdAt,
        nextAttemptAt = nextAttemptAt,
    )

    private suspend fun enqueue(
        opType: String,
        payload: String,
        clientOpId: String,
        entityId: String = taskId,
        createdAt: Long = 1_000,
        nextAttemptAt: Long = 0,
    ) {
        database.outboxDao().enqueue(
            OutboxOperationEntity(
                id = clientOpId, clientOpId = clientOpId, opType = opType,
                entityType = "TASK", entityId = entityId, payloadJson = payload,
                status = OutboxStatus.PENDING, attemptCount = 0, nextAttemptAt = nextAttemptAt,
                lastError = null, dependsOnOpId = null, createdAt = createdAt, updatedAt = createdAt,
            ),
        )
    }

    private fun respond(code: Int, body: String) {
        server.enqueue(
            MockResponse.Builder()
                .code(code)
                .setHeader("Content-Type", "application/json")
                .body(body)
                .build(),
        )
    }

    private fun serverTask(
        title: String = "Задача",
        status: String = "TODO",
        priority: String = "MEDIUM",
        version: Long,
        assignees: String = "[]",
    ) = """
        {"id":"$taskId","title":"$title","status":"$status","priority":"$priority",
         "createdBy":"$me","assignees":$assignees,"version":$version,
         "fieldVersions":{"title":1000},
         "createdAt":"2026-11-12T10:00:00Z","updatedAt":"2026-11-12T10:00:00Z"}
    """.trimIndent()

    private fun serverComment(id: String, clientOpId: String) = """
        {"id":"$id","taskId":"$taskId","authorId":"$me","authorName":"Я",
         "clientOpId":"$clientOpId","body":"Готово","createdAt":"2026-11-12T10:00:00Z"}
    """.trimIndent()

    private fun taskEvent(id: Long, type: String, payload: String): SyncEventDto {
        val body = if (type == "TASK_CREATED" || type == "TASK_UPDATED") """{"task": $payload}""" else payload
        return SyncEventDto(
            id = id,
            scope = "TASK",
            scopeId = taskId,
            type = type,
            payload = json.parseToJsonElement(body),
            createdAt = Instant.ofEpochMilli(1_700_000_000_000),
        )
    }
}
