package com.mango.messenger.push

import com.mango.core.common.Outcome
import com.mango.core.data.repository.ConversationRepository
import com.mango.core.data.repository.MessageRepository
import com.mango.core.database.MangoDatabase
import com.mango.core.database.entity.MessageEntity
import com.mango.core.model.ClientOpId
import com.mango.core.model.ConversationId
import com.mango.core.network.auth.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationActionHandlerTest {
    private val session = mockk<SessionManager>()
    private val db = mockk<MangoDatabase>(relaxed = true)
    private val messages = mockk<MessageRepository>()
    private val conversations = mockk<ConversationRepository>()
    private val handler = NotificationActionHandler(session, db, messages, conversations)

    private fun prepare(chat: String = "chat") {
        coEvery { session.currentUserId() } returns "owner"
        coEvery { db.userDao().currentUserId() } returns "owner"
        val message = mockk<MessageEntity>()
        every { message.conversationId } returns chat
        every { message.seq } returns 42L
        coEvery { db.messageDao().findById("message") } returns message
    }

    @Test fun `old account notification cannot send a reply`() = runTest {
        prepare()
        coEvery { session.currentUserId() } returns "another"
        assertFalse(handler.handle(NotificationActionReceiver.REPLY, "owner", "chat", "message", "Hello"))
        coVerify(exactly = 0) { messages.sendText(any(), any(), any()) }
    }

    @Test fun `reply uses durable repository queue`() = runTest {
        prepare()
        coEvery { messages.sendText(ConversationId("chat"), "Hello", null) } returns Outcome.Success(ClientOpId("op"))
        assertTrue(handler.handle(NotificationActionReceiver.REPLY, "owner", "chat", "message", " Hello "))
        coVerify(exactly = 1) { messages.sendText(ConversationId("chat"), "Hello", null) }
    }

    @Test fun `different local account blocks notification action`() = runTest {
        prepare()
        coEvery { db.userDao().currentUserId() } returns "another"
        assertFalse(handler.handle(NotificationActionReceiver.READ, "owner", "chat", "message", null))
        coVerify(exactly = 0) { conversations.markRead(any(), any()) }
    }

    @Test fun `read action marks only displayed sequence`() = runTest {
        prepare()
        coEvery { conversations.markRead(ConversationId("chat"), 42L) } returns Outcome.Success(Unit)
        assertTrue(handler.handle(NotificationActionReceiver.READ, "owner", "chat", "message", null))
        coVerify(exactly = 1) { conversations.markRead(ConversationId("chat"), 42L) }
    }

    @Test fun `mismatched conversation cannot be marked read`() = runTest {
        prepare("another-chat")
        assertFalse(handler.handle(NotificationActionReceiver.READ, "owner", "chat", "message", null))
        coVerify(exactly = 0) { conversations.markRead(any(), any()) }
    }

    @Test fun `blank and oversized replies are rejected`() = runTest {
        prepare()
        for (reply in listOf(null, "  ", "x".repeat(4001))) {
            assertFalse(handler.handle(NotificationActionReceiver.REPLY, "owner", "chat", "message", reply))
        }
        coVerify(exactly = 0) { messages.sendText(any(), any(), any()) }
    }
}
