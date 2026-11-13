package com.mango.core.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mango.core.common.MutableTimeProvider
import com.mango.core.common.NoOpLogger
import com.mango.core.common.RetryPolicy
import com.mango.core.data.outbox.OpType
import com.mango.core.data.outbox.OutboxJson
import com.mango.core.data.outbox.SendMessagePayload
import com.mango.core.data.outbox.UploadAttachmentPayload
import com.mango.core.data.mapper.toEntity
import com.mango.core.database.MangoDatabase
import com.mango.core.database.entity.AttachmentEntity
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
import com.mango.core.network.AttachmentsRemoteDataSource
import com.mango.core.network.MessagingRemoteDataSource
import com.mango.core.network.TasksRemoteDataSource
import com.mango.core.network.api.AttachmentsApi
import com.mango.core.network.api.BlobTransferApi
import com.mango.core.network.api.MessagingApi
import com.mango.core.network.api.TasksApi
import com.mango.core.network.dto.SyncEventDto
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
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Группы и вложения на клиенте: очередь загрузки и применение событий.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GroupsAndAttachmentsSyncTest {

    private lateinit var server: MockWebServer
    private lateinit var database: MangoDatabase
    private lateinit var applier: EventApplier
    private lateinit var processor: OutboxProcessor

    private val time = MutableTimeProvider(Instant.ofEpochMilli(1_700_000_000_000))
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val me = "user-me"
    private val chat = "conv-1"

    @Before
    fun setUp() = runTest {
        server = MockWebServer()
        server.start()
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangoDatabase::class.java,
        ).allowMainThreadQueries().build()

        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        applier = EventApplier(database, json, NoOpLogger())
        processor = OutboxProcessor(
            database = database,
            remote = MessagingRemoteDataSource(retrofit.create(MessagingApi::class.java)),
            tasksRemote = TasksRemoteDataSource(retrofit.create(TasksApi::class.java)),
            attachmentsRemote = AttachmentsRemoteDataSource(
                retrofit.create(AttachmentsApi::class.java),
                retrofit.create(BlobTransferApi::class.java),
            ),
            applier = applier,
            conflicts = TaskConflictNotifier(),
            time = time,
            logger = NoOpLogger(),
            retryPolicy = RetryPolicy.Default,
        )

        database.userDao().upsert(UserEntity(me, "me", "Я", null, null, PresenceStatus.ONLINE, null, isMe = true, updatedAt = 1))
        database.conversationDao().upsert(
            ConversationEntity(
                id = chat, type = ConversationType.GROUP, title = "Команда", avatarUrl = null,
                mutedUntil = null, draft = null, myRole = MemberRole.MEMBER, leftAt = null, updatedAt = 1,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
        server.close()
    }

    // ───────────────────────────── загрузка ─────────────────────────────

    @Test
    fun `файл загружается, затем уходит сообщение со ссылкой на него`() = runTest {
        val file = File.createTempFile("att", ".png").apply { writeBytes(ByteArray(1000) { it.toByte() }) }
        queueAttachmentMessage(file)

        respond(201, """{"attachmentId":"att-1","uploadUrl":"/v1/attachments/att-1/content?expires=1&signature=s","expiresAt":"2026-11-13T10:00:00Z","uploaded":false}""")
        respond(204, "")
        respond(201, serverMessage(clientMsgId = "msg-local", id = "msg-server"))

        // Первый проход — загрузка; сообщение ждёт её и уходит следующим проходом.
        processor.runOnce()
        processor.runOnce()

        val ticket = server.takeRequest()
        assertEquals("/v1/attachments/upload-ticket", ticket.url.encodedPath)
        val upload = server.takeRequest()
        assertEquals("PUT", upload.method)
        assertEquals(1000L, upload.bodySize)
        assertNull(upload.headers["Authorization"], "Токен не должен уходить по ссылке хранилища")
        val send = server.takeRequest()
        assertTrue(send.body!!.utf8().contains("\"attachmentIds\":[\"att-1\"]"), send.body!!.utf8())

        val attachment = database.attachmentDao().findById("att-1")!!
        assertEquals("msg-server", attachment.messageId, "Вложение переехало на серверный id сообщения")
        assertEquals(file.path, attachment.localUri, "Свой файл не нужно скачивать заново")
        assertEquals(SyncStatus.SYNCED, attachment.syncStatus)
        assertTrue(database.outboxDao().all().isEmpty())
    }

    @Test
    fun `уже принятый файл не загружается повторно`() = runTest {
        val file = File.createTempFile("att", ".png").apply { writeBytes(ByteArray(10)) }
        queueAttachmentMessage(file)
        respond(200, """{"attachmentId":"att-1","uploadUrl":"/x","expiresAt":"2026-11-13T10:00:00Z","uploaded":true}""")

        processor.runOnce()

        assertEquals(1, server.requestCount, "После ответа «уже загружен» передачи нет")
        assertNull(database.outboxDao().findByClientOpId("upload-1"))
    }

    @Test
    fun `отказ в загрузке помечает и сообщение — оно не повиснет в «отправляется»`() = runTest {
        val file = File.createTempFile("att", ".png").apply { writeBytes(ByteArray(10)) }
        queueAttachmentMessage(file)
        respond(415, """{"code":"UNSUPPORTED_MEDIA_TYPE","message":"Тип файла не поддерживается"}""")

        processor.runOnce()

        assertEquals(SyncStatus.FAILED, database.attachmentDao().findById("att-1")!!.syncStatus)
        val message = database.messageDao().findById("msg-local")!!
        assertEquals(MessageDeliveryStatus.FAILED, message.deliveryStatus)
        assertEquals(OutboxStatus.FAILED, database.outboxDao().findByClientOpId("msg-local")!!.status)
    }

    @Test
    fun `удалённый с устройства файл — окончательный отказ, а не вечные повторы`() = runTest {
        queueAttachmentMessage(File("/does/not/exist.png"))

        processor.runOnce()

        assertEquals(0, server.requestCount)
        assertEquals(MessageDeliveryStatus.FAILED, database.messageDao().findById("msg-local")!!.deliveryStatus)
    }

    // ───────────────────────────── события ─────────────────────────────

    @Test
    fun `исключение самого пользователя убирает группу с устройства`() = runTest {
        database.messageDao().upsert(localMessage("m-1", body = "старое"))

        applier.applyBatch(listOf(event(1, "CONVERSATION_MEMBER_REMOVED", """{"conversationId":"$chat","userId":"$me"}""")))

        assertNull(database.conversationDao().findById(chat))
        assertNull(database.messageDao().findById("m-1"), "Переписка удалена вместе с группой")
    }

    @Test
    fun `исключение другого участника убирает только его`() = runTest {
        applier.applyBatch(
            listOf(
                event(1, "CONVERSATION_MEMBER_ADDED", """{"conversationId":"$chat","members":[${member("user-b", "Борис")}]}"""),
                event(2, "CONVERSATION_MEMBER_REMOVED", """{"conversationId":"$chat","userId":"user-b"}"""),
            ),
        )

        assertNotNull(database.conversationDao().findById(chat))
        assertTrue(database.conversationDao().membersOf(chat).none { it.userId == "user-b" })
    }

    @Test
    fun `сведения об участнике-себе не сбрасывают отметку «это я»`() = runTest {
        applier.applyBatch(
            listOf(event(1, "CONVERSATION_MEMBER_UPDATED", """{"conversationId":"$chat","member":${member(me, "Я", "ADMIN")}}""")),
        )

        assertEquals(me, database.userDao().currentUserId(), "Без отметки приложение не знает, кто вошёл")
        assertEquals(MemberRole.ADMIN, database.conversationDao().roleOf(chat, me))
    }

    @Test
    fun `обновление карточки чата не стирает черновик`() = runTest {
        database.conversationDao().updateDraft(chat, "недописанное", 2)

        applier.applyBatch(listOf(event(1, "CONVERSATION_UPDATED", """{"conversation":${conversationDto("Новое название")}}""")))

        val stored = database.conversationDao().findById(chat)!!
        assertEquals("Новое название", stored.title)
        assertEquals("недописанное", stored.draft)
    }

    @Test
    fun `системное сообщение хранит структуру события`() = runTest {
        applier.applyBatch(
            listOf(
                event(
                    1, "MESSAGE_CREATED",
                    """{"message":${serverMessage(
                        clientMsgId = "system:x", id = "sys-1", type = "SYSTEM",
                        extra = ""","system":{"kind":"MEMBERS_ADDED","actorId":"user-a","actorName":"Анна","targets":[{"userId":"user-b","name":"Борис"}]}""",
                    )}}""",
                ),
            ),
        )

        val stored = database.messageDao().findById("sys-1")!!
        assertEquals(MessageType.SYSTEM, stored.type)
        val domain = com.mango.core.data.mapper.SystemEventCodec.decode(stored.body)!!
        assertEquals(com.mango.core.model.SystemEventKind.MEMBERS_ADDED, domain.kind)
        assertEquals("Борис", domain.targets.single().name)
    }

    @Test
    fun `чужое вложение запоминается без локального файла`() = runTest {
        applier.applyBatch(
            listOf(
                event(
                    1, "MESSAGE_CREATED",
                    """{"message":${serverMessage(
                        clientMsgId = "their", id = "msg-2", type = "IMAGE",
                        extra = ""","attachments":[{"id":"att-9","mimeType":"image/png","sizeBytes":5,"fileName":"a.png","width":10,"height":20}]""",
                    )}}""",
                ),
            ),
        )

        val attachment = database.attachmentDao().findById("att-9")!!
        assertEquals("msg-2", attachment.messageId)
        assertNull(attachment.localUri)
        assertEquals(10, attachment.width)
    }

    // ─────────────────────────── вспомогательное ───────────────────────────

    private suspend fun queueAttachmentMessage(file: File) {
        database.messageDao().upsert(localMessage("msg-local", type = MessageType.IMAGE, pending = true))
        database.attachmentDao().upsert(
            AttachmentEntity(
                id = "att-1", messageId = "msg-local", taskId = null, mimeType = "image/png",
                sizeBytes = file.takeIf { it.exists() }?.length() ?: 10, fileName = "a.png", remoteUrl = null,
                localUri = file.path, width = null, height = null, durationMs = null,
                uploadProgress = 0f, syncStatus = SyncStatus.PENDING, createdAt = 1,
            ),
        )
        enqueue(
            "upload-1", OpType.UPLOAD_ATTACHMENT, "ATTACHMENT", "att-1",
            OutboxJson.encodeToString(
                UploadAttachmentPayload(
                    attachmentId = "att-1", conversationId = chat, localPath = file.path,
                    mimeType = "image/png", sizeBytes = file.takeIf { it.exists() }?.length() ?: 10,
                    sha256 = "0".repeat(64), fileName = "a.png",
                ),
            ),
        )
        enqueue(
            "msg-local", OpType.SEND_MESSAGE, "MESSAGE", "msg-local",
            OutboxJson.encodeToString(
                SendMessagePayload(chat, "msg-local", text = null, type = "IMAGE", attachmentIds = listOf("att-1")),
            ),
            dependsOn = "upload-1",
        )
    }

    private suspend fun enqueue(
        id: String,
        opType: String,
        entityType: String,
        entityId: String,
        payload: String,
        dependsOn: String? = null,
    ) = database.outboxDao().enqueue(
        OutboxOperationEntity(
            id = id, clientOpId = id, opType = opType, entityType = entityType, entityId = entityId,
            payloadJson = payload, status = if (dependsOn == null) OutboxStatus.PENDING else OutboxStatus.BLOCKED,
            attemptCount = 0, nextAttemptAt = 0, lastError = null, dependsOnOpId = dependsOn,
            createdAt = 1, updatedAt = 1,
        ),
    )

    private fun localMessage(
        id: String,
        type: MessageType = MessageType.TEXT,
        body: String? = null,
        pending: Boolean = false,
    ) = MessageEntity(
        id = id, conversationId = chat, senderId = me, clientMsgId = id, seq = if (pending) null else 1,
        type = type, body = body, replyToId = null, replyToSenderName = null, replyToSnapshot = null,
        forwardedFromId = null, forwardedFromSenderName = null,
        deliveryStatus = if (pending) MessageDeliveryStatus.PENDING else MessageDeliveryStatus.SENT,
        syncStatus = if (pending) SyncStatus.PENDING else SyncStatus.SYNCED,
        pinnedAt = null, editedAt = null, deletedAt = null, createdAt = 1,
    )

    private fun respond(code: Int, body: String) {
        server.enqueue(
            MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build(),
        )
    }

    private fun serverMessage(
        clientMsgId: String,
        id: String,
        type: String = "IMAGE",
        extra: String = ""","attachments":[{"id":"att-1","mimeType":"image/png","sizeBytes":1000,"fileName":"a.png"}]""",
    ) = """{"id":"$id","conversationId":"$chat","senderId":"$me","senderName":"Я","clientMsgId":"$clientMsgId",
        "seq":5,"type":"$type","createdAt":"2026-11-13T10:00:00Z"$extra}"""

    private fun member(id: String, name: String, role: String = "MEMBER") =
        """{"userId":"$id","username":"$id","displayName":"$name","role":"$role","joinedAt":"2026-11-13T10:00:00Z","lastReadSeq":0}"""

    private fun conversationDto(title: String) =
        """{"id":"$chat","type":"GROUP","title":"$title","memberCount":3,"myRole":"MEMBER","lastEventSeq":4,
            "lastReadSeq":0,"unreadCount":0,"createdAt":"2026-11-13T10:00:00Z","updatedAt":"2026-11-13T10:00:00Z"}"""

    private fun event(id: Long, type: String, payload: String) = SyncEventDto(
        id = id,
        scope = "CONVERSATION",
        scopeId = chat,
        type = type,
        payload = json.parseToJsonElement(payload),
        createdAt = Instant.ofEpochMilli(1_700_000_000_000),
    )
}
