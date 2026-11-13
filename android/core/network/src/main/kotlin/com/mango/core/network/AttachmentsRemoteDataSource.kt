package com.mango.core.network

import com.mango.core.common.AppError
import com.mango.core.common.Outcome
import com.mango.core.common.map
import com.mango.core.network.api.AttachmentsApi
import com.mango.core.network.api.BlobTransferApi
import com.mango.core.network.dto.DownloadUrlDto
import com.mango.core.network.dto.UploadTicketDto
import com.mango.core.network.dto.UploadTicketRequestDto
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AttachmentsRemoteDataSource @Inject constructor(
    private val api: AttachmentsApi,
    private val transfer: BlobTransferApi,
) {

    suspend fun uploadTicket(request: UploadTicketRequestDto): Outcome<UploadTicketDto> =
        apiCall { api.uploadTicket(request) }

    suspend fun downloadUrl(attachmentId: String): Outcome<DownloadUrlDto> =
        apiCall { api.downloadUrl(attachmentId) }

    /**
     * Отправка файла по ссылке из разрешения.
     *
     * Отказ 403 на этом шаге означает истёкшую ссылку (загрузка шла
     * дольше срока её действия), а не отсутствие прав: права проверены
     * при выдаче разрешения. Поэтому он переводится в повторяемую
     * ошибку — следующая попытка возьмёт новое разрешение.
     */
    suspend fun upload(
        url: String,
        file: File,
        mimeType: String,
        onProgress: (Float) -> Unit = {},
    ): Outcome<Unit> {
        val body = ProgressFileBody(file, mimeType.toMediaTypeOrNull(), onProgress)
        return when (val result = apiCall { transfer.upload(url, body) }.map { }) {
            is Outcome.Failure ->
                if (result.error == AppError.Forbidden) Outcome.Failure(AppError.Timeout()) else result
            is Outcome.Success -> result
        }
    }

    /** Скачивание в файл. Пишется во временный, затем переименовывается. */
    suspend fun download(url: String, target: File): Outcome<File> =
        apiCall { transfer.download(url) }.let { outcome ->
            when (outcome) {
                is Outcome.Failure -> outcome
                is Outcome.Success -> try {
                    target.parentFile?.mkdirs()
                    val temp = File(target.parentFile, "${target.name}.part")
                    outcome.value.byteStream().use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (!temp.renameTo(target)) throw IOException("не удалось сохранить файл")
                    Outcome.Success(target)
                } catch (e: IOException) {
                    Outcome.Failure(AppError.Network(e))
                }
            }
        }
}

/**
 * Тело запроса из файла с отчётом о прогрессе.
 *
 * Файл читается потоком: большое видео не должно целиком попадать
 * в память. Прогресс сообщается не чаще чем раз в процент, иначе
 * запись в базу на каждый блок замедлила бы саму передачу.
 */
private class ProgressFileBody(
    private val file: File,
    private val mediaType: MediaType?,
    private val onProgress: (Float) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength().coerceAtLeast(1)
        var sent = 0L
        var lastReported = -1
        file.source().use { source ->
            while (true) {
                val read = source.read(sink.buffer, SEGMENT)
                if (read == -1L) break
                sink.flush()
                sent += read
                val percent = (sent * 100 / total).toInt()
                if (percent != lastReported) {
                    lastReported = percent
                    onProgress(sent.toFloat() / total)
                }
            }
        }
    }

    private companion object {
        const val SEGMENT = 64L * 1024
    }
}
