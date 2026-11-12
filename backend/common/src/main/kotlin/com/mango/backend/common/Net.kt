package com.mango.backend.common

/**
 * Проверка, что строка — литерал IP-адреса.
 *
 * Нужна перед записью в колонку типа `inet`: PostgreSQL отвергает всё,
 * что не является адресом, и такая ошибка прерывает транзакцию. Значение
 * приходит из заголовка `X-Forwarded-For`, то есть полностью управляется
 * клиентом — без проверки произвольный заголовок валил бы вход с кодом 500.
 *
 * Разбор ручной и не обращается к DNS: `InetAddress.getByName` для имени
 * хоста выполняет сетевой запрос, то есть блокирует поток обработки
 * запроса на время резолва. Для журнала сессий это недопустимая цена.
 */
fun String?.toIpLiteralOrNull(): String? {
    val value = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null

    // IPv6 может приходить в скобках: [::1]:51234
    val unwrapped = if (value.startsWith("[")) {
        value.substringAfter('[').substringBefore(']')
    } else {
        value
    }

    // Отбрасываем зону интерфейса (fe80::1%eth0) — в inet она не хранится.
    val candidate = unwrapped.substringBefore('%')

    return when {
        candidate.isIpv4() -> candidate
        candidate.isIpv6() -> candidate
        else -> null
    }
}

private fun String.isIpv4(): Boolean {
    val parts = split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() &&
            part.length <= 3 &&
            part.all(Char::isDigit) &&
            // Ведущий ноль запрещён: "010" в разных системах читается
            // то как 10, то как 8, и такие адреса используют для обхода фильтров.
            (part.length == 1 || part[0] != '0') &&
            part.toInt() in 0..255
    }
}

private fun String.isIpv6(): Boolean {
    if (!contains(':')) return false
    if (length > 45) return false

    // Сжатие нулей допускается ровно один раз.
    if (indexOf("::") != lastIndexOf("::")) return false

    val groups = split(':')
    if (groups.size > 8) return false

    return groups.all { group ->
        group.isEmpty() || (group.length <= 4 && group.all { it.isHexDigit() })
    }
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
