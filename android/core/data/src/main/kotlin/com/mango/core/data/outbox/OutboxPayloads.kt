package com.mango.core.data.outbox

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Типы операций в очереди исходящих.
 *
 * Строковые константы, а не enum: значение попадает в персистентную БД,
 * и переименование enum-класса не должно ломать уже стоящие в очереди
 * операции у пользователя, обновившего приложение.
 */
object OpType {
    const val SEND_MESSAGE = "SEND_MESSAGE"
    const val EDIT_MESSAGE = "EDIT_MESSAGE"
    const val DELETE_MESSAGE = "DELETE_MESSAGE"
    const val TOGGLE_REACTION = "TOGGLE_REACTION"
    const val MARK_READ = "MARK_READ"
    const val SAVE_DRAFT = "SAVE_DRAFT"
    const val CREATE_TASK = "CREATE_TASK"
    const val PATCH_TASK = "PATCH_TASK"
    const val DELETE_TASK = "DELETE_TASK"
    const val ADD_TASK_COMMENT = "ADD_TASK_COMMENT"
    const val SET_TASK_ASSIGNEES = "SET_TASK_ASSIGNEES"
}

object EntityType {
    const val MESSAGE = "MESSAGE"
    const val CONVERSATION = "CONVERSATION"
    const val TASK = "TASK"
    const val TASK_COMMENT = "TASK_COMMENT"
}

/** Полезная нагрузка операций. Сериализуется в outbox_operations.payload_json. */
@Serializable
sealed interface OutboxPayload

@Serializable
data class SendMessagePayload(
    val conversationId: String,
    val clientMsgId: String,
    val text: String,
    val replyToId: String? = null,
) : OutboxPayload

@Serializable
data class EditMessagePayload(
    val messageId: String,
    val text: String,
) : OutboxPayload

@Serializable
data class DeleteMessagePayload(
    val messageId: String,
    val forEveryone: Boolean,
) : OutboxPayload

@Serializable
data class ToggleReactionPayload(
    val messageId: String,
    val emoji: String,
    val add: Boolean,
) : OutboxPayload

@Serializable
data class MarkReadPayload(
    val conversationId: String,
    val seq: Long,
) : OutboxPayload

@Serializable
data class SaveDraftPayload(
    val conversationId: String,
    val draft: String?,
) : OutboxPayload

@Serializable
data class CreateTaskPayload(
    val taskId: String,
    val title: String,
    val description: String? = null,
    val priority: String,
    val dueAtMillis: Long? = null,
    val conversationId: String? = null,
    val assigneeIds: List<String> = emptyList(),
    val parentTaskId: String? = null,
    val sourceMessageId: String? = null,
) : OutboxPayload

/**
 * Частичное изменение задачи.
 *
 * Отправляются только изменённые поля и [baseVersion]. Это позволяет серверу
 * разрешать конфликт по полям, а не отвергать всю операцию целиком —
 * см. docs/architecture/sync-model.md.
 */
@Serializable
data class PatchTaskPayload(
    val taskId: String,
    val baseVersion: Long,
    val changedFields: Map<String, String?>,
    /** Метки времени локального изменения по полям — для LWW на сервере. */
    val fieldTimestamps: Map<String, Long>,
) : OutboxPayload

@Serializable
data class DeleteTaskPayload(val taskId: String) : OutboxPayload

@Serializable
data class AddTaskCommentPayload(
    val taskId: String,
    val commentId: String,
    val body: String,
) : OutboxPayload

/** Изменение набора исполнителей как add/remove — сливается на сервере как OR-Set. */
@Serializable
data class SetTaskAssigneesPayload(
    val taskId: String,
    val add: List<String> = emptyList(),
    val remove: List<String> = emptyList(),
) : OutboxPayload

/**
 * Единый сериализатор очереди.
 *
 * `ignoreUnknownKeys = true` обязателен: после обновления приложения
 * в очереди могут лежать операции, сериализованные прежней версией схемы,
 * и их нельзя терять.
 */
val OutboxJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "op"
}
