package com.mango.backend.common

import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Серверная идемпотентность операций записи.
 *
 * Клиент повторяет операцию при неизвестном исходе — таймаут, обрыв,
 * убийство процесса. Без сохранённого результата повтор создал бы второе
 * сообщение или вторую задачу, то есть ровно ту потерю данных наоборот,
 * от которой защищает очередь на клиенте.
 *
 * Ключ — `client_op_id`, сгенерированный устройством ДО первого запроса
 * (UUIDv7). Уникальность пары `(user_id, client_op_id)` обеспечена индексом,
 * поэтому гонка двух одновременных повторов разрешается базой, а не кодом.
 */
class IdempotencyStore(private val time: TimeSource) {

    /**
     * Сохранённый результат ранее выполненной операции.
     *
     * [requestHash] позволяет отличить честный повтор от попытки переиспользовать
     * ключ с ДРУГИМ телом запроса. Второе — ошибка клиента, и молча вернуть
     * чужой результат было бы хуже, чем сообщить о конфликте.
     */
    data class StoredResult(
        val statusCode: Int,
        val resultJson: String?,
        val requestHash: String?,
    )

    fun find(connection: Connection, userId: UUID, clientOpId: String): StoredResult? =
        connection.queryOne(
            """
            SELECT status_code, result, request_hash
            FROM outbox_operations
            WHERE user_id = ? AND client_op_id = ?
            """.trimIndent(),
            userId, clientOpId,
        ) { rs ->
            StoredResult(
                statusCode = rs.getInt("status_code"),
                resultJson = rs.getString("result"),
                requestHash = rs.getString("request_hash"),
            )
        }

    /**
     * Фиксирует результат операции.
     *
     * Вызывается В ТОЙ ЖЕ транзакции, что и сама операция: иначе возможно
     * состояние «сообщение записано, отметка об идемпотентности нет»,
     * и повтор создал бы дубликат.
     */
    fun record(
        connection: Connection,
        id: UUID,
        userId: UUID,
        clientOpId: String,
        opType: String,
        statusCode: Int,
        resultJson: String?,
        requestHash: String?,
    ) {
        connection.update(
            """
            INSERT INTO outbox_operations
                (id, user_id, client_op_id, op_type, request_hash, status, status_code, result, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id, client_op_id) DO NOTHING
            """.trimIndent(),
            id,
            userId,
            clientOpId,
            opType,
            requestHash,
            if (statusCode < 400) "APPLIED" else "REJECTED",
            statusCode,
            resultJson?.let(::Json),
            time.now(),
        )
    }

    /**
     * Удаление записей старше срока хранения.
     *
     * Журнал идемпотентности не должен расти бесконечно. Семь суток
     * заметно больше любого разумного времени жизни неотправленной
     * операции на устройстве.
     */
    fun purgeOlderThan(connection: Connection, before: Instant): Int =
        connection.update("DELETE FROM outbox_operations WHERE created_at < ?", before)

    companion object {
        /**
         * Отпечаток тела запроса.
         *
         * Хранится хеш, а не само тело: тело содержит текст сообщения,
         * а журнал идемпотентности живёт неделю и не должен становиться
         * вторым местом хранения переписки.
         */
        fun hashRequest(body: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(body.toByteArray(Charsets.UTF_8))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }
    }
}

/**
 * Исход проверки ключа идемпотентности.
 */
sealed interface IdempotencyCheck {
    /** Операция ещё не выполнялась — можно применять. */
    data object Fresh : IdempotencyCheck

    /** Операция уже применялась: возвращается сохранённый результат. */
    data class Replay(val statusCode: Int, val resultJson: String?) : IdempotencyCheck
}

/**
 * Проверяет ключ и решает, выполнять ли операцию.
 *
 * Повтор с тем же ключом и тем же телом возвращает сохранённый результат.
 * Повтор с тем же ключом и ДРУГИМ телом — ошибка клиента: ключ обязан
 * однозначно определять операцию.
 */
fun IdempotencyStore.check(
    connection: Connection,
    userId: UUID,
    clientOpId: String,
    requestHash: String,
): IdempotencyCheck {
    val stored = find(connection, userId, clientOpId) ?: return IdempotencyCheck.Fresh

    if (stored.requestHash != null && stored.requestHash != requestHash) {
        throw AppException.Conflict(
            "Ключ идемпотентности уже использован с другим содержимым запроса",
        )
    }

    return IdempotencyCheck.Replay(stored.statusCode, stored.resultJson)
}
