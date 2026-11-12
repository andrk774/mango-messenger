package com.mango.backend.common

import io.lettuce.core.RedisClient
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Ограничение частоты с общим счётчиком в Redis.
 *
 * Отличие от [InMemoryRateLimiter] принципиальное: при нескольких
 * экземплярах приложения счётчики в памяти дают предел, умноженный на
 * число экземпляров, то есть защита слабеет ровно тогда, когда система
 * растёт. Общий счётчик такого эффекта не имеет.
 */
class RedisRateLimiter(
    redisUrl: String,
    private val fallback: RateLimiter = InMemoryRateLimiter(),
) : RateLimiter, AutoCloseable {

    private val log = LoggerFactory.getLogger(RedisRateLimiter::class.java)

    private val client: RedisClient = RedisClient.create(redisUrl)
    private val connection: StatefulRedisConnection<String, String> = client.connect()

    override suspend fun acquire(
        key: String,
        limit: Int,
        window: Duration,
    ): RateLimitVerdict = withContext(Dispatchers.IO) {
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val result = connection.sync().eval<List<Long>>(
                INCREMENT_SCRIPT,
                ScriptOutputType.MULTI,
                arrayOf(redisKey(key)),
                window.toMillis().toString(),
            )

            val used = result[0].toInt()
            val ttlMillis = result[1]

            RateLimitVerdict(
                allowed = used <= limit,
                remaining = (limit - used).coerceAtLeast(0),
                retryAfterSeconds = if (used <= limit) {
                    0
                } else {
                    (ttlMillis / 1000).coerceAtLeast(1)
                },
            )
        }.getOrElse { error ->
            // Недоступность Redis не должна открывать дверь перебору,
            // но и не должна ронять вход для добросовестных пользователей.
            // Поэтому — переход на локальный счётчик: защита слабее,
            // но она есть.
            log.warn("Redis недоступен, счётчики временно локальные: {}", error.message)
            fallback.acquire(key, limit, window)
        }
    }

    override fun close() {
        runCatching { connection.close() }
        runCatching { client.shutdown() }
    }

    private companion object {
        fun redisKey(key: String) = "mango:ratelimit:$key"

        /**
         * Атомарный инкремент с установкой срока жизни.
         *
         * Выполняется одним сценарием, а не парой INCR + EXPIRE:
         * между двумя командами процесс может быть убит, и тогда ключ
         * остался бы без срока жизни навсегда — пользователь оказался бы
         * заблокирован насовсем.
         */
        const val INCREMENT_SCRIPT = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return {current, redis.call('PTTL', KEYS[1])}
        """
    }
}
