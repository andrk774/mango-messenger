package com.mango.backend.app

import com.mango.backend.auth.AuthRepository
import com.mango.backend.auth.CurrentUserResponse
import com.mango.backend.auth.DeviceInfoDto
import com.mango.backend.auth.LoginRequest
import com.mango.backend.auth.TokenPairResponse
import com.mango.backend.messaging.ConversationDto
import com.mango.backend.messaging.CreateConversationRequest
import com.mango.backend.messaging.SendMessageRequest
import com.mango.backend.notifications.PushMessage
import com.mango.backend.notifications.PushProvider
import com.mango.backend.notifications.PushRepository
import com.mango.backend.notifications.PushResult
import com.mango.backend.notifications.PushService
import com.mango.backend.notifications.RegisterPushTokenRequest
import com.mango.backend.tasks.CreateTaskRequest
import com.mango.backend.tasks.PatchTaskRequest
import com.mango.backend.tasks.TaskDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Push: кому уходят уведомления, кому — нет, и напоминания о задачах.
 *
 * Провайдер подменён записывающим: проверяется решение сервера «кому
 * и что отправить», а не сеть Google (её проверяет FcmPushProviderTest).
 */
class PushIntegrationTest : IntegrationTestBase() {

    @BeforeEach
    fun cleanup() = resetDatabase()

    private class RecordingProvider : PushProvider {
        override val name = "FCM"
        val sent = CopyOnWriteArrayList<Pair<String, PushMessage>>()
        val invalid = mutableSetOf<String>()

        override suspend fun send(token: String, message: PushMessage): PushResult {
            sent += token to message
            return if (token in invalid) PushResult.InvalidToken else PushResult.Delivered
        }

        fun tokens(): List<String> = sent.map { it.first }
    }

    private class Person(val tokens: TokenPairResponse, val id: UUID, val pushToken: String)

    @Test
    fun `новое сообщение будит собеседника, но не отправителя`() {
        val push = RecordingProvider()
        withApp(pushProvider = push) { client ->
            val (anna, boris, chat) = client.pair()

            client.send(anna, chat.id, "Привет")
            awaitPushes(push, 1)

            assertEquals(listOf(boris.pushToken), push.tokens())
            val data = push.sent.single().second.data
            assertEquals("message", data["type"])
            assertEquals(chat.id.toString(), data["conversationId"])
            assertTrue(data.values.none { "Привет" in it }, "Текст сообщения не уходит провайдеру (ADR-0010)")
        }
    }

    @Test
    fun `после выхода устройство не получает уведомлений`() {
        val push = RecordingProvider()
        withApp(pushProvider = push) { client ->
            val (anna, boris, chat) = client.pair()

            client.post("/v1/auth/logout") { bearerAuth(boris.tokens.accessToken) }
            client.send(anna, chat.id, "Ты здесь?")
            delay(300)

            assertTrue(push.sent.isEmpty(), "Уведомление ушло на устройство после выхода: ${push.tokens()}")
        }
    }

    @Test
    fun `явное снятие токена прекращает уведомления`() {
        val push = RecordingProvider()
        withApp(pushProvider = push) { client ->
            val (anna, boris, chat) = client.pair()

            assertEquals(
                HttpStatusCode.NoContent,
                client.delete("/v1/users/me/devices/push") { bearerAuth(boris.tokens.accessToken) }.status,
            )
            client.send(anna, chat.id, "Тук-тук")
            delay(300)

            assertTrue(push.sent.isEmpty())
        }
    }

    @Test
    fun `недействительный токен снимается после первого отказа`() {
        val push = RecordingProvider()
        withApp(pushProvider = push) { client ->
            val (anna, boris, chat) = client.pair()
            push.invalid += boris.pushToken

            client.send(anna, chat.id, "Раз")
            awaitPushes(push, 1)
            client.send(anna, chat.id, "Два")
            delay(300)

            assertEquals(1, push.sent.size, "Повторно на удалённое приложение не отправляем")
        }
    }

    @Test
    fun `токен одного телефона не остаётся у прежнего владельца`() {
        val push = RecordingProvider()
        withApp(pushProvider = push) { client ->
            val (anna, boris, chat) = client.pair()
            // Телефон Бориса перешёл к Вере: тот же токен FCM, новый вход.
            val vera = client.person("vera", pushToken = boris.pushToken)
            client.post("/v1/conversations") {
                bearerAuth(anna.tokens.accessToken)
                contentType(ContentType.Application.Json)
                setBody(CreateConversationRequest(type = "DIRECT", memberIds = listOf(vera.id)))
            }

            client.send(anna, chat.id, "Борису")
            delay(300)

            assertTrue(push.sent.isEmpty(), "Сообщение Бориса не должно будить телефон Веры")
        }
    }

    @Test
    fun `системные сообщения группы не будят телефон`() {
        val push = RecordingProvider()
        withApp(pushProvider = push) { client ->
            val anna = client.person("anna")
            val boris = client.person("boris")
            val group: ConversationDto = client.post("/v1/conversations") {
                bearerAuth(anna.tokens.accessToken)
                contentType(ContentType.Application.Json)
                setBody(CreateConversationRequest(type = "GROUP", memberIds = listOf(boris.id), title = "Команда"))
            }.body()

            client.patch("/v1/conversations/${group.id}") {
                bearerAuth(anna.tokens.accessToken)
                contentType(ContentType.Application.Json)
                setBody(com.mango.backend.messaging.UpdateConversationRequest("Новое имя"))
            }
            delay(300)

            assertTrue(push.sent.isEmpty())
        }
    }

