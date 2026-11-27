package com.mango.messenger.ui.format

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Форматирование времени для интерфейса.
 *
 * Живёт в presentation-слое, а не в design system и не в domain: формат
 * зависит от локали и часового пояса устройства, а это свойство конкретного
 * приложения, а не модели данных.
 */
object TimeFormatters {

    private val timeOnly get() = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    private val dayAndMonth get() = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    private val fullDate get() = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
    private val fullDateTime get() = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault())

    /**
     * Метка для списка чатов: сегодня — время, на этой неделе — день недели,
     * в этом году — число и месяц, иначе — с годом.
     */
    fun conversationTimestamp(
        instant: Instant,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val date = instant.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()

        return when {
            date == today -> timeOnly.format(instant.atZone(zone))
            date == today.minusDays(1) -> "вчера"
            date.isAfter(today.minusDays(7)) ->
                date.dayOfWeek.getDisplayName(
                    java.time.format.TextStyle.SHORT,
                    Locale.getDefault(),
                )
            date.year == today.year -> dayAndMonth.format(instant.atZone(zone))
            else -> fullDate.format(instant.atZone(zone))
        }
    }

    /** Время сообщения внутри переписки — всегда часы и минуты. */
    fun messageTime(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        timeOnly.format(instant.atZone(zone))

    /** Разделитель дат внутри переписки. */
    fun dateSeparator(
        instant: Instant,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val date = instant.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return when {
            date == today -> "Сегодня"
            date == today.minusDays(1) -> "Вчера"
            date.year == today.year -> dayAndMonth.format(instant.atZone(zone))
            else -> fullDate.format(instant.atZone(zone))
        }
    }

    /**
     * Срок задачи.
     *
     * Для просроченных и ближайших сроков даётся относительная формулировка:
     * «3 дня назад» понятнее, чем дата, когда нужно быстро оценить ситуацию.
     */
    fun dueDate(
        dueAt: Instant,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val dueDate = dueAt.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        val days = dueDate.toEpochDay() - today.toEpochDay()

        return when {
            days == 0L -> "сегодня, ${timeOnly.format(dueAt.atZone(zone))}"
            days == 1L -> "завтра, ${timeOnly.format(dueAt.atZone(zone))}"
            days == -1L -> "вчера"
            days in 2..6 -> "через ${plural(days, "день", "дня", "дней")}"
            days in -6..-2 -> "${plural(-days, "день", "дня", "дней")} назад"
            dueDate.year == today.year -> dayAndMonth.format(dueAt.atZone(zone))
            else -> fullDate.format(dueAt.atZone(zone))
        }
    }

    /** Строка статуса в заголовке чата. */
    fun lastSeen(
        lastSeenAt: Instant?,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (lastSeenAt == null) return "был(а) давно"
        val minutes = Duration.between(lastSeenAt, now).toMinutes()
        return when {
            minutes < 1 -> "был(а) только что"
            minutes < 60 -> "был(а) ${plural(minutes, "минуту", "минуты", "минут")} назад"
            minutes < 60 * 24 -> {
                val hours = minutes / 60
                "был(а) ${plural(hours, "час", "часа", "часов")} назад"
            }
            else -> "был(а) ${conversationTimestamp(lastSeenAt, now, zone)}"
        }
    }

    fun fullTimestamp(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        fullDateTime.format(instant.atZone(zone))

    /**
     * Русские формы множественного числа.
     *
     * Правила отличаются от английских, и подстановка одной формы даёт
     * «1 дней» — заметную неряшливость в интерфейсе, которым пользуются каждый день.
     */
    internal fun plural(count: Long, one: String, few: String, many: String): String {
        val n = count % 100
        val form = when {
            n in 11..14 -> many
            n % 10 == 1L -> one
            n % 10 in 2..4 -> few
            else -> many
        }
        return "$count $form"
    }

    /** Группировка сообщений по дате — для разделителей в переписке. */
    fun sameDay(a: Instant, b: Instant, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        // atZone().toLocalDate(), а не LocalDate.ofInstant: последний доступен
        // только с API 34, тогда как minSdk проекта — 26 (поймано lint).
        a.atZone(zone).toLocalDate() == b.atZone(zone).toLocalDate()
}
