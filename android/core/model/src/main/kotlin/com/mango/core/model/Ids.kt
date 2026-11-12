package com.mango.core.model

/**
 * Типизированные идентификаторы.
 *
 * Value class не создаёт накладных расходов в рантайме, но не даёт передать
 * ConversationId туда, где ожидается MessageId — ошибка, которую в мессенджере
 * с десятком сущностей легко сделать и тяжело заметить.
 */
@JvmInline
value class UserId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
value class DeviceId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
value class ConversationId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
value class MessageId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
value class TaskId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
value class AttachmentId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
value class CommentId(val value: String) {
    override fun toString(): String = value
}

/**
 * Идентификатор клиентской операции.
 *
 * Генерируется на устройстве ДО отправки, переживает перезапуск приложения
 * и передаётся серверу как ключ идемпотентности. Это основа защиты от дубликатов
 * при повторных попытках отправки — см. docs/architecture/sync-model.md.
 */
@JvmInline
value class ClientOpId(val value: String) {
    override fun toString(): String = value
}
