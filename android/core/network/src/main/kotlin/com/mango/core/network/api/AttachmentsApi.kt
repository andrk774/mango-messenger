package com.mango.core.network.api

import com.mango.core.network.dto.DownloadUrlDto
import com.mango.core.network.dto.UploadTicketDto
import com.mango.core.network.dto.UploadTicketRequestDto
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Streaming
import retrofit2.http.Url

/** Разрешения и ссылки — с токеном пользователя. */
interface AttachmentsApi {

    @POST("v1/attachments/upload-ticket")
    suspend fun uploadTicket(@Body body: UploadTicketRequestDto): Response<UploadTicketDto>

    @GET("v1/attachments/{id}/url")
    suspend fun downloadUrl(@Path("id") id: String): Response<DownloadUrlDto>
}

/**
 * Передача содержимого по подписанным ссылкам.
 *
 * Строится на клиенте БЕЗ подстановки токена: ссылка сама несёт право
 * доступа, а при переходе на внешнее хранилище (ADR-0019) токен нашего
 * API уходил бы постороннему сервису.
 */
interface BlobTransferApi {

    @PUT
    suspend fun upload(@Url url: String, @Body body: RequestBody): Response<Unit>

    @Streaming
    @GET
    suspend fun download(@Url url: String): Response<ResponseBody>
}
