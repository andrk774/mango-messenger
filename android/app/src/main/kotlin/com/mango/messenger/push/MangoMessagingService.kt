package com.mango.messenger.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.mango.core.common.Logger
import com.mango.core.sync.SyncEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Приём push.
 *
 * Push несёт только идентификаторы. Сервис забирает изменения с сервера
 * тем же путём, что и при открытии приложения (журнал событий), и лишь
 * затем показывает уведомление из локальной базы. Так текст переписки
 * не проходит через Firebase, а локальные данные к моменту открытия
 * уведомления уже актуальны.
 */
@AndroidEntryPoint
class MangoMessagingService : FirebaseMessagingService() {

    @Inject lateinit var syncEngine: SyncEngine
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var registrar: PushRegistrar
    @Inject lateinit var logger: Logger
    @Inject lateinit var sessionManager: com.mango.core.network.auth.SessionManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch { registrar.register(token) }
    }

    /**
     * Вызывается в фоновом потоке; на обработку система даёт около
     * двадцати секунд, поэтому синхронизация ограничена по времени.
     * Не успели — уведомление всё равно показывается, с общим текстом.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        runBlocking {
            // FCM может доставить уже отправленный push после выхода.
            if (!sessionManager.restore()) return@runBlocking
            when (data["type"]) {
                "message" -> {
                    val conversationId = data["conversationId"] ?: return@runBlocking
                    val messageId = data["messageId"] ?: return@runBlocking
                    withTimeoutOrNull(SYNC_TIMEOUT_MILLIS) { syncEngine.catchUp() }
                    notifier.showMessage(conversationId, messageId)
                }

                "task_reminder" -> {
                    val taskId = data["taskId"] ?: return@runBlocking
                    withTimeoutOrNull(SYNC_TIMEOUT_MILLIS) { syncEngine.refreshTasks() }
                    notifier.showReminder(taskId)
                }

                else -> logger.i(TAG, "push неизвестного вида: ${data["type"]}")
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "Push"
        const val SYNC_TIMEOUT_MILLIS = 12_000L
    }
}
