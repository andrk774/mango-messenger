package com.mango.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.mango.core.database.dao.ConversationDao
import com.mango.core.database.dao.MessageDao
import com.mango.core.database.dao.NotificationDao
import com.mango.core.database.dao.OutboxDao
import com.mango.core.database.dao.TaskDao
import com.mango.core.database.dao.UserDao
import com.mango.core.database.entity.AttachmentEntity
import com.mango.core.database.entity.ConversationEntity
import com.mango.core.database.entity.ConversationMemberEntity
import com.mango.core.database.entity.MessageEntity
import com.mango.core.database.entity.MessageGapEntity
import com.mango.core.database.entity.MessageReactionEntity
import com.mango.core.database.entity.NotificationEntity
import com.mango.core.database.entity.OutboxOperationEntity
import com.mango.core.database.entity.SyncStateEntity
import com.mango.core.database.entity.TaskAssigneeEntity
import com.mango.core.database.entity.TaskCommentEntity
import com.mango.core.database.entity.TaskEntity
import com.mango.core.database.entity.TaskEventEntity
import com.mango.core.database.entity.UserEntity

/**
 * Локальная база данных — единственный источник истины для пользовательского интерфейса.
 *
 * `exportSchema = true` обязателен: без экспортированной схемы невозможно
 * написать тест миграции, а значит невозможно безопасно менять схему
 * в приложении, которое уже установлено у пользователей.
 *
 * Деструктивные миграции НЕ включаются: в offline-first приложении
 * локальная база может содержать неотправленные сообщения и задачи,
 * и их потеря при обновлении приложения недопустима.
 */
@Database(
    entities = [
        UserEntity::class,
        ConversationEntity::class,
        ConversationMemberEntity::class,
        MessageEntity::class,
        MessageReactionEntity::class,
        MessageGapEntity::class,
        AttachmentEntity::class,
        TaskEntity::class,
        TaskAssigneeEntity::class,
        TaskCommentEntity::class,
        TaskEventEntity::class,
        NotificationEntity::class,
        OutboxOperationEntity::class,
        SyncStateEntity::class,
    ],
    version = MangoDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MangoDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun taskDao(): TaskDao
    abstract fun outboxDao(): OutboxDao
    abstract fun notificationDao(): NotificationDao
    abstract fun attachmentDao(): com.mango.core.database.dao.AttachmentDao

    companion object {
        const val VERSION = 1
        const val NAME = "mango.db"
    }
}
