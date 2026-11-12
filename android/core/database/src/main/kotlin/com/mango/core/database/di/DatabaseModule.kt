package com.mango.core.database.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.mango.core.database.MangoDatabase
import com.mango.core.database.dao.ConversationDao
import com.mango.core.database.dao.MessageDao
import com.mango.core.database.dao.NotificationDao
import com.mango.core.database.dao.OutboxDao
import com.mango.core.database.dao.TaskDao
import com.mango.core.database.dao.UserDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MangoDatabase =
        Room.databaseBuilder(context, MangoDatabase::class.java, MangoDatabase.NAME)
            // WAL: чтение не блокируется записью. Для мессенджера это важно —
            // синхронизация пишет в фоне, пока пользователь скроллит переписку.
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            // Деструктивная миграция НЕ включается намеренно: локальная база
            // может содержать неотправленные сообщения и задачи.
            .build()

    @Provides
    fun provideUserDao(database: MangoDatabase): UserDao = database.userDao()

    @Provides
    fun provideConversationDao(database: MangoDatabase): ConversationDao =
        database.conversationDao()

    @Provides
    fun provideMessageDao(database: MangoDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideTaskDao(database: MangoDatabase): TaskDao = database.taskDao()

    @Provides
    fun provideOutboxDao(database: MangoDatabase): OutboxDao = database.outboxDao()

    @Provides
    fun provideNotificationDao(database: MangoDatabase): NotificationDao =
        database.notificationDao()
}
