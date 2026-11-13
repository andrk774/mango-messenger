package com.mango.backend.notifications

import com.mango.backend.auth.JWT_AUTH
import com.mango.backend.auth.callerIdentity
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.put

fun Route.notificationsRoutes(push: PushService) {
    authenticate(JWT_AUTH) {
        // Токен привязывается к устройству текущей сессии: другого
        // способа узнать, чьё это устройство, у сервера нет.
        put("/v1/users/me/devices/push") {
            val caller = callerIdentity()
            push.register(caller.sessionId, call.receive<RegisterPushTokenRequest>())
            call.respond(HttpStatusCode.NoContent)
        }

        delete("/v1/users/me/devices/push") {
            push.unregister(callerIdentity().sessionId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
