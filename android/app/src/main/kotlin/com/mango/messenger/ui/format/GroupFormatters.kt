package com.mango.messenger.ui.format

import com.mango.core.model.MemberRole
import com.mango.core.model.SystemEvent
import com.mango.core.model.SystemEventKind
import java.util.Locale

/**
 * Текст системных сообщений группы.
 *
 * Формулировки намеренно безличные («Анна: исключение из группы — Борис»
 * вместо «Анна исключила Бориса»): приложение не знает грамматического
 * рода участников и не умеет склонять имена. Неверный род или падеж
 * в чужом имени выглядит хуже, чем сдержанная формулировка.
 */
object SystemEventFormatter {

    fun text(event: SystemEvent?): String {
        if (event == null) return "Изменение в группе"
        val actor = event.actorName.ifBlank { "Участник" }
        val names = event.targets.joinToString(", ") { it.name.ifBlank { "участник" } }
        return when (event.kind) {
            SystemEventKind.MEMBERS_ADDED -> "$actor: добавление в группу — $names"
            SystemEventKind.MEMBER_REMOVED -> "$actor: исключение из группы — $names"
            SystemEventKind.MEMBER_LEFT -> buildString {
                append("$actor: выход из группы")
                // Если уходил владелец, в том же событии указан новый.
                if (event.role == MemberRole.OWNER && names.isNotEmpty()) {
                    append(". Владелец группы теперь — $names")
                }
            }
            SystemEventKind.TITLE_CHANGED -> "$actor: новое название — «${event.title.orEmpty()}»"
            SystemEventKind.ROLE_CHANGED -> "$actor: $names — ${roleLabel(event.role).lowercase()}"
            SystemEventKind.OWNER_CHANGED -> "$actor: владелец группы теперь — $names"
            SystemEventKind.UNKNOWN -> "Изменение в группе"
        }
    }

    fun roleLabel(role: MemberRole?): String = when (role) {
        MemberRole.OWNER -> "Владелец"
        MemberRole.ADMIN -> "Администратор"
        MemberRole.MEMBER, null -> "Участник"
    }
}

object FileSizeFormatter {

    /** «512 Б», «1,4 КБ», «12,3 МБ» — с запятой, как принято в русской записи. */
    fun format(bytes: Long): String {
        val units = listOf("Б", "КБ", "МБ", "ГБ")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (unit == 0) {
            "$bytes ${units[0]}"
        } else {
            String.format(Locale.forLanguageTag("ru"), "%.1f %s", value, units[unit])
        }
    }
}
