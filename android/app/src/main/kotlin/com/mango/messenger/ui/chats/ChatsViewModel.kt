package com.mango.messenger.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mango.core.common.AppError
import com.mango.core.common.Outcome
import com.mango.core.data.repository.ConversationRepository
import com.mango.core.data.repository.SyncSnapshot
import com.mango.core.data.repository.SyncStatusRepository
import com.mango.core.model.Conversation
import com.mango.core.model.ConversationId
import com.mango.core.model.User
import com.mango.core.model.UserId
import com.mango.messenger.ui.tasks.toUserMessage
import com.mango.messenger.ui.tasks.toOnlineOnlyMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatsUiState(
    val isLoading: Boolean = true,
    val conversations: List<Conversation> = emptyList(),
    val searchQuery: String = "",
    val sync: SyncSnapshot = SyncSnapshot(0, 0, null),
    val isRefreshing: Boolean = false,
) {
    val isEmpty: Boolean get() = !isLoading && conversations.isEmpty()
}

/** Состояние диалога «новая переписка». */
data class NewChatUiState(
    val isOpen: Boolean = false,
    val query: String = "",
    val results: List<User> = emptyList(),
    val isSearching: Boolean = false,
    val hint: String? = null,
    /** Режим создания группы: выбор нескольких людей и название. */
    val isGroup: Boolean = false,
    val groupTitle: String = "",
    val selected: List<User> = emptyList(),
    val isCreating: Boolean = false,
) {
    val canCreateGroup: Boolean
        get() = isGroup && groupTitle.isNotBlank() && selected.isNotEmpty() && !isCreating
}

sealed interface ChatsEvent {
    data class Message(val text: String) : ChatsEvent
    data class OpenConversation(val id: ConversationId) : ChatsEvent
}

@HiltViewModel
class ChatsViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    syncStatusRepository: SyncStatusRepository,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val isRefreshing = MutableStateFlow(false)

    private val _newChat = MutableStateFlow(NewChatUiState())
    val newChat: StateFlow<NewChatUiState> = _newChat

    private val _events = MutableSharedFlow<ChatsEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: Flow<ChatsEvent> = _events.asSharedFlow()

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val uiState: StateFlow<ChatsUiState> = combine(
        conversationRepository.observeConversations(),
        searchQuery.debounce(150),
        syncStatusRepository.observeSnapshot(),
        isRefreshing,
    ) { conversations, query, sync, refreshing ->
        ChatsUiState(
            isLoading = false,
            conversations = conversations.filterByQuery(query),
            searchQuery = query,
            sync = sync,
            isRefreshing = refreshing,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatsUiState(),
    )

    init {
        refresh()
    }

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    /**
     * Обновление списка с сервера.
     *
     * Интерфейс при этом не блокируется: он подписан на Room и уже
     * показывает то, что есть локально. Сбой сети здесь не ошибка —
     * просто данные остались прежними.
     */
    fun refresh() {
        viewModelScope.launch {
            isRefreshing.value = true
            try {
                val result = conversationRepository.refresh()
                if (result is Outcome.Failure && result.error !is AppError.Network) {
                    _events.emit(ChatsEvent.Message(result.error.toUserMessage()))
                }
            } finally {
                isRefreshing.value = false
            }
        }
    }

    // ───────────────────────── новая переписка ─────────────────────────

    fun openNewChat() {
        _newChat.value = NewChatUiState(isOpen = true)
    }

    fun closeNewChat() {
        _newChat.value = NewChatUiState()
    }

    fun onPeopleQueryChange(query: String) {
        _newChat.value = _newChat.value.copy(query = query, hint = null)
        if (query.trim().length < MIN_SEARCH_LENGTH) {
            _newChat.value = _newChat.value.copy(results = emptyList())
            return
        }
        searchPeople(query.trim())
    }

    private fun searchPeople(query: String) {
        viewModelScope.launch {
            _newChat.value = _newChat.value.copy(isSearching = true)
            try {
                when (val result = conversationRepository.searchPeople(query)) {
                    is Outcome.Success -> _newChat.value = _newChat.value.copy(
                        results = result.value,
                        hint = if (result.value.isEmpty()) "Никого не найдено" else null,
                    )

                    is Outcome.Failure -> _newChat.value = _newChat.value.copy(
                        results = emptyList(),
                        hint = result.error.toUserMessage(),
                    )
                }
            } finally {
                _newChat.value = _newChat.value.copy(isSearching = false)
            }
        }
    }

    /**
     * Открывает переписку с выбранным человеком.
     *
     * Требует сети: личный чат создаёт сервер, потому что только он может
     * гарантировать, что второго такого чата не появится.
     */
    fun startChatWith(userId: UserId) {
        viewModelScope.launch {
            when (val result = conversationRepository.openDirect(userId)) {
                is Outcome.Success -> {
                    closeNewChat()
                    _events.emit(ChatsEvent.OpenConversation(result.value))
                }

                is Outcome.Failure -> _newChat.value = _newChat.value.copy(
                    hint = result.error.toOnlineOnlyMessage(),
                )
            }
        }
    }

    // ─────────────────────────── новая группа ───────────────────────────

    fun toggleGroupMode() {
        val current = _newChat.value
        _newChat.value = current.copy(isGroup = !current.isGroup, selected = emptyList(), hint = null)
    }

    fun onGroupTitleChange(title: String) {
        _newChat.value = _newChat.value.copy(groupTitle = title.take(128))
    }

    fun toggleSelected(user: User) {
        val current = _newChat.value
        val selected = if (current.selected.any { it.id == user.id }) {
            current.selected.filterNot { it.id == user.id }
        } else {
            current.selected + user
        }
        _newChat.value = current.copy(selected = selected)
    }

    /** Группу создаёт сервер: только он назначает владельца и проверяет участников. */
    fun createGroup() {
        val state = _newChat.value
        if (!state.canCreateGroup) return
        viewModelScope.launch {
            _newChat.value = state.copy(isCreating = true, hint = null)
            when (val result = conversationRepository.createGroup(state.groupTitle, state.selected.map { it.id })) {
                is Outcome.Success -> {
                    closeNewChat()
                    _events.emit(ChatsEvent.OpenConversation(result.value))
                }
                is Outcome.Failure -> _newChat.value = _newChat.value.copy(
                    isCreating = false,
                    hint = result.error.toOnlineOnlyMessage(),
                )
            }
        }
    }

    private fun List<Conversation>.filterByQuery(query: String): List<Conversation> {
        if (query.isBlank()) return this
        val normalized = query.trim().lowercase()
        return filter { conversation ->
            conversation.title.lowercase().contains(normalized) ||
                conversation.lastMessage?.text?.lowercase()?.contains(normalized) == true
        }
    }

    private companion object {
        const val MIN_SEARCH_LENGTH = 2
    }
}
