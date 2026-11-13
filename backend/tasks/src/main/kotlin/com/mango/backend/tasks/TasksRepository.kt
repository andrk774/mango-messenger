package com.mango.backend.tasks

import com.mango.backend.common.ApiJson
import com.mango.backend.common.Json
import com.mango.backend.common.instant
import com.mango.backend.common.instantOrNull
import com.mango.backend.common.query
import com.mango.backend.common.queryOne
import com.mango.backend.common.update
import com.mango.backend.common.uuid
import com.mango.backend.common.uuidOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** Текущее состояние задачи — то, что нужно для разрешения конфликта. */
data class TaskState(
    val id: UUID,
    val createdBy: UUID,
    val conversationId: UUID?,
    val version: Long,
    val fields: Map<String, String?>,
    val fieldVersions: Map<String, Long>,
)

/** Снимок сообщения-источника для задачи. */
data class MessageSnapshot(
    val messageId: UUID,
    val conversationId: UUID,
    val senderName: String,
    val text: String?,
    val createdAt: Instant,
    val deleted: Boolean,
)

/**
 * Доступ к данным задач.
 *
 * Каждое чтение содержит условие видимости. Оно записано один раз
 * ([VISIBLE_TO]) и подставляется во все запросы — иначе правило доступа
 * неизбежно разошлось бы между выборкой списка и выборкой одной задачи.
 */
class TasksRepository {

    // ─────────────────────────── видимость ───────────────────────────

    /**
     * Видна ли задача пользователю.
     *
     * Ответ для чужой и для несуществующей задачи одинаков: различие
     * позволяло бы перебором выяснять, какие задачи существуют.
     */
    fun isVisible(connection: Connection, taskId: UUID, userId: UUID): Boolean =
        connection.queryOne(
            "SELECT 1 FROM tasks t WHERE t.id = ? AND t.deleted_at IS NULL AND $VISIBLE_TO",
            taskId, userId, userId, userId,
        ) { true } ?: false

    /**
     * Кому должны прийти события об изменении задачи.
     *
     * Создатель, действующие исполнители и — для задачи чата — его
     * участники. Тот же круг, что и у [isVisible]: получатель события
     * обязан иметь право прочитать задачу.
     */
    fun recipients(connection: Connection, taskId: UUID): List<UUID> = connection.query(
        """
        SELECT t.created_by AS user_id FROM tasks t WHERE t.id = ?
        UNION
        SELECT a.user_id FROM task_assignees a WHERE a.task_id = ? AND $ASSIGNEE_PRESENT
        UNION
        SELECT cm.user_id FROM tasks t
        JOIN conversation_members cm
          ON cm.conversation_id = t.conversation_id AND cm.left_at IS NULL
        WHERE t.id = ?
        """.trimIndent(),
        taskId, taskId, taskId,
    ) { it.uuid("user_id") }

    fun isConversationMember(connection: Connection, conversationId: UUID, userId: UUID): Boolean =
        connection.queryOne(
            """
            SELECT 1 FROM conversation_members
            WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL
            """.trimIndent(),
            conversationId, userId,
        ) { true } ?: false

    fun existingUsers(connection: Connection, ids: Collection<UUID>): Set<UUID> {
        if (ids.isEmpty()) return emptySet()
        val placeholders = ids.joinToString(", ") { "?" }
        return connection.query(
            "SELECT id FROM users WHERE deleted_at IS NULL AND id IN ($placeholders)",
            *ids.toTypedArray(),
        ) { it.uuid("id") }.toSet()
    }

    // ─────────────────────────── создание ───────────────────────────

