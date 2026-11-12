package com.mango.backend.messaging

import com.mango.backend.common.instant
import com.mango.backend.common.instantOrNull
import com.mango.backend.common.longOrNull
import com.mango.backend.common.query
import com.mango.backend.common.queryOne
import com.mango.backend.common.update
import com.mango.backend.common.uuid
import com.mango.backend.common.uuidOrNull
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class MembershipRecord(
    val role: String,
    val lastReadSeq: Long,
    val joinedAt: Instant,
) {
    val canManageMembers: Boolean get() = role == "OWNER" || role == "ADMIN"
}

/**
 * Доступ к данным чатов и сообщений.
 *
 * Каждый запрос, читающий чужие данные, содержит условие принадлежности
 * к чату. Защита от доступа по подобранному идентификатору строится
 * именно на этом, а не на непредсказуемости UUID.
 */
class MessagingRepository {

    // ─────────────────────────── участие ───────────────────────────

    /**
     * Проверка принадлежности к чату.
     *
     * Вызывается перед КАЖДОЙ операцией с сообщениями. Возвращает `null`,
     * если пользователь не состоит в чате или вышел из него.
     */
    fun membership(connection: Connection, conversationId: UUID, userId: UUID): MembershipRecord? =
        connection.queryOne(
            """
            SELECT role, last_read_seq, joined_at
            FROM conversation_members
            WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL
            """.trimIndent(),
            conversationId, userId,
        ) { rs ->
            MembershipRecord(
                role = rs.getString("role"),
                lastReadSeq = rs.getLong("last_read_seq"),
                joinedAt = rs.instant("joined_at"),
            )
        }

    /** Идентификаторы активных участников — получатели событий. */
    fun memberIds(connection: Connection, conversationId: UUID): List<UUID> =
        connection.query(
            """
            SELECT user_id FROM conversation_members
            WHERE conversation_id = ? AND left_at IS NULL
            """.trimIndent(),
            conversationId,
        ) { it.uuid("user_id") }

    fun members(connection: Connection, conversationId: UUID): List<MemberDto> =
        connection.query(
            """
            SELECT cm.user_id, cm.role, cm.joined_at, cm.last_read_seq,
                   u.username, u.display_name, u.avatar_url
            FROM conversation_members cm
            JOIN users u ON u.id = cm.user_id
            WHERE cm.conversation_id = ? AND cm.left_at IS NULL
            ORDER BY
                CASE cm.role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END,
                u.display_name
            """.trimIndent(),
            conversationId,
        ) { rs ->
            MemberDto(
                userId = rs.uuid("user_id"),
                username = rs.getString("username"),
                displayName = rs.getString("display_name"),
                avatarUrl = rs.getString("avatar_url"),
                role = rs.getString("role"),
                joinedAt = rs.instant("joined_at"),
                lastReadSeq = rs.getLong("last_read_seq"),
            )
        }

    fun usersExist(connection: Connection, ids: Collection<UUID>): Set<UUID> {
        if (ids.isEmpty()) return emptySet()
        val placeholders = ids.joinToString(", ") { "?" }
        return connection.query(
            "SELECT id FROM users WHERE deleted_at IS NULL AND id IN ($placeholders)",
            *ids.toTypedArray(),
        ) { it.uuid("id") }.toSet()
    }

    // ─────────────────────────── чаты ───────────────────────────

    /**
     * Создание чата.
     *
     * Для DIRECT используется `ON CONFLICT DO NOTHING`: повторная попытка
     * открыть переписку с тем же человеком не должна создавать второй чат,
     * а конфликт здесь — штатный исход, а не ошибка (исключение прервало бы
     * транзакцию).
     */
    fun insertConversation(
        connection: Connection,
        id: UUID,
        type: String,
        title: String?,
        createdBy: UUID,
        directKey: String?,
        now: Instant,
    ): UUID? = connection.queryOne(
        """
        INSERT INTO conversations (id, type, title, created_by, direct_key, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (direct_key) WHERE type = 'DIRECT' AND deleted_at IS NULL DO NOTHING
        RETURNING id
        """.trimIndent(),
        id, type, title, createdBy, directKey, now, now,
    ) { it.uuid("id") }

    fun findByDirectKey(connection: Connection, directKey: String): UUID? =
        connection.queryOne(
            """
            SELECT id FROM conversations
            WHERE direct_key = ? AND type = 'DIRECT' AND deleted_at IS NULL
            """.trimIndent(),
            directKey,
        ) { it.uuid("id") }

