package com.mango.backend.tasks

/**
 * Изменение одного поля задачи, пришедшее от клиента.
 *
 * [timestamp] — момент, когда пользователь сделал правку на устройстве,
 * а не момент, когда запрос дошёл до сервера. Правка, сделанная в самолёте
 * вчера, должна проиграть правке, сделанной сегодня, даже если доехала
 * позже.
 */
data class FieldChange(
    val field: String,
    val value: String?,
    val timestamp: Long,
)

/** Итог разрешения: что применено, что отклонено и новые метки полей. */
data class Resolution(
    val applied: Map<String, String?>,
    /** Поля, где победила версия сервера. Клиенту сообщаются как `conflicts`. */
    val rejected: List<String>,
    val fieldVersions: Map<String, Long>,
) {
    val hasChanges: Boolean get() = applied.isNotEmpty()
}

/**
 * Разрешение конфликтов «последняя запись побеждает» — по каждому полю отдельно.
 *
 * ## Почему по полю, а не по объекту целиком
 *
 * Один человек меняет срок, другой в то же время — приоритет. Конфликта
 * по сути нет, и блокировка по версии всего объекта отвергла бы вторую
 * правку на пустом месте. Сравнение по полю принимает обе.
 *
 * ## Правила (ADR-0005)
 *
 * 1. Метка времени правки новее сохранённой — правка применяется.
 * 2. Старее — побеждает сервер, поле попадает в `rejected`.
 * 3. Метки равны:
 *    * для `status` побеждает более «сильное» намерение
 *      (DONE > CANCELLED > REVIEW > IN_PROGRESS > TODO);
 *    * для остальных полей остаётся значение сервера — правило
 *      детерминированное, а значит одинаковое на всех узлах.
 *
 * ## Защита от неверных часов на устройстве
 *
 * Метка клиента ограничивается сверху текущим временем сервера. Без этого
 * устройство с часами, ушедшими на год вперёд, выигрывало бы любой
 * конфликт в течение года, и чужие правки молча терялись бы.
 *
 * ## Быстрый путь
 *
 * Если клиент правил актуальную версию задачи (`baseVersion` совпал),
 * конкурирующих правок не было, и сравнивать метки незачем: все изменения
 * применяются. Без этого устройство с отстающими часами проигрывало бы
 * собственной предыдущей правке — пользователь менял бы поле, а сервер
 * отвечал бы «изменено на другом устройстве», хотя другого устройства нет.
 *
 * Функция чистая: не обращается ни к базе, ни к часам, поэтому проверяется
 * модульными тестами исчерпывающе.
 */
object ConflictResolver {

    /** Поля, которые клиент вправе менять частичным изменением. */
    val EDITABLE_FIELDS = setOf("title", "description", "status", "priority", "due_at", "remind_at")

    private val STATUS_WEIGHT = mapOf(
        "TODO" to 0,
        "IN_PROGRESS" to 1,
        "REVIEW" to 2,
        "CANCELLED" to 3,
        "DONE" to 4,
    )

    fun resolve(
        current: Map<String, String?>,
        currentVersions: Map<String, Long>,
        changes: List<FieldChange>,
        serverNow: Long,
        clientSawLatest: Boolean = false,
    ): Resolution {
        val applied = linkedMapOf<String, String?>()
        val rejected = mutableListOf<String>()
        val versions = currentVersions.toMutableMap()

        for (change in changes) {
            require(change.field in EDITABLE_FIELDS) { "Поле ${change.field} не изменяется" }

            val incoming = change.timestamp.coerceAtMost(serverNow)
            val stored = currentVersions[change.field] ?: 0L

            val wins = when {
                clientSawLatest -> true
                incoming > stored -> true
                incoming < stored -> false
                change.field == "status" -> statusWins(change.value, current["status"])
                else -> false
            }

            // Повтор той же самой правки — не конфликт: значение и так
            // такое, и сообщать клиенту «вы проиграли» было бы неправдой.
            if (!wins && current[change.field] == change.value) continue

            if (wins) {
                applied[change.field] = change.value
                versions[change.field] = maxOf(incoming, stored)
            } else {
                rejected += change.field
            }
        }

        return Resolution(applied, rejected, versions)
    }

    private fun statusWins(incoming: String?, stored: String?): Boolean {
        val incomingWeight = STATUS_WEIGHT[incoming] ?: return false
        val storedWeight = STATUS_WEIGHT[stored] ?: return true
        return incomingWeight > storedWeight
    }
}
