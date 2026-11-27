package com.mango.messenger.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mango.core.designsystem.component.EmptyState
import com.mango.core.designsystem.component.FullScreenLoading
import com.mango.core.designsystem.component.MangoTopAppBar
import com.mango.core.designsystem.component.TaskCard
import com.mango.core.designsystem.component.label
import com.mango.core.model.Task
import com.mango.core.model.TaskId
import com.mango.core.model.TaskScope
import com.mango.core.model.TaskStatus
import com.mango.messenger.ui.format.TimeFormatters

/**
 * Экран списка задач.
 *
 * Смена статуса здесь полностью работоспособна и офлайн: изменение
 * применяется к локальной базе и ставится в очередь синхронизации,
 * а индикатор на карточке показывает, что оно ещё не доставлено.
 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun TasksScreen(
    onTaskClick: (TaskId) -> Unit,
    onProfile: () -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TasksViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        viewModel.refresh()
    }
    val availableChats by viewModel.availableChats.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var newTaskTitle by remember { mutableStateOf<String?>(null) }
    var view by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("Список") }
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var direction by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    var searchOpen by remember { mutableStateOf(false) }
    var filtersOpen by remember { mutableStateOf(false) }
    var statusFilter by remember { mutableStateOf<TaskStatus?>(null) }
    val visibleTasks = state.tasks.filter {
        (query.isBlank() || it.title.contains(query, ignoreCase = true) || it.description.orEmpty().contains(query, ignoreCase = true)) &&
            (direction==0 || direction==1 && it.id in state.incomingIds || direction==2 && it.createdBy.value==state.currentUserId) &&
            (statusFilter==null || it.status==statusFilter)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is TasksEvent.Error -> snackbarHostState.showSnackbar(event.message)
                is TasksEvent.Created -> onTaskClick(event.taskId)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            MangoTopAppBar(
                title = "Задачи",
                actions = {
                    androidx.compose.material3.IconButton(onClick={filtersOpen=true}) {
                        Icon(androidx.compose.material.icons.Icons.Default.SwapVert,contentDescription="Фильтры и вид задач")
                    }
                    androidx.compose.material3.IconButton(onClick={searchOpen=!searchOpen}) {
                        Icon(androidx.compose.material.icons.Icons.Default.Search,contentDescription="Поиск задач")
                    }
                },
                subtitle = when {
                    state.overdueCount > 0 -> "${state.overdueCount} просрочено"
                    else -> null
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { newTaskTitle = "" },shape=androidx.compose.foundation.shape.CircleShape,
                containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary) {
                Icon(Icons.Default.Add, contentDescription = "Создать задачу")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            PrimaryTabRow(selectedTabIndex=direction,containerColor=MaterialTheme.colorScheme.surface) {
                listOf("Все","Входящие","Исходящие").forEachIndexed { index,label ->
                    Tab(selected=direction==index,onClick={direction=index},text={Text(label,maxLines=1)},
                        selectedContentColor=MaterialTheme.colorScheme.primary,unselectedContentColor=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if(searchOpen) com.mango.core.designsystem.component.MangoSearchBar(
                query = query, onQueryChange = { query = it }, placeholder = "Поиск задач",
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            androidx.compose.material3.TextButton(onClick={viewModel.onIncludeCompletedChange(!state.includeCompleted)},modifier=Modifier.padding(horizontal=16.dp)) {
                Icon(Icons.Default.Inventory2,null)
                Text(if(state.includeCompleted)"  Скрыть завершённые" else "  Завершённые задачи",Modifier.padding(8.dp))
            }

            when {
                state.isLoading -> FullScreenLoading()

                visibleTasks.isEmpty() -> Column {
                    if(direction==0) WelcomeTasks(onProfile=onProfile,onChat=onNewChat,onTask={newTaskTitle=""})
                    EmptyState(
                    icon = Icons.Outlined.Checklist,
                    title = when (state.scope) {
                        TaskScope.MINE -> "У вас нет активных задач"
                        TaskScope.TEAM -> "В командных чатах задач нет"
                        TaskScope.FROM_CHATS -> "Задач из переписок пока нет"
                    },
                    description = "Задачу можно создать здесь или прямо из сообщения в чате.",
                    actionText = "Создать задачу",
                    onAction = { newTaskTitle = "" },
                    )
                }

                else -> when (view) {
                    "Kanban" -> TaskBoard(visibleTasks, onTaskClick, viewModel::onStatusChange)
                    "Календарь" -> TaskCalendar(visibleTasks, onTaskClick, viewModel::onStatusChange)
                    else -> TaskList(visibleTasks, onTaskClick, viewModel::onStatusChange,
                        header={if(direction==0)WelcomeTasks(onProfile=onProfile,onChat=onNewChat,onTask={newTaskTitle=""})})
                }
            }
        }
    }

    if(filtersOpen) androidx.compose.material3.ModalBottomSheet(
        onDismissRequest={filtersOpen=false},containerColor=MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(horizontal=24.dp).verticalScroll(rememberScrollState())) {
            Text("Статус",style=MaterialTheme.typography.titleMedium)
            androidx.compose.material3.TextButton(onClick={statusFilter=null;filtersOpen=false}) {Text("Все статусы")}
            TaskStatus.entries.forEach {status->
                androidx.compose.material3.TextButton(onClick={statusFilter=status;viewModel.onIncludeCompletedChange(true);filtersOpen=false}) {Text(status.label())}
            }
            androidx.compose.material3.HorizontalDivider()
            Text("Отображение",style=MaterialTheme.typography.titleMedium)
            listOf("Список","Kanban","Календарь").forEach {mode->
                androidx.compose.material3.TextButton(onClick={view=mode;filtersOpen=false}){Text(mode)}
            }
        }
    }

    newTaskTitle?.let { initial ->
        NewTaskDialog(
            initialTitle = initial,
            chats = availableChats,
            onDismiss = { newTaskTitle = null },
            onConfirm = { title, conversationId ->
                viewModel.onCreateTask(title, conversationId = conversationId)
                newTaskTitle = null
            },
        )
    }
}

@Composable
private fun ScopeTabs(selected: TaskScope, onSelect: (TaskScope) -> Unit) {
    val scopes = TaskScope.entries
    PrimaryTabRow(selectedTabIndex = scopes.indexOf(selected)) {
        scopes.forEach { scope ->
            Tab(
                selected = scope == selected,
                onClick = { onSelect(scope) },
                text = {
                    Text(
                        text = when (scope) {
                            TaskScope.MINE -> "Мои"
                            TaskScope.TEAM -> "Команда"
                            TaskScope.FROM_CHATS -> "Из чатов"
                        },
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

@Composable
private fun FilterRow(
    includeCompleted: Boolean,
    onIncludeCompletedChange: (Boolean) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = includeCompleted,
                onClick = { onIncludeCompletedChange(!includeCompleted) },
                label = { Text("Показывать выполненные") },
            )
        }
    }
}

@Composable
internal fun TaskList(
    tasks: List<Task>,
    onTaskClick: (TaskId) -> Unit,
    onStatusChange: (TaskId, TaskStatus) -> Unit,
    header: @Composable () -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, bottom = 88.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {header()}
        items(items = tasks, key = { it.id.value }) { task ->
            TaskInboxRow(task,onClick={onTaskClick(task.id)},onStatus={onStatusChange(task.id,it)})
        }
    }
}

/**
 * Быстрая смена статуса прямо из списка.
 *
 * Это основной путь использования: статус меняют чаще, чем открывают
 * карточку задачи, поэтому действие вынесено на один тап.
 */
