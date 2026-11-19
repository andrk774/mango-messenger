package com.mango.messenger.ui.conversation

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mango.core.designsystem.component.ConversationTopAppBar
import com.mango.core.designsystem.component.EmptyState
import com.mango.core.designsystem.component.MessageBubble
import com.mango.core.designsystem.component.ReactionChipData
import com.mango.core.model.Attachment
import com.mango.core.model.Message
import com.mango.core.model.MessageType
import com.mango.core.model.SyncStatus
import com.mango.core.model.UserId
import com.mango.messenger.ui.chats.initials
import com.mango.messenger.ui.format.FileSizeFormatter
import com.mango.messenger.ui.format.SystemEventFormatter
import com.mango.messenger.ui.format.TimeFormatters
import java.io.File

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
    onOpenInfo: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var showSearch by remember { mutableStateOf(false) }
    var showEmoji by remember { mutableStateOf(false) }
    var showStickers by remember { mutableStateOf(false) }
    if (showEmoji) EmojiPicker("Эмоджи", composerEmojis, onPick = {
        viewModel.onComposerChange(state.composerText + it)
    }, onDismiss = { showEmoji = false })
    if (showSearch) MessageSearchDialog(viewModel, onDismiss = { showSearch = false })

    // Системный выбор файла: приложению не нужно разрешение на чтение
    // всего хранилища — доступ даётся только к выбранному файлу.
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::sendAttachment)
    }
    val pickSticker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::sendSticker)
    }
    if (showStickers) StickerPicker(onSend = viewModel::sendUncaptionedAttachment, onOwnPicture = {
        showStickers = false
        pickSticker.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/gif"))
    }, onDismiss = { showStickers = false })

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ConversationEvent.Message -> snackbarHostState.showSnackbar(event.text)
                is ConversationEvent.OpenFile -> {
                    val opened = openFile(context, event.file, event.mimeType)
                    if (!opened) snackbarHostState.showSnackbar("Нет приложения, чтобы открыть этот файл")
                }
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
        containerColor = com.mango.messenger.ui.settings.chatBackgroundColor(appearance.chatBackground),
        topBar = {
            ConversationTopAppBar(
                title = state.title,
                initials = state.title.initials(),
                avatarUrl = state.conversation?.avatarUrl,
                statusLine = when {
                    state.hasUnsent -> "${state.pendingOperations} в очереди отправки"
                    state.isGroup -> "${state.conversation?.memberCount ?: 0} участников"
                    else -> null
                },
                onNavigateBack = onNavigateBack,
                onTitleClick = if (state.isGroup) ({ onOpenInfo(state.conversationId) }) else null,
                actions = {
                    androidx.compose.material3.TextButton(onClick = { showSearch = true }) { Text("Поиск") }
                    if (state.isGroup) {
                        IconButton(onClick = { onOpenInfo(state.conversationId) }) {
                            Icon(Icons.Outlined.Info, contentDescription = "О группе")
                        }
                    }
                },
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
                    senderNames = if (state.isGroup) state.memberNames else emptyMap(),
                    listState = listState,
                    onRetry = viewModel::retry,
                    onReaction = viewModel::react,
                    onOpenAttachment = viewModel::openAttachment,
                    loadImage = viewModel::imageFile,
                    onReport = viewModel::reportUser,
                    onTranscribe = viewModel::transcribe,
                    modifier = Modifier.weight(1f),
                )
            }

            VoiceComposer(onSend = viewModel::sendUncaptionedAttachment)
            Row {
                androidx.compose.material3.TextButton(onClick = { showEmoji = true }) { Text("☺ Эмоджи") }
                androidx.compose.material3.TextButton(onClick = { showStickers = true }) { Text("Стикеры") }
            }
            Composer(
                text = state.composerText,
                canSend = state.canSend,
                onTextChange = viewModel::onComposerChange,
                onSend = viewModel::send,
                onAttach = { pickFile.launch(ATTACHABLE_TYPES) },
            )
        }
    }
}

/** Типы для системного выбора файла — те же, что принимает сервер. */
private val ATTACHABLE_TYPES = arrayOf(
    "image/jpeg", "image/png", "image/gif", "image/webp", "image/heic",
    "video/mp4", "video/webm",
    "audio/ogg", "audio/mpeg", "audio/mp4", "audio/aac", "audio/webm",
    "application/pdf", "application/zip", "text/plain",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
)

/**
 * Открытие файла внешним приложением.
 *
 * Через FileProvider: прямой путь file:// запрещён начиная с Android 7,
 * а временное разрешение на чтение выдаётся только открывающему приложению.
 */
