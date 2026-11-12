package com.mango.core.common

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Политика повторных попыток для очереди исходящих операций.
 *
 * Exponential backoff с «полным джиттером»: задержка выбирается случайно
 * в диапазоне [base, exp], где exp растёт экспоненциально. Без джиттера
 * все устройства, потерявшие сеть одновременно, вернутся к серверу одной
 * волной — это создаёт пик нагрузки ровно в момент, когда сервер
 * восстанавливается.
 *
 * Классификация ошибок делегируется [AppError.isRetryable], а ограничение
 * числа попыток различается для сетевых и серверных ошибок: нет смысла
 * прекращать попытки только потому, что пользователь долго был в самолёте.
 */
data class RetryPolicy(
    val baseDelayMillis: Long = 1_000,
    val maxDelayMillis: Long = 5 * 60 * 1000,
    val maxAttemptsForServerErrors: Int = 8,
    val multiplier: Double = 2.0,
) {

    init {
        require(baseDelayMillis > 0) { "baseDelayMillis должен быть положительным" }
        require(maxDelayMillis >= baseDelayMillis) { "maxDelayMillis < baseDelayMillis" }
        require(multiplier > 1.0) { "multiplier должен быть больше 1" }
    }

    /**
     * Задержка перед попыткой номер [attempt] (нумерация с нуля).
     *
     * @param random передаётся явно, чтобы тесты были детерминированными.
     */
    fun delayMillis(attempt: Int, random: Random = Random.Default): Long {
        require(attempt >= 0) { "attempt не может быть отрицательным" }

        val exponential = baseDelayMillis * multiplier.pow(attempt)
        val capped = min(exponential, maxDelayMillis.toDouble()).toLong()

        // Полный джиттер: случайная величина в [base, capped].
        if (capped <= baseDelayMillis) return baseDelayMillis
        return random.nextLong(baseDelayMillis, capped + 1)
    }

    /**
     * Нужно ли повторять операцию.
     *
     * Сетевые ошибки и таймауты повторяются неограниченно: устройство могло
     * быть офлайн сутками, и это не повод терять сообщение пользователя.
     * Серверные ошибки ограничены [maxAttemptsForServerErrors], иначе
     * битая операция будет вечно биться о 500-й код.
     */
    fun shouldRetry(error: AppError, attempt: Int): Boolean = when {
        !error.isRetryable -> false
        error is AppError.Network || error is AppError.Timeout -> true
        else -> attempt < maxAttemptsForServerErrors
    }

    /** Если сервер прислал Retry-After, он имеет приоритет над нашей формулой. */
    fun effectiveDelayMillis(
        error: AppError,
        attempt: Int,
        random: Random = Random.Default,
    ): Long {
        val serverHint = (error as? AppError.RateLimited)?.retryAfterSeconds
        return serverHint?.times(1_000) ?: delayMillis(attempt, random)
    }

    companion object {
        val Default = RetryPolicy()

        /** Для операций, где пользователь ждёт результат на экране. */
        val Interactive = RetryPolicy(
            baseDelayMillis = 500,
            maxDelayMillis = 8_000,
            maxAttemptsForServerErrors = 3,
        )
    }
}
