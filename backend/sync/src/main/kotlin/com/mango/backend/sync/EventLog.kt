package com.mango.backend.sync

import com.mango.backend.common.Json
import com.mango.backend.common.TimeSource
import com.mango.backend.common.bind
import com.mango.backend.common.instant
import com.mango.backend.common.query
import com.mango.backend.common.uuid
import com.mango.backend.common.uuidOrNull
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

enum class EventScope { CONVERSATION, TASK, PROFILE }

/** Типы событий. Строки, а не enum: значение уходит клиенту и живёт в БД. */
object EventTypes {
    const val CONVERSATION_CREATED = "CONVERSATION_CREATED"
    const val CONVERSATION_UPDATED = "CONVERSATION_UPDATED"
    const val MEMBER_ADDED = "CONVERSATION_MEMBER_ADDED"
    const val MEMBER_REMOVED = "CONVERSATION_MEMBER_REMOVED"
    const val MESSAGE_CREATED = "MESSAGE_CREATED"
    const val MESSAGE_EDITED = "MESSAGE_EDITED"
    const val MESSAGE_DELETED = "MESSAGE_DELETED"
    const val READ_MARKER_MOVED = "READ_MARKER_MOVED"
}

/**
 * Событие, которое нужно записать в журнал.
 *
 * [recipients] — те, кому событие должно быть видно. Запись выполняется
 * по строке на получателя (fan-out на запись).
 */
data class OutgoingEvent(
    val scope: EventScope,
    val scopeId: UUID?,
    val type: String,
    val payloadJson: String,
    val recipients: Collection<UUID>,
)

/** Событие, записанное в журнал и получившее идентификатор. */
data class StoredEvent(
    val id: Long,
    val userId: UUID,
    val scope: EventScope,
    val scopeId: UUID?,
    val type: String,
    val payloadJson: String,
    val createdAt: Instant,
)

/**
 * Персональный журнал событий.
 *
 * ## Почему строка на получателя
 *
 * Выбран fan-out на запись: одно событие превращается в N строк, по одной
 * на участника. Плата — усиление записи; выигрыш — предельно простая
 * догонка: у клиента ровно один курсор `lastEventId`, и запрос
 * «что я пропустил» сводится к `WHERE user_id = ? AND id > ?`.
 *
 * Альтернатива (общий журнал на чат плюс курсор на каждый чат) дешевле
 * по записи, но превращает догонку в обход всех чатов и переносит
 * сложность на клиента, где её труднее всего отлаживать.
 *
 * Для личных переписок и небольших групп усиление записи незначительно.
 * Для крупных групп решение придётся пересмотреть — это зафиксировано
 * как ограничение, а не как незамеченный недостаток.
 *
 * ## Почему запись идёт в транзакции вызывающего
 *
 * Событие и само изменение данных обязаны фиксироваться вместе. Иначе
 * возможны два испорченных состояния: сообщение есть, а события нет
 * (клиент никогда о нём не узнает), либо событие есть, а сообщения нет
 * (клиент получит ссылку в пустоту).
 */
class EventLog(private val time: TimeSource) {

    /**
     * Записывает события и возвращает их с присвоенными идентификаторами.
     *
     * Идентификаторы нужны вызывающему, чтобы разослать события через
     * WebSocket после фиксации транзакции.
     */
    fun append(connection: Connection, events: List<OutgoingEvent>): List<StoredEvent> {
        val rows = events.flatMap { event ->
            event.recipients.distinct().map { recipient -> event to recipient }
        }
        if (rows.isEmpty()) return emptyList()

        val now = time.now()

        // Одна многострочная вставка вместо N отдельных: при группе из
        // полусотни участников это разница между одним обращением к базе
        // и полусотней.
        val placeholders = rows.joinToString(", ") { "(?, ?, ?, ?, ?, ?)" }
        val sql = """
            INSERT INTO sync_events (user_id, scope, scope_id, event_type, payload, created_at)
            VALUES $placeholders
            RETURNING id, user_id, scope, scope_id, event_type, payload, created_at
        """.trimIndent()

        return connection.prepareStatement(sql).use { statement ->
            var index = 1
            rows.forEach { (event, recipient) ->
                statement.bind(index++, recipient)
                statement.bind(index++, event.scope.name)
                statement.bind(index++, event.scopeId)
                statement.bind(index++, event.type)
                statement.bind(index++, Json(event.payloadJson))
                statement.bind(index++, now)
            }
            statement.executeQuery().use { rs ->
                buildList { while (rs.next()) add(mapEvent(rs)) }
            }
        }
    }

    /**
     * События после курсора.
     *
     * Порядок по `id` строго возрастающий, поэтому клиент может применять
     * их последовательно и продвигать курсор после каждого применения.
     */
    fun since(
        connection: Connection,
        userId: UUID,
        sinceId: Long,
        limit: Int,
    ): List<StoredEvent> = connection.query(
        """
        SELECT id, user_id, scope, scope_id, event_type, payload, created_at
        FROM sync_events
        WHERE user_id = ? AND id > ?
        ORDER BY id
        LIMIT ?
        """.trimIndent(),
        userId, sinceId, limit,
        map = ::mapEvent,
    )

    /** Наибольший идентификатор события пользователя. 0, если событий нет. */
    fun lastEventId(connection: Connection, userId: UUID): Long =
        connection.query(
            "SELECT COALESCE(MAX(id), 0) AS last_id FROM sync_events WHERE user_id = ?",
            userId,
        ) { it.getLong("last_id") }.firstOrNull() ?: 0L

    /**
     * Самый ранний сохранённый идентификатор.
     *
     * Нужен, чтобы понять, не устарел ли курсор клиента: если он меньше
     * этого значения, часть событий уже удалена, и догонка невозможна —
     * клиенту отвечают требованием перезагрузить область.
     */
    fun earliestEventId(connection: Connection, userId: UUID): Long =
        connection.query(
            "SELECT COALESCE(MIN(id), 0) AS first_id FROM sync_events WHERE user_id = ?",
            userId,
        ) { it.getLong("first_id") }.firstOrNull() ?: 0L

    private fun mapEvent(rs: ResultSet) = StoredEvent(
        id = rs.getLong("id"),
        userId = rs.uuid("user_id"),
        scope = EventScope.valueOf(rs.getString("scope")),
        scopeId = rs.uuidOrNull("scope_id"),
        type = rs.getString("event_type"),
        payloadJson = rs.getString("payload") ?: "{}",
        createdAt = rs.instant("created_at"),
    )
}

/**
 * Рассылка событий подписчикам в реальном времени.
 *
 * Отделена от журнала намеренно: журнал обязан быть записан транзакционно,
 * а рассылка выполняется ПОСЛЕ фиксации и может не состояться — сеть,
 * перезапуск, недоступность Redis. Потеря рассылки не теряет событие:
 * клиент получит его при следующей сверке по курсору.
 *
 * Реализация по умолчанию ничего не делает: это корректное поведение
 * для окружения без realtime, а не заглушка «на потом».
 */
interface EventBus {
    suspend fun publish(events: List<StoredEvent>)

    companion object {
        val Noop = object : EventBus {
            override suspend fun publish(events: List<StoredEvent>) = Unit
        }
    }
}