private fun openFile(context: android.content.Context, file: File, mimeType: String): Boolean {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

@Composable
private fun MessageList(
    messages: List<Message>,
    currentUserId: UserId?,
    senderNames: Map<UserId, String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onRetry: (Message) -> Unit,
    onReaction: (Message, String) -> Unit,
    onOpenAttachment: (Attachment) -> Unit,
    loadImage: suspend (Attachment) -> File?,
    onReport: suspend (String,String) -> String,
    onTranscribe: suspend (String) -> String,
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
            if (message.type == MessageType.SYSTEM) {
                SystemLine(SystemEventFormatter.text(message.system))
                return@items
            }

            val isOutgoing = currentUserId != null && message.senderId == currentUserId

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start,
            ) {
                if (!message.isDeleted) {
                    message.attachments.filterNot { it.mimeType.startsWith("audio/") }.forEach { attachment ->
                        if (attachment.isImage) {
                            ImageAttachment(attachment, loadImage, onClick = { onOpenAttachment(attachment) })
                        } else {
                            FileAttachment(attachment, onClick = { onOpenAttachment(attachment) })
                        }
                        Spacer(Modifier.size(2.dp))
                    }
                }

                // Пузырь — для текста или подписи. Сообщение-картинка без
                // подписи обходится без пустого пузыря, но время и статус
                // доставки всё равно нужны — они показываются пузырём.
                MessageBubble(
                    mediaContent = {
                        message.attachments.filter {it.mimeType.startsWith("audio/")}.forEach { attachment ->
                            VoicePlayback(attachment.id.value,load={loadImage(attachment)},durationMs=attachment.durationMs,
                                transcribe=if(message.syncStatus==SyncStatus.SYNCED) ({onTranscribe(attachment.id.value)}) else null)
                            UploadState(attachment)
                        }
                    },
                    text = message.text,
                    isOutgoing = isOutgoing,
                    timeLabel = TimeFormatters.messageTime(message.createdAt),
                    deliveryStatus = message.deliveryStatus,
                    senderName = if (isOutgoing) null else senderNames[message.senderId],
                    replyTo = message.replyTo,
                    reactions = message.reactions.map {
                        ReactionChipData(it.emoji, it.count, it.reactedByMe)
                    },
                    isEdited = message.isEdited,
                    isDeleted = message.isDeleted,
                    isPinned = message.isPinned,
                    onReactionClick = { onReaction(message, it) },
                )

                if (message.canRetry) {
                    com.mango.core.designsystem.component.MangoTextButton(
                        text = "Отправить снова",
                        onClick = { onRetry(message) },
                    )
                }
                if (!message.isDeleted && message.syncStatus == SyncStatus.SYNCED) {
                    var showReport by remember(message.id) { mutableStateOf(false) }
                    if(showReport) ReportDialog(onDismiss={showReport=false},onSend={reason -> onReport(message.senderId.value,reason)})
                    var showReactions by remember(message.id) { mutableStateOf(false) }
                    if (showReactions) EmojiPicker("Реакция", reactionEmojis, onPick = {
                        onReaction(message, it); showReactions = false
                    }, onDismiss = { showReactions = false })
                    Row {
                        listOf("👍", "❤️", "😂").forEach { emoji ->
                            androidx.compose.material3.TextButton(onClick = { onReaction(message, emoji) }) { Text(emoji) }
                        }
                        androidx.compose.material3.TextButton(onClick = { showReactions = true }) { Text("＋") }
                        if(!isOutgoing) Box {
                            var menu by remember { mutableStateOf(false) }
                            androidx.compose.material3.TextButton(onClick={menu=true}) {Text("Ещё")}
                            androidx.compose.material3.DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                                androidx.compose.material3.DropdownMenuItem(text={Text("Пожаловаться на пользователя")},onClick={menu=false;showReport=true})
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
    )
}

/**
 * Превью картинки.
 *
 * Своя картинка показывается сразу из локального файла; чужая
 * скачивается по временной ссылке один раз и дальше берётся с диска.
 */
@Composable
private fun ImageAttachment(
    attachment: Attachment,
    loadImage: suspend (Attachment) -> File?,
    onClick: () -> Unit,
) {
    var file by remember(attachment.id) { mutableStateOf(attachment.localUri?.let(::File)?.takeIf { it.isFile }) }
    var failed by remember(attachment.id) { mutableStateOf(false) }
    LaunchedEffect(attachment.id) {
        if (file == null) {
            file = loadImage(attachment)
            failed = file == null
        }
    }

    val ratio = if ((attachment.width ?: 0) > 0 && (attachment.height ?: 0) > 0) {
        (attachment.width!!.toFloat() / attachment.height!!).coerceIn(0.5f, 2f)
    } else {
        4f / 3f
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .widthIn(max = 260.dp)
            .fillMaxWidth(0.75f)
            .aspectRatio(ratio)
            .clip(MaterialTheme.shapes.large)
            .clickable(enabled = file != null, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            when {
                file != null -> AsyncImage(
                    model = file,
                    contentDescription = attachment.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                failed -> Icon(Icons.Outlined.Image, contentDescription = "Картинка недоступна")
                else -> CircularProgressIndicator(Modifier.size(32.dp))
            }
            UploadState(attachment, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun FileAttachment(attachment: Attachment, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .widthIn(max = 280.dp)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = attachment.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = FileSizeFormatter.format(attachment.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            UploadState(attachment)
        }
    }
}

/** Прогресс загрузки и отказ — общие для картинок и файлов. */
@Composable
private fun UploadState(attachment: Attachment, modifier: Modifier = Modifier) {
    when {
        attachment.syncStatus == SyncStatus.FAILED -> Text(
            text = "Файл не загружен",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier.padding(4.dp),
        )
        attachment.isUploading -> LinearProgressIndicator(
            progress = { attachment.uploadProgress ?: 0f },
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .heightIn(min = 4.dp),
        )
    }
}

@Composable
private fun Composer(
    text: String,
    canSend: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
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
                IconButton(onClick = onAttach, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.AttachFile, contentDescription = "Прикрепить файл")
                }
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
