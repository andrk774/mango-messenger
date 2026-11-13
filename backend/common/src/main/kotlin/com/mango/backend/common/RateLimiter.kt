package com.mango.backend.common

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ограничение частоты запросов.
 *
 * Защищает вход от перебора паролей, а регистрацию и восстановление —
 * от массовой рассылки писем на чужие адреса.
 *
 * Счётчик считается по скользящему окну фиксированной длины: это грубее
 * настоящего скользящего окна, но на порядок дешевле и достаточно против
 * автоматизированного перебора.
 */
interface RateLimiter {
    /**
     * Регистрирует попытку и сообщает, разрешена ли она.
     *
     * @param key что ограничиваем, например `login:a***@example.com`
     * @param limit допустимое число попыток за окно
     * @param window длина окна
     */
    suspend fun acquire(key: String, limit: Int, window: Duration): RateLimitVerdict
}

data class RateLimitVerdict(
    val allowed: Boolean,
    val remaining: Int,
    val retryAfterSeconds: Long,
)

/** Ограничений нет — для тестов и для окружений без Redis. */
object NoopRateLimiter : RateLimiter {
    override suspend fun acquire(key: String, limit: Int, window: Duration) =
        RateLimitVerdict(allowed = true, remaining = limit, retryAfterSeconds = 0)
}

/**
 * Счётчики в памяти процесса.
 *
 * ВАЖНОЕ ОГРАНИЧЕНИЕ: при нескольких экземплярах приложения каждый ведёт
 * свой счёт, поэтому фактический предел умножается на число экземпляров.
 * Пригодно для одного процесса (локальная разработка, небольшая установка);
 * для нескольких реплик счётчик нужно вынести в Redis — он уже есть
 * в стеке и будет задействован на Этапе 4 вместе с рассылкой событий.
 */
class InMemoryRateLimiter(private val time: TimeSource = SystemTime) : RateLimiter {

    private class Window(val startedAtMillis: Long) {
        val count = AtomicInteger(0)
    }

    private val windows = ConcurrentHashMap<String, Window>()

    override suspend fun acquire(key: String, limit: Int, window: Duration): RateLimitVerdict {
        val now = time.now().toEpochMilli()
        val windowMillis = window.toMillis()

        val current = windows.compute(key) { _, existing ->
            if (existing == null || now - existing.startedAtMillis >= windowMillis) {
                Window(now)
            } else {
                existing
            }
        }!!

        val used = current.count.incrementAndGet()
        val elapsed = now - current.startedAtMillis
        val retryAfter = ((windowMillis - elapsed) / 1000).coerceAtLeast(1)

        // Периодическая уборка: без неё карта растёт по числу уникальных
        // адресов, и это превращается в утечку памяти под перебором.
        if (windows.size > CLEANUP_THRESHOLD) {
            windows.entries.removeIf { now - it.value.startedAtMillis >= windowMillis }
        }

        return RateLimitVerdict(
            allowed = used <= limit,
            remaining = (limit - used).coerceAtLeast(0),
            retryAfterSeconds = if (used <= limit) 0 else retryAfter,
        )
    }

    private companion object {
        const val CLEANUP_THRESHOLD = 10_000
    }
}

/**
 * Настройки ограничений.
 *
 * Значения подобраны так, чтобы не мешать человеку и мешать программе:
 * пять попыток входа за пятнадцать минут человек исчерпает только
 * при настоящей проблеме с паролем.
 */
object RateLimits {
    val LOGIN_LIMIT = 5
    val LOGIN_WINDOW: Duration = Duration.ofMinutes(15)

    val REGISTER_LIMIT = 3

    /** Для MANGO_ENV=LOCAL: домашняя сеть видна серверу одним адресом. */
    val REGISTER_LIMIT_LOCAL = 30
    val REGISTER_WINDOW: Duration = Duration.ofHours(1)

    val PASSWORD_RESET_LIMIT = 3
    val PASSWORD_RESET_WINDOW: Duration = Duration.ofHours(1)

    /**
     * Проверка кода из письма (подтверждение адреса, смена пароля).
     *
     * Код короткий, чтобы его можно было перепечатать, — значит, число
     * попыток обязано быть ограничено. Десять попыток за пятнадцать
     * минут хватит на опечатки, но не на перебор.
     */
    val CODE_LIMIT = 10
    val CODE_WINDOW: Duration = Duration.ofMinutes(15)
}
