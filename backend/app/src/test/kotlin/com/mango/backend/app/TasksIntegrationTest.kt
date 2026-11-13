package com.mango.backend.app

import com.mango.backend.auth.CurrentUserResponse
import com.mango.backend.auth.TokenPairResponse
import com.mango.backend.messaging.ConversationDto
import com.mango.backend.messaging.CreateConversationRequest
import com.mango.backend.messaging.MessageDto
import com.mango.backend.messaging.SendMessageRequest
import com.mango.backend.sync.SyncResponse
import com.mango.backend.tasks.AddCommentRequest
import com.mango.backend.tasks.AssigneesRequest
import com.mango.backend.tasks.CommentsResponse
import com.mango.backend.tasks.CreateTaskFromMessageRequest
import com.mango.backend.tasks.CreateTaskRequest
import com.mango.backend.tasks.PatchTaskRequest
import com.mango.backend.tasks.PatchTaskResponse
import com.mango.backend.tasks.TaskDto
import com.mango.backend.tasks.TaskEventsResponse
import com.mango.backend.tasks.TasksResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Задачи: доступ, конфликты, исполнители, связь с сообщением.
 *
 * Сценарии «два устройства» воспроизводят то, ради чего вообще нужна
 * модель разрешения конфликтов: обе правки основаны на одной и той же
 * версии задачи и приходят на сервер одна за другой.
 */
class TasksIntegrationTest : IntegrationTestBase() {

    @BeforeEach
    fun cleanup() = resetDatabase()

    // ─────────────────────────── создание ───────────────────────────

    @Test
    fun `повторное создание с тем же идентификатором возвращает ту же задачу`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val taskId = UUID.randomUUID()

        val first = client.createTask(anna, taskId, "Подготовить отчёт")
        val second = client.createTask(anna, taskId, "Подготовить отчёт")

        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals(
            HttpStatusCode.OK,
            second.status,
            "Повтор операции из очереди — не создание",
        )

