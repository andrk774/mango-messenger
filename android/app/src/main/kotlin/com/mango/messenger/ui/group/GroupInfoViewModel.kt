package com.mango.messenger.ui.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mango.core.common.AppError
import com.mango.core.common.Outcome
import com.mango.core.data.repository.ConversationRepository
import com.mango.core.data.repository.UserRepository
import com.mango.core.model.Conversation
import com.mango.core.model.ConversationId
import com.mango.core.model.ConversationMember
import com.mango.core.model.MemberRole
import com.mango.core.model.User
import com.mango.core.model.UserId
import com.mango.messenger.navigation.Routes
import com.mango.messenger.ui.tasks.toOnlineOnlyMessage
import dagger.hilt.android.lifecycle.HiltViewModel
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

/** Действие над участником, доступное текущему пользователю. */
enum class MemberAction(val label: String) {
    MAKE_ADMIN("Сделать администратором"),
    MAKE_MEMBER("Снять права администратора"),
    TRANSFER_OWNERSHIP("Передать владение"),
    REMOVE("Исключить из группы"),
}

data class GroupInfoUiState(
    val conversation: Conversation? = null,
    val members: List<ConversationMember> = emptyList(),
    val currentUserId: UserId? = null,
    val isBusy: Boolean = false,
    /** Открытый диалог поиска людей для добавления. */
    val addDialog: AddMembersState? = null,
) {
    val myRole: MemberRole? get() = conversation?.myRole
    val canManage: Boolean get() = myRole?.canManageMembers == true

    /**
     * Действия над участником — по тем же правилам, что проверяет сервер.
     *
     * Сервер всё равно решает сам; здесь правила нужны, чтобы не
     * показывать кнопки, которые гарантированно получат отказ.
     */
    fun actionsFor(member: ConversationMember): List<MemberAction> {
        if (member.user.id == currentUserId) return emptyList()
        return when (myRole) {
            MemberRole.OWNER -> listOfNotNull(
                if (member.role == MemberRole.MEMBER) MemberAction.MAKE_ADMIN else null,
                if (member.role == MemberRole.ADMIN) MemberAction.MAKE_MEMBER else null,
                MemberAction.TRANSFER_OWNERSHIP,
                MemberAction.REMOVE,
            )
            MemberRole.ADMIN -> if (member.role == MemberRole.MEMBER) listOf(MemberAction.REMOVE) else emptyList()
            else -> emptyList()
        }
    }
}

data class AddMembersState(
    val query: String = "",
    val results: List<User> = emptyList(),
    val selected: List<User> = emptyList(),
    val hint: String? = null,
)

sealed interface GroupInfoEvent {
    data class Message(val text: String) : GroupInfoEvent

    /** Пользователь вышел из группы — экран и переписка закрываются. */
    data object Left : GroupInfoEvent
}

@HiltViewModel
class GroupInfoViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ConversationRepository,
    userRepository: UserRepository,
) : ViewModel() {

    private val conversationId = ConversationId(savedStateHandle.get<String>(Routes.ARG_CONVERSATION_ID).orEmpty())
    private val isBusy = MutableStateFlow(false)
    private val addDialog = MutableStateFlow<AddMembersState?>(null)

    private val _events = MutableSharedFlow<GroupInfoEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: Flow<GroupInfoEvent> = _events.asSharedFlow()

    val uiState: StateFlow<GroupInfoUiState> = combine(
        repository.observeConversation(conversationId),
        repository.observeMembersWithRoles(conversationId),
        userRepository.observeCurrentUser(),
        isBusy,
        addDialog,
    ) { conversation, members, me, busy, dialog ->
        GroupInfoUiState(conversation, members, me?.id, busy, dialog)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupInfoUiState())

    init {
        viewModelScope.launch {
            val result = repository.loadMembers(conversationId)
            if (result is Outcome.Failure && result.error !is AppError.Network) {
                _events.emit(GroupInfoEvent.Message(result.error.toOnlineOnlyMessage()))
            }
        }
    }

    fun rename(title: String) = run { repository.renameGroup(conversationId, title) }

    fun perform(member: ConversationMember, action: MemberAction) = run {
        when (action) {
            MemberAction.MAKE_ADMIN -> repository.changeRole(conversationId, member.user.id, MemberRole.ADMIN)
            MemberAction.MAKE_MEMBER -> repository.changeRole(conversationId, member.user.id, MemberRole.MEMBER)
            MemberAction.TRANSFER_OWNERSHIP -> repository.changeRole(conversationId, member.user.id, MemberRole.OWNER)
            MemberAction.REMOVE -> repository.removeMember(conversationId, member.user.id)
        }
    }

    fun leave() {
        viewModelScope.launch {
            isBusy.value = true
            when (val result = repository.leaveGroup(conversationId)) {
                is Outcome.Success -> _events.emit(GroupInfoEvent.Left)
                is Outcome.Failure -> _events.emit(GroupInfoEvent.Message(result.error.toOnlineOnlyMessage()))
            }
            isBusy.value = false
        }
    }

    // ───────────────────────── добавление людей ─────────────────────────

    fun openAddDialog() {
        addDialog.value = AddMembersState()
    }

    fun closeAddDialog() {
        addDialog.value = null
    }

    fun onAddQueryChange(query: String) {
        val current = addDialog.value ?: return
        addDialog.value = current.copy(query = query, hint = null)
        if (query.trim().length < 2) {
            addDialog.value = addDialog.value?.copy(results = emptyList())
            return
        }
        viewModelScope.launch {
            val existing = uiState.value.members.map { it.user.id }.toSet()
            when (val result = repository.searchPeople(query.trim())) {
                is Outcome.Success -> addDialog.value = addDialog.value?.copy(
                    // Уже состоящие не предлагаются: добавить их второй раз нельзя.
                    results = result.value.filterNot { it.id in existing },
                    hint = if (result.value.isEmpty()) "Никого не найдено" else null,
                )
                is Outcome.Failure -> addDialog.value = addDialog.value?.copy(hint = result.error.toOnlineOnlyMessage())
            }
        }
    }

    fun toggleCandidate(user: User) {
        val current = addDialog.value ?: return
        addDialog.value = current.copy(
            selected = if (current.selected.any { it.id == user.id }) {
                current.selected.filterNot { it.id == user.id }
            } else {
                current.selected + user
            },
        )
    }

    fun confirmAdd() {
        val selected = addDialog.value?.selected.orEmpty()
        if (selected.isEmpty()) return
        addDialog.value = null
        run { repository.addMembers(conversationId, selected.map { it.id }) }
    }

    /** Сетевая операция с индикатором занятости и показом отказа. */
    private fun run(block: suspend () -> Outcome<Unit>) {
        viewModelScope.launch {
            isBusy.value = true
            val result = block()
            if (result is Outcome.Failure) _events.emit(GroupInfoEvent.Message(result.error.toOnlineOnlyMessage()))
            isBusy.value = false
        }
    }
}
