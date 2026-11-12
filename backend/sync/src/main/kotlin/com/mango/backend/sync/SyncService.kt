@file:UseSerializers(UuidSerializer::class, InstantSerializer::class)

package com.mango.backend.sync

import com.mango.backend.common.ApiJson
import com.mango.backend.common.AppException
import com.mango.backend.common.Database
import com.mango.backend.common.InstantSerializer
import com.mango.backend.common.UuidSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID

@Serializable
data class SyncEventDto(
    val id: Long,
    val scope: String,
    val scopeId: UUID? = null,
    val type: String,
    val payload: JsonElement,
    val createdAt: Instant,
)

@Serializable
data class SyncResponse(
    val events: List<SyncEventDto>,
    /** Курсор, до которого клиент может продвинуться, применив все события. */
    val lastEventId: Long,
    /** Есть ли ещё события за пределами страницы. */
    val hasMore: Boolean,
)

@Serializable
data class ResetRequiredResponse(
    val code: String = "CURSOR_TOO_OLD",
    val message: String,
    /** Области, которые клиенту нужно перезагрузить целиком. */
    val resetScopes: List<String>,
)

/**
 * Догонка пропущенных событий.
 *
 * Вызывается клиентом после каждого подключения WebSocket и при
 * обнаружении разрыва в нумерации входящих событий. Это и есть
 * механизм, который делает потерю соединения безобидной
 * (см. ADR-0006).
 */
class SyncService(
    private val database: Database,
    private val eventLog: EventLog,
) {

    suspend fun catchUp(userId: UUID, since: Long, limit: Int): SyncResponse =
        database.readOnly { connection ->
            if (since < 0) throw AppException.BadRequest("Курсор не может быть отрицательным")

            val earliest = eventLog.earliestEventId(connection, userId)

            // Курсор устарел: события между ним и самым ранним сохранённым
            // уже удалены, догнать их невозможно. Молча отдать «хвост»
            // означало бы оставить у клиента дыру в истории, о которой
            // он не узнает.
            if (since > 0 && earliest > 0 && since + 1 < earliest) {
                throw CursorTooOldException(
                    "Курсор $since устарел: самое раннее сохранённое событие $earliest",
                )
            }

            val page = eventLog.since(connection, userId, since, limit + 1)
            val hasMore = page.size > limit
            val events = if (hasMore) page.dropLast(1) else page

            SyncResponse(
                events = events.map { it.toDto() },
                // Курсор продвигается до последнего ВЫДАННОГО события,
                // а не до максимального в журнале: иначе клиент пропустил
                // бы остаток страницы.
                lastEventId = events.lastOrNull()?.id ?: since,
                hasMore = hasMore,
            )
        }

    /** Текущее положение журнала — отправляется клиенту при подключении. */
    suspend fun lastEventId(userId: UUID): Long =
        database.readOnly { eventLog.lastEventId(it, userId) }

    companion object {
        const val DEFAULT_LIMIT = 500
        const val MAX_LIMIT = 1000
    }
}

/** Курсор клиента старше сохранённой истории: нужна перезагрузка областей. */
class CursorTooOldException(message: String) : RuntimeException(message)

/** Преобразование для выдачи клиенту — используется и в HTTP, и в WebSocket. */
fun StoredEvent.toDto() = SyncEventDto(
    id = id,
    scope = scope.name,
    scopeId = scopeId,
    type = type,
    payload = parsePayload(payloadJson),
    createdAt = createdAt,
)

/**
 * Разбор сохранённой полезной нагрузки.
 *
 * Повреждённая запись не должна ронять выдачу всей страницы событий:
 * один испорченный JSON в базе заблокировал бы синхронизацию клиента
 * навсегда, потому что он никогда не смог бы продвинуть курсор дальше.
 */
private fun parsePayload(raw: String): JsonElement =
    runCatching { ApiJson.parseToJsonElement(raw) }.getOrElse { JsonObject(emptyMap()) }
