package com.mango.core.common

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.Random

/**
 * Генератор идентификаторов.
 *
 * Инжектируется, а не вызывается статически: иначе тесты не могут
 * подменить время, а состояние монотонного счётчика протекает между тестами.
 */
interface IdGenerator {
    /** Новый идентификатор в каноническом строковом виде UUID. */
    fun newId(): String
}

/**
 * Генератор UUID версии 7 (RFC 9562).
 *
 * Зачем именно v7, а не v4: старшие 48 бит — это unix-время в миллисекундах,
 * поэтому идентификаторы сортируются лексикографически в порядке создания.
 * Для мессенджера это даёт три вещи сразу:
 *
 *  1. Индексы в PostgreSQL и Room не фрагментируются при вставке (в отличие от v4).
 *  2. Локально созданные сообщения выстраиваются в правильном порядке
 *     ещё до того, как сервер присвоит им `seq`.
 *  3. ClientOpId в очереди исходящих операций естественно упорядочен,
 *     что упрощает отправку зависимых операций.
 *
 * Экземпляр монотонен: внутри одной миллисекунды используется 12-битный
 * счётчик в поле rand_a, поэтому два вызова подряд никогда не дадут
 * одинаковый или «убывающий» идентификатор.
 *
 * Класс потокобезопасен.
 */
class UuidV7Generator(
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val random: Random = SecureRandom(),
) : IdGenerator {

    /** Упакованное состояние: старшие 48 бит — timestamp, младшие 16 — счётчик. */
    private val state = AtomicLong(0)

    override fun newId(): String = generate(clockMillis())

    /** Генерация с явно заданной меткой времени — используется в тестах. */
    fun generate(nowMillis: Long): String {
        val packed = nextState(nowMillis)
        val timestamp = packed ushr 16
        val counter = packed and 0xFFFF

        val msb = (timestamp shl 16) or VERSION_BITS or counter
        val lsb = (random.nextLong() and LOWER_62_BITS) or VARIANT_BITS

        return format(msb, lsb)
    }

    /**
     * Атомарно подбирает пару (timestamp, counter), упакованную в Long.
     *
     * Если системные часы «отпрыгнули» назад (перевод времени, NTP),
     * timestamp не уменьшается, а продолжается от последнего известного:
     * монотонность идентификаторов важнее точности метки на несколько миллисекунд.
     */
    private fun nextState(nowMillis: Long): Long {
        while (true) {
            val current = state.get()
            val lastTimestamp = current ushr 16
            val lastCounter = current and 0xFFFF

            val next = when {
                // Новая миллисекунда: начинаем счётчик со случайного значения
                // в нижней половине диапазона, оставляя запас на рост.
                nowMillis > lastTimestamp ->
                    (nowMillis shl 16) or random.nextInt(COUNTER_START_RANGE).toLong()

                // Та же (или более ранняя) миллисекунда: увеличиваем счётчик.
                lastCounter < COUNTER_MAX ->
                    (lastTimestamp shl 16) or (lastCounter + 1)

                // Счётчик исчерпан — переходим к следующей миллисекунде.
                else ->
                    ((lastTimestamp + 1) shl 16)
            }

            if (state.compareAndSet(current, next)) return next
        }
    }

    private fun format(msb: Long, lsb: Long): String {
        val sb = StringBuilder(36)
        sb.appendHex(msb ushr 32, 8)
        sb.append('-')
        sb.appendHex((msb ushr 16) and 0xFFFF, 4)
        sb.append('-')
        sb.appendHex(msb and 0xFFFF, 4)
        sb.append('-')
        sb.appendHex((lsb ushr 48) and 0xFFFF, 4)
        sb.append('-')
        sb.appendHex(lsb and 0xFFFFFFFFFFFFL, 12)
        return sb.toString()
    }

    private fun StringBuilder.appendHex(value: Long, digits: Int) {
        val hex = java.lang.Long.toHexString(value)
        repeat(digits - hex.length) { append('0') }
        append(hex)
    }

    companion object {
        private const val VERSION_BITS = 0x7000L

        // Variant RFC 9562: старшие два бита младшего слова должны быть «10».
        // Long.MIN_VALUE — это ровно 0x8000000000000000.
        private const val VARIANT_BITS = Long.MIN_VALUE
        private const val LOWER_62_BITS = 0x3FFFFFFFFFFFFFFFL
        private const val COUNTER_MAX = 0x0FFFL // 12 бит под rand_a

        /** Стартовое значение счётчика берётся из нижней половины — запас на рост внутри мс. */
        private const val COUNTER_START_RANGE = 0x0800

        /** Извлекает метку времени создания из UUIDv7. Полезно для диагностики очереди. */
        fun timestampOf(uuid: String): Long {
            val hex = uuid.replace("-", "")
            require(hex.length == 32) { "Некорректный UUID: $uuid" }
            return hex.substring(0, 12).toLong(16)
        }

        /** Проверяет, что строка — корректный UUIDv7. */
        fun isValid(uuid: String): Boolean =
            UUID_PATTERN.matches(uuid) && uuid[14] == '7' && uuid[19] in "89ab"

        private val UUID_PATTERN =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
