package com.mango.backend.storage

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Подпись временных ссылок на загрузку и скачивание.
 *
 * Ссылка несёт срок действия и HMAC от (действие, вложение, срок).
 * Сервер не хранит выданные ссылки: проверка — пересчёт подписи.
 * Действие входит в подпись, поэтому ссылка на скачивание не годится
 * для загрузки и наоборот.
 *
 * Так же устроены presigned-ссылки S3; при переходе на S3 (ADR-0019)
 * этот класс заменяется подписью самого хранилища, а формат ответа
 * для клиента не меняется.
 */
class UrlSigner(key: String) {

    private val secret = SecretKeySpec(key.toByteArray(Charsets.UTF_8), ALGORITHM)

    fun sign(action: String, attachmentId: UUID, expiresAt: Instant): String {
        val mac = Mac.getInstance(ALGORITHM).apply { init(secret) }
        val data = "$action\n$attachmentId\n${expiresAt.epochSecond}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.toByteArray()))
    }

    /**
     * Проверка подписи и срока.
     *
     * Сравнение за постоянное время: побайтовое сравнение с ранним
     * выходом позволило бы подбирать подпись по времени ответа.
     */
    fun verify(
        action: String,
        attachmentId: UUID,
        expiresEpochSecond: Long,
        signature: String,
        now: Instant,
    ): Boolean {
        if (now.epochSecond > expiresEpochSecond) return false
        val expected = sign(action, attachmentId, Instant.ofEpochSecond(expiresEpochSecond))
        return MessageDigest.isEqual(expected.toByteArray(), signature.toByteArray())
    }

    companion object {
        const val UPLOAD = "upload"
        const val DOWNLOAD = "download"
        private const val ALGORITHM = "HmacSHA256"
    }
}
