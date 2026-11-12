package com.mango.messenger.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mango.core.designsystem.component.ConversationTopAppBar
import com.mango.core.designsystem.component.EmptyState
import com.mango.core.designsystem.component.MessageBubble
import com.mango.core.designsystem.component.ReactionChipData
import com.mango.core.model.Message
import com.mango.core.model.UserId
import com.mango.messenger.ui.chats.initials
import com.mango.messenger.ui.format.TimeFormatters

/**
 * Экран переписки.
 *
 * Список рисуется перевёрнутым (`reverseLayout`): новые сообщения внизу,
 * и при добавлении сообщения не нужно вручную прокручивать — позиция
 * сохраняется сама. Это же избавляет от скачка при догрузке истории вверх.
 */
@Composable
fun ConversationScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ConversationEvent.Message -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    // Отметка о прочтении ставится, когда список показал последние
    // сообщения, а не при открытии экрана: иначе чат считался бы
    // прочитанным даже при мгновенном выходе назад.
    val reachedBottom by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 }
    }
    LaunchedEffect(reachedBottom, state.messages.size) {
        if (reachedBottom && state.messages.isNotEmpty()) viewModel.markRead()
    }

    // Догрузка истории при приближении к началу переписки.
    val shouldLoadOlder by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && last >= total - 5
        }
    }
    LaunchedEffect(shouldLoadOlder) {
        if (shouldLoadOlder) viewModel.loadOlder()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            ConversationTopAppBar(
                title = state.title,
                initials = state.title.initials(),
                avatarUrl = state.conversation?.avatarUrl,
                statusLine = when {
                    state.hasUnsent -> "${state.pendingOperations} в очереди отправки"
                    state.conversation?.memberCount?.let { it > 2 } == true ->
                        "${state.conversation?.memberCount} участников"
                    else -> null
                },
                onNavigateBack = onNavigateBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            if (state.isEmpty) {
                EmptyState(
                    icon = Icons.AutoMirrored.Outlined.Chat,
                    title = "Сообщений пока нет",
                    description = "Напишите первое — оно отправится даже без сети.",
                    modifier = Modifier.weight(1f),
                )
            } else {
                MessageList(
                    messages = state.messages,
                    currentUserId = state.currentUserId,
                    listState = listState,
                    onRetry = viewModel::retry,
                    modifier = Modifier.weight(1f),
                )
            }

            Composer(
                text = state.composerText,
                canSend = state.canSend,
                onTextChange = viewModel::onComposerChange,
                onSend = viewModel::send,
            )
        }
    }
}

@Composable
private fun MessageList(
    messages: List<Message>,
    currentUserId: UserId?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onRetry: (Message) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        // Список уже отсортирован от новых к старым, поэтому переворот
        // ставит свежие сообщения внизу экрана.
        reverseLayout = true,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 12.dp,
            vertical = 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(
            items = messages,
            // Ключ — клиентский идентификатор, а не серверный: он не
            // меняется при подтверждении отправки, поэтому Compose
            // не пересоздаёт элемент и не теряет его состояние.
            key = { it.clientMsgId.value },
        ) { message ->
            val isOutgoing = currentUserId != null && message.senderId == currentUserId

            Column {
                MessageBubble(
                    text = message.text,
                    isOutgoing = isOutgoing,
                    timeLabel = TimeFormatters.messageTime(message.createdAt),
                    deliveryStatus = message.deliveryStatus,
                    senderName = null,
                    replyTo = message.replyTo,
                    reactions = message.reactions.map {
                        ReactionChipData(it.emoji, it.count, it.reactedByMe)
                    },
                    isEdited = message.isEdited,
                    isDeleted = message.isDeleted,
                    isPinned = message.isPinned,
                )

                if (message.canRetry) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        com.mango.core.designsystem.component.MangoTextButton(
                            text = "Отправить снова",
                            onClick = { onRetry(message) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(
    text: String,
    canSend: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Сообщение") },
                    maxLines = 5,
                    shape = MaterialTheme.shapes.extraLarge,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = onSend,
                    enabled = canSend,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Отправить",
                        tint = if (canSend) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    )
                }
            }
        }
    }
}
