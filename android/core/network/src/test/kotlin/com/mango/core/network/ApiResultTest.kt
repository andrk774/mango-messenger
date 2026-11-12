package com.mango.core.network

import com.mango.core.common.AppError
import com.mango.core.common.Outcome
import com.mango.core.network.dto.MessageDto
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Перевод ответов сервера в доменные ошибки.
 *
 * Эти проверки удерживают соответствие кодов состояния и реакции
 * приложения. Ошибка здесь не падает заметно: запрос просто ведёт себя
 * не так, как ожидает экран, — например, отказ в доступе выглядит как
 * сбой сети и бесконечно повторяется.
 */
class ApiResultTest {

    @Test
    fun `успешный ответ возвращает тело`() {
        val outcome = Response.success("данные").toOutcome()
        assertEquals("данные", (outcome as Outcome.Success).value)
    }

    @Test
    fun `ответ без содержимого считается успехом`() {
        val outcome: Outcome<Unit> = Response.success<Unit>(204, null).toOutcome()
        assertIs<Outcome.Success<Unit>>(outcome)
    }

    @Test
    fun `код 401 означает необходимость входа`() {
        assertIs<AppError.Unauthorized>(errorFor(401))
    }

    @Test
    fun `код 403 не должен повторяться`() {
        val error = errorFor(403)
        assertIs<AppError.Forbidden>(error)
        assertTrue(
            !error.isRetryable,
            "Повтор запрещённой операции бессмысленен и выглядит как перебор",
        )
    }

    @Test
    fun `код 422 возвращает ошибки по полям`() {
        val error = errorFor(
            422,
            """{"code":"VALIDATION_FAILED","message":"Данные не приняты",
                "fieldErrors":{"email":"Некорректный адрес"}}""",
        )
        val validation = assertIs<AppError.Validation>(error)
        assertEquals("Некорректный адрес", validation.fieldErrors["email"])
    }

    @Test
    fun `код 429 сохраняет подсказку сервера о задержке`() {
        val response = Response.error<Unit>(
            """{"code":"RATE_LIMITED"}""".toResponseBody("application/json".toMediaType()),
            okhttp3.Response.Builder()
                .code(429)
                .message("Too Many Requests")
                .protocol(Protocol.HTTP_1_1)
                .header("Retry-After", "42")
                .request(Request.Builder().url("http://localhost/v1/auth/login").build())
                .build(),
        )

        val error = assertIs<AppError.RateLimited>(response.toAppError())
        // В kotlin.test порядок аргументов — ожидаемое, фактическое, пояснение.
        assertEquals(
            42L,
            error.retryAfterSeconds,
            "Подсказка сервера приоритетнее собственной формулы задержки",
        )
    }

    @Test
    fun `код 410 помечается как устаревший курсор`() {
        val error = errorFor(410, """{"code":"CURSOR_TOO_OLD","message":"Курсор устарел"}""")
        val conflict = assertIs<AppError.Conflict>(error)
        assertTrue(
            CURSOR_TOO_OLD in conflict.conflictingFields,
            "Движок синхронизации опознаёт этот случай по признаку: ${conflict.conflictingFields}",
        )
    }

    @Test
    fun `ошибки сервера повторяются`() {
        val error = errorFor(503)
        assertIs<AppError.Server>(error)
        assertTrue(error.isRetryable)
    }

    @Test
    fun `нечитаемое тело ошибки не ломает разбор`() {
        val error = errorFor(500, "это не json")
        assertIs<AppError.Server>(error)
    }

    @Test
    fun `исключения сети переводятся в доменные ошибки`() = runTest {
        assertIs<AppError.Network>(
            (apiCall<Unit> { throw UnknownHostException("нет DNS") } as Outcome.Failure).error,
        )
        assertIs<AppError.Timeout>(
            (apiCall<Unit> { throw SocketTimeoutException() } as Outcome.Failure).error,
        )
        assertIs<AppError.Network>(
            (apiCall<Unit> { throw IOException("обрыв") } as Outcome.Failure).error,
        )
    }

    @Test
    fun `сетевая ошибка подлежит повтору`() = runTest {
        val outcome = apiCall<Unit> { throw IOException("обрыв") }
        val error = (outcome as Outcome.Failure).error
        assertTrue(
            error.isRetryable,
            "Иначе сообщение, написанное в метро, потерялось бы",
        )
    }

    @Test
    fun `неизвестные поля в ответе не ломают разбор`() {
        // Сервер может добавить поле раньше, чем обновится приложение.
        val json = """
            {"id":"m1","conversationId":"c1","senderId":"u1","senderName":"Иван",
             "clientMsgId":"op1","seq":1,"type":"TEXT","body":"Привет",
             "createdAt":"2026-11-12T10:00:00Z","совершенноНовоеПоле":42}
        """.trimIndent()

        val message = NetworkJson.decodeFromString<MessageDto>(json)
        assertEquals("m1", message.id)
        assertEquals(1L, message.seq)
        assertNull(message.editedAt)
    }

    private fun errorFor(code: Int, body: String = """{"code":"X","message":"y"}"""): AppError =
        Response.error<Unit>(
            body.toResponseBody("application/json".toMediaType()),
            okhttp3.Response.Builder()
                .code(code)
                .message("error")
                .protocol(Protocol.HTTP_1_1)
                .request(Request.Builder().url("http://localhost/v1/test").build())
                .build(),
        ).toAppError()
}
