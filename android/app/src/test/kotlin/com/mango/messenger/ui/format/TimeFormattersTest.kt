package com.mango.messenger.ui.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class TimeFormattersTest {

    private val zone: ZoneId = ZoneOffset.UTC

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0) =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC)

    private val now = at(2026, 11, 12, 15, 30)

    @Test
    fun `conversation timestamp shows time for today`() {
        val result = TimeFormatters.conversationTimestamp(at(2026, 11, 12, 9, 5), now, zone)
        assertEquals("09:05", result)
    }

    @Test
    fun `conversation timestamp says yesterday`() {
        val result = TimeFormatters.conversationTimestamp(at(2026, 11, 11, 23, 59), now, zone)
        assertEquals("вчера", result)
    }

    @Test
    fun `conversation timestamp uses weekday within the last week`() {
        val result = TimeFormatters.conversationTimestamp(at(2026, 11, 8), now, zone)
        assertFalse("Должен быть день недели, а не время", result.contains(":"))
        assertTrue("Короткое название дня недели", result.length in 2..6)
    }

    @Test
    fun `conversation timestamp omits the year within the current year`() {
        val result = TimeFormatters.conversationTimestamp(at(2026, 3, 4), now, zone)
        assertFalse("Год текущий — показывать его не нужно", result.contains("2026"))
    }

    @Test
    fun `conversation timestamp includes the year for older dates`() {
        val result = TimeFormatters.conversationTimestamp(at(2024, 3, 4), now, zone)
        assertTrue("Для прошлых лет год обязателен: $result", result.contains("2024"))
    }

    @Test
    fun `due date is relative for the near term`() {
        assertTrue(TimeFormatters.dueDate(at(2026, 11, 12, 18, 0), now, zone).startsWith("сегодня"))
        assertTrue(TimeFormatters.dueDate(at(2026, 11, 13, 9, 0), now, zone).startsWith("завтра"))
        assertEquals("вчера", TimeFormatters.dueDate(at(2026, 11, 11), now, zone))
        assertEquals("через 3 дня", TimeFormatters.dueDate(at(2026, 11, 15), now, zone))
        assertEquals("3 дня назад", TimeFormatters.dueDate(at(2026, 11, 9), now, zone))
    }

    @Test
    fun `due date falls back to a calendar date when far away`() {
        val result = TimeFormatters.dueDate(at(2026, 12, 25), now, zone)
        assertFalse("Далёкий срок не описывается словом «через»", result.startsWith("через"))
    }

    @Test
    fun `russian plural forms are correct`() {
        // Это главная причина существования отдельной функции:
        // «1 дней» в ежедневно используемом интерфейсе выглядит небрежно.
        assertEquals("1 день", TimeFormatters.plural(1, "день", "дня", "дней"))
        assertEquals("2 дня", TimeFormatters.plural(2, "день", "дня", "дней"))
        assertEquals("4 дня", TimeFormatters.plural(4, "день", "дня", "дней"))
        assertEquals("5 дней", TimeFormatters.plural(5, "день", "дня", "дней"))
        assertEquals("11 дней", TimeFormatters.plural(11, "день", "дня", "дней"))
        assertEquals("12 дней", TimeFormatters.plural(12, "день", "дня", "дней"))
        assertEquals("14 дней", TimeFormatters.plural(14, "день", "дня", "дней"))
        assertEquals("21 день", TimeFormatters.plural(21, "день", "дня", "дней"))
        assertEquals("22 дня", TimeFormatters.plural(22, "день", "дня", "дней"))
        assertEquals("25 дней", TimeFormatters.plural(25, "день", "дня", "дней"))
        assertEquals("101 день", TimeFormatters.plural(101, "день", "дня", "дней"))
        assertEquals("111 дней", TimeFormatters.plural(111, "день", "дня", "дней"))
    }

    @Test
    fun `last seen is relative and handles unknown value`() {
        assertEquals("был(а) давно", TimeFormatters.lastSeen(null, now, zone))
        assertEquals(
            "был(а) только что",
            TimeFormatters.lastSeen(now.minusSeconds(30), now, zone),
        )
        assertEquals(
            "был(а) 5 минут назад",
            TimeFormatters.lastSeen(now.minusSeconds(5 * 60), now, zone),
        )
        assertEquals(
            "был(а) 2 часа назад",
            TimeFormatters.lastSeen(now.minusSeconds(2 * 3600), now, zone),
        )
    }

    @Test
    fun `date separator uses words for recent days`() {
        assertEquals("Сегодня", TimeFormatters.dateSeparator(at(2026, 11, 12, 1, 0), now, zone))
        assertEquals("Вчера", TimeFormatters.dateSeparator(at(2026, 11, 11, 1, 0), now, zone))
    }

    @Test
    fun `same day comparison respects the time zone boundary`() {
        val lateEvening = at(2026, 11, 12, 23, 30)
        val nextMorning = at(2026, 11, 13, 0, 30)

        assertTrue(TimeFormatters.sameDay(lateEvening, at(2026, 11, 12, 1, 0), zone))
        assertFalse(
            "Переход через полночь — это другой день, здесь нужен разделитель",
            TimeFormatters.sameDay(lateEvening, nextMorning, zone),
        )
    }

    @Test
    fun `message time is always hours and minutes`() {
        assertEquals("07:08", TimeFormatters.messageTime(at(2026, 5, 1, 7, 8), zone))
        assertEquals("23:59", TimeFormatters.messageTime(at(2026, 5, 1, 23, 59), zone))
    }
}
