package com.mango.backend.auth

import com.mango.backend.common.AppException
import io.ktor.http.Cookie
import io.ktor.server.routing.RoutingContext
import java.time.Duration
import java.time.Instant
import java.net.URI

private const val COOKIE = "mango_browser_refresh"

/** JSON + non-simple header and same-origin checks; no credentialed CORS is enabled. */
internal fun RoutingContext.browserSession(): Boolean {
    if (call.request.headers["X-Mango-Browser"] != "1") return false
    val site = call.request.headers["Sec-Fetch-Site"]
    if (site != null && site !in setOf("same-origin", "none")) throw AppException.Forbidden("Same-origin browser session required")
    val origin = call.request.headers["Origin"]
    if (origin != null) {
        val uri = runCatching { URI(origin) }.getOrNull()
        if (uri == null || uri.scheme !in setOf("http", "https") || uri.rawAuthority != call.request.headers["Host"])
            throw AppException.Forbidden("Same-origin browser session required")
    }
    call.response.headers.append("Cache-Control", "no-store")
    return true
}

internal fun RoutingContext.browserRefresh(): String = call.request.cookies[COOKIE] ?: throw AppException.Unauthorized()

internal fun RoutingContext.setBrowserSession(token: String, expires: Instant) {
    // HTTP is supported only for loopback development. Real hosts always require HTTPS.
    val host = call.request.headers["Host"].orEmpty().substringBefore(':')
    call.response.cookies.append(Cookie(
        name = COOKIE, value = token, path = "/v1/auth", httpOnly = true,
        secure = host !in setOf("localhost", "127.0.0.1"),
        maxAge = Duration.between(Instant.now(), expires).seconds.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
        extensions = mapOf("SameSite" to "Strict"),
    ))
}

internal fun RoutingContext.clearBrowserSession() = setBrowserSession("", Instant.EPOCH)
