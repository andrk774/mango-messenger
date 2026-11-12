package com.mango.core.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mango.core.common.NoOpLogger
import com.mango.core.database.MangoDatabase
import com.mango.core.database.entity.ConversationEntity
import com.mango.core.database.entity.ConversationMemberEntity
import com.mango.core.database.entity.MessageEntity
import com.mango.core.database.entity.UserEntity
import com.mango.core.model.ConversationType
import com.mango.core.model.MemberRole
import com.mango.core.model.MessageDeliveryStatus
import com.mango.core.model.MessageType
import com.mango.core.model.PresenceStatus
import com.mango.core.model.SyncStatus
import com.mango.core.network.dto.SyncEventDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
 * Проверка применения серверных событий.
 *
 * Эти тесты защищают инвариант, от которого зависит вся синхронизация:
 * одно и то же событие, применённое дважды, не должно оставлять следа
 * во второй раз. Повторное применение неизбежно — событие может прийти
 * и через сокет, и через догонку по курсору.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class EventApplierTest {

    private lateinit var database: MangoDatabase
    private lateinit var applier: EventApplier

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val conversationId = "conv-1"
    private val me = "user-me"
    private val other = "user-other"

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangoDatabase::class.java,
        ).allowMainThreadQueries().build()

        applier = EventApplier(database, json, NoOpLogger())

        database.userDao().upsert(user(me, isMe = true))
        database.userDao().upsert(user(other, isMe = false))
        database.conversationDao().upsert(conversation())
        database.conversationDao().upsertMembers(
            listOf(
                member(me),
                member(other),
            ),
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `входящее сообщение записывается и продвигает курсор`() = runTest {
        applier.applyBatch(listOf(messageCreated(eventId = 5, messageId = "m1", seq = 1)))

        val stored = database.messageDao().findById("m1")
        assertNotNull(stored)
        assertEquals(1L, stored!!.seq)
        assertEquals(SyncStatus.SYNCED, stored.syncStatus)

        assertEquals(5L, database.outboxDao().syncState("GLOBAL")?.lastEventId)
    }

    @Test
    fun `повторное применение события не создаёт дубликат`() = runTest {
        val event = messageCreated(eventId = 5, messageId = "m1", seq = 1)

        applier.applyBatch(listOf(event))
        applier.applyBatch(listOf(event))

        val messages = database.messageDao().observeRecent(conversationId).first()
        assertEquals(
            "Событие могло прийти и сокетом, и догонкой — дубликата быть не должно",
            1,
            messages.size,
        )
    }

    @Test
    fun `собственное сообщение подтверждается, а не задваивается`() = runTest {
        // Локальная строка, созданная при отправке.
        database.messageDao().upsert(
            MessageEntity(
                id = "local-op",
                conversationId = conversationId,
                senderId = me,
                clientMsgId = "local-op",
                seq = null,
                type = MessageType.TEXT,
                body = "Привет",
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
                createdAt = 1_000,
            ),
        )

        applier.applyBatch(
            listOf(
                messageCreated(
                    eventId = 7,
                    messageId = "srv-1",
                    seq = 4,
                    senderId = me,
                    clientMsgId = "local-op",
                    body = "Привет",
                ),
            ),
        )

        val messages = database.messageDao().observeRecent(conversationId).first()
        assertEquals(
            "Собственное сообщение не должно появиться в переписке дважды",
            1,
            messages.size,
        )
        assertEquals("srv-1", messages.single().id)
        assertEquals(4L, messages.single().seq)
        assertEquals(SyncStatus.SYNCED, messages.single().syncStatus)
    }

    @Test
    fun `удаление стирает текст и сохраняет номер`() = runTest {
        applier.applyBatch(listOf(messageCreated(eventId = 5, messageId = "m1", seq = 1)))

        applier.applyBatch(
            listOf(
                event(
                    id = 6,
                    type = "MESSAGE_DELETED",
                    payload = """
                        {"conversationId":"$conversationId","messageId":"m1",
                         "seq":1,"deletedAt":"2026-11-12T10:05:00Z"}
                    """.trimIndent(),
                ),
            ),
        )

        val stored = database.messageDao().findById("m1")!!
        assertNull("Текст удалённого сообщения не хранится", stored.body)
        assertNotNull(stored.deletedAt)
        assertEquals(
            "Номер обязан остаться занятым, иначе клиент решит, что пропустил сообщение",
            1L,
            stored.seq,
        )
    }

    @Test
    fun `нераспознанное событие не останавливает применение пачки`() = runTest {
        applier.applyBatch(
            listOf(
                event(id = 1, type = "СОВЕРШЕННО_НОВЫЙ_ТИП", payload = """{"что":"то"}"""),
                messageCreated(eventId = 2, messageId = "m1", seq = 1),
            ),
        )

        assertNotNull(
            "Событие после неизвестного обязано примениться",
            database.messageDao().findById("m1"),
        )
        assertEquals(
            "Курсор должен продвинуться, иначе клиент застрянет на этом месте навсегда",
            2L,
            database.outboxDao().syncState("GLOBAL")?.lastEventId,
        )
    }

    @Test
    fun `курсор продвигается до последнего события пачки`() = runTest {
        applier.applyBatch(
            listOf(
                messageCreated(eventId = 10, messageId = "m1", seq = 1),
                messageCreated(eventId = 11, messageId = "m2", seq = 2),
                messageCreated(eventId = 12, messageId = "m3", seq = 3),
            ),
        )

        assertEquals(12L, database.outboxDao().syncState("GLOBAL")?.lastEventId)
        assertEquals(
            3,
            database.messageDao().observeRecent(conversationId).first().size,
        )
    }

    @Test
    fun `чужая отметка прочтения не обнуляет наши непрочитанные`() = runTest {
        applier.applyBatch(
            listOf(
                messageCreated(eventId = 1, messageId = "m1", seq = 1, senderId = other),
                messageCreated(eventId = 2, messageId = "m2", seq = 2, senderId = other),
            ),
        )

        applier.applyBatch(
            listOf(
                event(
                    id = 3,
                    type = "READ_MARKER_MOVED",
                    payload = """{"conversationId":"$conversationId","userId":"$other","seq":2}""",
                ),
            ),
        )

        // Отметка собеседника касается его, а не нас: наши непрочитанные
        // остаются непрочитанными.
        val unread = database.messageDao().countUnread(conversationId, 0, me)
        assertEquals(2, unread)
    }

    @Test
    fun `сообщение из неизвестного чата не теряется`() = runTest {
        // Чат мог ещё не загрузиться: событие пришло раньше первичной
        // загрузки. Внешний ключ не должен приводить к потере сообщения.
        applier.applyBatch(
            listOf(
                messageCreated(
                    eventId = 1,
                    messageId = "m1",
                    seq = 1,
                    conversationIdOverride = "conv-unknown",
                ),
            ),
        )

        assertNotNull(database.messageDao().findById("m1"))
        assertNotNull(database.conversationDao().findById("conv-unknown"))
    }

    @Test
    fun `автор сообщения запоминается для отображения имени`() = runTest {
        applier.applyBatch(
            listOf(
                messageCreated(
                    eventId = 1,
                    messageId = "m1",
                    seq = 1,
                    senderId = "user-new",
                    senderName = "Новый Участник",
                ),
            ),
        )

        val author = database.userDao().findById("user-new")
        assertNotNull("Иначе в переписке будет виден идентификатор вместо имени", author)
        assertEquals("Новый Участник", author!!.displayName)
    }

    @Test
    fun `существующий профиль не затирается данными из сообщения`() = runTest {
        applier.applyBatch(
            listOf(messageCreated(eventId = 1, messageId = "m1", seq = 1, senderId = me)),
        )

        val profile = database.userDao().findById(me)!!
        assertTrue(
            "Признак «это я» не должен потеряться при применении события",
            profile.isMe,
        )
    }

    // ──────────────────────── вспомогательное ────────────────────────

    private fun messageCreated(
        eventId: Long,
        messageId: String,
        seq: Long,
        senderId: String = other,
        senderName: String = "Иван",
        clientMsgId: String = messageId,
        body: String = "Сообщение $seq",
        conversationIdOverride: String? = null,
    ): SyncEventDto = event(
        id = eventId,
        type = "MESSAGE_CREATED",
        payload = """
            {"message":{
              "id":"$messageId",
              "conversationId":"${conversationIdOverride ?: conversationId}",
              "senderId":"$senderId",
              "senderName":"$senderName",
              "clientMsgId":"$clientMsgId",
              "seq":$seq,
              "type":"TEXT",
              "body":"$body",
              "createdAt":"2026-11-12T10:00:00Z"
            }}
        """.trimIndent(),
    )

    private fun event(id: Long, type: String, payload: String) = SyncEventDto(
        id = id,
        scope = "CONVERSATION",
        scopeId = conversationId,
        type = type,
        payload = json.parseToJsonElement(payload),
        createdAt = Instant.ofEpochMilli(1_700_000_000_000),
    )

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

    private fun member(userId: String) = ConversationMemberEntity(
        conversationId = conversationId,
        userId = userId,
        role = MemberRole.MEMBER,
        joinedAt = 1_000,
        lastReadSeq = 0,
        leftAt = null,
    )

    private fun user(id: String, isMe: Boolean) = UserEntity(
        id = id,
        username = id,
        displayName = if (isMe) "Я" else "Иван",
        avatarUrl = null,
        bio = null,
        presence = PresenceStatus.OFFLINE,
        lastSeenAt = null,
        isMe = isMe,
        updatedAt = 1_000,
    )
}
