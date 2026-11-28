package com.mango.messenger.ui.conversation

import androidx.lifecycle.SavedStateHandle
import com.mango.core.common.Outcome
import com.mango.core.common.AppError
import com.mango.core.data.repository.*
import com.mango.core.datastore.SettingsStore
import com.mango.core.datastore.UserSettings
import com.mango.core.model.Message
import com.mango.core.model.MessageId
import com.mango.messenger.navigation.Routes
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.test.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.After

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConversationHistoryTest {
    @After fun tearDown() { Dispatchers.resetMain() }
    @Test fun `older cached history crosses 200 and reaches end without duplicate fetch`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        run {
            val history = (450L downTo 1L).map { seq -> mockk<Message> {
                every { id } returns MessageId("m$seq")
                every { this@mockk.seq } returns seq
            } }
            val messages = mockk<MessageRepository>()
            every { messages.observeRecentMessages(any(), any()) } answers { flowOf(history.take(secondArg())) }
            coEvery { messages.loadHistory(any(), any(), any()) } answers {
                when (secondArg<Long?>()) {
                    251L -> Outcome.Failure(AppError.Network()) // Cached rows must remain accessible offline.
                    1L -> Outcome.Success(0)
                    else -> Outcome.Success(50)
                }
            }
            val chats = mockk<ConversationRepository>(relaxed = true)
            every { chats.observeConversation(any()) } returns flowOf(null)
            every { chats.observeMembersWithRoles(any()) } returns flowOf(emptyList())
            val users = mockk<UserRepository>()
            every { users.observeCurrentUser() } returns flowOf(null)
            val sync = mockk<SyncStatusRepository>()
            every { sync.observeSnapshot() } returns flowOf(SyncSnapshot(0, 0, null))
            val settings = mockk<SettingsStore>()
            every { settings.settings } returns flowOf(UserSettings())
            val vm = ConversationViewModel(mockk(), mockk(), mockk(),
                SavedStateHandle(mapOf(Routes.ARG_CONVERSATION_ID to "chat")),
                messages, chats, users, sync, settings)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
            advanceUntilIdle()
            assertEquals(200, vm.uiState.value.messages.size)
            vm.loadOlder(); vm.loadOlder() // A second request while loading is ignored.
            advanceUntilIdle()
            assertEquals(300, vm.uiState.value.messages.size)
            vm.loadOlder(); advanceUntilIdle()
            vm.loadOlder(); advanceUntilIdle()
            assertEquals(450, vm.uiState.value.messages.size)
            assertEquals(1L, vm.uiState.value.messages.last().seq)
            vm.loadOlder(); advanceUntilIdle()
            vm.loadOlder(); advanceUntilIdle()
            coVerify(exactly = 1) { messages.loadHistory(any(), 1L, any()) }
            assertEquals(450, vm.uiState.value.messages.map { it.id }.distinct().size)
            vm.viewModelScope.cancel()
        }
    }
}
