package com.mango.messenger.ui.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mango.core.designsystem.component.ConversationListItem
import com.mango.core.designsystem.component.EmptyState
import com.mango.core.designsystem.component.FullScreenLoading
import com.mango.core.designsystem.component.MangoSearchBar
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
    modifier: Modifier = Modifier,
    viewModel: ChatsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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
    ) { padding ->
        Column(Modifier.padding(padding)) {
            MangoSearchBar(
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
                    description = "Личные и групповые переписки появятся здесь. " +
                        "Обмен сообщениями включается на следующем этапе разработки.",
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

private fun com.mango.core.model.MessagePreview.previewText(): String = when {
    isDeleted -> "Сообщение удалено"
    !text.isNullOrBlank() -> text.orEmpty()
    else -> when (type) {
        com.mango.core.model.MessageType.IMAGE -> "Фотография"
        com.mango.core.model.MessageType.VIDEO -> "Видео"
        com.mango.core.model.MessageType.VOICE -> "Голосовое сообщение"
        com.mango.core.model.MessageType.FILE -> "Файл"
        com.mango.core.model.MessageType.SYSTEM -> "Системное сообщение"
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