    @Test
    fun `без устройства токен не регистрируется`() = withApp { client ->
        // newUser входит без сведений об устройстве.
        val tokens = client.newUser("anna@example.com", "anna")
        val response = client.registerPush(tokens, "token-x")
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    // ─────────────────────────── напоминания ───────────────────────────

    @Test
    fun `напоминание приходит один раз и снова — после переноса`() {
        val push = RecordingProvider()
        withApp(pushProvider = push) { client ->
            val anna = client.person("anna")
            val service = PushService(database, PushRepository(), AuthRepository(), push, time)

            val taskId = UUID.randomUUID()
            val task: TaskDto = client.post("/v1/tasks") {
                bearerAuth(anna.tokens.accessToken)
                contentType(ContentType.Application.Json)
                setBody(CreateTaskRequest(taskId = taskId, title = "Позвонить", remindAt = time.now().plusSeconds(60)))
            }.body()

            assertEquals(0, service.sendDueReminders(), "Время ещё не пришло")

            time.advance(61_000)
            assertEquals(1, service.sendDueReminders())
            assertEquals(0, service.sendDueReminders(), "Второй раз не отправляется")

            val reminder = push.sent.single()
            assertEquals(anna.pushToken, reminder.first, "Без исполнителей напоминание получает автор")
            assertEquals(mapOf("type" to "task_reminder", "taskId" to taskId.toString()), reminder.second.data)

            // Перенос напоминания включает его заново.
            time.advance(1_000)
            client.patch("/v1/tasks/$taskId") {
                bearerAuth(anna.tokens.accessToken)
                contentType(ContentType.Application.Json)
                setBody(
                    PatchTaskRequest(
                        baseVersion = task.version,
                        changedFields = mapOf("remind_at" to time.now().plusSeconds(30).toString()),
                        fieldTimestamps = mapOf("remind_at" to time.now().toEpochMilli()),
                    ),
                )
            }
            time.advance(31_000)
            assertEquals(1, service.sendDueReminders())
        }
    }

    @Test
    fun `выполненная задача не напоминает о себе`() {
        val push = RecordingProvider()
        withApp(pushProvider = push) { client ->
            val anna = client.person("anna")
            val service = PushService(database, PushRepository(), AuthRepository(), push, time)
            val taskId = UUID.randomUUID()
            val task: TaskDto = client.post("/v1/tasks") {
                bearerAuth(anna.tokens.accessToken)
                contentType(ContentType.Application.Json)
                setBody(CreateTaskRequest(taskId = taskId, title = "Готово заранее", remindAt = time.now().plusSeconds(60)))
            }.body()
            time.advance(1_000)
            client.patch("/v1/tasks/$taskId") {
                bearerAuth(anna.tokens.accessToken)
                contentType(ContentType.Application.Json)
                setBody(
                    PatchTaskRequest(task.version, mapOf("status" to "DONE"), mapOf("status" to time.now().toEpochMilli())),
                )
            }

            time.advance(120_000)
            assertEquals(0, service.sendDueReminders())
        }
    }

    // ──────────────────────── вспомогательное ────────────────────────

    /** Вход с устройством и регистрация push-токена — как это делает приложение. */
    private suspend fun HttpClient.person(name: String, pushToken: String = "token-$name"): Person {
        newUser("$name@example.com", name)
        val tokens: TokenPairResponse = post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                LoginRequest(
                    email = "$name@example.com",
                    password = "ТестовыйПароль-2026",
                    device = DeviceInfoDto(platform = "ANDROID", model = "Pixel"),
                ),
            )
        }.body()
        assertEquals(HttpStatusCode.NoContent, registerPush(tokens, pushToken).status)
        val me: CurrentUserResponse = get("/v1/users/me") { bearerAuth(tokens.accessToken) }.body()
        return Person(tokens, me.id, pushToken)
    }

    private suspend fun HttpClient.registerPush(tokens: TokenPairResponse, token: String): HttpResponse =
        put("/v1/users/me/devices/push") {
            bearerAuth(tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(RegisterPushTokenRequest(provider = "FCM", token = token))
        }

    private suspend fun HttpClient.pair(): Triple<Person, Person, ConversationDto> {
        val anna = person("anna")
        val boris = person("boris")
        val chat: ConversationDto = post("/v1/conversations") {
            bearerAuth(anna.tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(CreateConversationRequest(type = "DIRECT", memberIds = listOf(boris.id)))
        }.body()
        return Triple(anna, boris, chat)
    }

    private suspend fun HttpClient.send(from: Person, conversationId: UUID, text: String) {
        post("/v1/conversations/$conversationId/messages") {
            bearerAuth(from.tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(SendMessageRequest(clientMsgId = UUID.randomUUID().toString(), body = text))
        }
    }

    /** Push уходит асинхронно, после ответа API. */
    private suspend fun awaitPushes(push: RecordingProvider, count: Int) {
        repeat(50) {
            if (push.sent.size >= count) return
            delay(50)
        }
        error("ожидалось push: $count, получено ${push.sent.size}")
    }
}
