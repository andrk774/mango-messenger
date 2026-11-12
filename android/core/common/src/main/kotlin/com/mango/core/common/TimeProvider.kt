package com.mango.core.common

import java.time.Instant

/**
 * Источник времени.
 *
 * Инжектируется, чтобы тесты на дедлайны задач, напоминания и backoff
 * не зависели от системных часов и выполнялись мгновенно.
 */
interface TimeProvider {
    fun now(): Instant
    fun nowMillis(): Long = now().toEpochMilli()
}

class SystemTimeProvider : TimeProvider {
    override fun now(): Instant = Instant.now()
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** Управляемое время для тестов. */
class MutableTimeProvider(private var current: Instant = Instant.EPOCH) : TimeProvider {
    override fun now(): Instant = current

    fun set(instant: Instant) {
        current = instant
    }

    fun advanceMillis(millis: Long) {
        current = current.plusMillis(millis)
    }
}