    /**
     * Вставка задачи. `null` — задача с таким id уже существует.
     *
     * `ON CONFLICT DO NOTHING` делает повторное создание безопасным:
     * клиент повторяет операцию при неизвестном исходе, и вторая задача
     * появиться не должна.
     */
    fun insertTask(
        connection: Connection,
        id: UUID,
        createdBy: UUID,
        conversationId: UUID?,
        title: String,
        description: String?,
        priority: String,
        dueAt: Instant?,
        remindAt: Instant?,
        parentTaskId: UUID?,
        sourceMessageId: UUID?,
        sourceSnapshot: String?,
        fieldVersions: Map<String, Long>,
        now: Instant,
    ): UUID? = connection.queryOne(
        """
        INSERT INTO tasks (
            id, created_by, conversation_id, title, description, priority,
            due_at, remind_at, parent_task_id, source_message_id,
            source_message_snapshot, field_versions, created_at, updated_at
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (id) DO NOTHING
        RETURNING id
        """.trimIndent(),
        id, createdBy, conversationId, title, description, priority,
        dueAt, remindAt, parentTaskId, sourceMessageId,
        sourceSnapshot?.let(::Json), versionsJson(fieldVersions), now, now,
    ) { it.uuid("id") }

    fun taskOwner(connection: Connection, id: UUID): UUID? =
        connection.queryOne("SELECT created_by FROM tasks WHERE id = ?", id) { it.uuid("created_by") }

    // ─────────────────────────── состояние ───────────────────────────

    /**
     * Состояние задачи с блокировкой строки.
     *
     * Блокировка нужна, чтобы два одновременных изменения одной задачи
     * разрешались последовательно: иначе оба прочитали бы одни и те же
     * метки полей и оба решили бы, что выиграли.
     */
    fun lockState(connection: Connection, id: UUID): TaskState? = connection.queryOne(
        """
        SELECT id, created_by, conversation_id, version, field_versions,
               title, description, status, priority, due_at, remind_at
        FROM tasks
        WHERE id = ? AND deleted_at IS NULL
        FOR UPDATE
        """.trimIndent(),
        id,
    ) { rs ->
        TaskState(
            id = rs.uuid("id"),
            createdBy = rs.uuid("created_by"),
            conversationId = rs.uuidOrNull("conversation_id"),
            version = rs.getLong("version"),
            fields = mapOf(
                "title" to rs.getString("title"),
                "description" to rs.getString("description"),
                "status" to rs.getString("status"),
                "priority" to rs.getString("priority"),
                "due_at" to rs.instantOrNull("due_at")?.toString(),
                "remind_at" to rs.instantOrNull("remind_at")?.toString(),
            ),
            fieldVersions = parseVersions(rs.getString("field_versions")),
        )
    }

    /**
     * Применение разрешённых изменений.
     *
     * Запрос строится из списка ПРОВЕРЕННЫХ имён полей ([ConflictResolver.EDITABLE_FIELDS]),
     * а значения передаются параметрами. Имена колонок из клиентского ввода
     * в SQL не попадают — подстановка их напрямую была бы инъекцией.
     */
    fun applyFields(
        connection: Connection,
        id: UUID,
        applied: Map<String, String?>,
        fieldVersions: Map<String, Long>,
        now: Instant,
    ) {
        val assignments = mutableListOf<String>()
        val params = mutableListOf<Any?>()

        applied.forEach { (field, value) ->
            val column = COLUMN_BY_FIELD.getValue(field)
            assignments += "$column = ?"
            params += if (field == "due_at" || field == "remind_at") value?.let(Instant::parse) else value
        }

        if ("remind_at" in applied) {
            // Перенесённое напоминание должно сработать снова (V003).
            assignments += "reminded_at = NULL"
        }

        if ("status" in applied) {
            // Время завершения выставляется сервером: клиенту здесь
            // доверять незачем, а для отчётов по срокам оно важно.
            assignments += "completed_at = CASE WHEN ? = 'DONE' THEN ?::timestamptz ELSE NULL END"
            params += applied["status"]
            params += now
        }

        assignments += "field_versions = ?"
        params += versionsJson(fieldVersions)
        assignments += "version = version + 1"
        assignments += "updated_at = ?"
        params += now

        connection.update(
            "UPDATE tasks SET ${assignments.joinToString(", ")} WHERE id = ?",
            *(params + id).toTypedArray(),
        )
    }

