package com.mango.backend.common

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.postgresql.util.PGobject
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Доступ к PostgreSQL.
 *
 * Сознательно без ORM: схема опирается на возможности PostgreSQL, которые
 * ORM скрывает или ломает — частичные уникальные индексы, JSONB, citext,
 * `INSERT ... ON CONFLICT DO NOTHING RETURNING`, `UPDATE ... RETURNING`
 * для выдачи порядкового номера сообщения. Именно на этих местах держится
 * корректность идемпотентности и порядка, поэтому SQL здесь виден целиком.
 */
class Database(
    jdbcUrl: String,
    username: String,
    password: String,
    poolSize: Int = 10,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(Database::class.java)

    private val dataSource: HikariDataSource = HikariDataSource(
        HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = username
            this.password = password
            maximumPoolSize = poolSize
            minimumIdle = minOf(2, poolSize)
            // Запрос, который не получил соединение за 10 секунд, уже бесполезен:
            // клиент к этому моменту отвалится по своему таймауту.
            connectionTimeout = 10_000
            idleTimeout = 600_000
            maxLifetime = 1_700_000
            // Транзакциями управляем явно: автокоммит скрыл бы частично
            // применённые операции вроде «выдать seq и вставить сообщение».
            isAutoCommit = false
            poolName = "mango-pool"
            // Прогрев и ранняя диагностика: без этого ошибка подключения
            // всплывёт на первом запросе пользователя, а не при старте.
            initializationFailTimeout = 30_000
        },
    )

    /**
     * Диспетчер под блокирующий JDBC.
     *
     * Параллелизм ограничен размером пула: запускать больше корутин, чем
     * есть соединений, бессмысленно — лишние просто займут потоки
     * ожиданием соединения.
     */
    private val dispatcher: CoroutineDispatcher =
        Dispatchers.IO.limitedParallelism(poolSize)

    /**
     * Выполняет блок в транзакции: commit при успехе, rollback при любом
     * исключении.
     */
    suspend fun <T> transaction(block: (Connection) -> T): T = withContext(dispatcher) {
        dataSource.connection.use { connection ->
            try {
                val result = block(connection)
                connection.commit()
                result
            } catch (e: Throwable) {
                runCatching { connection.rollback() }
                    .onFailure { log.warn("rollback не удался", it) }
                throw e
            }
        }
    }

    /**
     * Чтение без изменения данных: транзакция всё равно нужна для снимка.
     *
     * Режим read-only — защита от случайной записи на пути чтения, а не
     * оптимизация. Снимать флаг можно ТОЛЬКО после завершения транзакции:
     * PostgreSQL отвергает его изменение в середине, и попытка сделать это
     * в `finally` превращала любое исключение из блока (например, отказ
     * в доступе) в отказ 500. Поэтому порядок строгий: завершить
     * транзакцию, затем снять флаг.
     */
    suspend fun <T> readOnly(block: (Connection) -> T): T = withContext(dispatcher) {
        dataSource.connection.use { connection ->
            connection.isReadOnly = true
            try {
                val result = block(connection)
                connection.commit()
                result
            } catch (e: Throwable) {
                runCatching { connection.rollback() }
                    .onFailure { log.warn("rollback при чтении не удался", it) }
                throw e
            } finally {
                runCatching { connection.isReadOnly = false }
            }
        }
    }

    /** Проверка живости для /health/ready. */
    suspend fun isHealthy(): Boolean = runCatching {
        readOnly { connection ->
            connection.prepareStatement("SELECT 1").use { it.executeQuery().next() }
        }
    }.getOrElse {
        log.warn("проверка БД не прошла: {}", it.message)
        false
    }

    override fun close() = dataSource.close()
}

// ──────────────────────── помощники JDBC ────────────────────────
//
// Назначение: убрать повторяющийся шаблон и, главное, единообразно
// обрабатывать NULL. `ResultSet.getLong` возвращает 0 для NULL, и эта
// особенность JDBC — классический источник молчаливо неверных данных.

fun Connection.prepare(sql: String, vararg params: Any?): PreparedStatement =
    prepareStatement(sql).apply { bindAll(params) }

fun PreparedStatement.bindAll(params: Array<out Any?>) {
    params.forEachIndexed { index, value -> bind(index + 1, value) }
}

fun PreparedStatement.bind(index: Int, value: Any?) {
    when (value) {
        null -> setObject(index, null)
        is UUID -> setObject(index, value)
        is Instant -> setTimestamp(index, Timestamp.from(value))
        is String -> setString(index, value)
        is Long -> setLong(index, value)
        is Int -> setInt(index, value)
        is Boolean -> setBoolean(index, value)
        is Double -> setDouble(index, value)
        is ByteArray -> setBytes(index, value)
        is Json -> setObject(index, value.toPgObject())
        is Enum<*> -> setString(index, value.name)
        else -> setObject(index, value)
    }
}

/** Обёртка, помечающая строку как JSONB. Без неё PostgreSQL отвергает text. */
@JvmInline
value class Json(val raw: String) {
    fun toPgObject(): PGobject = PGobject().apply {
        type = "jsonb"
        value = raw
    }

    companion object {
        val EMPTY_OBJECT = Json("{}")
    }
}

/** Выполняет запрос и отображает каждую строку. */
fun <T> Connection.query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    prepare(sql, *params).use { statement ->
        statement.executeQuery().use { rs ->
            buildList { while (rs.next()) add(map(rs)) }
        }
    }

/** Выполняет запрос, ожидая не более одной строки. */
fun <T> Connection.queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? =
    prepare(sql, *params).use { statement ->
        statement.executeQuery().use { rs -> if (rs.next()) map(rs) else null }
    }

/** Выполняет изменение и возвращает число затронутых строк. */
fun Connection.update(sql: String, vararg params: Any?): Int =
    prepare(sql, *params).use { it.executeUpdate() }

fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)
fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)

fun ResultSet.instant(column: String): Instant =
    getTimestamp(column)?.toInstant() ?: error("Колонка $column не должна быть NULL")

fun ResultSet.instantOrNull(column: String): Instant? = getTimestamp(column)?.toInstant()

/** Long, корректно различающий 0 и NULL. */
fun ResultSet.longOrNull(column: String): Long? {
    val value = getLong(column)
    return if (wasNull()) null else value
}

fun ResultSet.intOrNull(column: String): Int? {
    val value = getInt(column)
    return if (wasNull()) null else value
}

fun ResultSet.json(column: String): Json = Json(getString(column) ?: "{}")

/**
 * Код ошибки PostgreSQL для нарушения уникальности.
 *
 * Используется, чтобы отличить «такая запись уже есть» (ожидаемая ситуация
 * при повторе операции) от настоящего сбоя.
 */
const val PG_UNIQUE_VIOLATION = "23505"

const val PG_FOREIGN_KEY_VIOLATION = "23503"

fun SQLException.isUniqueViolation(): Boolean = sqlState == PG_UNIQUE_VIOLATION

fun SQLException.isForeignKeyViolation(): Boolean = sqlState == PG_FOREIGN_KEY_VIOLATION
