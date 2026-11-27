package com.mango.messenger.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.work.WorkManager
import com.mango.core.common.Outcome
import com.mango.core.data.repository.ConversationRepository
import com.mango.core.data.repository.MessageRepository
import com.mango.core.database.MangoDatabase
import com.mango.core.model.ConversationId
import com.mango.core.network.auth.SessionManager
import com.mango.core.sync.SyncWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** Explicit PendingIntents only. An old notification cannot act as a different account. */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {
    @Inject lateinit var handler: NotificationActionHandler

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != REPLY && action != READ) return
        val owner = intent.getStringExtra(OWNER) ?: return
        val chat = intent.getStringExtra(CHAT) ?: return
        val message = intent.getStringExtra(MESSAGE) ?: return
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(INPUT)?.toString()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val accepted = withTimeoutOrNull(8_000) {
                    handler.handle(action, owner, chat, message, reply)
                } == true
                if (accepted) {
                    SyncWorker.enqueueAfterCurrent(WorkManager.getInstance(context))
                    NotificationManagerCompat.from(context).cancel(chat.hashCode())
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the notification available for retry; never log reply text or tokens.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val REPLY = "com.mango.messenger.notification.REPLY"
        const val READ = "com.mango.messenger.notification.READ"
        const val OWNER = "owner"
        const val CHAT = "chat"
        const val MESSAGE = "message"
        const val INPUT = "reply_text"
    }
}

class NotificationActionHandler @Inject constructor(
    private val session: SessionManager,
    private val database: MangoDatabase,
    private val messages: MessageRepository,
    private val conversations: ConversationRepository,
) {
    suspend fun handle(action: String, owner: String, chat: String, messageId: String, reply: String?): Boolean {
        if (session.currentUserId() != owner || database.userDao().currentUserId() != owner) return false
        val message = database.messageDao().findById(messageId) ?: return false
        if (message.conversationId != chat) return false
        val result = when (action) {
            NotificationActionReceiver.REPLY -> {
                val text = reply?.trim().orEmpty()
                if (text.isEmpty() || text.length > 4_000) return false
                messages.sendText(ConversationId(chat), text)
            }
            NotificationActionReceiver.READ -> {
                // Mark only the message represented by this notification, not later unread messages.
                val seq = message.seq ?: return false
                conversations.markRead(ConversationId(chat), seq)
            }
            else -> return false
        }
        return result is Outcome.Success
    }
}