@Composable
private fun StatusQuickActions(
    current: TaskStatus,
    onSelect: (TaskStatus) -> Unit,
) {
    LazyRow(
        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(TaskStatus.entries.filter { it != current }) { status ->
            FilterChip(
                selected = false,
                onClick = { onSelect(status) },
                label = {
                    Text(
                        text = status.label(),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
            )
        }
    }
}

@Composable
private fun NewTaskDialog(
    initialTitle: String,
    chats: List<com.mango.core.model.Conversation>,
    onDismiss: () -> Unit,
    onConfirm: (String, com.mango.core.model.ConversationId?) -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var selectedChat by remember { mutableStateOf<com.mango.core.model.ConversationId?>(null) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая задача") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            com.mango.core.designsystem.component.MangoTextField(
                value = title,
                onValueChange = { title = it },
                label = "Название",
                placeholder = "Что нужно сделать?",
                singleLine = false,
            )
            Text("Кому доступна задача")
            androidx.compose.material3.FilterChip(selected = selectedChat == null, onClick = { selectedChat = null }, label = { Text("Только мне") })
            chats.forEach { chat ->
                androidx.compose.material3.FilterChip(selected = selectedChat == chat.id, onClick = { selectedChat = chat.id }, label = { Text(chat.title) })
            }
            if (selectedChat != null) Text("Участники выбранного чата увидят задачу. Исполнителей можно назначить в её карточке.")
            if (chats.isEmpty()) Text("Для совместной задачи сначала начните переписку с пользователем или создайте группу.")
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.mango.core.designsystem.component.MangoTextButton(
                    text = "Отмена",
                    onClick = onDismiss,
                )
                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                com.mango.core.designsystem.component.MangoPrimaryButton(
                    text = "Создать",
                    onClick = { onConfirm(title, selectedChat) },
                    enabled = title.isNotBlank(),
                )
            }
        },
    )
}
