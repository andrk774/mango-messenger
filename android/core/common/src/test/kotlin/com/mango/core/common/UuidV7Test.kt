package com.mango.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class UuidV7Test {

    /** Детерминированный генератор: фиксированный Random, управляемое время. */
    private fun generatorAt(millis: Long, seed: Long = 1L) =
        UuidV7Generator(clockMillis = { millis }, random = Random(seed))

    @Test
    fun `produces canonical uuid format`() {
        val uuid = generatorAt(1_700_000_000_000L).newId()
        assertEquals(36, uuid.length)
        assertTrue("Не похоже на UUID: $uuid", UuidV7Generator.isValid(uuid))
    }

    @Test
    fun `sets version 7 and rfc 9562 variant bits`() {
        val generator = UuidV7Generator()
        repeat(500) {
            val uuid = generator.newId()
            assertEquals("Версия должна быть 7 в $uuid", '7', uuid[14])
            assertTrue("Variant некорректен в $uuid", uuid[19] in "89ab")
        }
    }

    @Test
    fun `ids increase lexicographically over real time`() {
        val generator = UuidV7Generator()
        val generated = List(10_000) { generator.newId() }
        generated.zipWithNext { previous, next ->
            assertTrue("Порядок нарушен: $previous >= $next", previous < next)
        }
    }

    @Test
    fun `stays monotonic within a single millisecond`() {
        // Худший случай: много вызовов внутри одной миллисекунды.
        val generator = generatorAt(1_700_000_000_000L)
        val generated = List(3_000) { generator.newId() }

        assertEquals("Найдены дубликаты", generated.size, generated.toSet().size)
        generated.zipWithNext { previous, next ->
            assertTrue("Порядок нарушен внутри одной мс: $previous >= $next", previous < next)
        }
    }

    @Test
    fun `clock moving backwards does not break monotonicity`() {
        var now = 1_700_000_000_000L
        val generator = UuidV7Generator(clockMillis = { now }, random = Random(1))

        val forward = generator.newId()
        now -= 60_000 // имитируем перевод часов назад на минуту
        val backward = generator.newId()

        assertTrue(
            "При переводе часов назад порядок должен сохраниться: $forward -> $backward",
            backward > forward,
        )
    }

    @Test
    fun `exhausted counter rolls over to next millisecond`() {
        // 12-битный счётчик вмещает 4096 значений; стартует в нижней половине,
        // поэтому 5000 вызовов гарантированно переполнят его.
        val generator = generatorAt(1_700_000_000_000L)
        val generated = List(5_000) { generator.newId() }

        assertEquals("Найдены дубликаты после переполнения", generated.size, generated.toSet().size)
        generated.zipWithNext { previous, next ->
            assertTrue("Порядок нарушен после переполнения", previous < next)
        }
        assertTrue(
            "Метка времени должна была сдвинуться вперёд",
            UuidV7Generator.timestampOf(generated.last()) > 1_700_000_000_000L,
        )
    }

    @Test
    fun `no collisions under concurrent generation`() {
        val generator = UuidV7Generator()
        val threads = 8
        val perThread = 5_000
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val tasks = List(threads) { Callable { List(perThread) { generator.newId() } } }
            val all = pool.invokeAll(tasks).flatMap { it.get(60, TimeUnit.SECONDS) }
            assertEquals(
                "Коллизии при параллельной генерации",
                threads * perThread,
                all.toSet().size,
            )
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `timestampOf round trips the creation time`() {
        val millis = 1_700_123_456_789L
        val uuid = generatorAt(millis).newId()
        assertEquals(millis, UuidV7Generator.timestampOf(uuid))
    }

    @Test
    fun `isValid rejects non v7 uuids`() {
        // UUIDv4 — версия 4, не 7.
        assertTrue(!UuidV7Generator.isValid("9f1b7a4c-2e3d-4f5a-8b6c-1d2e3f4a5b6c"))
        assertTrue(!UuidV7Generator.isValid("not-a-uuid"))
    }
}
