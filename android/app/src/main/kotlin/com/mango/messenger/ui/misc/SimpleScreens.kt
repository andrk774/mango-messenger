package com.mango.messenger.ui.misc

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mango.core.data.repository.UserRepository
import com.mango.core.designsystem.component.EmptyState
import com.mango.core.designsystem.component.MangoSearchBar
import com.mango.core.designsystem.component.MangoTopAppBar
import com.mango.core.designsystem.component.MemberListItem
import com.mango.core.model.User
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class ContactsUiState(
    val query: String = "",
    val contacts: List<User> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val userRepository: UserRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ContactsUiState> = query
        .flatMapLatest { text ->
            val source = if (text.isBlank()) {
                userRepository.observeContacts()
            } else {
                userRepository.observeSearch(text)
            }
            source.map { users ->
                ContactsUiState(query = text, contacts = users, isLoading = false)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContactsUiState())

    fun onQueryChange(text: String) {
        query.value = text
    }
}

@Composable
fun ContactsScreen(
    modifier: Modifier = Modifier,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = { MangoTopAppBar(title = "Контакты") },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            MangoSearchBar(
                query = state.query,
                onQueryChange = viewModel::onQueryChange,
                placeholder = "Поиск людей",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (state.contacts.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.People,
                    title = if (state.query.isBlank()) "Список контактов пуст" else "Никого не найдено",
                    description = if (state.query.isBlank()) {
                        "Контакты появятся после подключения серверной части и поиска пользователей."
                    } else {
                        null
                    },
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(items = state.contacts, key = { it.id.value }) { user ->
                        MemberListItem(
                            displayName = user.displayName,
                            initials = user.initials,
                            roleLabel = "@${user.username}",
                            avatarUrl = user.avatarUrl,
                            presence = user.presence,
                        )
                        HorizontalDivider(Modifier.padding(start = 68.dp))
                    }
                }
            }
        }
    }
}

/**
 * Экран уведомлений.
 *
 * Лента заполняется на Этапе 6 вместе с push-доставкой. Экран присутствует
 * в навигации уже сейчас, но честно сообщает о своём состоянии, а не
 * показывает выдуманные данные.
 */
@Composable
fun NotificationsScreen(modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = { MangoTopAppBar(title = "Уведомления") },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            EmptyState(
                icon = Icons.Outlined.Notifications,
                title = "Уведомлений нет",
                description = "Здесь появятся упоминания, назначенные задачи, " +
                    "напоминания и события чатов. Доставка push включается на Этапе 6.",
            )
        }
    }
}
