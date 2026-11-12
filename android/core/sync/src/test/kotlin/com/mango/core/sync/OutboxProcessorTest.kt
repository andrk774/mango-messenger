package com.mango.core.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mango.core.common.MutableTimeProvider
import com.mango.core.common.NoOpLogger
import com.mango.core.common.RetryPolicy
import com.mango.core.data.outbox.OpType
import com.mango.core.data.outbox.OutboxJson
import com.mango.core.data.outbox.SendMessagePayload
import com.mango.core.database.MangoDatabase
import com.mango.core.database.entity.ConversationEntity
import com.mango.core.database.entity.MessageEntity
import com.mango.core.database.entity.OutboxOperationEntity
import com.mango.core.database.entity.OutboxStatus
import com.mango.core.database.entity.UserEntity
import com.mango.core.model.ConversationType
import com.mango.core.model.MemberRole
import com.mango.core.model.MessageDeliveryStatus
import com.mango.core.model.MessageType
import com.mango.core.model.PresenceStatus
import com.mango.core.model.SyncStatus
import com.mango.core.network.MessagingRemoteDataSource
import com.mango.core.network.api.MessagingApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
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
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant

/**
 * Проверка отправки очереди.
 *
 * Запросы идут через настоящий HTTP-клиент к поддельному серверу:
 * так проверяется и логика очереди, и разбор ответа. Подделка источника
 * данных пропустила бы расхождение DTO с форматом сервера — а это
 * именно тот класс ошибок, который дороже всего обнаружить позже.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class OutboxProcessorTest {

    private lateinit var server: MockWebServer
    private lateinit var database: MangoDatabase
    private lateinit var processor: OutboxProcessor

    private val time = MutableTimeProvider(Instant.ofEpochMilli(1_700_000_000_000))
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val conversationId = "conv-1"
    private val me = "user-me"

    @Before
    fun setUp() = runTest {
        server = MockWebServer()
        server.start()

        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangoDatabase::class.java,
        ).allowMainThreadQueries().build()

        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(MessagingApi::class.java)

        processor = OutboxProcessor(
            database = database,
            remote = MessagingRemoteDataSource(api),
            applier = EventApplier(database, json, NoOpLogger()),
            time = time,
            logger = NoOpLogger(),
            retryPolicy = RetryPolicy.Default,
        )

        database.userDao().upsert(user(me, isMe = true))
        database.conversationDao().upsert(conversation())
    }

    @After
    fun tearDown() {
        database.close()
        server.close()
    }

    @Test
    fun `сообщение уходит на сервер и получает номер`() = runTest {
        enqueueLocalMessage(clientMsgId = "op-1", text = "Привет")

        server.enqueue(
            MockResponse.Builder()
                .code(201)
                .setHeader("Content-Type", "application/json")
                .body(serverMessage(id = "srv-1", seq = 7, clientMsgId = "op-1", body = "Привет"))
                .build(),
        )

        val result = processor.runOnce()

        assertEquals(1, result.processed)
        assertEquals(0, result.failed)

        // Локальная строка переехала на серверный идентификатор.
        assertNull("Локальная строка не должна остаться", database.messageDao().findById("op-1"))

        val stored = database.messageDao().findById("srv-1")
        assertNotNull("Сообщение должно быть записано под серверным id", stored)
        assertEquals(7L, stored!!.seq)
        assertEquals(SyncStatus.SYNCED, stored.syncStatus)
        assertEquals(MessageDeliveryStatus.SENT, stored.deliveryStatus)

        // Операция убрана из очереди: повторять её незачем.
        assertNull(database.outboxDao().findByClientOpId("op-1"))
    }

    @Test
    fun `повтор уже выполненной операции не считается ошибкой`() = runTest {
        enqueueLocalMessage(clientMsgId = "op-1", text = "Привет")

        // Сервер отвечает 200: операция уже применялась ранее.
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body(serverMessage(id = "srv-1", seq = 3, clientMsgId = "op-1", body = "Привет"))
                .build(),
        )

        val result = processor.runOnce()

        assertEquals(1, result.processed)
        assertEquals(3L, database.messageDao().findById("srv-1")?.seq)
        assertNull(database.outboxDao().findByClientOpId("op-1"))
    }

    @Test
    fun `отказ в доступе помечает сообщение как неотправленное`() = runTest {
        enqueueLocalMessage(clientMsgId = "op-1", text = "В чужой чат")

        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .setHeader("Content-Type", "application/json")
                .body("""{"code":"FORBIDDEN","message":"Чат недоступен"}""")
                .build(),
        )

        val result = processor.runOnce()

        assertEquals(0, result.processed)
        assertEquals(1, result.failed)

        val operation = database.outboxDao().findByClientOpId("op-1")
        assertNotNull("Операция остаётся для разбора пользователем", operation)
        assertEquals(OutboxStatus.FAILED, operation!!.status)

        val message = database.messageDao().findById("op-1")
        assertEquals(
            "Пользователь должен видеть, что сообщение не ушло",
            MessageDeliveryStatus.FAILED,
            message?.deliveryStatus,
        )
    }

    @Test
    fun `ошибка сервера откладывает попытку, а не отменяет операцию`() = runTest {
        enqueueLocalMessage(clientMsgId = "op-1", text = "Привет")

        server.enqueue(MockResponse.Builder().code(503).body("""{"code":"UNAVAILABLE"}""").build())

        val result = processor.runOnce()

        assertEquals(0, result.failed)
        assertEquals(1, result.retriesScheduled)

        val operation = database.outboxDao().findByClientOpId("op-1")!!
        assertEquals(OutboxStatus.PENDING, operation.status)
        assertEquals(1, operation.attemptCount)
        assertTrue(
            "Следующая попытка должна быть отложена",
            operation.nextAttemptAt > time.nowMillis(),
        )

        // До назначенного времени операция не выбирается повторно.
        assertEquals(0, processor.runOnce().processed)
    }

    @Test
    fun `операции отправляются в порядке создания`() = runTest {
        enqueueLocalMessage(clientMsgId = "op-1", text = "Первое", createdAt = 1_000)
        enqueueLocalMessage(clientMsgId = "op-2", text = "Второе", createdAt = 2_000)

        repeat(2) { index ->
            server.enqueue(
                MockResponse.Builder()
                    .code(201)
                    .setHeader("Content-Type", "application/json")
                    .body(
                        serverMessage(
                            id = "srv-${index + 1}",
                            seq = (index + 1).toLong(),
                            clientMsgId = "op-${index + 1}",
                            body = if (index == 0) "Первое" else "Второе",
                        ),
                    )
                    .build(),
            )
        }

        processor.runOnce()

        val first = server.takeRequest()
        val second = server.takeRequest()

        assertTrue(
            "Первым должно уйти более раннее сообщение",
            first.body!!.utf8().contains("op-1"),
        )
        assertTrue(second.body!!.utf8().contains("op-2"))
    }

    @Test
    fun `операции неподдерживаемого типа не блокируют очередь`() = runTest {
        // Задачи сервером пока не принимаются. Их операции не должны
        // занимать порцию и мешать отправке сообщений.
        database.outboxDao().enqueue(
            OutboxOperationEntity(
                id = "task-op",
                clientOpId = "task-op",
                opType = OpType.CREATE_TASK,
                entityType = "TASK",
                entityId = "task-1",
                payloadJson = "{}",
                status = OutboxStatus.PENDING,
                attemptCount = 0,
                nextAttemptAt = 0,
                lastError = null,
                dependsOnOpId = null,
                createdAt = 500,
                updatedAt = 500,
            ),
        )
        enqueueLocalMessage(clientMsgId = "op-1", text = "Сообщение", createdAt = 1_000)

        server.enqueue(
            MockResponse.Builder()
                .code(201)
                .setHeader("Content-Type", "application/json")
                .body(serverMessage(id = "srv-1", seq = 1, clientMsgId = "op-1", body = "Сообщение"))
                .build(),
        )

        val result = processor.runOnce()

        assertEquals("Сообщение должно уйти, несмотря на операцию задачи", 1, result.processed)
        assertNotNull(
            "Операция задачи остаётся в очереди до появления поддержки на сервере",
            database.outboxDao().findByClientOpId("task-op"),
        )
    }

    @Test
    fun `застрявшая операция восстанавливается при следующем проходе`() = runTest {
        enqueueLocalMessage(clientMsgId = "op-1", text = "Привет")
        database.outboxDao().updateStatus("op-1", OutboxStatus.IN_FLIGHT, time.nowMillis())

        server.enqueue(
            MockResponse.Builder()
                .code(201)
                .setHeader("Content-Type", "application/json")
                .body(serverMessage(id = "srv-1", seq = 1, clientMsgId = "op-1", body = "Привет"))
                .build(),
        )

        val result = processor.runOnce()

        assertEquals(
            "Операция, прерванная на полпути, обязана уехать повторно",
            1,
            result.processed,
        )
    }

    // ──────────────────────── вспомогательное ────────────────────────

    private suspend fun enqueueLocalMessage(
        clientMsgId: String,
        text: String,
        createdAt: Long = 1_000,
    ) {
        database.messageDao().upsert(
            MessageEntity(
                id = clientMsgId,
                conversationId = conversationId,
                senderId = me,
                clientMsgId = clientMsgId,
                seq = null,
                type = MessageType.TEXT,
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
                createdAt = createdAt,
            ),
        )
        database.outboxDao().enqueue(
            OutboxOperationEntity(
                id = clientMsgId,
                clientOpId = clientMsgId,
                opType = OpType.SEND_MESSAGE,
                entityType = "MESSAGE",
                entityId = clientMsgId,
                payloadJson = OutboxJson.encodeToString(
                    SendMessagePayload(
                        conversationId = conversationId,
                        clientMsgId = clientMsgId,
                        text = text,
                        replyToId = null,
                    ),
                ),
                status = OutboxStatus.PENDING,
                attemptCount = 0,
                nextAttemptAt = 0,
                lastError = null,
                dependsOnOpId = null,
                createdAt = createdAt,
                updatedAt = createdAt,
            ),
        )
    }

    private fun serverMessage(
        id: String,
        seq: Long,
        clientMsgId: String,
        body: String,
    ): String = """
        {
          "id": "$id",
          "conversationId": "$conversationId",
          "senderId": "$me",
          "senderName": "Я",
          "clientMsgId": "$clientMsgId",
          "seq": $seq,
          "type": "TEXT",
          "body": "$body",
          "createdAt": "2026-11-12T10:00:00Z"
        }
    """.trimIndent()

    private fun conversation() = ConversationEntity(
        id = conversationId,
        type = ConversationType.DIRECT,
        title = "Иван",
        avatarUrl = null,
        memberCount = 2,
        pinnedMessageCount = 0,
        lastEventSeq = 0,
        lastReadSeq = 0,
        mutedUntil = null,
        draft = null,
        myRole = MemberRole.MEMBER,
        isArchived = false,
        leftAt = null,
        updatedAt = 1_000,
    )

    private fun user(id: String, isMe: Boolean) = UserEntity(
        id = id,
        username = id,
        displayName = if (isMe) "Я" else id,
        avatarUrl = null,
        bio = null,
        presence = PresenceStatus.ONLINE,
        lastSeenAt = null,
        isMe = isMe,
        updatedAt = 1_000,
    )
}
