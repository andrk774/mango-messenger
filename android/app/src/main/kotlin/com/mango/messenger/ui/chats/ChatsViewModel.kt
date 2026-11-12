package com.mango.messenger.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mango.core.data.repository.ConversationRepository
import com.mango.core.data.repository.SyncSnapshot
import com.mango.core.data.repository.SyncStatusRepository
import com.mango.core.model.Conversation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Состояние экрана списка чатов.
 *
 * Immutable data class как единый источник истины для UI: Compose
 * перерисовывает экран от одного объекта, а не от набора независимых
 * полей, которые могут разойтись между собой.
 */
data class ChatsUiState(
    val isLoading: Boolean = true,
    val conversations: List<Conversation> = emptyList(),
    val searchQuery: String = "",
    val sync: SyncSnapshot = SyncSnapshot(0, 0, null),
) {
    val isEmpty: Boolean get() = !isLoading && conversations.isEmpty()
}

@HiltViewModel
class ChatsViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    syncStatusRepository: SyncStatusRepository,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val uiState: StateFlow<ChatsUiState> = combine(
        conversationRepository.observeConversations(),
        // debounce на вводе: фильтрация запускается не на каждое нажатие,
        // иначе при длинном списке набор текста заметно подлагивает.
        searchQuery.debounce(150),
        syncStatusRepository.observeSnapshot(),
    ) { conversations, query, sync ->
        ChatsUiState(
            isLoading = false,
            conversations = conversations.filterByQuery(query),
            searchQuery = query,
            sync = sync,
        )
    }.stateIn(
        scope = viewModelScope,
        // Подписка живёт 5 секунд после уходa с экрана: при повороте и
        // быстром возврате данные не перезапрашиваются.
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatsUiState(),
    )

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    private fun List<Conversation>.filterByQuery(query: String): List<Conversation> {
        if (query.isBlank()) return this
        val normalized = query.trim().lowercase()
        return filter { conversation ->
            conversation.title.lowercase().contains(normalized) ||
                conversation.lastMessage?.text?.lowercase()?.contains(normalized) == true
        }
    }
}
