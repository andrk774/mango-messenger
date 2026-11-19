package com.mango.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mango.core.database.dao.ConversationDao
import com.mango.core.database.entity.OutboxStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Курсор прочтения при записи серверной карточки чата.
 *
 * Регрессия: карточка, полученная до доставки отметки прочтения,
 * откатывала локальный курсор, и прочитанные сообщения снова
 * считались непрочитанными.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ConversationReadCursorTest {

    private lateinit var database: MangoDatabase
    private lateinit var dao: ConversationDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangoDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.conversationDao()
    }

    @After
    fun tearDown() = database.close()

    private suspend fun queueReadReceipt(status: OutboxStatus, conversationId: String = Fixtures.CONVERSATION) {
        database.outboxDao().enqueue(
            Fixtures.outboxOperation(
                id = "read-$status-$conversationId",
                opType = "MARK_READ",
                entityType = "CONVERSATION",
                entityId = conversationId,
                status = status,
            ),
        )
    }

    private suspend fun cursor() = dao.findById(Fixtures.CONVERSATION)!!.lastReadSeq

    @Test
    fun `local cursor ahead of server is kept while read receipt is unsent`() = runTest {
        dao.upsert(Fixtures.conversation(lastReadSeq = 10))
        queueReadReceipt(OutboxStatus.PENDING)

        dao.upsertFromServer(Fixtures.conversation(lastReadSeq = 4))

        assertEquals(10L, cursor())
    }

    @Test
    fun `in flight and blocked receipts also protect the local cursor`() = runTest {
        for (status in listOf(OutboxStatus.IN_FLIGHT, OutboxStatus.BLOCKED)) {
            database.clearAllTables()
            dao.upsert(Fixtures.conversation(lastReadSeq = 10))
            queueReadReceipt(status)

            dao.upsertFromServer(Fixtures.conversation(lastReadSeq = 4))

            assertEquals("статус $status", 10L, cursor())
        }
    }

    @Test
    fun `server cursor ahead of local wins`() = runTest {
        dao.upsert(Fixtures.conversation(lastReadSeq = 3))
        queueReadReceipt(OutboxStatus.PENDING)

        dao.upsertFromServer(Fixtures.conversation(lastReadSeq = 8))

        assertEquals(8L, cursor())
    }

    @Test
    fun `equal cursors stay unchanged`() = runTest {
        dao.upsert(Fixtures.conversation(lastReadSeq = 5))
        queueReadReceipt(OutboxStatus.PENDING)

        dao.upsertFromServer(Fixtures.conversation(lastReadSeq = 5))

        assertEquals(5L, cursor())
    }

    @Test
    fun `without unsent receipt server is the source of truth`() = runTest {
        // Так выглядит база после смены аккаунта без очистки (сессия
        // истекла, вошёл другой пользователь): курсор прежнего владельца
        // не должен скрывать непрочитанное от нового.
        dao.upsert(Fixtures.conversation(lastReadSeq = 10))

        dao.upsertFromServer(Fixtures.conversation(lastReadSeq = 2))

        assertEquals(2L, cursor())
    }

    @Test
    fun `failed receipt does not pin the cursor`() = runTest {
        // Сервер отверг отметку окончательно: локальное значение больше
        // не подкреплено ничем, что дойдёт до сервера.
        dao.upsert(Fixtures.conversation(lastReadSeq = 10))
        queueReadReceipt(OutboxStatus.FAILED)

        dao.upsertFromServer(Fixtures.conversation(lastReadSeq = 2))

        assertEquals(2L, cursor())
    }

    @Test
    fun `receipt of another chat does not protect this one`() = runTest {
        dao.upsert(Fixtures.conversation(lastReadSeq = 10))
        dao.upsert(Fixtures.conversation(id = "conv-2"))
        queueReadReceipt(OutboxStatus.PENDING, conversationId = "conv-2")

        dao.upsertFromServer(Fixtures.conversation(lastReadSeq = 2))

        assertEquals(2L, cursor())
    }

    @Test
    fun `resync before receipt delivery does not bring unread back`() = runTest {
        database.userDao().upsert(Fixtures.user(id = Fixtures.ME, isMe = true))
        database.userDao().upsert(Fixtures.user(id = Fixtures.OTHER))
        dao.upsert(Fixtures.conversation(lastReadSeq = 0))
        val messages = database.messageDao()
        (1L..3L).forEach { seq ->
            messages.upsert(
                Fixtures.message(id = "in-$seq", clientMsgId = "c$seq", senderId = Fixtures.OTHER, seq = seq),
            )
        }
        assertEquals(3, dao.observeTotalUnread(Fixtures.ME).first())

        // Пользователь дочитал чат без сети.
        dao.advanceLastReadSeq(Fixtures.CONVERSATION, 3)
        queueReadReceipt(OutboxStatus.PENDING)
        assertEquals(0, dao.observeTotalUnread(Fixtures.ME).first())

        // Пересинхронизация дважды приносит карточку со старым курсором.
        repeat(2) { dao.upsertFromServer(Fixtures.conversation(lastReadSeq = 0)) }

        assertEquals(0, dao.observeTotalUnread(Fixtures.ME).first())
        assertEquals(0, dao.observeConversationList(Fixtures.ME).first().single().unreadCount)
    }
}
