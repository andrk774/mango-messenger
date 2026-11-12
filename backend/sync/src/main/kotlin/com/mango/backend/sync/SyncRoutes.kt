package com.mango.backend.sync

import com.mango.backend.auth.JWT_AUTH
import com.mango.backend.auth.callerIdentity
import com.mango.backend.common.AppException
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.syncRoutes(service: SyncService) {
    authenticate(JWT_AUTH) {
        route("/v1/sync") {

            get {
                val caller = callerIdentity()

                val since = call.request.queryParameters["since"]?.toLongOrNull()
                    ?: throw AppException.BadRequest("Параметр since обязателен")

                val limit = (call.request.queryParameters["limit"]?.toIntOrNull()
                    ?: SyncService.DEFAULT_LIMIT)
                    .coerceIn(1, SyncService.MAX_LIMIT)

                try {
                    call.respond(service.catchUp(caller.userId, since, limit))
                } catch (e: CursorTooOldException) {
                    // 410 Gone, а не 400: запрос корректен, просто запрошенное
                    // состояние больше не существует. Клиент обязан
                    // перезагрузить перечисленные области, но НЕ всю базу.
                    call.respond(
                        HttpStatusCode.Gone,
                        ResetRequiredResponse(
                            message = e.message ?: "Курсор устарел",
                            resetScopes = listOf(EventScope.CONVERSATION.name),
                        ),
                    )
                }
            }
        }
    }
}
