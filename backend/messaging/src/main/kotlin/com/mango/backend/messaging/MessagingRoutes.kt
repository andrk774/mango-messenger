package com.mango.backend.messaging

import com.mango.backend.auth.JWT_AUTH
import com.mango.backend.auth.callerIdentity
import com.mango.backend.common.AppException
import com.mango.backend.common.toUuidOrNull
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.util.UUID

private fun RoutingContext.pathUuid(name: String): UUID =
    call.parameters[name]?.toUuidOrNull()
        ?: throw AppException.BadRequest("Некорректный идентификатор в пути")

fun Route.messagingRoutes(
    conversations: ConversationService,
    messages: MessageService,
) {
    authenticate(JWT_AUTH) {

        route("/v1/conversations") {

            get {
                val caller = callerIdentity()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                    ?.coerceIn(1, 200) ?: ConversationService.DEFAULT_LIMIT
                call.respond(conversations.list(caller.userId, limit))
            }

            post {
                val caller = callerIdentity()
                val body = call.receive<CreateConversationRequest>()
                val created = conversations.create(caller.userId, body)
                // 200, а не 201: для личного чата запрос мог вернуть уже
                // существующий, и утверждать «создано» было бы неверно.
                call.respond(HttpStatusCode.OK, created)
            }

            get("/{conversationId}") {
                val caller = callerIdentity()
                call.respond(conversations.get(caller.userId, pathUuid("conversationId")))
            }

            get("/{conversationId}/members") {
                val caller = callerIdentity()
                call.respond(conversations.members(caller.userId, pathUuid("conversationId")))
            }

            put("/{conversationId}/read") {
                val caller = callerIdentity()
                val body = call.receive<MarkReadRequest>()
                conversations.markRead(caller.userId, pathUuid("conversationId"), body.seq)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/{conversationId}/messages") {
                val caller = callerIdentity()
                val params = call.request.queryParameters

                val limit = params["limit"]?.toIntOrNull()
                    ?.coerceIn(1, MessageService.MAX_PAGE_SIZE)
                    ?: MessageService.DEFAULT_PAGE_SIZE

                call.respond(
                    messages.history(
                        userId = caller.userId,
                        conversationId = pathUuid("conversationId"),
                        beforeSeq = params["beforeSeq"]?.toLongOrNull(),
                        afterSeq = params["afterSeq"]?.toLongOrNull(),
                        limit = limit,
                    ),
                )
            }

            post("/{conversationId}/messages") {
                val caller = callerIdentity()
                val body = call.receive<SendMessageRequest>()

                val result = messages.send(
                    userId = caller.userId,
                    conversationId = pathUuid("conversationId"),
                    request = body,
                )

                // 201 для нового сообщения, 200 для повтора уже выполненной
                // операции. Клиент по коду отличает «создано» от «уже было»,
                // не разбирая тело.
                call.respond(
                    if (result.created) HttpStatusCode.Created else HttpStatusCode.OK,
                    result.message,
                )
            }
        }

        route("/v1/messages") {

            patch("/{messageId}") {
                val caller = callerIdentity()
                val body = call.receive<EditMessageRequest>()
                call.respond(messages.edit(caller.userId, pathUuid("messageId"), body.body))
            }

            delete("/{messageId}") {
                val caller = callerIdentity()
                messages.delete(caller.userId, pathUuid("messageId"))
                call.respond(HttpStatusCode.NoContent)
            }
        }

        get("/v1/users/search") {
            val caller = callerIdentity()
            val query = call.request.queryParameters["q"]
                ?: throw AppException.BadRequest("Параметр q обязателен")
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                ?.coerceIn(1, 50) ?: 20
            call.respond(conversations.searchUsers(caller.userId, query, limit))
        }

        /**
         * Первичная загрузка.
         *
         * Возвращает то же, что список чатов: сами чаты с последним
         * сообщением и положение журнала событий. Отдельная выборка не
         * нужна — список уже строится одним запросом и уже содержит
         * курсор, с которого клиент начинает слушать события.
         */
        get("/v1/sync/bootstrap") {
            val caller = callerIdentity()
            call.respond(conversations.list(caller.userId, ConversationService.DEFAULT_LIMIT))
        }
    }
}
