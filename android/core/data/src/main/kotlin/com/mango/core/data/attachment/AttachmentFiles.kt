package com.mango.core.data.attachment

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Файл, подготовленный к отправке. */
data class ImportedFile(
    val file: File,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val fileName: String,
    val width: Int?,
    val height: Int?,
)

/** Почему файл нельзя отправить — показывается пользователю как есть. */
class AttachmentRejected(message: String) : Exception(message)

/**
 * Ограничения на вложения на стороне клиента.
 *
 * Повторяют серверные (AttachmentPolicy), чтобы отказ был виден сразу,
 * а не после загрузки ста мегабайт. Решает всё равно сервер: этот список
 * — удобство, а не защита.
 */
object ClientAttachmentPolicy {
    const val IMAGE_MAX_BYTES = 20L * 1024 * 1024
    const val FILE_MAX_BYTES = 100L * 1024 * 1024

    val allowedTypes = setOf(
        "image/jpeg", "image/png", "image/gif", "image/webp", "image/heic",
        "video/mp4", "video/webm",
        "audio/ogg", "audio/mpeg", "audio/mp4", "audio/aac", "audio/webm",
        "application/pdf", "application/zip",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "text/plain",
    )

    fun maxBytesFor(mimeType: String): Long =
        if (mimeType.startsWith("image/")) IMAGE_MAX_BYTES else FILE_MAX_BYTES
}

/**
 * Файлы вложений на устройстве.
 *
 * Исходящий файл копируется в личный каталог приложения ДО постановки
 * в очередь: разрешение на чтение выбранного пользователем файла
 * (content://) действует, пока жив процесс, а очередь должна дослать
 * файл и после перезапуска.
 */
@Singleton
class AttachmentFileStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val outgoingDir: File get() = File(context.filesDir, "attachments/outgoing")
    private val downloadsDir: File get() = File(context.cacheDir, "attachments")

    /**
     * Копирует выбранный файл, попутно считая размер и SHA-256.
     *
     * Хеш считается при копировании, а не вторым проходом: большой файл
     * читается один раз.
     */
    fun import(uri: Uri, attachmentId: String): ImportedFile {
        val resolver = context.contentResolver
        val (displayName, declaredSize) = resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                (if (nameIndex >= 0) cursor.getString(nameIndex) else null) to
                    (if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null)
            } else {
                null to null
            }
        } ?: (null to null)

        val mimeType = normalizeAttachmentMime(displayName, resolver.getType(uri) ?: guessMime(displayName))
            ?: throw AttachmentRejected("Не удалось определить тип файла")
        if (mimeType !in ClientAttachmentPolicy.allowedTypes) {
            throw AttachmentRejected("Такой тип файла отправить нельзя")
        }
        val limit = ClientAttachmentPolicy.maxBytesFor(mimeType)
        if (declaredSize != null && declaredSize > limit) {
            throw AttachmentRejected("Файл больше ${limit / (1024 * 1024)} МБ")
        }

        outgoingDir.mkdirs()
        val target = File(outgoingDir, attachmentId)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("файл недоступен")
            input.use { source ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > limit) throw AttachmentRejected("Файл больше ${limit / (1024 * 1024)} МБ")
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        if (total == 0L) {
            target.delete()
            throw AttachmentRejected("Файл пуст")
        }

        val (width, height) = if (mimeType.startsWith("image/")) imageSize(target) else (null to null)

        return ImportedFile(
            file = target,
            mimeType = mimeType,
            sizeBytes = total,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            fileName = displayName?.takeIf { it.isNotBlank() } ?: "file",
            width = width,
            height = height,
        )
    }

    /** Куда сохранять скачанный файл. Имя — как у отправителя, каталог — по вложению. */
    fun downloadTarget(attachmentId: String, fileName: String): File {
        val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").ifBlank { "file" }
        return File(File(downloadsDir, attachmentId), safeName)
    }

    /** Размеры картинки без декодирования пикселей — только заголовок. */
    private fun imageSize(file: File): Pair<Int?, Int?> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return options.outWidth.takeIf { it > 0 } to options.outHeight.takeIf { it > 0 }
    }

    private fun guessMime(name: String?): String? =
        name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
}

internal fun normalizeAttachmentMime(name: String?, declared: String?): String? {
    val type = declared?.lowercase()
    // Some Android providers report MPEG-4 audio (.m4a) as MPEG audio (.mp3).
    // The server still validates the actual file signature.
    return if (name?.endsWith(".m4a", ignoreCase = true) == true &&
        type in listOf(null, "audio/mpeg", "audio/x-m4a", "audio/mp4")) "audio/mp4" else type
}
