package com.mango.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mango.core.database.dao.OutboxDao
import com.mango.core.database.entity.OutboxStatus
import com.mango.core.database.entity.SyncStateEntity
import kotlinx.coroutines.flow.first
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

/**
 * Тесты очереди исходящих операций.
 *
 * Это ядро offline-first, поэтому проверяются именно инварианты, от которых
 * зависит сохранность пользовательских данных: идемпотентность постановки
 * в очередь, восстановление после убийства процесса, порядок зависимых
 * операций и расписание повторов.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class OutboxDaoTest {

    private lateinit var database: MangoDatabase
    private lateinit var dao: OutboxDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangoDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.outboxDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `enqueueing the same client op id twice does not duplicate`() = runTest {
        val first = dao.enqueue(Fixtures.outboxOperation(id = "op-a", clientOpId = "client-1"))
        val second = dao.enqueue(Fixtures.outboxOperation(id = "op-b", clientOpId = "client-1"))

        assertTrue("Первая постановка должна пройти", first > 0)
        assertEquals("Дубликат по client_op_id должен быть отвергнут", -1L, second)
        assertEquals(1, dao.all().size)
    }

    @Test
    fun `next batch respects scheduled attempt time`() = runTest {
        dao.enqueue(Fixtures.outboxOperation(id = "ready", clientOpId = "c1", nextAttemptAt = 1_000))
        dao.enqueue(Fixtures.outboxOperation(id = "later", clientOpId = "c2", nextAttemptAt = 10_000))

        val batch = dao.nextBatch(nowMillis = 5_000)

        assertEquals(
            "Операция с будущим временем попытки не должна выбираться",
            listOf("ready"),
            batch.map { it.id },
        )
    }

    @Test
    fun `next batch preserves user action order`() = runTest {
        dao.enqueue(Fixtures.outboxOperation(id = "third", clientOpId = "c3", createdAt = 3_000))
        dao.enqueue(Fixtures.outboxOperation(id = "first", clientOpId = "c1", createdAt = 1_000))
        dao.enqueue(Fixtures.outboxOperation(id = "second", clientOpId = "c2", createdAt = 2_000))

        val batch = dao.nextBatch(nowMillis = 10_000)

        assertEquals(listOf("first", "second", "third"), batch.map { it.id })
    }

    @Test
    fun `dependent operation is not sent before its dependency`() = runTest {
        // Комментарий к задаче не должен уехать раньше создания самой задачи.
        dao.enqueue(
            Fixtures.outboxOperation(
                id = "create-task", clientOpId = "task-op", opType = "CREATE_TASK", createdAt = 1_000,
            ),
        )
        dao.enqueue(
            Fixtures.outboxOperation(
                id = "add-comment",
                clientOpId = "comment-op",
                opType = "ADD_TASK_COMMENT",
                dependsOnOpId = "task-op",
                createdAt = 2_000,
            ),
        )

        val beforeDependencyDone = dao.nextBatch(nowMillis = 10_000)
        assertEquals(
            "Пока зависимость в очереди, зависимая операция выбираться не должна",
            listOf("create-task"),
            beforeDependencyDone.map { it.id },
        )

        // Зависимость выполнена и удалена из очереди.
        dao.delete("create-task")

        val afterDependencyDone = dao.nextBatch(nowMillis = 10_000)
        assertEquals(listOf("add-comment"), afterDependencyDone.map { it.id })
    }

    @Test
    fun `in flight operations are recovered to pending after restart`() = runTest {
        // Имитируем состояние после убийства процесса во время отправки.
        dao.enqueue(Fixtures.outboxOperation(id = "stuck", clientOpId = "c1"))
        dao.updateStatus("stuck", OutboxStatus.IN_FLIGHT, nowMillis = 2_000)
        assertEquals("Застрявшая операция не выбирается", 0, dao.nextBatch(10_000).size)

        val recovered = dao.recoverInFlight(nowMillis = 5_000)

        assertEquals(1, recovered)
        val batch = dao.nextBatch(nowMillis = 10_000)
        assertEquals(
            "После восстановления операция должна снова стать отправляемой",
            listOf("stuck"),
            batch.map { it.id },
        )
    }

    @Test
    fun `scheduling a retry increments attempt count and postpones`() = runTest {
        dao.enqueue(Fixtures.outboxOperation(id = "op", clientOpId = "c1"))

        dao.scheduleRetry(id = "op", nextAttemptAt = 20_000, error = "timeout", nowMillis = 5_000)

        val stored = dao.findByClientOpId("c1")!!
        assertEquals(1, stored.attemptCount)
        assertEquals(20_000L, stored.nextAttemptAt)
        assertEquals("timeout", stored.lastError)
        assertEquals(OutboxStatus.PENDING, stored.status)
        assertEquals("До назначенного времени не выбирается", 0, dao.nextBatch(10_000).size)
        assertEquals("После — выбирается", 1, dao.nextBatch(25_000).size)
    }

    @Test
    fun `failed operations are excluded from automatic retries`() = runTest {
        dao.enqueue(Fixtures.outboxOperation(id = "op", clientOpId = "c1"))

        dao.markFailed("op", error = "403 Forbidden", nowMillis = 5_000)

        assertEquals("FAILED не переотправляется автоматически", 0, dao.nextBatch(10_000).size)
        assertEquals(1, dao.observeFailedCount().first())
        assertEquals(listOf("op"), dao.observeFailed().first().map { it.id })
    }

    @Test
    fun `pending count covers all states awaiting delivery`() = runTest {
        dao.enqueue(Fixtures.outboxOperation(id = "p", clientOpId = "c1"))
        dao.enqueue(Fixtures.outboxOperation(id = "i", clientOpId = "c2"))
        dao.updateStatus("i", OutboxStatus.IN_FLIGHT, 1_000)
        dao.enqueue(Fixtures.outboxOperation(id = "b", clientOpId = "c3"))
        dao.updateStatus("b", OutboxStatus.BLOCKED, 1_000)
        dao.enqueue(Fixtures.outboxOperation(id = "f", clientOpId = "c4"))
        dao.markFailed("f", "error", 1_000)

        assertEquals(
            "PENDING + IN_FLIGHT + BLOCKED, без FAILED",
            3,
            dao.observePendingCount().first(),
        )
    }

    @Test
    fun `completing an operation removes it and advances the cursor atomically`() = runTest {
        dao.upsertSyncState(SyncStateEntity(scope = "GLOBAL", lastEventId = 10, lastSyncedAt = 1_000))
        dao.enqueue(Fixtures.outboxOperation(id = "op", clientOpId = "c1"))

        dao.completeOperation(operationId = "op", scope = "GLOBAL", eventId = 25, nowMillis = 5_000)

        assertNull("Операция должна исчезнуть из очереди", dao.findByClientOpId("c1"))
        val state = dao.syncState("GLOBAL")!!
        assertEquals(25L, state.lastEventId)
        assertEquals(5_000L, state.lastSyncedAt)
    }

    @Test
    fun `cursor never moves backwards`() = runTest {
        dao.upsertSyncState(SyncStateEntity(scope = "GLOBAL", lastEventId = 100, lastSyncedAt = 1_000))

        // Запоздавшее событие с меньшим id не должно откатить курсор:
        // иначе уже применённые события будут обработаны повторно.
        dao.advanceCursor("GLOBAL", eventId = 50, nowMillis = 2_000)

        assertEquals(100L, dao.syncState("GLOBAL")!!.lastEventId)

        dao.advanceCursor("GLOBAL", eventId = 150, nowMillis = 3_000)
        assertEquals(150L, dao.syncState("GLOBAL")!!.lastEventId)
    }

    @Test
    fun `blocked operations unblock when dependency leaves the queue`() = runTest {
        dao.enqueue(Fixtures.outboxOperation(id = "dep", clientOpId = "dep-op", createdAt = 1_000))
        dao.enqueue(
            Fixtures.outboxOperation(
                id = "child", clientOpId = "child-op", dependsOnOpId = "dep-op", createdAt = 2_000,
            ),
        )
        dao.updateStatus("child", OutboxStatus.BLOCKED, 1_000)

        assertEquals(0, dao.unblockResolved(nowMillis = 3_000))

        dao.delete("dep")
        assertEquals(1, dao.unblockResolved(nowMillis = 4_000))
        assertEquals(OutboxStatus.PENDING, dao.findByClientOpId("child-op")!!.status)
    }

    @Test
    fun `deleting operations for an entity clears all of them`() = runTest {
        dao.enqueue(Fixtures.outboxOperation(id = "o1", clientOpId = "c1", entityType = "TASK", entityId = "t1"))
        dao.enqueue(Fixtures.outboxOperation(id = "o2", clientOpId = "c2", entityType = "TASK", entityId = "t1"))
        dao.enqueue(Fixtures.outboxOperation(id = "o3", clientOpId = "c3", entityType = "TASK", entityId = "t2"))

        dao.deleteForEntity("TASK", "t1")

        assertEquals(listOf("o3"), dao.all().map { it.id })
        assertNotNull(dao.findByClientOpId("c3"))
    }

    @Test
    fun `reset flag is stored per scope`() = runTest {
        dao.upsertSyncState(SyncStateEntity(scope = "CONVERSATION:conv-1", lastEventId = 5, lastSyncedAt = null))
        dao.upsertSyncState(SyncStateEntity(scope = "TASKS", lastEventId = 7, lastSyncedAt = null))

        dao.setNeedsReset("CONVERSATION:conv-1", true)

        assertTrue(dao.syncState("CONVERSATION:conv-1")!!.needsReset)
        assertTrue(
            "Сброс одной области не должен затрагивать другие",
            !dao.syncState("TASKS")!!.needsReset,
        )
    }
}
