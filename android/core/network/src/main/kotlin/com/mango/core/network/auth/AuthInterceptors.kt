package com.mango.core.network.auth

import com.mango.core.common.Logger
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject

/**
 * Подстановка токена доступа.
 *
 * Заголовок не добавляется к запросам, которые по смыслу выполняются без
 * сессии: иначе просроченный токен ломал бы вход и регистрацию.
 */
class AuthInterceptor @Inject constructor(
    private val sessionManager: SessionManager,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (request.header(HEADER) != null || request.isPublicEndpoint()) {
            return chain.proceed(request)
        }

        val token = sessionManager.currentAccessToken()
            ?: return chain.proceed(request)

        return chain.proceed(
            request.newBuilder().header(HEADER, "Bearer $token").build(),
        )
    }

    private fun Request.isPublicEndpoint(): Boolean {
        val path = url.encodedPath
        return PUBLIC_PATHS.any { path.endsWith(it) }
    }

    private companion object {
        const val HEADER = "Authorization"

        val PUBLIC_PATHS = listOf(
            "/v1/auth/register",
            "/v1/auth/login",
            "/v1/auth/refresh",
            "/v1/auth/verify-email",
            "/v1/auth/password/forgot",
            "/v1/auth/password/reset",
            "/v1/health",
        )
    }
}

/**
 * Прозрачное обновление токена при ответе 401.
 *
 * OkHttp вызывает этот механизм ровно тогда, когда сервер отверг запрос
 * из-за учётных данных, поэтому обновление происходит по факту отказа,
 * а не по часам клиента — расхождение времени на устройстве не создаёт
 * ни лишних обменов, ни пропущенных.
 */
class TokenAuthenticator @Inject constructor(
    private val sessionManager: SessionManager,
    private val logger: Logger,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // Защита от зацикливания: если запрос уже повторялся с новым
        // токеном и снова получил 401, обновление не помогает.
        if (response.priorResponseCount() >= 1) {
            logger.i(TAG, "повторный отказ после обновления токена, требуется вход")
            return null
        }

        // Authenticator синхронен по контракту OkHttp, поэтому ожидание
        // здесь неизбежно. Вызов выполняется в потоке OkHttp, а не в
        // главном, и защищён замком от параллельных обновлений.
        val refreshed = runBlocking { sessionManager.refresh() }
        if (!refreshed) return null

        val token = sessionManager.currentAccessToken() ?: return null

        return response.request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
    }

    private fun Response.priorResponseCount(): Int {
        var count = 0
        var current = priorResponse
        while (current != null) {
            count++
            current = current.priorResponse
        }
        return count
    }

    private companion object {
        const val TAG = "TokenAuthenticator"
    }
}
