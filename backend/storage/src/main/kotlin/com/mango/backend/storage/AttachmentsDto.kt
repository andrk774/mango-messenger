@file:UseSerializers(UuidSerializer::class, InstantSerializer::class)

package com.mango.backend.storage

import com.mango.backend.common.InstantSerializer
import com.mango.backend.common.UuidSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.util.UUID

@Serializable
data class UploadTicketRequest(
    /** Идентификатор от клиента — ключ идемпотентности: повтор вернёт то же разрешение. */
    val attachmentId: UUID,
    val conversationId: UUID,
    val mimeType: String,
    val sizeBytes: Long,
    /** SHA-256 содержимого, 64 шестнадцатеричных символа. Сверяется после загрузки. */
    val checksumSha256: String,
    val fileName: String = "",
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Int? = null,
)

@Serializable
data class UploadTicketResponse(
    val attachmentId: UUID,
    /**
     * Куда отправить содержимое методом PUT.
     *
     * Может быть относительной (от адреса API) или абсолютной —
     * клиент обязан поддерживать оба вида: при переходе на внешнее
     * хранилище ссылка станет абсолютной.
     */
    val uploadUrl: String,
    val expiresAt: Instant,
    /** Файл уже загружен этим же разрешением раньше — загружать повторно не нужно. */
    val uploaded: Boolean,
)

@Serializable
data class DownloadUrlResponse(
    val url: String,
    val expiresAt: Instant,
    val mimeType: String,
    val sizeBytes: Long,
    val fileName: String,
)
