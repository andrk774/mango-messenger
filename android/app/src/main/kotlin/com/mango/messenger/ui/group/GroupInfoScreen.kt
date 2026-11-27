package com.mango.messenger.ui.group

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mango.core.designsystem.component.MangoSearchBar
import com.mango.core.designsystem.component.MangoTextButton
import com.mango.core.designsystem.component.MemberListItem
import com.mango.core.model.ConversationMember
import com.mango.messenger.ui.format.SystemEventFormatter

/**
 * Сведения о группе: название, участники, роли, выход.
 *
 * Все изменения выполняются на сервере и требуют сети; экран показывает
 * только те действия, которые разрешены роли пользователя.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    onNavigateBack: () -> Unit,
    onLeft: () -> Unit,
    viewModel: GroupInfoViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var renaming by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var selectedMember by remember { mutableStateOf<ConversationMember?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is GroupInfoEvent.Message -> snackbarHostState.showSnackbar(event.text)
                GroupInfoEvent.Left -> onLeft()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.conversation?.title ?: "Группа") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (state.canManage) {
                        IconButton(onClick = { renaming = true }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Переименовать")
                        }
                        IconButton(onClick = viewModel::openAddDialog) {
                            Icon(Icons.Outlined.PersonAdd, contentDescription = "Добавить участников")
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
                .padding(padding),
        ) {
            if (state.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())

            Text(
                text = "Участники: ${state.members.size}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(16.dp),
            )

            LazyColumn(Modifier.weight(1f)) {
                items(state.members, key = { it.user.id.value }) { member ->
                    val actions = state.actionsFor(member)
                    MemberListItem(
                        displayName = member.user.displayName +
                            if (member.user.id == state.currentUserId) " (вы)" else "",
                        initials = member.user.initials,
                        roleLabel = SystemEventFormatter.roleLabel(member.role),
                        avatarUrl = member.user.avatarUrl,
                        onClick = if (actions.isNotEmpty()) ({ selectedMember = member }) else null,
                    )
                    HorizontalDivider(Modifier.padding(start = 68.dp))
                }
            }

            MangoTextButton(
                text = "Покинуть группу",
                onClick = { confirmLeave = true },
                enabled = !state.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            )
        }
    }

    if (renaming) {
        var title by remember { mutableStateOf(state.conversation?.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Название группы") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(128) },
                    singleLine = true,
                )
            },
            confirmButton = {
                MangoTextButton(
                    text = "Сохранить",
                    enabled = title.isNotBlank(),
                    onClick = {
                        renaming = false
                        viewModel.rename(title)
                    },
                )
            },
            dismissButton = { MangoTextButton(text = "Отмена", onClick = { renaming = false }) },
        )
    }

    selectedMember?.let { member ->
        AlertDialog(
            onDismissRequest = { selectedMember = null },
            title = { Text(member.user.displayName) },
            text = {
                Column {
                    state.actionsFor(member).forEach { action ->
                        MangoTextButton(
                            text = action.label,
                            onClick = {
                                selectedMember = null
                                viewModel.perform(member, action)
                            },
                        )
                    }
                }
            },
            confirmButton = { MangoTextButton(text = "Закрыть", onClick = { selectedMember = null }) },
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Покинуть группу?") },
            text = {
                Text(
                    "Переписка и файлы группы исчезнут с этого устройства. " +
                        "Вернуться можно, только если вас добавят снова." +
                        if (state.myRole == com.mango.core.model.MemberRole.OWNER) {
                            " Владение перейдёт самому давнему администратору или участнику."
                        } else {
                            ""
                        },
                )
            },
            confirmButton = {
                MangoTextButton(
                    text = "Покинуть",
                    onClick = {
                        confirmLeave = false
                        viewModel.leave()
                    },
                )
            },
            dismissButton = { MangoTextButton(text = "Отмена", onClick = { confirmLeave = false }) },
        )
    }

    state.addDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = viewModel::closeAddDialog,
            title = { Text("Добавить участников") },
            text = {
                Column {
                    MangoSearchBar(
                        query = dialog.query,
                        onQueryChange = viewModel::onAddQueryChange,
                        placeholder = "Имя или имя пользователя",
                    )
                    if (dialog.selected.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Выбрано: " + dialog.selected.joinToString(", ") { it.displayName },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    dialog.hint?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(dialog.results, key = { it.id.value }) { user ->
                            val chosen = dialog.selected.any { it.id == user.id }
                            MemberListItem(
                                displayName = user.displayName,
                                initials = user.initials,
                                roleLabel = if (chosen) "✓ выбран" else "@${user.username}",
                                avatarUrl = user.avatarUrl,
                                onClick = { viewModel.toggleCandidate(user) },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                MangoTextButton(
                    text = "Добавить",
                    enabled = dialog.selected.isNotEmpty(),
                    onClick = viewModel::confirmAdd,
                )
            },
            dismissButton = { MangoTextButton(text = "Отмена", onClick = viewModel::closeAddDialog) },
        )
    }
}