    /** Задача уже удалена этим пользователем как автором — повтор удаления. */
    fun isDeletedByAuthor(connection: Connection, id: UUID, userId: UUID): Boolean =
        connection.queryOne(
            "SELECT 1 FROM tasks WHERE id = ? AND created_by = ? AND deleted_at IS NOT NULL",
            id, userId,
        ) { true } ?: false

    fun softDelete(connection: Connection, id: UUID, now: Instant): Int = connection.update(
        "UPDATE tasks SET deleted_at = ?, version = version + 1, updated_at = ? WHERE id = ? AND deleted_at IS NULL",
        now, now, id,
    )

    // ─────────────────────────── исполнители ───────────────────────────

    /**
     * Добавление исполнителя.
     *
     * Метка добавления обновляется даже для ранее удалённого: по сравнению
     * меток `assigned_at` и `removed_at` определяется, состоит ли человек
     * в наборе (см. [ASSIGNEE_PRESENT]).
     */
    fun addAssignee(connection: Connection, taskId: UUID, userId: UUID, by: UUID, now: Instant) =
        connection.update(
            """
            INSERT INTO task_assignees (task_id, user_id, assigned_by, assigned_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (task_id, user_id)
            DO UPDATE SET
                -- Метка строго больше метки удаления: операция, пришедшая
                -- позже, обязана победить даже в пределах одной миллисекунды.
                assigned_at = GREATEST(
                    EXCLUDED.assigned_at,
                    COALESCE(task_assignees.removed_at, EXCLUDED.assigned_at) + INTERVAL '1 microsecond'
                ),
                assigned_by = EXCLUDED.assigned_by
            """.trimIndent(),
            taskId, userId, by, now,
        )

    /**
     * Удаление исполнителя — пометкой, а не стиранием строки.
     *
     * Стирание сделало бы невозможным правильное слияние: параллельное
     * добавление с другого устройства не знало бы, что человека удаляли.
     */
    fun removeAssignee(connection: Connection, taskId: UUID, userId: UUID, now: Instant) =
        connection.update(
            """
            UPDATE task_assignees
            SET removed_at = GREATEST(?::timestamptz, assigned_at + INTERVAL '1 microsecond')
            WHERE task_id = ? AND user_id = ?
            """.trimIndent(),
            now, taskId, userId,
        )

    // ─────────────────────────── комментарии ───────────────────────────

    fun insertComment(
        connection: Connection,
        id: UUID,
        taskId: UUID,
        authorId: UUID,
        clientOpId: String,
        body: String,
        now: Instant,
    ): Boolean = connection.queryOne(
        """
        INSERT INTO task_comments (id, task_id, author_id, client_op_id, body, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT (task_id, author_id, client_op_id) DO NOTHING
        RETURNING id
        """.trimIndent(),
        id, taskId, authorId, clientOpId, body, now,
    ) { true } ?: false

    fun commentByClientOp(
        connection: Connection,
        taskId: UUID,
        authorId: UUID,
        clientOpId: String,
    ): TaskCommentDto? = connection.queryOne(
        "$COMMENT_SELECT WHERE c.task_id = ? AND c.author_id = ? AND c.client_op_id = ?",
        taskId, authorId, clientOpId,
    ) { mapComment(it) }

    fun comments(connection: Connection, taskId: UUID): List<TaskCommentDto> = connection.query(
        "$COMMENT_SELECT WHERE c.task_id = ? AND c.deleted_at IS NULL ORDER BY c.created_at, c.id",
        taskId,
    ) { mapComment(it) }

    // ─────────────────────────── история ───────────────────────────

