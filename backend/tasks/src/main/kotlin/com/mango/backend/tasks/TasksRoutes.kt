package com.mango.backend.tasks

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
import io.ktor.server.routing.route
import java.util.UUID

private fun RoutingContext.pathUuid(name: String): UUID =
    call.parameters[name]?.toUuidOrNull()
        ?: throw AppException.BadRequest("Некорректный идентификатор в пути")

fun Route.tasksRoutes(service: TaskService) {
    authenticate(JWT_AUTH) {

        route("/v1/tasks") {

            get {
                val caller = callerIdentity()
                val params = call.request.queryParameters

                val scope = when (params["scope"]?.lowercase() ?: "mine") {
                    "mine" -> TaskScope.MINE
                    "team" -> TaskScope.TEAM
                    "chat" -> TaskScope.CHAT
                    "from_chats" -> TaskScope.FROM_CHATS
                    else -> throw AppException.BadRequest("Допустимо: mine, team, chat, from_chats")
                }

                call.respond(
                    service.list(
                        userId = caller.userId,
                        scope = scope,
                        conversationId = params["conversationId"]?.toUuidOrNull(),
                        statuses = params.getAll("status").orEmpty()
                            .flatMap { it.split(',') }
                            .map { it.trim().uppercase() }
                            .filter { it.isNotEmpty() }
                            .toSet(),
                        includeCompleted = params["includeCompleted"]?.toBooleanStrictOrNull() ?: false,
                        limit = params["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: TaskService.DEFAULT_LIMIT,
                    ),
                )
            }

            post {
                val caller = callerIdentity()
                val result = service.create(caller.userId, call.receive<CreateTaskRequest>())
                // 201 — создана сейчас, 200 — повтор уже выполненной операции.
                call.respond(if (result.created) HttpStatusCode.Created else HttpStatusCode.OK, result.task)
            }

            get("/{taskId}") {
                val caller = callerIdentity()
                call.respond(service.get(caller.userId, pathUuid("taskId")))
            }

            patch("/{taskId}") {
                val caller = callerIdentity()
                call.respond(
                    service.patch(caller.userId, pathUuid("taskId"), call.receive<PatchTaskRequest>()),
                )
            }

            delete("/{taskId}") {
                val caller = callerIdentity()
                service.delete(caller.userId, pathUuid("taskId"))
                call.respond(HttpStatusCode.NoContent)
            }

            post("/{taskId}/assignees") {
                val caller = callerIdentity()
                call.respond(
                    service.changeAssignees(caller.userId, pathUuid("taskId"), call.receive<AssigneesRequest>()),
                )
            }

            get("/{taskId}/comments") {
                val caller = callerIdentity()
                call.respond(service.comments(caller.userId, pathUuid("taskId")))
            }

            post("/{taskId}/comments") {
                val caller = callerIdentity()
                val result = service.addComment(
                    caller.userId,
                    pathUuid("taskId"),
                    call.receive<AddCommentRequest>(),
                )
                call.respond(if (result.created) HttpStatusCode.Created else HttpStatusCode.OK, result.comment)
            }

            get("/{taskId}/events") {
                val caller = callerIdentity()
                call.respond(service.history(caller.userId, pathUuid("taskId")))
            }
        }

        post("/v1/messages/{messageId}/to-task") {
            val caller = callerIdentity()
            val result = service.createFromMessage(
                caller.userId,
                pathUuid("messageId"),
                call.receive<CreateTaskFromMessageRequest>(),
            )
            call.respond(if (result.created) HttpStatusCode.Created else HttpStatusCode.OK, result.task)
        }
    }
}
