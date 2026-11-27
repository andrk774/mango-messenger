package com.mango.messenger.ui.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mango.core.designsystem.component.ConversationListItem
import com.mango.core.designsystem.component.EmptyState
import com.mango.core.designsystem.component.FullScreenLoading
import com.mango.core.designsystem.component.MangoSearchBar
import com.mango.core.designsystem.component.MangoTextButton
import com.mango.core.designsystem.component.MemberListItem
import com.mango.core.designsystem.component.PagingFooterLoading
import com.mango.core.designsystem.component.MangoTopAppBar
import com.mango.core.designsystem.component.SyncStatusIndicator
import com.mango.core.model.Conversation
import com.mango.core.model.SyncStatus
import com.mango.messenger.ui.format.TimeFormatters

/**
 * Экран списка чатов.
 *
 * Данные приходят из Room через репозиторий, поэтому экран полностью
 * работоспособен без сети. Состояние синхронизации показывается в заголовке:
 * пользователь видит, что часть изменений ещё не доставлена.
 */
@Composable
fun ChatsScreen(
    onConversationClick: (Conversation) -> Unit,
    onOpenConversationById: (String) -> Unit,
    modifier: Modifier = Modifier,
    onWelcome: () -> Unit = {},
    startNewChat: Boolean = false,
    viewModel: ChatsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val newChat by viewModel.newChat.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSearch by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var plusMenu by remember { androidx.compose.runtime.mutableStateOf(false) }
    val context=androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(startNewChat) { if(startNewChat) viewModel.openNewChat() }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ChatsEvent.Message -> snackbarHostState.showSnackbar(event.text)
                is ChatsEvent.OpenConversation -> onOpenConversationById(event.id.value)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            MangoTopAppBar(
                title = "Чаты",
                subtitle = when {
                    state.sync.hasProblems ->
                        "${state.sync.failedOperations} не отправлено"
                    state.sync.pendingOperations > 0 ->
                        "Синхронизация: ${state.sync.pendingOperations} в очереди"
                    else -> null
                },
                actions = {
                    androidx.compose.material3.IconButton(onClick={showSearch=!showSearch}) {
                        Icon(Icons.Default.Search,contentDescription="Поиск чатов")
                    }
                    if (!state.sync.isFullySynced) {
                        SyncStatusIndicator(
                            status = if (state.sync.hasProblems) {
                                SyncStatus.FAILED
                            } else {
                                SyncStatus.IN_FLIGHT
                            },
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = {plusMenu=true},shape=androidx.compose.foundation.shape.CircleShape,
                containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary) {
                Icon(Icons.Default.Add, contentDescription = "Добавить")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            com.mango.messenger.ui.stories.StoriesEntry()
            com.mango.core.designsystem.component.MangoWelcomeBanner("Добро пожаловать в Mango", "Четыре шага для лёгкого старта", onWelcome)
            if(showSearch) MangoSearchBar(
                query = state.searchQuery,
                onQueryChange = viewModel::onSearchQueryChange,
                placeholder = "Поиск по чатам",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            when {
                state.isLoading -> FullScreenLoading()

                state.isEmpty && state.searchQuery.isBlank() -> EmptyState(
                    icon = Icons.AutoMirrored.Outlined.Chat,
                    title = "Пока нет чатов",
                    description = "Начните разговор с другом или соберите команду в группе.",
                    actionText = "Начать общение",
                    onAction = viewModel::openNewChat,
                )

                state.conversations.isEmpty() -> EmptyState(
                    icon = Icons.AutoMirrored.Outlined.Chat,
                    title = "Ничего не найдено",
                    description = "По запросу «${state.searchQuery}» чатов нет.",
                )

                else -> ConversationList(
                    conversations = state.conversations,
                    onConversationClick = onConversationClick,
                )
            }
        }
    }

    if (newChat.isOpen) {
        NewChatDialog(
            state = newChat,
            onQueryChange = viewModel::onPeopleQueryChange,
            onSelect = { viewModel.startChatWith(it.id) },
            onToggleGroup = viewModel::toggleGroupMode,
            onGroupTitleChange = viewModel::onGroupTitleChange,
            onToggleSelected = viewModel::toggleSelected,
            onCreateGroup = viewModel::createGroup,
            onDismiss = viewModel::closeNewChat,
        )
    }
    if(plusMenu) androidx.compose.material3.AlertDialog(onDismissRequest={plusMenu=false},title={Text("Добавить")},text={Column{
        androidx.compose.material3.TextButton(onClick={plusMenu=false;viewModel.openNewChat()}){Text("Новый чат")}
        for(label in listOf("Контакты на телефоне","Облачная синхронизация")) androidx.compose.material3.TextButton(onClick={plusMenu=false;context.startActivity(android.content.Intent(context,com.mango.messenger.ui.misc.PhoneContactsActivity::class.java).putExtra("pick",label=="Контакты на телефоне"))}){Text(label)}
    }},confirmButton={androidx.compose.material3.TextButton(onClick={plusMenu=false}){Text("Закрыть")}})
}

@Composable
private fun ConversationList(
    conversations: List<Conversation>,
    onConversationClick: (Conversation) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(
            items = conversations,
            // Стабильный ключ: без него Compose пересоздаёт элементы при
            // любом изменении списка, теряя состояние и вызывая лишние recomposition.
            key = { it.id.value },
        ) { conversation ->
            ConversationListItem(
                title = conversation.title,
                initials = conversation.title.initials(),
                avatarUrl = conversation.avatarUrl,
                avatarSeed = conversation.id.value,
                lastMessageText = conversation.lastMessage?.previewText() ?: "Нет сообщений",
                lastMessageSenderPrefix = conversation.lastMessage
                    ?.senderName
                    ?.takeIf { conversation.memberCount > 2 && it.isNotBlank() },
                timeLabel = TimeFormatters.conversationTimestamp(
                    conversation.lastMessage?.createdAt ?: conversation.updatedAt,
                ),
                unreadCount = conversation.unreadCount,
                isMuted = conversation.isMuted,
                deliveryStatus = conversation.lastMessage
                    ?.deliveryStatus
                    ?.takeIf { it.isLocalOnly },
                onClick = { onConversationClick(conversation) },
            )
            HorizontalDivider(Modifier.padding(start = 80.dp))
        }
    }
}

@Composable
private fun NewChatDialog(
    state: NewChatUiState,
    onQueryChange: (String) -> Unit,
    onSelect: (com.mango.core.model.User) -> Unit,
    onToggleGroup: () -> Unit,
    onGroupTitleChange: (String) -> Unit,
    onToggleSelected: (com.mango.core.model.User) -> Unit,
    onCreateGroup: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.isGroup) "Новая группа" else "Новая переписка") },
        text = {
            Column {
                if (state.isGroup) {
                    androidx.compose.material3.OutlinedTextField(
                        value = state.groupTitle,
                        onValueChange = onGroupTitleChange,
                        label = { Text("Название группы") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.selected.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Участники: " + state.selected.joinToString(", ") { it.displayName },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                MangoSearchBar(
                    query = state.query,
                    onQueryChange = onQueryChange,
                    placeholder = "Имя или имя пользователя",
                )
                Spacer(Modifier.height(12.dp))

                when {
                    state.isSearching || state.isCreating -> PagingFooterLoading()

                    state.hint != null -> Text(
                        text = state.hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(items = state.results, key = { it.id.value }) { user ->
                            val chosen = state.selected.any { it.id == user.id }
                            MemberListItem(
                                displayName = user.displayName,
                                initials = user.initials,
                                roleLabel = if (chosen) "✓ выбран" else "@${user.username}",
                                avatarUrl = user.avatarUrl,
                                onClick = { if (state.isGroup) onToggleSelected(user) else onSelect(user) },
                            )
                            HorizontalDivider(Modifier.padding(start = 68.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (state.isGroup) {
                MangoTextButton(text = "Создать", onClick = onCreateGroup, enabled = state.canCreateGroup)
            } else {
                MangoTextButton(text = "Закрыть", onClick = onDismiss)
            }
        },
        dismissButton = {
            MangoTextButton(
                text = if (state.isGroup) "Личная переписка" else "Создать группу",
                onClick = onToggleGroup,
            )
        },
    )
}

private fun com.mango.core.model.MessagePreview.previewText(): String = when {
    isDeleted -> "Сообщение удалено"
    !text.isNullOrBlank() -> text.orEmpty()
    else -> when (type) {
        com.mango.core.model.MessageType.IMAGE -> "Фотография"
        com.mango.core.model.MessageType.VIDEO -> "Видео"
        com.mango.core.model.MessageType.VOICE -> "Голосовое сообщение"
        com.mango.core.model.MessageType.FILE -> "Файл"
        com.mango.core.model.MessageType.SYSTEM -> com.mango.messenger.ui.format.SystemEventFormatter.text(system)
        com.mango.core.model.MessageType.TEXT -> "Пустое сообщение"
    }
}

/** Инициалы из названия чата — для аватара-заглушки. */
internal fun String.initials(): String =
    trim().split(' ')
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "?" }