    fun insertEvent(
        connection: Connection,
        taskId: UUID,
        actorId: UUID?,
        type: String,
        field: String?,
        oldValue: String?,
        newValue: String?,
        now: Instant,
    ) = connection.update(
        """
        INSERT INTO task_events (task_id, actor_id, event_type, field, old_value, new_value, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        taskId, actorId, type, field,
        oldValue?.let { Json(ApiJson.encodeToString(JsonElement.serializer(), JsonPrimitive(it))) },
        newValue?.let { Json(ApiJson.encodeToString(JsonElement.serializer(), JsonPrimitive(it))) },
        now,
    )

    fun events(connection: Connection, taskId: UUID): List<TaskEventDto> = connection.query(
        """
        SELECT e.id, e.task_id, e.actor_id, u.display_name AS actor_name,
               e.event_type, e.field, e.old_value, e.new_value, e.created_at
        FROM task_events e
        LEFT JOIN users u ON u.id = e.actor_id
        WHERE e.task_id = ?
        ORDER BY e.id
        """.trimIndent(),
        taskId,
    ) { rs ->
        TaskEventDto(
            id = rs.getLong("id"),
            taskId = rs.uuid("task_id"),
            actorId = rs.uuidOrNull("actor_id"),
            actorName = rs.getString("actor_name"),
            type = rs.getString("event_type"),
            field = rs.getString("field"),
            oldValue = rs.getString("old_value")?.let(ApiJson::parseToJsonElement),
            newValue = rs.getString("new_value")?.let(ApiJson::parseToJsonElement),
            createdAt = rs.instant("created_at"),
        )
    }

    // ─────────────────────────── сообщение-источник ───────────────────────────

    fun messageSnapshot(connection: Connection, messageId: UUID): MessageSnapshot? =
        connection.queryOne(
            """
            SELECT m.id, m.conversation_id, m.body, m.created_at, m.deleted_at,
                   u.display_name AS sender_name
            FROM messages m
            JOIN users u ON u.id = m.sender_id
            WHERE m.id = ?
            """.trimIndent(),
            messageId,
        ) { rs ->
            MessageSnapshot(
                messageId = rs.uuid("id"),
                conversationId = rs.uuid("conversation_id"),
                senderName = rs.getString("sender_name"),
                text = rs.getString("body"),
                createdAt = rs.instant("created_at"),
                deleted = rs.instantOrNull("deleted_at") != null,
            )
        }

    // ─────────────────────────── выборки ───────────────────────────

    /**
     * Идентификаторы задач по области.
     *
     * Сначала выбираются идентификаторы, затем задачи загружаются пачкой
     * в [load]: так исполнители и счётчики подтягиваются двумя запросами
     * на всю страницу, а не по запросу на каждую задачу.
     */
    fun listIds(
        connection: Connection,
        userId: UUID,
        scope: TaskScope,
        conversationId: UUID?,
        statuses: Set<String>,
        includeCompleted: Boolean,
        limit: Int,
    ): List<UUID> {
        val conditions = mutableListOf("t.deleted_at IS NULL", VISIBLE_TO)
        val params = mutableListOf<Any?>(userId, userId, userId)

        when (scope) {
            TaskScope.MINE -> {
                conditions += """
                    (t.created_by = ? OR EXISTS (
                        SELECT 1 FROM task_assignees a
                        WHERE a.task_id = t.id AND a.user_id = ? AND $ASSIGNEE_PRESENT))
                """.trimIndent()
                params += userId
                params += userId
            }
            TaskScope.TEAM -> conditions += "t.conversation_id IS NOT NULL"
            TaskScope.CHAT -> {
                conditions += "t.conversation_id = ?"
                params += conversationId
            }
            TaskScope.FROM_CHATS -> conditions += "t.source_message_id IS NOT NULL"
        }

        if (statuses.isNotEmpty()) {
            conditions += "t.status IN (${statuses.joinToString(", ") { "?" }})"
            // addAll, а не +=: иначе весь набор ушёл бы одним параметром.
            params.addAll(statuses)
        } else if (!includeCompleted) {
            conditions += "t.status NOT IN ('DONE', 'CANCELLED')"
        }

        params += limit

        return connection.query(
            """
            SELECT t.id FROM tasks t
            WHERE ${conditions.joinToString(" AND ")}
            ORDER BY (t.due_at IS NULL), t.due_at, t.created_at DESC
            LIMIT ?
            """.trimIndent(),
            *params.toTypedArray(),
        ) { it.uuid("id") }
    }

    /** Загрузка задач с исполнителями и счётчиками, в порядке входного списка. */
    fun load(connection: Connection, ids: List<UUID>): List<TaskDto> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(", ") { "?" }

        val assignees = connection.query(
            """
            SELECT a.task_id, u.id, u.username, u.display_name, u.avatar_url
            FROM task_assignees a
            JOIN users u ON u.id = a.user_id
            WHERE a.task_id IN ($placeholders) AND $ASSIGNEE_PRESENT
            ORDER BY u.display_name
            """.trimIndent(),
            *ids.toTypedArray(),
        ) { rs ->
            rs.uuid("task_id") to AssigneeDto(
                userId = rs.uuid("id"),
                username = rs.getString("username"),
                displayName = rs.getString("display_name"),
                avatarUrl = rs.getString("avatar_url"),
            )
        }.groupBy({ it.first }, { it.second })

        val tasks = connection.query(
            """
            SELECT t.*,
                   c.title AS conversation_title,
                   src.deleted_at AS source_deleted_at,
                   (SELECT count(*) FROM tasks s
                     WHERE s.parent_task_id = t.id AND s.deleted_at IS NULL) AS subtask_count,
                   (SELECT count(*) FROM tasks s
                     WHERE s.parent_task_id = t.id AND s.deleted_at IS NULL
                       AND s.status = 'DONE') AS completed_subtask_count,
                   (SELECT count(*) FROM task_comments tc
                     WHERE tc.task_id = t.id AND tc.deleted_at IS NULL) AS comment_count
            FROM tasks t
            LEFT JOIN conversations c ON c.id = t.conversation_id
            LEFT JOIN messages src ON src.id = t.source_message_id
            WHERE t.id IN ($placeholders)
            """.trimIndent(),
            *ids.toTypedArray(),
        ) { rs -> mapTask(rs, assignees[rs.uuid("id")].orEmpty()) }
            .associateBy { it.id }

        return ids.mapNotNull { tasks[it] }
    }

    // ─────────────────────────── отображение ───────────────────────────

    private fun mapTask(rs: ResultSet, assignees: List<AssigneeDto>): TaskDto {
        val snapshot = rs.getString("source_message_snapshot")?.let {
            runCatching { ApiJson.parseToJsonElement(it).jsonObject }.getOrNull()
        }
        val sourceMessageId = rs.uuidOrNull("source_message_id")

        return TaskDto(
            id = rs.uuid("id"),
            title = rs.getString("title"),
            description = rs.getString("description"),
            status = rs.getString("status"),
            priority = rs.getString("priority"),
            createdBy = rs.uuid("created_by"),
            assignees = assignees,
            conversationId = rs.uuidOrNull("conversation_id"),
            conversationTitle = rs.getString("conversation_title"),
            source = snapshot?.let {
                TaskSourceDto(
                    messageId = sourceMessageId,
                    conversationId = it.string("conversationId")?.let(UUID::fromString),
                    senderName = it.string("senderName").orEmpty(),
                    textSnapshot = it.string("text"),
                    sourceCreatedAt = it.string("createdAt")?.let(Instant::parse),
                    // Сообщение удалено мягко или ссылка обнулена —
                    // в обоих случаях переход к нему невозможен, но снимок
                    // текста остаётся читаемым.
                    isSourceUnavailable = sourceMessageId == null ||
                        rs.instantOrNull("source_deleted_at") != null,
                )
            },
            parentTaskId = rs.uuidOrNull("parent_task_id"),
            subtaskCount = rs.getInt("subtask_count"),
            completedSubtaskCount = rs.getInt("completed_subtask_count"),
            commentCount = rs.getInt("comment_count"),
            dueAt = rs.instantOrNull("due_at"),
            remindAt = rs.instantOrNull("remind_at"),
            version = rs.getLong("version"),
            fieldVersions = parseVersions(rs.getString("field_versions")),
            completedAt = rs.instantOrNull("completed_at"),
            createdAt = rs.instant("created_at"),
            updatedAt = rs.instant("updated_at"),
        )
    }

    private fun mapComment(rs: ResultSet) = TaskCommentDto(
        id = rs.uuid("id"),
        taskId = rs.uuid("task_id"),
        authorId = rs.uuid("author_id"),
        authorName = rs.getString("author_name"),
        clientOpId = rs.getString("client_op_id"),
        body = rs.getString("body"),
        createdAt = rs.instant("created_at"),
        editedAt = rs.instantOrNull("edited_at"),
    )

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {

        /**
         * Состоит ли человек в наборе исполнителей.
         *
         * Набор сливается поэлементно: добавление одного человека не
         * затрагивает других, поэтому назначения с двух устройств
         * складываются. Для одного и того же человека побеждает операция,
         * пришедшая последней: метки назначения и удаления строго возрастают
         * (см. [addAssignee], [removeAssignee]), и ничьих не бывает.
         *
         * Удаление помечается, а не стирается: иначе последующее сравнение
         * меток было бы невозможно.
         */
        const val ASSIGNEE_PRESENT = "(a.removed_at IS NULL OR a.assigned_at > a.removed_at)"

        /**
         * Правило видимости задачи. Параметры: userId трижды.
         *
         * Задача видна создателю, действующему исполнителю и — если она
         * принадлежит чату — участникам этого чата.
         */
        const val VISIBLE_TO = """(
            t.created_by = ?
            OR EXISTS (
                SELECT 1 FROM task_assignees a
                WHERE a.task_id = t.id AND a.user_id = ?
                  AND (a.removed_at IS NULL OR a.assigned_at > a.removed_at)
            )
            OR (t.conversation_id IS NOT NULL AND EXISTS (
                SELECT 1 FROM conversation_members cm
                WHERE cm.conversation_id = t.conversation_id
                  AND cm.user_id = ? AND cm.left_at IS NULL
            ))
        )"""

        /** Имя поля API → колонка. Единственный источник имён колонок для UPDATE. */
        private val COLUMN_BY_FIELD = mapOf(
            "title" to "title",
            "description" to "description",
            "status" to "status",
            "priority" to "priority",
            "due_at" to "due_at",
            "remind_at" to "remind_at",
        )

        private const val COMMENT_SELECT = """
            SELECT c.id, c.task_id, c.author_id, c.client_op_id, c.body,
                   c.created_at, c.edited_at, u.display_name AS author_name
            FROM task_comments c
            JOIN users u ON u.id = c.author_id
        """

        fun versionsJson(versions: Map<String, Long>): Json =
            Json(ApiJson.encodeToString(JsonObject.serializer(), JsonObject(versions.mapValues { JsonPrimitive(it.value) })))

        fun parseVersions(raw: String?): Map<String, Long> {
            if (raw.isNullOrBlank()) return emptyMap()
            return runCatching {
                ApiJson.parseToJsonElement(raw).jsonObject.mapNotNull { (key, value) ->
                    (value as? JsonPrimitive)?.longOrNull?.let { key to it }
                }.toMap()
            }.getOrDefault(emptyMap())
        }
    }
}

enum class TaskScope { MINE, TEAM, CHAT, FROM_CHATS }
