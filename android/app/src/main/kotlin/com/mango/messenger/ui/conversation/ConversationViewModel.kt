package com.mango.messenger.ui.conversation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mango.core.common.Outcome
import com.mango.core.data.repository.ConversationRepository
import com.mango.core.data.repository.MessageRepository
import com.mango.core.data.repository.SyncStatusRepository
import com.mango.core.data.repository.UserRepository
import com.mango.core.model.Conversation
import com.mango.core.model.ConversationId
import com.mango.core.model.Message
import com.mango.core.model.UserId
import com.mango.messenger.navigation.Routes
import com.mango.messenger.ui.tasks.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConversationUiState(
    val conversationId: String = "",
    val title: String = "",
    val messages: List<Message> = emptyList(),
    val conversation: Conversation? = null,
    val currentUserId: UserId? = null,
    val composerText: String = "",
    val isLoadingHistory: Boolean = false,
    val pendingOperations: Int = 0,
    /** Имена участников — подписи к чужим сообщениям в группе. */
    val memberNames: Map<UserId, String> = emptyMap(),
) {
    val isGroup: Boolean get() = conversation?.type == com.mango.core.model.ConversationType.GROUP

    val isEmpty: Boolean get() = messages.isEmpty() && !isLoadingHistory
    val canSend: Boolean get() = composerText.isNotBlank()

    /** Есть ли неотправленное — показывается в заголовке. */
    val hasUnsent: Boolean get() = pendingOperations > 0
}

sealed interface ConversationEvent {
    data class Message(val text: String) : ConversationEvent

    /** Файл скачан — экран открывает его подходящим приложением. */
    data class OpenFile(val file: java.io.File, val mimeType: String) : ConversationEvent
}