        val mine: TasksResponse = client.get("/v1/tasks?scope=mine") { bearerAuth(anna.accessToken) }.body()
        assertEquals(1, mine.items.size, "Вторая задача появиться не должна")
    }

    @Test
    fun `личная задача не видна посторонним и выглядит как несуществующая`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val stranger = client.newUser("stranger@example.com", "stranger")
        val taskId = UUID.randomUUID()
        client.createTask(anna, taskId, "Личное")

        val foreign = client.get("/v1/tasks/$taskId") { bearerAuth(stranger.accessToken) }
        val absent = client.get("/v1/tasks/${UUID.randomUUID()}") { bearerAuth(stranger.accessToken) }

        assertEquals(HttpStatusCode.Forbidden, foreign.status)
        assertEquals(
            foreign.status,
            absent.status,
            "Иначе перебором можно выяснять, какие задачи существуют",
        )
    }

    @Test
    fun `задача чата видна его участникам и не видна посторонним`() = withApp { client ->
        val (anna, boris, chat) = client.twoInChat()
        val stranger = client.newUser("stranger@example.com", "stranger")
        val taskId = UUID.randomUUID()

        client.createTask(anna, taskId, "Общая", conversationId = chat.id)

        assertEquals(HttpStatusCode.OK, client.get("/v1/tasks/$taskId") { bearerAuth(boris.accessToken) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/tasks/$taskId") { bearerAuth(stranger.accessToken) }.status)
    }

    @Test
    fun `нельзя создать задачу в чужом чате`() = withApp { client ->
        val (_, _, chat) = client.twoInChat()
        val stranger = client.newUser("stranger@example.com", "stranger")

        val response = client.createTask(stranger, UUID.randomUUID(), "Вторжение", conversationId = chat.id)
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ──────────────── одновременное редактирование ────────────────

    @Test
    fun `два устройства меняют разные поля - обе правки применяются`() = withApp { client ->
        val (anna, boris, chat) = client.twoInChat()
        val taskId = UUID.randomUUID()
        val base: TaskDto = client.createTask(anna, taskId, "Релиз", conversationId = chat.id).body()

        val now = serverMoves()

        // Обе правки основаны на одной версии — как будто оба устройства
        // были офлайн и вернулись в сеть почти одновременно.
        val fromAnna: PatchTaskResponse = client.patchTask(
            anna, taskId, base.version, mapOf("priority" to "HIGH"), now + 10,
        ).body()
        val fromBoris: PatchTaskResponse = client.patchTask(
            boris, taskId, base.version, mapOf("due_at" to "2026-12-31T10:00:00Z"), now + 20,
        ).body()

        assertTrue(fromAnna.conflicts.isEmpty())
        assertTrue(
            fromBoris.conflicts.isEmpty(),
            "Правки разных полей не конфликтуют, хотя основаны на одной версии",
        )

        val final = fromBoris.task
        assertEquals("HIGH", final.priority, "Правка Анны не должна потеряться")
        assertEquals("2026-12-31T10:00:00Z", final.dueAt.toString())
    }

    @Test
    fun `два устройства меняют одно поле - побеждает более поздняя правка`() = withApp { client ->
        val (anna, boris, chat) = client.twoInChat()
        val taskId = UUID.randomUUID()
        val base: TaskDto = client.createTask(anna, taskId, "Черновик", conversationId = chat.id).body()

        val now = serverMoves()

        // Борис правил позже, но его запрос дошёл первым.
        val later: PatchTaskResponse = client.patchTask(
            boris, taskId, base.version, mapOf("title" to "Версия Бориса"), now + 200,
        ).body()
        assertTrue(later.conflicts.isEmpty())

        // Правка Анны сделана раньше, но доехала позже — она проигрывает.
        val earlier: PatchTaskResponse = client.patchTask(
            anna, taskId, base.version, mapOf("title" to "Версия Анны"), now + 100,
        ).body()

        assertEquals(listOf("title"), earlier.conflicts, "Клиенту сообщается, что его правка проиграла")
        assertEquals("Версия Бориса", earlier.task.title)
    }

    @Test
    fun `проигравшая правка остаётся в истории`() = withApp { client ->
        val (anna, boris, chat) = client.twoInChat()
        val taskId = UUID.randomUUID()
        val base: TaskDto = client.createTask(anna, taskId, "Черновик", conversationId = chat.id).body()
        val now = serverMoves()

        client.patchTask(boris, taskId, base.version, mapOf("title" to "Победитель"), now + 200)
        client.patchTask(anna, taskId, base.version, mapOf("title" to "Проигравший"), now + 100)

        val history: TaskEventsResponse =
            client.get("/v1/tasks/$taskId/events") { bearerAuth(anna.accessToken) }.body()

        val rejected = history.items.singleOrNull { it.type == "EDIT_REJECTED" }
        assertNotNull(
            rejected,
            "Отклонённая правка не должна исчезать молча: ${history.items.map { it.type }}",
        )
        assertTrue(
            rejected.newValue.toString().contains("Проигравший"),
            "Предложенное значение сохраняется, чтобы его можно было восстановить",
        )
    }

    @Test
    fun `при равных метках завершение побеждает возврат в работу`() = withApp { client ->
        val (anna, boris, chat) = client.twoInChat()
        val taskId = UUID.randomUUID()
        val base: TaskDto = client.createTask(anna, taskId, "Задача", conversationId = chat.id).body()
        val sameMoment = serverMoves() + 50

        client.patchTask(anna, taskId, base.version, mapOf("status" to "DONE"), sameMoment)
        val second: PatchTaskResponse = client.patchTask(
            boris, taskId, base.version, mapOf("status" to "IN_PROGRESS"), sameMoment,
        ).body()

        assertEquals("DONE", second.task.status, "Завершение — более сильное намерение")
        assertEquals(listOf("status"), second.conflicts)
        assertNotNull(second.task.completedAt, "Время завершения выставляет сервер")
    }

    @Test
    fun `клиент с актуальной версией не проигрывает из-за отстающих часов`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val taskId = UUID.randomUUID()
        val base: TaskDto = client.createTask(anna, taskId, "Исходное").body()

        // Часы устройства отстают на сутки, но конкурирующих правок не было.
        val response: PatchTaskResponse = client.patchTask(
            anna, taskId, base.version, mapOf("title" to "Новое"),
            time.now().toEpochMilli() - 24 * 3600 * 1000,
        ).body()

        assertTrue(response.conflicts.isEmpty(), "Спорить пользователю с самим собой незачем")
        assertEquals("Новое", response.task.title)
    }

    @Test
    fun `одновременные правки одной задачи не теряют обновлений`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val taskId = UUID.randomUUID()
        val base: TaskDto = client.createTask(anna, taskId, "Исходное").body()
        val now = serverMoves()

        // Блокировка строки задачи сериализует изменения: без неё два
        // запроса прочитали бы одни и те же метки и затёрли бы друг друга.
        val fields = listOf(
            "title" to "Новое название",
            "description" to "Новое описание",
            "priority" to "URGENT",
            "status" to "IN_PROGRESS",
        )
        coroutineScope {
            fields.mapIndexed { index, (field, value) ->
                // Метки строго позже создания задачи: при равенстве с ним
                // правка законно проиграла бы значению сервера.
                async { client.patchTask(anna, taskId, base.version, mapOf(field to value), now + index + 1) }
            }.awaitAll()
        }

        val final: TaskDto = client.get("/v1/tasks/$taskId") { bearerAuth(anna.accessToken) }.body()
        assertEquals("Новое название", final.title)
        assertEquals("Новое описание", final.description)
        assertEquals("URGENT", final.priority)
        assertEquals("IN_PROGRESS", final.status)
        assertEquals(base.version + fields.size, final.version, "Каждое изменение увеличивает версию")
    }

    @Test
    fun `служебное поле через частичное изменение не меняется`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val taskId = UUID.randomUUID()
        val base: TaskDto = client.createTask(anna, taskId, "Задача").body()

        val response = client.patchTask(
            anna, taskId, base.version, mapOf("created_by" to UUID.randomUUID().toString()),
            time.now().toEpochMilli(),
        )
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    }

    // ─────────────────────────── исполнители ───────────────────────────

    @Test
    fun `два устройства назначают разных людей - остаются оба`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val boris = client.newUser("boris@example.com", "boris")
        val clara = client.newUser("clara@example.com", "clara")
        val borisId = client.me(boris).id
        val claraId = client.me(clara).id

        val group: ConversationDto = client.post("/v1/conversations") {
            bearerAuth(anna.accessToken)
            contentType(ContentType.Application.Json)
            setBody(CreateConversationRequest("GROUP", listOf(borisId, claraId), "Команда"))
        }.body()

        val taskId = UUID.randomUUID()
        client.createTask(anna, taskId, "Общая", conversationId = group.id)

        // Анна назначает Бориса, Борис со своего устройства назначает Клару.
        client.assignees(anna, taskId, add = listOf(borisId))
        val final: TaskDto = client.assignees(boris, taskId, add = listOf(claraId)).body()

        assertEquals(
            setOf(borisId, claraId),
            final.assignees.map { it.userId }.toSet(),
            "Набор исполнителей сливается: одно назначение не затирает другое",
        )
    }

    @Test
    fun `исполнитель задачи чата должен быть его участником`() = withApp { client ->
        val (anna, _, chat) = client.twoInChat()
        val stranger = client.newUser("stranger@example.com", "stranger")
        val strangerId = client.me(stranger).id

        val taskId = UUID.randomUUID()
        client.createTask(anna, taskId, "Задача", conversationId = chat.id)

        val response = client.assignees(anna, taskId, add = listOf(strangerId))
        assertEquals(
            HttpStatusCode.UnprocessableEntity,
            response.status,
            "Назначение выдаёт право видеть задачу — иначе через него утекло бы содержимое чата",
        )
    }

    @Test
    fun `личную задачу нельзя назначить другому`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val borisId = client.me(client.newUser("boris@example.com", "boris")).id

        val response = client.createTask(anna, UUID.randomUUID(), "Личное", assigneeIds = listOf(borisId))
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    }

    @Test
    fun `снятый исполнитель теряет доступ к личной задаче`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val annaId = client.me(anna).id
        val taskId = UUID.randomUUID()

        client.createTask(anna, taskId, "Себе", assigneeIds = listOf(annaId))
        val after: TaskDto = client.assignees(anna, taskId, remove = listOf(annaId)).body()

        assertTrue(after.assignees.isEmpty())
        // Создатель по-прежнему видит свою задачу.
        assertEquals(HttpStatusCode.OK, client.get("/v1/tasks/$taskId") { bearerAuth(anna.accessToken) }.status)
    }

    // ─────────────────────────── комментарии ───────────────────────────

    @Test
    fun `повтор комментария с тем же идентификатором не создаёт дубликат`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val taskId = UUID.randomUUID()
        client.createTask(anna, taskId, "Задача")

        val first = client.comment(anna, taskId, "op-1", "Первый комментарий")
        val second = client.comment(anna, taskId, "op-1", "Первый комментарий")

        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals(HttpStatusCode.OK, second.status)

        val comments: CommentsResponse =
            client.get("/v1/tasks/$taskId/comments") { bearerAuth(anna.accessToken) }.body()
        assertEquals(1, comments.items.size)

        val task: TaskDto = client.get("/v1/tasks/$taskId") { bearerAuth(anna.accessToken) }.body()
        assertEquals(1, task.commentCount)
    }

    @Test
    fun `посторонний не комментирует чужую задачу`() = withApp { client ->
        val anna = client.newUser("anna@example.com", "anna")
        val stranger = client.newUser("stranger@example.com", "stranger")
        val taskId = UUID.randomUUID()
        client.createTask(anna, taskId, "Личное")

        assertEquals(HttpStatusCode.Forbidden, client.comment(stranger, taskId, "op-1", "Я здесь").status)
    }

    // ─────────────────────── задача из сообщения ───────────────────────

    @Test
    fun `задача из сообщения хранит снимок и переживает его удаление`() = withApp { client ->
        val (anna, boris, chat) = client.twoInChat()

        val message: MessageDto = client.post("/v1/conversations/${chat.id}/messages") {
            bearerAuth(boris.accessToken)
            contentType(ContentType.Application.Json)
            setBody(SendMessageRequest(clientMsgId = "op-1", body = "Обнови документацию к пятнице"))
        }.body()

        val taskId = UUID.randomUUID()
        val created = client.post("/v1/messages/${message.id}/to-task") {
            bearerAuth(anna.accessToken)
            contentType(ContentType.Application.Json)
            setBody(CreateTaskFromMessageRequest(taskId = taskId, title = "Документация"))
        }
        assertEquals(HttpStatusCode.Created, created.status)

        val task: TaskDto = created.body()
        assertEquals(chat.id, task.conversationId, "Задача из сообщения принадлежит его чату")
        val source = assertNotNull(task.source)
        assertEquals("Обнови документацию к пятнице", source.textSnapshot)
        assertEquals(message.id, source.messageId)
        assertTrue(!source.isSourceUnavailable)

        // Автор удаляет сообщение.
        client.delete("/v1/messages/${message.id}") { bearerAuth(boris.accessToken) }

        val after: TaskDto = client.get("/v1/tasks/$taskId") { bearerAuth(anna.accessToken) }.body()
        val afterSource = assertNotNull(after.source)
        assertTrue(afterSource.isSourceUnavailable, "Переход к удалённому сообщению невозможен")
        assertEquals(
            "Обнови документацию к пятнице",
            afterSource.textSnapshot,
            "Снимок обязан сохраниться — иначе задача теряет смысл",
        )
    }

    @Test
    fun `нельзя создать задачу из сообщения чужого чата`() = withApp { client ->
        val (_, boris, chat) = client.twoInChat()
        val stranger = client.newUser("stranger@example.com", "stranger")

        val message: MessageDto = client.post("/v1/conversations/${chat.id}/messages") {
            bearerAuth(boris.accessToken)
            contentType(ContentType.Application.Json)
            setBody(SendMessageRequest(clientMsgId = "op-1", body = "Секрет"))
        }.body()

        val response = client.post("/v1/messages/${message.id}/to-task") {
            bearerAuth(stranger.accessToken)
            contentType(ContentType.Application.Json)
            setBody(CreateTaskFromMessageRequest(taskId = UUID.randomUUID(), title = "Украдено"))
        }

        assertEquals(
            HttpStatusCode.Forbidden,
            response.status,
            "Иначе по идентификатору сообщения можно было бы вытянуть его текст",
        )
    }

    // ─────────────────────────── удаление ───────────────────────────

    @Test
    fun `удалить задачу может только автор`() = withApp { client ->
        val (anna, boris, chat) = client.twoInChat()
        val taskId = UUID.randomUUID()
        client.createTask(anna, taskId, "Задача Анны", conversationId = chat.id)

        assertEquals(HttpStatusCode.Forbidden, client.delete("/v1/tasks/$taskId") { bearerAuth(boris.accessToken) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/tasks/$taskId") { bearerAuth(anna.accessToken) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/tasks/$taskId") { bearerAuth(anna.accessToken) }.status)

        // Повтор после потерянного ответа — успех, а не отказ.
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/tasks/$taskId") { bearerAuth(anna.accessToken) }.status)
        // Посторонний по-прежнему не отличает удалённую задачу от чужой.
        assertEquals(HttpStatusCode.Forbidden, client.delete("/v1/tasks/$taskId") { bearerAuth(boris.accessToken) }.status)

        val forBoris: SyncResponse = client.get("/v1/sync?since=0") { bearerAuth(boris.accessToken) }.body()
        assertEquals(
            1,
            forBoris.events.count { it.type == "TASK_DELETED" },
            "Повтор удаления не должен порождать второе событие",
        )
    }

    // ─────────────────────────── журнал событий ───────────────────────────

    @Test
    fun `изменения задачи доходят до участников чата и не доходят до посторонних`() = withApp { client ->
        val (anna, boris, chat) = client.twoInChat()
        val stranger = client.newUser("stranger@example.com", "stranger")
        val taskId = UUID.randomUUID()

        val base: TaskDto = client.createTask(anna, taskId, "Задача", conversationId = chat.id).body()
        client.patchTask(anna, taskId, base.version, mapOf("status" to "IN_PROGRESS"), time.now().toEpochMilli())

        val forBoris: SyncResponse = client.get("/v1/sync?since=0") { bearerAuth(boris.accessToken) }.body()
        val taskEvents = forBoris.events.filter { it.scope == "TASK" }.map { it.type }
        assertTrue("TASK_CREATED" in taskEvents, "получено: $taskEvents")
        assertTrue("TASK_UPDATED" in taskEvents, "получено: $taskEvents")

        val forStranger: SyncResponse = client.get("/v1/sync?since=0") { bearerAuth(stranger.accessToken) }.body()
        assertTrue(forStranger.events.none { it.scope == "TASK" })
    }

    @Test
    fun `список мои содержит созданные и назначенные задачи`() = withApp { client ->
        val (anna, boris, chat) = client.twoInChat()
        val borisId = client.me(boris).id

        val assignedToBoris = UUID.randomUUID()
        client.createTask(anna, assignedToBoris, "Для Бориса", conversationId = chat.id, assigneeIds = listOf(borisId))
        val annaOnly = UUID.randomUUID()
        client.createTask(anna, annaOnly, "Только Анне", conversationId = chat.id)

        val mine: TasksResponse = client.get("/v1/tasks?scope=mine") { bearerAuth(boris.accessToken) }.body()
        assertEquals(listOf(assignedToBoris), mine.items.map { it.id })

        val team: TasksResponse = client.get("/v1/tasks?scope=team") { bearerAuth(boris.accessToken) }.body()
        assertEquals(setOf(assignedToBoris, annaOnly), team.items.map { it.id }.toSet())
    }

    // ─────────────────────────── вспомогательное ───────────────────────────

    /**
     * Сдвигает время сервера вперёд и возвращает момент «до сдвига».
     *
     * Время в тестах управляемое. Метки правок отсчитываются от возвращённого
     * момента, а сервер к этому времени уже «прожил» секунду — так метки
     * клиента не обгоняют часы сервера и не урезаются защитой от устройств
     * с часами из будущего.
     */
    private fun serverMoves(): Long {
        val base = time.now().toEpochMilli()
        time.advance(1_000)
        return base
    }

    private data class Pair3(val a: TokenPairResponse, val b: TokenPairResponse, val chat: ConversationDto)

    private suspend fun HttpClient.twoInChat(): Pair3 {
        val anna = newUser("anna@example.com", "anna")
        val boris = newUser("boris@example.com", "boris")
        val chat: ConversationDto = post("/v1/conversations") {
            bearerAuth(anna.accessToken)
            contentType(ContentType.Application.Json)
            setBody(CreateConversationRequest(type = "DIRECT", memberIds = listOf(me(boris).id)))
        }.body()
        return Pair3(anna, boris, chat)
    }

    private suspend fun HttpClient.me(tokens: TokenPairResponse): CurrentUserResponse =
        get("/v1/users/me") { bearerAuth(tokens.accessToken) }.body()

    private suspend fun HttpClient.createTask(
        tokens: TokenPairResponse,
        taskId: UUID,
        title: String,
        conversationId: UUID? = null,
        assigneeIds: List<UUID> = emptyList(),
    ): HttpResponse = post("/v1/tasks") {
        bearerAuth(tokens.accessToken)
        contentType(ContentType.Application.Json)
        setBody(
            CreateTaskRequest(
                taskId = taskId,
                title = title,
                conversationId = conversationId,
                assigneeIds = assigneeIds,
            ),
        )
    }

    private suspend fun HttpClient.patchTask(
        tokens: TokenPairResponse,
        taskId: UUID,
        baseVersion: Long,
        fields: Map<String, String?>,
        timestamp: Long,
    ): HttpResponse = patch("/v1/tasks/$taskId") {
        bearerAuth(tokens.accessToken)
        contentType(ContentType.Application.Json)
        setBody(
            PatchTaskRequest(
                baseVersion = baseVersion,
                changedFields = fields,
                fieldTimestamps = fields.keys.associateWith { timestamp },
            ),
        )
    }

    private suspend fun HttpClient.assignees(
        tokens: TokenPairResponse,
        taskId: UUID,
        add: List<UUID> = emptyList(),
        remove: List<UUID> = emptyList(),
    ): HttpResponse = post("/v1/tasks/$taskId/assignees") {
        bearerAuth(tokens.accessToken)
        contentType(ContentType.Application.Json)
        setBody(AssigneesRequest(add, remove))
    }

    private suspend fun HttpClient.comment(
        tokens: TokenPairResponse,
        taskId: UUID,
        clientOpId: String,
        body: String,
    ): HttpResponse = post("/v1/tasks/$taskId/comments") {
        bearerAuth(tokens.accessToken)
        contentType(ContentType.Application.Json)
        setBody(AddCommentRequest(clientOpId, body))
    }
}
