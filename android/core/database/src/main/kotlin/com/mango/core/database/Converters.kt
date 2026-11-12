package com.mango.core.database

import androidx.room.TypeConverter
import com.mango.core.database.entity.OutboxStatus
import com.mango.core.model.ConversationType
import com.mango.core.model.MemberRole
import com.mango.core.model.MessageDeliveryStatus
import com.mango.core.model.MessageType
import com.mango.core.model.NotificationType
import com.mango.core.model.PresenceStatus
import com.mango.core.model.SyncStatus
import com.mango.core.model.TaskEvent
import com.mango.core.model.TaskPriority
import com.mango.core.model.TaskStatus

/**
 * Конвертеры типов для Room.
 *
 * Enum-значения хранятся строками, а не ordinal: при добавлении нового
 * значения в середину перечисления ordinal сместился бы и молча испортил
 * все существующие записи.
 *
 * Время хранится как epoch millis (Long), а не как ISO-строка: так сравнения
 * и сортировки в SQL работают по индексу.
 */
class Converters {

    @TypeConverter
    fun toConversationType(value: String): ConversationType = ConversationType.valueOf(value)

    @TypeConverter
    fun fromConversationType(value: ConversationType): String = value.name

    @TypeConverter
    fun toMemberRole(value: String?): MemberRole? = value?.let(MemberRole::valueOf)

    @TypeConverter
    fun fromMemberRole(value: MemberRole?): String? = value?.name

    @TypeConverter
    fun toMessageType(value: String): MessageType = MessageType.valueOf(value)

    @TypeConverter
    fun fromMessageType(value: MessageType): String = value.name

    @TypeConverter
    fun toDeliveryStatus(value: String): MessageDeliveryStatus =
        MessageDeliveryStatus.valueOf(value)

    @TypeConverter
    fun fromDeliveryStatus(value: MessageDeliveryStatus): String = value.name

    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus = SyncStatus.valueOf(value)

    @TypeConverter
    fun fromSyncStatus(value: SyncStatus): String = value.name

    @TypeConverter
    fun toPresenceStatus(value: String): PresenceStatus = PresenceStatus.valueOf(value)

    @TypeConverter
    fun fromPresenceStatus(value: PresenceStatus): String = value.name

    @TypeConverter
    fun toTaskStatus(value: String): TaskStatus = TaskStatus.valueOf(value)

    @TypeConverter
    fun fromTaskStatus(value: TaskStatus): String = value.name

    @TypeConverter
    fun toTaskPriority(value: String): TaskPriority = TaskPriority.valueOf(value)

    @TypeConverter
    fun fromTaskPriority(value: TaskPriority): String = value.name

    @TypeConverter
    fun toTaskEventType(value: String): TaskEvent.Type = TaskEvent.Type.valueOf(value)

    @TypeConverter
    fun fromTaskEventType(value: TaskEvent.Type): String = value.name

    @TypeConverter
    fun toNotificationType(value: String): NotificationType = NotificationType.valueOf(value)

    @TypeConverter
    fun fromNotificationType(value: NotificationType): String = value.name

    @TypeConverter
    fun toOutboxStatus(value: String): OutboxStatus = OutboxStatus.valueOf(value)

    @TypeConverter
    fun fromOutboxStatus(value: OutboxStatus): String = value.name
}
