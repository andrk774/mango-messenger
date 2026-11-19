package com.mango.backend.common

import java.sql.Connection
import java.util.UUID

fun Connection.isBlocked(userId: UUID): Boolean =
    queryOne("SELECT 1 FROM user_blocks WHERE user_id = ?", userId) { true } ?: false

/** Rotation keeps the preceding access token valid; explicit revocation does not. */
fun Connection.accountSessionAllowed(userId: UUID, sessionId: UUID): Boolean =
    queryOne("""
        SELECT 1 FROM users u JOIN sessions s ON s.user_id=u.id
        WHERE u.id=? AND s.id=? AND u.deleted_at IS NULL
          AND (s.revoked_at IS NULL OR s.revoked_reason='ROTATED')
          AND NOT EXISTS (SELECT 1 FROM user_blocks b WHERE b.user_id=u.id)
    """.trimIndent(), userId, sessionId) { true } ?: false