    fun insertMember(
        connection: Connection,
        conversationId: UUID,
        userId: UUID,
        role: String,
        now: Instant,
    ) = connection.update(
        """
        INSERT INTO conversation_members (conversation_id, user_id, role, joined_at)
        VALUES (?, ?, ?, ?)
        ON CONFLICT (conversation_id, user_id)
        DO UPDATE SET left_at = NULL, role = EXCLUDED.role
        """.trimIndent(),
        conversationId, userId, role, now,
    )

    fun conversationsFor(connection: Connection, userId: UUID, limit: Int): List<ConversationDto> =
        connection.query(
            CONVERSATION_SELECT +
                " WHERE cm.user_id = ? AND cm.left_at IS NULL" +
                " ORDER BY COALESCE(m.created_at, c.updated_at) DESC LIMIT ?",
            userId, limit,
        ) { mapConversation(it) }

    fun conversationFor(connection: Connection, userId: UUID, id: UUID): ConversationDto? =
        connection.queryOne(
            CONVERSATION_SELECT +
                " WHERE cm.user_id = ? AND cm.left_at IS NULL AND c.id = ?",
            userId, id,
        ) { mapConversation(it) }

    fun touchConversation(
        connection: Connection,
        conversationId: UUID,
        lastMessageId: UUID?,
        now: Instant,
    ) = connection.update(
        "UPDATE conversations SET last_message_id = ?, updated_at = ? WHERE id = ?",
        lastMessageId, now, conversationId,
    )

    // ───────────────────────── сообщения ─────────────────────────

    /**
     * Блокировка чата на время выдачи порядкового номера.
     *
     * `SELECT ... FOR UPDATE` сериализует одновременные отправки в один чат.
     * Это обязательное условие корректности: без блокировки два запроса
     * могли бы получить одинаковый `seq`, а уникальный индекс превратил бы
     * это в отказ вместо упорядочивания.
     *
     * Блокировка берётся ДО проверки на дубликат, поэтому повторная
     * доставка одной и той же операции увидит уже зафиксированное
     * сообщение и не создаст второе.
     */
    fun lockConversation(connection: Connection, conversationId: UUID): Boolean =
        connection.queryOne(
            "SELECT id FROM conversations WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
            conversationId,
        ) { true } ?: false

    fun findByClientMsgId(
        connection: Connection,
        conversationId: UUID,
        senderId: UUID,
        clientMsgId: String,
    ): MessageDto? = connection.queryOne(
        "$MESSAGE_SELECT WHERE m.conversation_id = ? AND m.sender_id = ? AND m.client_msg_id = ?",
        conversationId, senderId, clientMsgId,
    ) { mapMessage(it) }

    /**
     * Выдаёт следующий порядковый номер.
     *
     * Счётчик живёт в строке чата, поэтому инкремент атомарен и защищён
     * той же блокировкой, что взята в [lockConversation].
     */
    fun nextSeq(connection: Connection, conversationId: UUID): Long =
        connection.queryOne(
            """
            UPDATE conversations SET last_event_seq = last_event_seq + 1
            WHERE id = ?
            RETURNING last_event_seq
            """.trimIndent(),
            conversationId,
        ) { it.getLong("last_event_seq") }
            ?: error("Чат $conversationId исчез между блокировкой и выдачей номера")

