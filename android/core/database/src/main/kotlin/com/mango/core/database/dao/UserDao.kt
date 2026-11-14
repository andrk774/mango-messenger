package com.mango.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.mango.core.database.entity.NotificationEntity
import com.mango.core.database.entity.UserEntity
import com.mango.core.model.PresenceStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {

    @Upsert
    suspend fun upsertRaw(user: UserEntity)

    /**
     * Запись пользователя с сохранением отметки «это я».
     *
     * Сведения о людях приходят отовсюду: участники чата, исполнители
     * задач, авторы сообщений, результаты поиска — и все они записываются
     * с `isMe = false`. Если среди них оказывается сам пользователь,
     * прямая запись сбрасывала бы отметку, и приложение переставало бы
     * понимать, кто вошёл: отправка отказывала бы с «не авторизован».
     * Снять отметку может только выход из учётной записи (очистка базы).
     */
    @Transaction
    suspend fun upsert(user: UserEntity) {
        val wasMe = findById(user.id)?.isMe == true
        upsertRaw(if (wasMe && !user.isMe) user.copy(isMe = true) else user)
    }

    @Transaction
    suspend fun upsertAll(users: List<UserEntity>) {
        users.forEach { upsert(it) }
    }

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun findById(id: String): UserEntity?

    @Query("SELECT * FROM users WHERE id = :id")
    fun observeById(id: String): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE is_me = 1 LIMIT 1")
    fun observeCurrentUser(): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE is_me = 1 LIMIT 1")
    suspend fun currentUser(): UserEntity?

    @Query("SELECT id FROM users WHERE is_me = 1 LIMIT 1")
    suspend fun currentUserId(): String?

    @Query("SELECT * FROM users WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<String>): List<UserEntity>

    @Query(
        """
        SELECT * FROM users
        WHERE is_me = 0
          AND (display_name LIKE '%' || :query || '%' OR username LIKE '%' || :query || '%')
        ORDER BY display_name
        LIMIT :limit
        """,
    )
    fun observeSearch(query: String, limit: Int = 50): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE is_me = 0 ORDER BY display_name")
    fun observeContacts(): Flow<List<UserEntity>>

    @Query("UPDATE users SET presence = :presence, last_seen_at = :lastSeenAt WHERE id = :id")
    suspend fun updatePresence(id: String, presence: PresenceStatus, lastSeenAt: Long?)

    /** Сброс признака текущего пользователя — при смене аккаунта. */
    @Query("UPDATE users SET is_me = 0 WHERE is_me = 1")
    suspend fun clearCurrentUserFlag()
}

@Dao
interface NotificationDao {
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(notification: NotificationEntity)

    @Upsert
    suspend fun upsert(notification: NotificationEntity)

    @Upsert
    suspend fun upsertAll(notifications: List<NotificationEntity>)

    @Query("SELECT * FROM notifications ORDER BY created_at DESC LIMIT :limit")
    fun observeAll(limit: Int = 200): Flow<List<NotificationEntity>>

    @Query("SELECT COUNT(*) FROM notifications WHERE read_at IS NULL")
    fun observeUnreadCount(): Flow<Int>

    @Query("UPDATE notifications SET read_at = :readAt WHERE id IN (:ids) AND read_at IS NULL")
    suspend fun markRead(ids: List<String>, readAt: Long)

    @Query("UPDATE notifications SET read_at = :readAt WHERE read_at IS NULL")
    suspend fun markAllRead(readAt: Long)

    @Query("DELETE FROM notifications WHERE created_at < :beforeMillis")
    suspend fun deleteOlderThan(beforeMillis: Long)
}
