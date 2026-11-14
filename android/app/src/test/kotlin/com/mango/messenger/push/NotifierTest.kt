package com.mango.messenger.push

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mango.core.database.MangoDatabase
import com.mango.core.datastore.SettingsStore
import com.mango.core.datastore.UserSettings
import com.mango.core.network.auth.SessionManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NotifierTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = mockk<MangoDatabase>(relaxed = true)
    private val settings = mockk<SettingsStore>()
    private val session = mockk<SessionManager>()
    private val notifier = Notifier(context, database, settings, session)

    @Test
    fun `delayed message after logout does not access account data`() = runTest {
        coEvery { session.restore() } returns false
        notifier.showMessage("old-conversation", "old-message")
        verify(exactly = 0) { database.conversationDao() }
        assertNoNotifications()
    }

    @Test
    fun `delayed reminder after logout does not access account data`() = runTest {
        coEvery { session.restore() } returns false
        notifier.showReminder("old-task")
        verify(exactly = 0) { database.taskDao() }
        assertNoNotifications()
    }

    @Test
    fun `reminder for missing task does not create a generic notification`() = runTest {
        coEvery { session.restore() } returns true
        every { settings.settings } returns flowOf(UserSettings())
        coEvery { database.taskDao().findById("old-task") } returns null
        notifier.showReminder("old-task")
        assertNoNotifications()
    }

    private fun assertNoNotifications() {
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun `active session can display a reminder`() = runTest {
        coEvery { session.restore() } returns true
        every { session.currentAccessToken() } returns "test-access"
        every { settings.settings } returns flowOf(UserSettings())
        coEvery { database.taskDao().findById("task") } returns mockk {
            every { title } returns "Test reminder"
            every { remindAt } returns null
        }
        notifier.createChannels()
        notifier.showReminder("task")
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(1, manager.activeNotifications.size)
    }

    @Test
    fun `session revoked while preparing reminder prevents posting`() = runTest {
        coEvery { session.restore() } returns true
        every { session.currentAccessToken() } returns null
        every { settings.settings } returns flowOf(UserSettings())
        coEvery { database.taskDao().findById("task") } returns mockk {
            every { title } returns "Test reminder"
            every { remindAt } returns null
        }
        notifier.createChannels()
        notifier.showReminder("task")
        assertNoNotifications()
    }
}
