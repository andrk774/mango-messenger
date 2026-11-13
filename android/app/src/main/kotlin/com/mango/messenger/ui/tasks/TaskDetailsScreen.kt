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
import javax.inject.Inject

@HiltViewModel
class TaskDetailsViewModel @Inject constructor(state: SavedStateHandle, private val repository: TaskRepository) : ViewModel() {
    private val id = TaskId(checkNotNull(state.get<String>("taskId")))
    val task = repository.observeTask(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val feedback = MutableStateFlow<String?>(null)
    fun status(value: TaskStatus) { viewModelScope.launch { report(repository.changeStatus(id, value)) } }
    fun save(title: String, description: String, date: String) {
        val due = if (date.isBlank()) null else runCatching { LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
            .getOrElse { feedback.value = "Укажите дату в формате ГГГГ-ММ-ДД"; return }
        viewModelScope.launch { report(repository.edit(id, title, description, due)) }
    }
    private fun report(result: Outcome<Unit>) {
        feedback.value = when (result) { is Outcome.Success -> "Сохранено"; is Outcome.Failure -> result.error.toUserMessage() }
    }
}

@Composable
fun TaskDetailsScreen(onNavigateBack: () -> Unit, viewModel: TaskDetailsViewModel = hiltViewModel()) {
    val task by viewModel.task.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()
    Scaffold(topBar = { MangoTopAppBar(title = "Задача", onNavigateBack = onNavigateBack) }) { padding ->
        val value = task
        if (value == null) { Text("Задача недоступна", Modifier.padding(padding).padding(16.dp)); return@Scaffold }
        var title by remember(value.id) { mutableStateOf(value.title) }
        var description by remember(value.id) { mutableStateOf(value.description.orEmpty()) }
        var date by remember(value.id) { mutableStateOf(value.dueAt?.atZone(ZoneId.systemDefault())?.toLocalDate()?.toString().orEmpty()) }
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text("Описание") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(date, { date = it }, label = { Text("Срок: ГГГГ-ММ-ДД; пусто — без срока") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { viewModel.save(title, description, date) }, enabled = title.isNotBlank()) { Text("Сохранить") }
            feedback?.let { Text(it) }
            Text("Статус: ${value.status.label()}")
            TaskStatus.entries.forEach { status ->
                FilterChip(selected = value.status == status, onClick = { viewModel.status(status) }, label = { Text(status.label()) })
            }
            value.source?.let { Text("Из сообщения: ${it.textSnapshot.orEmpty()}") }
            if (value.assignees.isNotEmpty()) Text("Исполнители: ${value.assignees.joinToString { it.displayName }}")
        }
    }
}
