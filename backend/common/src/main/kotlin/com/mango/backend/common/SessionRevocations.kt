package com.mango.backend.common

import io.lettuce.core.RedisClient
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID

/**
 * Список отозванных сессий.
 *
 * ## Зачем он нужен
 *
 * Access token — подписанный JWT: он проверяется без обращения к базе,
 * и это его главное достоинство. Обратная сторона — отзыв сессии сам по
 * себе НЕ прекращает действие уже выданного токена. При обнаружении кражи
 * refresh token все сессии пользователя закрываются, но украденный access
 * продолжал бы работать до истечения срока — то есть защита срабатывала бы
 * с задержкой в четверть часа ровно в тот момент, когда дорога каждая минута.
 *
 * Список закрывает это окно: идентификатор отозванной сессии кладётся
 * сюда на время жизни access token, и проверка токена его отвергает.
 *
 * ## Почему хранение временное
 *
 * Запись живёт ровно столько, сколько мог бы прожить выданный под эту
 * сессию токен. Дальше она бесполезна: токен истечёт сам.
 *
 * ## Поведение при недоступности хранилища
 *
 * Проверка пропускает запрос. Выбор сознательный: недоступность Redis
 * не должна превращаться в отказ в обслуживании для всех пользователей.
 * Цена — возврат к исходному окну в несколько минут на время сбоя.
 */
interface SessionRevocations {

    suspend fun revoke(sessionId: UUID, ttl: Duration)

    suspend fun isRevoked(sessionId: UUID): Boolean

    companion object {
        /**
         * Ничего не отзывает.
         *
         * Оставлено для тестов, которым этот механизм не нужен. В рабочем
         * приложении не используется: отсутствие проверки означало бы, что
         * обнаружение кражи токена срабатывает с задержкой.
         */
        val Disabled = object : SessionRevocations {
            override suspend fun revoke(sessionId: UUID, ttl: Duration) = Unit
            override suspend fun isRevoked(sessionId: UUID) = false
        }
    }
}

/**
 * Список в памяти процесса.
 *
 * Корректен для установки из одного экземпляра: отозванная сессия
 * перестаёт действовать немедленно. При нескольких экземплярах каждый
 * знал бы только о своих отзывах, поэтому там нужен общий список.
 */
class InMemorySessionRevocations(private val time: TimeSource = SystemTime) : SessionRevocations {

    private val revoked = java.util.concurrent.ConcurrentHashMap<UUID, Long>()

    override suspend fun revoke(sessionId: UUID, ttl: Duration) {
        revoked[sessionId] = time.now().toEpochMilli() + ttl.toMillis()
        cleanUp()
    }

    override suspend fun isRevoked(sessionId: UUID): Boolean {
        val expiresAt = revoked[sessionId] ?: return false
        if (expiresAt <= time.now().toEpochMilli()) {
            revoked.remove(sessionId)
            return false
        }
        return true
    }

    /**
     * Уборка просроченных записей.
     *
     * Без неё список растёт по числу выходов за всё время работы
     * процесса, то есть превращается в утечку памяти.
     */
    private fun cleanUp() {
        if (revoked.size < CLEANUP_THRESHOLD) return
        val now = time.now().toEpochMilli()
        revoked.entries.removeIf { it.value <= now }
    }

    private companion object {
        const val CLEANUP_THRESHOLD = 1_000
    }
}

class RedisSessionRevocations(redisUrl: String) : SessionRevocations, AutoCloseable {

    private val log = LoggerFactory.getLogger(RedisSessionRevocations::class.java)

    private val client: RedisClient = RedisClient.create(redisUrl)
    private val connection: StatefulRedisConnection<String, String> = client.connect()

    override suspend fun revoke(sessionId: UUID, ttl: Duration) {
        withContext(Dispatchers.IO) {
            runCatching {
                connection.sync().set(
                    key(sessionId),
                    "1",
                    SetArgs.Builder.px(ttl.toMillis().coerceAtLeast(1)),
                )
            }.onFailure {
                // Запись не удалась: окно отзыва остаётся прежним.
                // Молчать нельзя — это ослабление защиты.
                log.warn("не удалось отозвать сессию {}: {}", Redact.id(sessionId), it.message)
            }
        }
    }

    override suspend fun isRevoked(sessionId: UUID): Boolean = withContext(Dispatchers.IO) {
        runCatching { connection.sync().exists(key(sessionId)) > 0 }
            .getOrElse {
                log.warn("проверка отзыва недоступна: {}", it.message)
                // Пропускаем запрос: отказ всем из-за сбоя хранилища хуже,
                // чем временный возврат к сроку жизни токена.
                false
            }
    }

    override fun close() {
        runCatching { connection.close() }
        runCatching { client.shutdown() }
    }

    private companion object {
        fun key(sessionId: UUID) = "mango:revoked:$sessionId"
    }
}
