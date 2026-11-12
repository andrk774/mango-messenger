package com.mango.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mango.core.database.dao.ConversationDao
import com.mango.core.database.dao.MessageDao
import com.mango.core.database.dao.UserDao
import com.mango.core.database.entity.MessageGapEntity
import com.mango.core.model.MessageDeliveryStatus
import com.mango.core.model.SyncStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Тесты DAO сообщений.
 *
 * Выполняются на JVM через Robolectric: эмулятор в окружении недоступен,
 * а проверять инварианты схемы (дедупликация, порядок, подсчёт непрочитанных)
 * нужно на настоящем SQLite, а не на моках.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MessageDaoTest {

    private lateinit var database: MangoDatabase
    private lateinit var messageDao: MessageDao
    private lateinit var conversationDao: ConversationDao
    private lateinit var userDao: UserDao

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangoDatabase::class.java,
        ).allowMainThreadQueries().build()

        messageDao = database.messageDao()
        conversationDao = database.conversationDao()
        userDao = database.userDao()

        userDao.upsert(Fixtures.user(id = Fixtures.ME, displayName = "Я", isMe = true))
        userDao.upsert(Fixtures.user(id = Fixtures.OTHER, displayName = "Иван Петров"))
        conversationDao.upsert(Fixtures.conversation())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `duplicate client message id is rejected by unique index`() = runTest {
        val first = Fixtures.message(id = "m1", clientMsgId = "op-1")
        val duplicate = Fixtures.message(id = "m2", clientMsgId = "op-1")

        assertEquals("Первая вставка должна пройти", 1L, messageDao.insertIgnore(first))
        assertEquals("Дубликат должен быть проигнорирован", -1L, messageDao.insertIgnore(duplicate))

        val stored = messageDao.observeRecent(Fixtures.CONVERSATION).first()
        assertEquals("В базе должно остаться одно сообщение", 1, stored.size)
        assertEquals("m1", stored.single().id)
    }

    @Test
    fun `confirming sent message replaces local id and assigns seq`() = runTest {
        messageDao.insertIgnore(Fixtures.message(id = "local-1", clientMsgId = "op-1"))

        val updated = messageDao.confirmSent(
            conversationId = Fixtures.CONVERSATION,
            clientMsgId = "op-1",
            serverId = "server-1",
            seq = 42,
            serverCreatedAt = 2_000,
        )

        assertEquals(1, updated)
        assertNull("Локальная строка не должна остаться", messageDao.findById("local-1"))

        val confirmed = messageDao.findById("server-1")
        assertNotNull(confirmed)
        assertEquals(42L, confirmed!!.seq)
        assertEquals(MessageDeliveryStatus.SENT, confirmed.deliveryStatus)
        assertEquals(SyncStatus.SYNCED, confirmed.syncStatus)
        assertEquals(2_000L, confirmed.createdAt)
    }

    @Test
    fun `confirming the same operation twice is idempotent`() = runTest {
        messageDao.insertIgnore(Fixtures.message(id = "local-1", clientMsgId = "op-1"))

        val firstApply = messageDao.confirmSent(
            Fixtures.CONVERSATION, "op-1", "server-1", 42, 2_000,
        )
        // Повторный ответ сервера на ту же операцию — например, после реконнекта.
        val secondApply = messageDao.confirmSent(
            Fixtures.CONVERSATION, "op-1", "server-1", 42, 2_000,
        )

        assertEquals(1, firstApply)
        assertEquals("Повтор должен обновить ту же строку, а не создать новую", 1, secondApply)

        val all = messageDao.observeRecent(Fixtures.CONVERSATION).first()
        assertEquals(1, all.size)
        assertEquals("server-1", all.single().id)
    }

    @Test
    fun `unconfirmed messages sort after confirmed ones`() = runTest {
        // Список рисуется реверсивно, поэтому «первым» в выборке должно идти
        // неподтверждённое сообщение — визуально оно внизу переписки.
        messageDao.upsert(Fixtures.message(id = "s1", clientMsgId = "c1", seq = 1, createdAt = 1_000))
        messageDao.upsert(Fixtures.message(id = "s2", clientMsgId = "c2", seq = 2, createdAt = 2_000))
        messageDao.upsert(Fixtures.message(id = "local", clientMsgId = "c3", seq = null, createdAt = 3_000))

        val ordered = messageDao.observeRecent(Fixtures.CONVERSATION).first()

        assertEquals(listOf("local", "s2", "s1"), ordered.map { it.id })
    }

    @Test
    fun `unread count ignores own and deleted messages`() = runTest {
        conversationDao.upsert(Fixtures.conversation(lastReadSeq = 10))

        // Входящие после последнего прочитанного — считаются.
        messageDao.upsert(Fixtures.message(id = "in-1", clientMsgId = "c1", senderId = Fixtures.OTHER, seq = 11))
        messageDao.upsert(Fixtures.message(id = "in-2", clientMsgId = "c2", senderId = Fixtures.OTHER, seq = 12))
        // Своё — не считается.
        messageDao.upsert(Fixtures.message(id = "mine", clientMsgId = "c3", senderId = Fixtures.ME, seq = 13))
        // Удалённое — не считается.
        messageDao.upsert(
            Fixtures.message(
                id = "del", clientMsgId = "c4", senderId = Fixtures.OTHER, seq = 14, deletedAt = 5_000,
            ),
        )
        // До последнего прочитанного — не считается.
        messageDao.upsert(Fixtures.message(id = "old", clientMsgId = "c5", senderId = Fixtures.OTHER, seq = 9))

        val unread = messageDao.countUnread(Fixtures.CONVERSATION, lastReadSeq = 10, currentUserId = Fixtures.ME)

        assertEquals(2, unread)
    }

    @Test
    fun `findUnsent returns only pending and in flight messages`() = runTest {
        messageDao.upsert(
            Fixtures.message(id = "p", clientMsgId = "c1", syncStatus = SyncStatus.PENDING, createdAt = 1_000),
        )
        messageDao.upsert(
            Fixtures.message(id = "f", clientMsgId = "c2", syncStatus = SyncStatus.IN_FLIGHT, createdAt = 2_000),
        )
        messageDao.upsert(
            Fixtures.message(id = "s", clientMsgId = "c3", syncStatus = SyncStatus.SYNCED, seq = 1),
        )
        messageDao.upsert(
            Fixtures.message(id = "x", clientMsgId = "c4", syncStatus = SyncStatus.FAILED),
        )

        val unsent = messageDao.findUnsent()

        assertEquals(
            "FAILED не должен автоматически переотправляться — это решение пользователя",
            listOf("p", "f"),
            unsent.map { it.id },
        )
    }

    @Test
    fun `deleting a message clears its body but keeps the row`() = runTest {
        messageDao.upsert(Fixtures.message(id = "m1", clientMsgId = "c1", seq = 1, body = "секрет"))

        messageDao.markDeleted("m1", deletedAt = 9_000, syncStatus = SyncStatus.SYNCED)

        val stored = messageDao.findById("m1")
        assertNotNull("Строка должна остаться — на неё могут ссылаться ответы и задачи", stored)
        assertNull("Тело должно быть стёрто", stored!!.body)
        assertEquals(9_000L, stored.deletedAt)
    }

    @Test
    fun `applying a server page closes the gap in one transaction`() = runTest {
        val gap = MessageGapEntity(Fixtures.CONVERSATION, fromSeq = 5, toSeq = 9)
        messageDao.upsertGap(gap)
        assertEquals(1, messageDao.gapsFor(Fixtures.CONVERSATION).size)

        messageDao.applyServerPage(
            messages = (5L..9L).map {
                Fixtures.message(id = "s$it", clientMsgId = "c$it", seq = it, syncStatus = SyncStatus.SYNCED)
            },
            closedGap = gap,
        )

        assertEquals("Разрыв должен быть закрыт", 0, messageDao.gapsFor(Fixtures.CONVERSATION).size)
        assertEquals(9L, messageDao.maxSeq(Fixtures.CONVERSATION))
        assertEquals(5L, messageDao.minSeq(Fixtures.CONVERSATION))
    }

    @Test
    fun `reactions cascade delete with their message`() = runTest {
        messageDao.upsert(Fixtures.message(id = "m1", clientMsgId = "c1", seq = 1))
        messageDao.upsertReaction(
            com.mango.core.database.entity.MessageReactionEntity(
                messageId = "m1",
                userId = Fixtures.OTHER,
                emoji = "👍",
                syncStatus = SyncStatus.SYNCED,
                createdAt = 1_000,
            ),
        )
        assertEquals(1, messageDao.reactionsFor(listOf("m1")).size)

        messageDao.deleteByConversation(Fixtures.CONVERSATION)

        assertEquals(
            "Реакции должны уйти вместе с сообщением по ON DELETE CASCADE",
            0,
            messageDao.reactionsFor(listOf("m1")).size,
        )
    }

    @Test
    fun `search matches message body and respects conversation filter`() = runTest {
        conversationDao.upsert(Fixtures.conversation(id = "conv-2", title = "Второй"))
        messageDao.upsert(Fixtures.message(id = "m1", clientMsgId = "c1", seq = 1, body = "обсудим релиз"))
        messageDao.upsert(Fixtures.message(id = "m2", clientMsgId = "c2", seq = 2, body = "привет"))
        messageDao.upsert(
            Fixtures.message(id = "m3", conversationId = "conv-2", clientMsgId = "c3", seq = 1, body = "релиз готов"),
        )

        assertEquals(2, messageDao.search("релиз", conversationId = null).size)
        assertEquals(
            listOf("m1"),
            messageDao.search("релиз", conversationId = Fixtures.CONVERSATION).map { it.id },
        )
    }
}
