package com.mango.backend.common

import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Генератор идентификаторов.
 *
 * Инжектируется, а не вызывается статически: иначе тесты не могут
 * подменить время, а состояние монотонного счётчика протекает между тестами.
 * Та же причина, что и на клиенте.
 */
interface IdGenerator {
    fun newId(): UUID
}

/**
 * UUID версии 7 (RFC 9562).
 *
 * Старшие 48 бит — unix-время в миллисекундах, поэтому идентификаторы
 * сортируются в порядке создания. Для PostgreSQL это принципиально:
 * вставка случайных UUID v4 фрагментирует B-tree индекс, тогда как
 * возрастающие значения дописываются в правый край страницы.
 *
 * Экземпляр монотонен: внутри одной миллисекунды используется 12-битный
 * счётчик в поле rand_a. Класс потокобезопасен.
 */
class UuidV7Generator(
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val random: java.util.Random = SecureRandom(),
) : IdGenerator {

    /** Упакованное состояние: старшие 48 бит — timestamp, младшие 16 — счётчик. */
    private val state = AtomicLong(0)

    override fun newId(): UUID = generate(clockMillis())

    /** Генерация с явной меткой времени — используется в тестах. */
    fun generate(nowMillis: Long): UUID {
        val packed = nextState(nowMillis)
        val timestamp = packed ushr 16
        val counter = packed and 0xFFFF

        val msb = (timestamp shl 16) or VERSION_BITS or counter
        val lsb = (random.nextLong() and LOWER_62_BITS) or VARIANT_BITS

        return UUID(msb, lsb)
    }

    /**
     * Если системные часы «отпрыгнули» назад (NTP, перевод времени),
     * timestamp не уменьшается: монотонность важнее точности метки
     * на несколько миллисекунд.
     */
    private fun nextState(nowMillis: Long): Long {
        while (true) {
            val current = state.get()
            val lastTimestamp = current ushr 16
            val lastCounter = current and 0xFFFF

            val next = when {
                nowMillis > lastTimestamp ->
                    (nowMillis shl 16) or random.nextInt(COUNTER_MAX / 2).toLong()

                lastCounter < COUNTER_MAX ->
                    (lastTimestamp shl 16) or (lastCounter + 1)

                else -> (lastTimestamp + 1) shl 16
            }

            if (state.compareAndSet(current, next)) return next
        }
    }

    companion object {
        private const val VERSION_BITS = 0x7000L
        private const val VARIANT_BITS = Long.MIN_VALUE // 0x8000000000000000
        private const val LOWER_62_BITS = 0x3FFFFFFFFFFFFFFFL
        private const val COUNTER_MAX = 0x0FFF

        /** Метка времени создания — полезна при разборе журналов. */
        fun timestampOf(uuid: UUID): Long = uuid.mostSignificantBits ushr 16

        fun isV7(uuid: UUID): Boolean = uuid.version() == 7 && uuid.variant() == 2
    }
}

/**
 * Разбор UUID из недоверенного ввода.
 *
 * `UUID.fromString` принимает укороченные формы вроде "1-1-1-1-1" и бросает
 * исключение на мусоре. В обработчике запроса нужно отличать «некорректный
 * формат» от сбоя, поэтому возвращается null, а не исключение.
 */
fun String.toUuidOrNull(): UUID? {
    if (length != 36) return null
    return try {
        UUID.fromString(this)
    } catch (_: IllegalArgumentException) {
        null
    }
}
