package com.mango.messenger.ui.tasks

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mango.core.common.Outcome
import com.mango.core.data.repository.TaskRepository
import com.mango.core.designsystem.component.MangoTopAppBar
import com.mango.core.designsystem.component.label
import com.mango.core.model.TaskId
import com.mango.core.model.TaskStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant
import javax.inject.Inject

internal fun editedDeadline(date: String, previous: Instant?, zone: ZoneId): Long? {
    if (date.isBlank()) return null
    val day = LocalDate.parse(date.trim())
    // Изменение названия не должно сбрасывать время существующего срока на полночь.
    return if (previous?.atZone(zone)?.toLocalDate() == day) previous.toEpochMilli()
    else day.atStartOfDay(zone).toInstant().toEpochMilli()
}

@HiltViewModel
class TaskDetailsViewModel @Inject constructor(
    state: SavedStateHandle,
    private val repository: TaskRepository,
    private val remote: com.mango.core.network.TasksRemoteDataSource,
    private val conversations: com.mango.core.data.repository.ConversationRepository,
    private val sync: com.mango.core.sync.SyncEngine,
) : ViewModel() {
    private val id = TaskId(checkNotNull(state.get<String>("taskId")))
    val task = repository.observeTask(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val feedback = MutableStateFlow<String?>(null)
    val comments = MutableStateFlow<List<com.mango.core.network.dto.TaskCommentDto>>(emptyList())
    val history = MutableStateFlow<List<com.mango.core.network.dto.TaskEventDto>>(emptyList())
    val detailError = MutableStateFlow<String?>(null)
    val commentBusy = MutableStateFlow(false)
    val commentText = MutableStateFlow("")
    val members = MutableStateFlow<List<com.mango.core.model.ConversationMember>>(emptyList())
    val assignmentBusy = MutableStateFlow(false)
    private var pendingComment: Pair<String, String>? = null
    init {
        refreshDetails()
        viewModelScope.launch {
            task.map { it?.conversationId }.distinctUntilChanged().collectLatest { conversationId ->
                members.value = emptyList()
                conversationId?.let {
                    when (val result = conversations.loadMembers(conversationId)) {
                        is Outcome.Failure -> detailError.value = result.error.toUserMessage()
                        is Outcome.Success -> Unit
                    }
                    conversations.observeMembersWithRoles(conversationId).collect { members.value = it }
                }
            }
        }
    }
    fun assign(userId: String, add: Boolean) {
        if (assignmentBusy.value) return
        assignmentBusy.value = true
        viewModelScope.launch {
            detailError.value = null
            try {
            when (val result = remote.assignees(id.value, if (add) listOf(userId) else emptyList(), if (add) emptyList() else listOf(userId))) {
                is Outcome.Success -> {
                    feedback.value = if (add) "Исполнитель назначен. Задача доступна ему в разделе «Мои»." else "Назначение снято"
                    sync.refreshTasks()
                    refreshDetails()
                }
                is Outcome.Failure -> detailError.value = result.error.toOnlineOnlyMessage()
            }
            } finally { assignmentBusy.value = false }
        }
    }
    fun refreshDetails() {
        viewModelScope.launch {
            detailError.value = null
            when (val result = remote.comments(id.value)) {
                is Outcome.Success -> comments.value = result.value.items
                is Outcome.Failure -> detailError.value = result.error.toUserMessage()
            }
            when (val result = remote.events(id.value)) {
                is Outcome.Success -> history.value = result.value.items
                is Outcome.Failure -> detailError.value = result.error.toUserMessage()
            }
        }
    }
    fun addComment() {
        val body = commentText.value.trim()
        if (body.isBlank() || commentBusy.value) return
        val pending = pendingComment?.takeIf { it.second == body }
            ?: (java.util.UUID.randomUUID().toString() to body).also { pendingComment = it }
        viewModelScope.launch {
            commentBusy.value = true
            try {
                when (val result = remote.addComment(id.value, pending.first, pending.second)) {
                    is Outcome.Success -> {
                        comments.value = (comments.value + result.value).distinctBy { it.id }
                        if (commentText.value.trim() == body) commentText.value = ""
                        pendingComment = null
                        refreshDetails()
                    }
                    is Outcome.Failure -> detailError.value = result.error.toUserMessage()
                }
            } finally { commentBusy.value = false }
        }
    }
    fun status(value: TaskStatus) { viewModelScope.launch { report(repository.changeStatus(id, value)) } }
    fun save(title: String, description: String, date: String, reminder: String) {
        val due = runCatching { editedDeadline(date, task.value?.dueAt, ZoneId.systemDefault()) }
            .getOrElse { feedback.value = "Укажите дату в формате ГГГГ-ММ-ДД"; return }
        val remind=runCatching { if(reminder.isBlank()) null else java.time.LocalDateTime.parse(reminder.trim().replace(' ','T')).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
            .getOrElse { feedback.value="Напоминание: ГГГГ-ММ-ДД ЧЧ:ММ";return }
        viewModelScope.launch { report(repository.edit(id, title, description, due, remind)) }
    }
    private fun report(result: Outcome<Unit>) {
        feedback.value = when (result) { is Outcome.Success -> "Сохранено"; is Outcome.Failure -> result.error.toUserMessage() }
    }
}

@Composable
fun TaskDetailsScreen(onNavigateBack: () -> Unit, viewModel: TaskDetailsViewModel = hiltViewModel()) {
    val task by viewModel.task.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()
    val comments by viewModel.comments.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val detailError by viewModel.detailError.collectAsStateWithLifecycle()
    val commentBusy by viewModel.commentBusy.collectAsStateWithLifecycle()
    val commentText by viewModel.commentText.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val assignmentBusy by viewModel.assignmentBusy.collectAsStateWithLifecycle()
    Scaffold(topBar = { MangoTopAppBar(title = "Задача", onNavigateBack = onNavigateBack) }) { padding ->
        val value = task
        if (value == null) { Text("Задача недоступна", Modifier.padding(padding).padding(16.dp)); return@Scaffold }
        var title by remember(value.id) { mutableStateOf(value.title) }
        var description by remember(value.id) { mutableStateOf(value.description.orEmpty()) }
        var date by remember(value.id) { mutableStateOf(value.dueAt?.atZone(ZoneId.systemDefault())?.toLocalDate()?.toString().orEmpty()) }
        var reminder by remember(value.id) { mutableStateOf(value.remindAt?.atZone(ZoneId.systemDefault())?.toLocalDateTime()?.toString().orEmpty()) }
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text("Описание") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(date, { date = it }, label = { Text("Срок: ГГГГ-ММ-ДД; пусто — без срока") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(reminder, {reminder=it},label={Text("Напоминание: ГГГГ-ММ-ДД ЧЧ:ММ")},supportingText={Text("Местное время телефона; пусто — без напоминания")},modifier=Modifier.fillMaxWidth())
            Button(onClick = { viewModel.save(title, description, date, reminder) }, enabled = title.isNotBlank()) { Text("Сохранить") }
            feedback?.let { Text(it) }
            Text("Статус: ${value.status.label()}")
            TaskStatus.entries.forEach { status ->
                FilterChip(selected = value.status == status, onClick = { viewModel.status(status) }, label = { Text(status.label()) })
            }
            value.source?.let { Text("Из сообщения: ${it.textSnapshot.orEmpty()}") }
            if (value.conversationId != null) {
                Text("Совместная задача: участники чата видят её в разделе «Команда», назначенные исполнители — также в «Мои».")
                Text("Назначить исполнителей", style = MaterialTheme.typography.titleMedium)
            } else Text("Личная задача. Для совместной задачи выберите чат при создании.")
            if (value.syncStatus != com.mango.core.model.SyncStatus.SYNCED) Text("Назначение станет доступно после отправки задачи на сервер.")
            if (value.assignees.isNotEmpty()) Text("Исполнители: ${value.assignees.joinToString { it.displayName }}")
            members.forEach { member ->
                val assigned = value.assignees.any { it.id == member.user.id }
                FilterChip(selected = assigned, enabled = !assignmentBusy && value.syncStatus == com.mango.core.model.SyncStatus.SYNCED,
                    onClick = { viewModel.assign(member.user.id.value, !assigned) }, label = { Text(member.user.displayName) })
            }
            HorizontalDivider()
            Text("Комментарии", style = MaterialTheme.typography.titleMedium)
            detailError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = viewModel::refreshDetails) { Text("Обновить комментарии и историю") }
            comments.forEach { comment ->
                Text("${comment.authorName}: ${comment.body}")
            }
            OutlinedTextField(commentText, { viewModel.commentText.value = it }, label = { Text("Комментарий") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = viewModel::addComment, enabled = !commentBusy && commentText.isNotBlank()) { Text("Отправить комментарий") }
            Text("История", style = MaterialTheme.typography.titleMedium)
            history.forEach { event ->
                Text("${event.createdAt} · ${event.actorName.orEmpty()} · ${event.type}${event.field?.let { ": $it" }.orEmpty()}")
            }
        }
    }
}
