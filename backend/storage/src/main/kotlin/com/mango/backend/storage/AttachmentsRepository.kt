package com.mango.backend.storage

import com.mango.backend.common.instantOrNull
import com.mango.backend.common.queryOne
import com.mango.backend.common.update
import com.mango.backend.common.uuid
import com.mango.backend.common.uuidOrNull
import java.sql.Connection
import java.time.Instant
import java.util.UUID

data class AttachmentRecord(
    val id: UUID,
    val ownerId: UUID,
    val conversationId: UUID?,
    val messageId: UUID?,
    val storageKey: String,
    val mimeType: String,
    val sizeBytes: Long,
    val fileName: String,
    val checksumSha256: String?,
    val scanStatus: String,
    val deletedAt: Instant?,
)

class AttachmentsRepository {

    fun isMember(connection: Connection, conversationId: UUID, userId: UUID): Boolean =
        connection.queryOne(
            """
            SELECT 1 FROM conversation_members cm
            JOIN conversations c ON c.id = cm.conversation_id AND c.deleted_at IS NULL
            WHERE cm.conversation_id = ? AND cm.user_id = ? AND cm.left_at IS NULL
            """.trimIndent(),
            conversationId, userId,
        ) { true } ?: false

    fun find(connection: Connection, id: UUID): AttachmentRecord? = connection.queryOne(
        """
        SELECT id, owner_id, conversation_id, message_id, storage_key, mime_type,
               size_bytes, file_name, checksum_sha256, scan_status, deleted_at
        FROM attachments WHERE id = ?
        """.trimIndent(),
        id,
    ) { rs ->
        AttachmentRecord(
            id = rs.uuid("id"),
            ownerId = rs.uuid("owner_id"),
            conversationId = rs.uuidOrNull("conversation_id"),
            messageId = rs.uuidOrNull("message_id"),
            storageKey = rs.getString("storage_key"),
            mimeType = rs.getString("mime_type"),
            sizeBytes = rs.getLong("size_bytes"),
            fileName = rs.getString("file_name"),
            checksumSha256 = rs.getString("checksum_sha256"),
            scanStatus = rs.getString("scan_status"),
            deletedAt = rs.instantOrNull("deleted_at"),
        )
    }

    /** Висящие разрешения пользователя: выданы, но файл ни к чему не привязан. */
    fun pendingCount(connection: Connection, ownerId: UUID, since: Instant): Int =
        connection.queryOne(
            """
            SELECT count(*) AS n FROM attachments
            WHERE owner_id = ? AND created_at > ? AND message_id IS NULL AND deleted_at IS NULL
            """.trimIndent(),
            ownerId, since,
        ) { it.getInt("n") } ?: 0

    @Suppress("LongParameterList")
    fun insertPending(
        connection: Connection,
        id: UUID,
        ownerId: UUID,
        conversationId: UUID,
        storageKey: String,
        mimeType: String,
        sizeBytes: Long,
        fileName: String,
        width: Int?,
        height: Int?,
        durationMs: Int?,
        checksumSha256: String,
        now: Instant,
    ): Boolean = connection.update(
        """
        INSERT INTO attachments
            (id, owner_id, conversation_id, storage_key, mime_type, size_bytes, file_name,
             width, height, duration_ms, checksum_sha256, scan_status, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
        ON CONFLICT (id) DO NOTHING
        """.trimIndent(),
        id, ownerId, conversationId, storageKey, mimeType, sizeBytes, fileName,
        width, height, durationMs, checksumSha256, now,
    ) == 1

    fun markClean(connection: Connection, id: UUID, now: Instant): Int = connection.update(
        "UPDATE attachments SET scan_status = 'CLEAN', uploaded_at = ? WHERE id = ? AND scan_status = 'PENDING'",
        now, id,
    )

    fun markRejected(connection: Connection, id: UUID): Int = connection.update(
        "UPDATE attachments SET scan_status = 'REJECTED' WHERE id = ? AND scan_status = 'PENDING'",
        id,
    )

    /**
     * Может ли пользователь получить файл.
     *
     * Автор — всегда (он видит своё превью до отправки). Остальные —
     * только если файл уже в сообщении, сообщение не удалено и они
     * состоят в чате сейчас. Вышедший из группы теряет доступ и к файлам.
     */
    fun canRead(connection: Connection, id: UUID, userId: UUID): Boolean = connection.queryOne(
        """
        SELECT 1 FROM attachments a
        WHERE a.id = ? AND a.deleted_at IS NULL
          AND (
            a.owner_id = ?
            OR EXISTS (
                SELECT 1 FROM messages m
                JOIN conversation_members cm
                  ON cm.conversation_id = m.conversation_id AND cm.user_id = ? AND cm.left_at IS NULL
                WHERE m.id = a.message_id AND m.deleted_at IS NULL
            )
          )
        """.trimIndent(),
        id, userId, userId,
    ) { true } ?: false
}
