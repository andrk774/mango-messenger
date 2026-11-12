package com.mango.core.model

enum class ConversationType { DIRECT, GROUP }

enum class MemberRole {
    OWNER,
    ADMIN,
    MEMBER;

    /** Может ли роль добавлять и удалять участников. */
    val canManageMembers: Boolean get() = this == OWNER || this == ADMIN

    /** Может ли роль менять настройки чата и закреплять сообщения. */
    val canManageConversation: Boolean get() = this == OWNER || this == ADMIN

    /** Может ли роль назначать других администраторов. */
    val canManageRoles: Boolean get() = this == OWNER
}

enum class MessageType { TEXT, IMAGE, VIDEO, VOICE, FILE, SYSTEM }

/**
 * Состояние исходящего сообщения с точки зрения клиента.
 *
 * PENDING/SENDING/FAILED — локальные состояния очереди отправки;
 * SENT/DELIVERED/READ подтверждаются сервером.
 */
enum class MessageDeliveryStatus {
    PENDING,
    SENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED;

    val isLocalOnly: Boolean get() = this == PENDING || this == SENDING || this == FAILED
}

enum class PresenceStatus { ONLINE, OFFLINE, AWAY, DO_NOT_DISTURB }

enum class TaskStatus {
    TODO,
    IN_PROGRESS,
    REVIEW,
    DONE,
    CANCELLED;

    val isTerminal: Boolean get() = this == DONE || this == CANCELLED

    /**
     * Вес статуса для разрешения конфликтов при одновременном изменении
     * с двух устройств: при равных метках времени более «сильное» намерение
     * (завершение/отмена) побеждает. См. docs/architecture/sync-model.md.
     */
    val conflictWeight: Int
        get() = when (this) {
            TODO -> 0
            IN_PROGRESS -> 1
            REVIEW -> 2
            CANCELLED -> 3
            DONE -> 4
        }
}

enum class TaskPriority {
    LOW,
    MEDIUM,
    HIGH,
    URGENT;

    val sortOrder: Int
        get() = when (this) {
            URGENT -> 0
            HIGH -> 1
            MEDIUM -> 2
            LOW -> 3
        }
}

/**
 * Состояние синхронизации локальной записи.
 *
 * Хранится рядом с данными в Room и показывается в интерфейсе,
 * чтобы пользователь понимал, что его изменение ещё не доехало до сервера.
 */
enum class SyncStatus {
    /** Запись подтверждена сервером. */
    SYNCED,

    /** Создана локально, ждёт отправки. */
    PENDING,

    /** Отправка в процессе. */
    IN_FLIGHT,

    /** Сервер отклонил операцию — требуется решение пользователя. */
    FAILED,
}

enum class TaskScope { MINE, TEAM, FROM_CHATS }

enum class NotificationType {
    NEW_MESSAGE,
    MENTION,
    TASK_ASSIGNED,
    TASK_UPDATED,
    TASK_COMMENT,
    TASK_REMINDER,
    CONVERSATION_EVENT,
}
