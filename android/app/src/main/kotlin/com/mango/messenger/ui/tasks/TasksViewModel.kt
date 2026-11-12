package com.mango.messenger.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mango.core.common.Outcome
import com.mango.core.data.repository.NewTaskDraft
import com.mango.core.data.repository.TaskRepository
import com.mango.core.model.Task
import com.mango.core.model.TaskId
import com.mango.core.model.TaskPriority
import com.mango.core.model.TaskScope
import com.mango.core.model.TaskStatus
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TasksUiState(
    val isLoading: Boolean = true,
    val scope: TaskScope = TaskScope.MINE,
    val includeCompleted: Boolean = false,
    val tasks: List<Task> = emptyList(),
    val isCreating: Boolean = false,
) {
    val isEmpty: Boolean get() = !isLoading && tasks.isEmpty()

    /** Группировка для Kanban-доски. */
    val board: Map<TaskStatus, List<Task>>
        get() = TaskStatus.entries.associateWith { status -> tasks.filter { it.status == status } }

    val overdueCount: Int get() = tasks.count { it.isOverdue }
}

/** Разовые события, которые не должны переигрываться при повороте экрана. */
sealed interface TasksEvent {
    data class Error(val message: String) : TasksEvent
    data class Created(val taskId: TaskId) : TasksEvent
}

@HiltViewModel
class TasksViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
) : ViewModel() {

    private val scope = MutableStateFlow(TaskScope.MINE)
    private val includeCompleted = MutableStateFlow(false)
    private val isCreating = MutableStateFlow(false)

    private val _events = MutableSharedFlow<TasksEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: Flow<TasksEvent> = _events.asSharedFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<TasksUiState> = combine(
        scope,
        includeCompleted,
        isCreating,
    ) { currentScope, withCompleted, creating ->
        Triple(currentScope, withCompleted, creating)
    }.flatMapLatest { (currentScope, withCompleted, creating) ->
        taskRepository.observeTasks(currentScope, withCompleted).map { tasks ->
            TasksUiState(
                isLoading = false,
                scope = currentScope,
                includeCompleted = withCompleted,
                tasks = tasks,
                isCreating = creating,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TasksUiState(),
    )

    fun onScopeChange(newScope: TaskScope) {
        scope.value = newScope
    }

    fun onIncludeCompletedChange(include: Boolean) {
        includeCompleted.value = include
    }

    /**
     * Смена статуса задачи.
     *
     * Работает офлайн: изменение применяется локально и ставится в очередь.
     * Ошибка возможна только при проблеме с локальным хранилищем, поэтому
     * сообщение пользователю формулируется именно так.
     */
    fun onStatusChange(taskId: TaskId, status: TaskStatus) {
        viewModelScope.launch {
            when (val result = taskRepository.changeStatus(taskId, status)) {
                is Outcome.Success -> Unit // Room-поток сам обновит интерфейс
                is Outcome.Failure -> _events.emit(
                    TasksEvent.Error(result.error.toUserMessage()),
                )
            }
        }
    }

    fun onCreateTask(title: String, priority: TaskPriority = TaskPriority.MEDIUM) {
        viewModelScope.launch {
            isCreating.value = true
            try {
                when (val result = taskRepository.create(NewTaskDraft(title = title, priority = priority))) {
                    is Outcome.Success -> _events.emit(TasksEvent.Created(result.value))
                    is Outcome.Failure -> _events.emit(
                        TasksEvent.Error(result.error.toUserMessage()),
                    )
                }
            } finally {
                isCreating.value = false
            }
        }
    }

    fun onDelete(taskId: TaskId) {
        viewModelScope.launch {
            (taskRepository.delete(taskId) as? Outcome.Failure)?.let {
                _events.emit(TasksEvent.Error(it.error.toUserMessage()))
            }
        }
    }
}

/**
 * Преобразование доменной ошибки в текст для пользователя.
 *
 * Живёт в presentation-слое: domain не должен знать о формулировках,
 * а UI не должен знать о внутренней структуре ошибок.
 */
internal fun com.mango.core.common.AppError.toUserMessage(): String = when (this) {
    is com.mango.core.common.AppError.Validation ->
        fieldErrors.values.firstOrNull() ?: "Проверьте введённые данные"
    is com.mango.core.common.AppError.Storage ->
        "Не удалось сохранить изменение на устройстве"
    is com.mango.core.common.AppError.Network ->
        "Нет подключения. Изменение сохранено и будет отправлено позже"
    is com.mango.core.common.AppError.Timeout ->
        "Сервер не ответил. Повторим автоматически"
    com.mango.core.common.AppError.Unauthorized -> "Требуется повторный вход"
    com.mango.core.common.AppError.Forbidden -> "Недостаточно прав для этого действия"
    is com.mango.core.common.AppError.NotFound -> "Объект не найден или удалён"
    is com.mango.core.common.AppError.Conflict ->
        "Изменено на другом устройстве — показана актуальная версия"
    is com.mango.core.common.AppError.RateLimited -> "Слишком много запросов, попробуйте позже"
    is com.mango.core.common.AppError.Server -> "Ошибка сервера. Повторим автоматически"
    is com.mango.core.common.AppError.Unknown -> "Непредвиденная ошибка"
}