    fun insertMessage(
        connection: Connection,
        id: UUID,
        conversationId: UUID,
        senderId: UUID,
        clientMsgId: String,
        seq: Long,
        type: String,
        body: String?,
        replyToId: UUID?,
        now: Instant,
    ) = connection.update(
        """
        INSERT INTO messages
            (id, conversation_id, sender_id, client_msg_id, seq, type, body, reply_to_id, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        id, conversationId, senderId, clientMsgId, seq, type, body, replyToId, now,
    )

    fun messageById(connection: Connection, id: UUID): MessageDto? =
        connection.queryOne("$MESSAGE_SELECT WHERE m.id = ?", id) { mapMessage(it) }

    /**
     * Страница истории.
     *
     * Пагинация по `seq`, без OFFSET: смещение заставляет базу
     * пересчитывать пропускаемые строки, и чем глубже уходит прокрутка,
     * тем дороже становится запрос.
     */
    fun history(
        connection: Connection,
        conversationId: UUID,
        beforeSeq: Long?,
        afterSeq: Long?,
        limit: Int,
    ): List<MessageDto> = when {
        // Догрузка вперёд — заполнение разрыва в истории клиента.
        afterSeq != null -> connection.query(
            "$MESSAGE_SELECT WHERE m.conversation_id = ? AND m.seq > ? ORDER BY m.seq ASC LIMIT ?",
            conversationId, afterSeq, limit,
        ) { mapMessage(it) }

        // Обычная прокрутка назад.
        beforeSeq != null -> connection.query(
            "$MESSAGE_SELECT WHERE m.conversation_id = ? AND m.seq < ? ORDER BY m.seq DESC LIMIT ?",
            conversationId, beforeSeq, limit,
        ) { mapMessage(it) }

        else -> connection.query(
            "$MESSAGE_SELECT WHERE m.conversation_id = ? ORDER BY m.seq DESC LIMIT ?",
            conversationId, limit,
        ) { mapMessage(it) }
    }

    fun editMessage(connection: Connection, id: UUID, body: String, now: Instant): Int =
        connection.update(
            "UPDATE messages SET body = ?, edited_at = ? WHERE id = ? AND deleted_at IS NULL",
            body, now, id,
        )

    /**
     * Удаление сообщения.
     *
     * Строка сохраняется: на неё могут ссылаться ответы и задачи,
     * а порядковый номер обязан остаться занятым, иначе в нумерации
     * появится дыра и клиент будет считать, что пропустил сообщение.
     */
    fun deleteMessage(connection: Connection, id: UUID, now: Instant): Int =
        connection.update(
            "UPDATE messages SET deleted_at = ?, body = NULL WHERE id = ? AND deleted_at IS NULL",
            now, id,
        )

    // ──────────────────── отметка о прочтении ────────────────────

    /**
     * Продвижение отметки о прочтении.
     *
     * Значение только растёт: запоздавший запрос с меньшим номером
     * не должен «откатывать» прочитанное назад.
     */
    fun advanceReadMarker(
        connection: Connection,
        conversationId: UUID,
        userId: UUID,
        seq: Long,
    ): Long = connection.queryOne(
        """
        UPDATE conversation_members
        SET last_read_seq = GREATEST(last_read_seq, ?)
        WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL
        RETURNING last_read_seq
        """.trimIndent(),
        seq, conversationId, userId,
    ) { it.getLong("last_read_seq") } ?: 0L

    // ───────────────────────── пользователи ─────────────────────────

    fun searchUsers(
        connection: Connection,
        query: String,
        excludeUserId: UUID,
        limit: Int,
    ): List<UserSummaryDto> = connection.query(
        """
        SELECT id, username, display_name, avatar_url
        FROM users
        WHERE deleted_at IS NULL
          AND id <> ?
          AND (username ILIKE ? OR display_name ILIKE ?)
        ORDER BY display_name
        LIMIT ?
        """.trimIndent(),
        excludeUserId, "%$query%", "%$query%", limit,
    ) { rs ->
        UserSummaryDto(
            id = rs.uuid("id"),
            username = rs.getString("username"),
            displayName = rs.getString("display_name"),
            avatarUrl = rs.getString("avatar_url"),
        )
    }

    // ──────────────────────── отображение ────────────────────────

    private fun mapMessage(rs: ResultSet): MessageDto {
        val replyToId = rs.uuidOrNull("reply_to_id")
        return MessageDto(
            id = rs.uuid("id"),
            conversationId = rs.uuid("conversation_id"),
            senderId = rs.uuid("sender_id"),
            senderName = rs.getString("sender_name") ?: "",
            clientMsgId = rs.getString("client_msg_id"),
            seq = rs.getLong("seq"),
            type = rs.getString("type"),
            body = rs.getString("body"),
            replyTo = replyToId?.let {
                val snapshot = rs.getString("reply_body")
                val replyDeleted = rs.instantOrNull("reply_deleted_at") != null
                MessageReferenceDto(
                    messageId = it,
                    senderName = rs.getString("reply_sender_name") ?: "",
                    textSnapshot = snapshot,
                    // Исходное сообщение удалено или недоступно: переход
                    // к нему блокируется, но сам ответ остаётся читаемым.
                    isUnavailable = replyDeleted || snapshot == null,
                )
            },
            editedAt = rs.instantOrNull("edited_at"),
            deletedAt = rs.instantOrNull("deleted_at"),
            pinnedAt = rs.instantOrNull("pinned_at"),
            createdAt = rs.instant("created_at"),
        )
    }

    private fun mapConversation(rs: ResultSet): ConversationDto {
        val type = rs.getString("type")
        val storedTitle = rs.getString("title")
        val counterpartName = rs.getString("other_name")

        val messageId = rs.uuidOrNull("msg_id")
        val lastMessage = messageId?.let {
            MessageDto(
                id = it,
                conversationId = rs.uuid("id"),
                senderId = rs.uuid("msg_sender_id"),
                senderName = rs.getString("msg_sender_name") ?: "",
                clientMsgId = rs.getString("msg_client_msg_id"),
                seq = rs.getLong("msg_seq"),
                type = rs.getString("msg_type"),
                body = rs.getString("msg_body"),
                replyTo = null,
                editedAt = rs.instantOrNull("msg_edited_at"),
                deletedAt = rs.instantOrNull("msg_deleted_at"),
                pinnedAt = rs.instantOrNull("msg_pinned_at"),
                createdAt = rs.instant("msg_created_at"),
            )
        }

        return ConversationDto(
            id = rs.uuid("id"),
            type = type,
            // У личного чата нет собственного названия: показывается имя
            // собеседника. Подстановка выполняется на сервере, чтобы
            // клиенты не повторяли одну и ту же логику каждый свою.
            title = when {
                type == "DIRECT" -> counterpartName ?: "Личный чат"
                else -> storedTitle ?: "Без названия"
            },
            avatarUrl = if (type == "DIRECT") rs.getString("other_avatar") else rs.getString("avatar_url"),
            memberCount = rs.getInt("member_count"),
            myRole = rs.getString("my_role"),
            lastEventSeq = rs.getLong("last_event_seq"),
            lastReadSeq = rs.getLong("last_read_seq"),
            unreadCount = rs.getInt("unread_count"),
            mutedUntil = rs.instantOrNull("muted_until"),
            lastMessage = lastMessage,
            createdAt = rs.instant("created_at"),
            updatedAt = rs.instant("updated_at"),
        )
    }

    private companion object {

        /**
         * Выборка сообщения вместе с автором и снимком сообщения-ответа.
         *
         * Соединения выполняются в одном запросе: иначе список из полусотни
         * сообщений порождал бы сотню дополнительных обращений к базе.
         */
        const val MESSAGE_SELECT = """
            SELECT m.id, m.conversation_id, m.sender_id, m.client_msg_id, m.seq,
                   m.type, m.body, m.reply_to_id, m.edited_at, m.deleted_at,
                   m.pinned_at, m.created_at,
                   sender.display_name AS sender_name,
                   reply.body         AS reply_body,
                   reply.deleted_at   AS reply_deleted_at,
                   reply_sender.display_name AS reply_sender_name
            FROM messages m
            JOIN users sender ON sender.id = m.sender_id
            LEFT JOIN messages reply ON reply.id = m.reply_to_id
            LEFT JOIN users reply_sender ON reply_sender.id = reply.sender_id
        """

        /**
         * Список чатов одним запросом.
         *
         * Последнее сообщение берётся через LATERAL: это один проход по
         * индексу `(conversation_id, seq DESC)` на каждый чат вместо
         * отдельного запроса из кода.
         */
        /**
         * Общая часть выборки чатов: условия WHERE и порядок дописываются
         * вызывающим. Склейка строк вместо подмены по образцу — чтобы
         * изменение одного запроса не ломало молча другой.
         *
         * Последнее сообщение берётся через LATERAL: это один проход по
         * индексу `(conversation_id, seq DESC)` на каждый чат вместо
         * отдельного запроса из кода.
         */
        const val CONVERSATION_SELECT = """
            SELECT
                c.id, c.type, c.title, c.avatar_url, c.last_event_seq,
                c.created_at, c.updated_at,
                cm.role AS my_role, cm.last_read_seq, cm.muted_until,
                (SELECT count(*) FROM conversation_members x
                  WHERE x.conversation_id = c.id AND x.left_at IS NULL) AS member_count,
                other.display_name AS other_name,
                other.avatar_url   AS other_avatar,
                m.id            AS msg_id,
                m.sender_id     AS msg_sender_id,
                m.client_msg_id AS msg_client_msg_id,
                m.seq           AS msg_seq,
                m.type          AS msg_type,
                m.body          AS msg_body,
                m.edited_at     AS msg_edited_at,
                m.deleted_at    AS msg_deleted_at,
                m.pinned_at     AS msg_pinned_at,
                m.created_at    AS msg_created_at,
                sender.display_name AS msg_sender_name,
                (SELECT count(*) FROM messages um
                  WHERE um.conversation_id = c.id
                    AND um.seq > cm.last_read_seq
                    AND um.deleted_at IS NULL
                    AND um.sender_id <> cm.user_id) AS unread_count
            FROM conversation_members cm
            JOIN conversations c ON c.id = cm.conversation_id AND c.deleted_at IS NULL
            LEFT JOIN LATERAL (
                SELECT u.display_name, u.avatar_url
                FROM conversation_members ocm
                JOIN users u ON u.id = ocm.user_id
                WHERE ocm.conversation_id = c.id
                  AND ocm.user_id <> cm.user_id
                  AND ocm.left_at IS NULL
                LIMIT 1
            ) other ON c.type = 'DIRECT'
            LEFT JOIN LATERAL (
                SELECT * FROM messages
                WHERE conversation_id = c.id
                ORDER BY seq DESC
                LIMIT 1
            ) m ON TRUE
            LEFT JOIN users sender ON sender.id = m.sender_id
        """
    }
}
