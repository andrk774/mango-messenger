package com.mango.messenger.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.mango.core.database.MangoDatabase
import com.mango.core.datastore.SettingsStore
import com.mango.core.model.MessageType
import com.mango.messenger.MainActivity
import com.mango.messenger.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Показ уведомлений.
 *
 * Содержимое берётся из локальной базы, а не из push: провайдер получает
 * только идентификаторы (ADR-0010). Текст сообщения на экране блокировки —
 * только если пользователь явно включил это в настройках.
 */
@Singleton
class Notifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MangoDatabase,
    private val settingsStore: SettingsStore,
    private val sessionManager: com.mango.core.network.auth.SessionManager,
) {

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_MESSAGES, "Сообщения", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_REMINDERS, "Напоминания о задачах", NotificationManager.IMPORTANCE_HIGH),
            ),
        )
    }

    suspend fun showMessage(conversationId: String, messageId: String) = withContext(Dispatchers.IO) {
        if (!sessionManager.restore()) return@withContext
        val settings = settingsStore.settings.first()
        if (!settings.messageNotificationsEnabled || !canPost()) return@withContext
        // Приложение открыто — сообщение и так видно, звук был бы лишним.
        if (isInForeground()) return@withContext

        val conversation = database.conversationDao().findById(conversationId) ?: return@withContext
        if ((conversation.mutedUntil ?: 0) > System.currentTimeMillis()) return@withContext

        val message = database.messageDao().findById(messageId)
        val sender = message?.let { database.userDao().findById(it.senderId) }
        val text = when {
            !settings.showMessagePreviewInNotification -> "Новое сообщение"
            message == null -> "Новое сообщение"
            !message.body.isNullOrBlank() && message.type != MessageType.SYSTEM -> message.body!!
            message.type == MessageType.IMAGE -> "Фотография"
            message.type == MessageType.VIDEO -> "Видео"
            message.type == MessageType.VOICE -> "Голосовое сообщение"
            else -> "Файл"
        }
        val title = if (conversation.type == com.mango.core.model.ConversationType.GROUP && sender != null) {
            "${conversation.title}: ${sender.displayName}"
        } else {
            conversation.title
        }

        post(
            id = conversationId.hashCode(),
            channel = CHANNEL_MESSAGES,
            title = title,
            text = text,
            intent = openIntent(MainActivity.EXTRA_CONVERSATION_ID, conversationId),
        )
    }

    suspend fun showReminder(taskId: String) = withContext(Dispatchers.IO) {
        if (!sessionManager.restore()) return@withContext
        val settings = settingsStore.settings.first()
        if (!settings.taskNotificationsEnabled || !canPost()) return@withContext
        val task = database.taskDao().findById(taskId) ?: return@withContext
        post(
            id = taskId.hashCode(),
            channel = CHANNEL_REMINDERS,
            title = "Напоминание",
            text = task.title,
            intent = openIntent(MainActivity.EXTRA_OPEN_TASKS, taskId),
        )
    }

    @Suppress("MissingPermission") // проверено в canPost()
    private fun post(id: Int, channel: String, title: String, text: String, intent: PendingIntent) {
        // Синхронизация могла отозвать сессию, пока готовилось уведомление.
        if (sessionManager.currentAccessToken() == null) return
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(intent)
            // На экране блокировки — без содержимого, если текст скрыт настройкой.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun openIntent(extra: String, value: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            value.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(extra, value)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private suspend fun isInForeground(): Boolean = withContext(Dispatchers.Main) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_REMINDERS = "reminders"
    }
}
