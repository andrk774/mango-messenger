package com.mango.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RetryPolicyTest {

    private val policy = RetryPolicy(
        baseDelayMillis = 1_000,
        maxDelayMillis = 60_000,
        maxAttemptsForServerErrors = 5,
    )

    @Test
    fun `first attempt waits at least the base delay`() {
        repeat(100) {
            val delay = policy.delayMillis(attempt = 0)
            assertTrue("delay=$delay", delay >= 1_000)
        }
    }

    @Test
    fun `delay never exceeds the configured maximum`() {
        for (attempt in 0..40) {
            repeat(50) {
                val delay = policy.delayMillis(attempt)
                assertTrue(
                    "attempt=$attempt delay=$delay превышает максимум",
                    delay <= 60_000,
                )
            }
        }
    }

    @Test
    fun `upper bound grows exponentially`() {
        // При полном джиттере растёт именно граница, поэтому проверяем максимум выборки.
        val random = Random(42)
        fun maxOver(attempt: Int) = (1..500).maxOf { policy.delayMillis(attempt, random) }

        val a0 = maxOver(0)
        val a2 = maxOver(2)
        val a4 = maxOver(4)

        assertTrue("a0=$a0 a2=$a2", a2 > a0)
        assertTrue("a2=$a2 a4=$a4", a4 > a2)
    }

    @Test
    fun `jitter produces varied delays`() {
        val random = Random(7)
        val values = (1..200).map { policy.delayMillis(attempt = 5, random = random) }.toSet()
        assertTrue("Джиттер не работает, получено ${values.size} уникальных значений", values.size > 10)
    }

    @Test
    fun `same seed yields identical delays`() {
        val first = (0..10).map { policy.delayMillis(it, Random(123)) }
        val second = (0..10).map { policy.delayMillis(it, Random(123)) }
        assertEquals(first, second)
    }

    @Test
    fun `network errors retry indefinitely`() {
        assertTrue(policy.shouldRetry(AppError.Network(), attempt = 0))
        assertTrue(policy.shouldRetry(AppError.Network(), attempt = 1_000))
        assertTrue(policy.shouldRetry(AppError.Timeout(), attempt = 1_000))
    }

    @Test
    fun `server errors are capped by max attempts`() {
        assertTrue(policy.shouldRetry(AppError.Server(503), attempt = 4))
        assertFalse(policy.shouldRetry(AppError.Server(503), attempt = 5))
    }

    @Test
    fun `non retryable errors are not retried`() {
        assertFalse(policy.shouldRetry(AppError.Forbidden, attempt = 0))
        assertFalse(policy.shouldRetry(AppError.Validation(mapOf("title" to "пусто")), attempt = 0))
        assertFalse(policy.shouldRetry(AppError.Conflict(listOf("status")), attempt = 0))
        assertFalse(policy.shouldRetry(AppError.NotFound("task"), attempt = 0))
    }

    @Test
    fun `server retry after hint wins over formula`() {
        val delay = policy.effectiveDelayMillis(
            error = AppError.RateLimited(retryAfterSeconds = 42),
            attempt = 0,
        )
        assertEquals(42_000L, delay)
    }

    @Test
    fun `falls back to own formula without hint`() {
        val delay = policy.effectiveDelayMillis(AppError.Network(), attempt = 0, random = Random(1))
        assertTrue(delay in 1_000..60_000)
    }
}