@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val messagingRemote: com.mango.core.network.MessagingRemoteDataSource,
    private val eventApplier: com.mango.core.sync.EventApplier,
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val userRepository: UserRepository,
    syncStatusRepository: SyncStatusRepository,
) : ViewModel() {

    private val conversationId: ConversationId = ConversationId(
        savedStateHandle.get<String>(Routes.ARG_CONVERSATION_ID).orEmpty(),
    )

    private val composerText = MutableStateFlow("")
    private val reacting = mutableSetOf<String>()
    fun react(message: Message, emoji: String) {
        val key = "${message.id.value}:$emoji"
        if (!reacting.add(key)) return
        viewModelScope.launch {
            try {
                val add = message.reactions.none { it.emoji == emoji && it.reactedByMe }
                when (val result = messagingRemote.react(message.id.value, emoji, add)) {
                    is Outcome.Success -> eventApplier.applyServerMessage(result.value)
                    is Outcome.Failure -> _events.emit(ConversationEvent.Message(result.error.toUserMessage()))
                }
            } finally { reacting.remove(key) }
        }
    }
    val searchResults = MutableStateFlow<List<com.mango.core.network.dto.MessageDto>>(emptyList())
    val searchBusy = MutableStateFlow(false)
    val searchError = MutableStateFlow<String?>(null)
    private var searchJob: kotlinx.coroutines.Job? = null
    private var searchQuery = ""
    fun searchMessages(query: String, more: Boolean = false) {
        val normalizedQuery = query.trim()
        val append = more && normalizedQuery == searchQuery
        searchQuery = normalizedQuery
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            searchError.value = null
            if (!append) searchResults.value = emptyList()
            if (query.trim().length < 2) { searchBusy.value = false; return@launch }
            searchBusy.value = true
            try {
                when (val result = messagingRemote.searchMessages(conversationId.value, normalizedQuery, if (append) searchResults.value.lastOrNull()?.seq else null)) {
                    is Outcome.Success -> searchResults.value = ((if (append) searchResults.value else emptyList()) + result.value.items).distinctBy { it.id }
                    is Outcome.Failure -> searchError.value = result.error.toUserMessage()
                }
            } finally { searchBusy.value = false }
        }
    }
    private val isLoadingHistory = MutableStateFlow(false)

    private val _events = MutableSharedFlow<ConversationEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: Flow<ConversationEvent> = _events.asSharedFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ConversationUiState> = combine(
        messageRepository.observeRecentMessages(conversationId, limit = 200),
        conversationRepository.observeConversation(conversationId),
        userRepository.observeCurrentUser(),
        composerText,
        combine(
            isLoadingHistory,
            syncStatusRepository.observeSnapshot(),
            conversationRepository.observeMembersWithRoles(conversationId),
        ) { loading, sync, members ->
            Triple(loading, sync.pendingOperations, members.associate { it.user.id to it.user.displayName })
        },
    ) { messages, conversation, currentUser, text, (loading, pending, names) ->
        ConversationUiState(
            conversationId = conversationId.value,
            title = conversation?.title ?: "Переписка",
            messages = messages,
            conversation = conversation,
            currentUserId = currentUser?.id,
            composerText = text,
            isLoadingHistory = loading,
            pendingOperations = pending,
            memberNames = names,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ConversationUiState(conversationId = conversationId.value),
    )

    init {
        loadHistory()
        loadMembers()
    }

    fun onComposerChange(value: String) {
        composerText.value = value
    }

    /**
     * Отправка.
     *
     * Поле очищается сразу, не дожидаясь ответа сервера: сообщение уже
     * сохранено локально и уедет само. Ожидание здесь означало бы, что
     * в офлайне набранный текст «зависает» в поле.
     */
    fun send() {
        val text = composerText.value.trim()
        if (text.isEmpty()) return

        composerText.value = ""

        viewModelScope.launch {
            when (val result = messageRepository.sendText(conversationId, text)) {
                is Outcome.Success -> Unit
                is Outcome.Failure -> {
                    // Текст возвращается в поле: пропажа набранного —
                    // худшее, что может сделать мессенджер.
                    composerText.value = text
                    _events.emit(ConversationEvent.Message(result.error.toUserMessage()))
                }
            }
        }
    }

    /**
     * Отправка файла.
     *
     * Подпись берётся из поля ввода: так привычнее, чем отдельный диалог,
     * и набранный текст не теряется.
     */
    fun sendAttachment(uri: android.net.Uri) {
        val caption = composerText.value.trim().takeIf { it.isNotEmpty() }
        composerText.value = ""
        viewModelScope.launch {
            when (val result = messageRepository.sendAttachment(conversationId, uri, caption)) {
                is Outcome.Success -> Unit
                is Outcome.Failure -> {
                    caption?.let { composerText.value = it }
                    _events.emit(ConversationEvent.Message(result.error.toUserMessage()))
                }
            }
        }
    }

    suspend fun sendVoice(uri: android.net.Uri): Boolean = when (val result = messageRepository.sendAttachment(conversationId, uri, null)) {
        is Outcome.Success -> true
        is Outcome.Failure -> { _events.emit(ConversationEvent.Message(result.error.toUserMessage())); false }
    }

    /** Файл картинки для превью: свой — сразу, чужой — после скачивания. */
    suspend fun imageFile(attachment: com.mango.core.model.Attachment): java.io.File? =
        when (val result = messageRepository.attachmentFile(attachment.id)) {
            is Outcome.Success -> result.value
            is Outcome.Failure -> null
        }

    fun openAttachment(attachment: com.mango.core.model.Attachment) {
        viewModelScope.launch {
            when (val result = messageRepository.attachmentFile(attachment.id)) {
                is Outcome.Success -> _events.emit(ConversationEvent.OpenFile(result.value, attachment.mimeType))
                is Outcome.Failure -> _events.emit(ConversationEvent.Message(result.error.toUserMessage()))
            }
        }
    }

    fun retry(message: Message) {
        viewModelScope.launch {
            messageRepository.retry(message.clientMsgId)
        }
    }

    /** Отмечает прочитанным всё до последнего подтверждённого сообщения. */
    fun markRead() {
        viewModelScope.launch {
            val lastSeq = uiState.value.messages.mapNotNull { it.seq }.maxOrNull() ?: return@launch
            conversationRepository.markRead(conversationId, lastSeq)
        }
    }

    /** Догрузка более ранних сообщений при прокрутке вверх. */
    fun loadOlder() {
        val oldest = uiState.value.messages.mapNotNull { it.seq }.minOrNull() ?: return
        loadHistory(beforeSeq = oldest)
    }

    private fun loadHistory(beforeSeq: Long? = null) {
        viewModelScope.launch {
            isLoadingHistory.value = true
            try {
                val result = messageRepository.loadHistory(conversationId, beforeSeq)
                if (result is Outcome.Failure) {
                    // Отсутствие сети не повод показывать ошибку: история
                    // уже есть локально, и экран остаётся рабочим.
                    if (result.error !is com.mango.core.common.AppError.Network) {
                        _events.emit(ConversationEvent.Message(result.error.toUserMessage()))
                    }
                }
            } finally {
                isLoadingHistory.value = false
            }
        }
    }

    private fun loadMembers() {
        viewModelScope.launch { conversationRepository.loadMembers(conversationId) }
    }
}
