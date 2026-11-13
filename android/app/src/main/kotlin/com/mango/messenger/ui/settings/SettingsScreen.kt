package com.mango.messenger.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mango.core.data.repository.SyncSnapshot
import com.mango.core.data.repository.SyncStatusRepository
import com.mango.core.data.repository.UserRepository
import com.mango.core.datastore.SettingsStore
import com.mango.core.datastore.ThemeMode
import com.mango.core.datastore.UserSettings
import com.mango.core.designsystem.component.MangoAvatar
import com.mango.core.designsystem.component.MangoTopAppBar
import com.mango.core.designsystem.component.SectionHeader
import com.mango.core.model.User
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val currentUser: User? = null,
    val sync: SyncSnapshot = SyncSnapshot(0, 0, null),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    userRepository: UserRepository,
    syncStatusRepository: SyncStatusRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsStore.settings,
        userRepository.observeCurrentUser(),
        syncStatusRepository.observeSnapshot(),
    ) { settings, user, sync ->
        SettingsUiState(settings = settings, currentUser = user, sync = sync)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setThemeMode(mode: ThemeMode) = update { settingsStore.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = update { settingsStore.setDynamicColorEnabled(enabled) }
    fun setMessageNotifications(enabled: Boolean) =
        update { settingsStore.setMessageNotificationsEnabled(enabled) }

    fun setTaskNotifications(enabled: Boolean) =
        update { settingsStore.setTaskNotificationsEnabled(enabled) }

    fun setMessagePreview(enabled: Boolean) =
        update { settingsStore.setShowMessagePreviewInNotification(enabled) }

    fun setReadReceipts(enabled: Boolean) = update { settingsStore.setSendReadReceipts(enabled) }
    fun setTypingIndicator(enabled: Boolean) =
        update { settingsStore.setSendTypingIndicator(enabled) }

    fun setWifiOnlyMedia(enabled: Boolean) =
        update { settingsStore.setAutoDownloadMediaOnWifiOnly(enabled) }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

@Composable
fun SettingsScreen(
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.settings

    Scaffold(
        modifier = modifier,
        topBar = { MangoTopAppBar(title = "Настройки") },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            ProfileRow(state.currentUser)
            androidx.compose.material3.TextButton(
                onClick = onSignOut,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Выйти из аккаунта")
            }
            HorizontalDivider()

            SectionHeader("Внешний вид")
            ThemeModeSelector(
                selected = settings.themeMode,
                onSelect = viewModel::setThemeMode,
            )
            SwitchRow(
                title = "Динамические цвета",
                subtitle = "Подстраивать палитру под обои (Android 12+)",
                checked = settings.dynamicColorEnabled,
                onCheckedChange = viewModel::setDynamicColor,
            )

            HorizontalDivider()
            SectionHeader("Уведомления")
            SwitchRow(
                title = "Новые сообщения",
                checked = settings.messageNotificationsEnabled,
                onCheckedChange = viewModel::setMessageNotifications,
            )
            SwitchRow(
                title = "Задачи и напоминания",
                checked = settings.taskNotificationsEnabled,
                onCheckedChange = viewModel::setTaskNotifications,
            )
            SwitchRow(
                title = "Показывать текст сообщения",
                subtitle = "Текст будет виден на экране блокировки. " +
                    "По умолчанию выключено.",
                checked = settings.showMessagePreviewInNotification,
                onCheckedChange = viewModel::setMessagePreview,
            )

            HorizontalDivider()
            SectionHeader("Конфиденциальность")
            SwitchRow(
                title = "Отправлять отчёты о прочтении",
                checked = settings.sendReadReceipts,
                onCheckedChange = viewModel::setReadReceipts,
            )
            SwitchRow(
                title = "Показывать, что я печатаю",
                checked = settings.sendTypingIndicator,
                onCheckedChange = viewModel::setTypingIndicator,
            )

            HorizontalDivider()
            SectionHeader("Данные")
            SwitchRow(
                title = "Загружать медиа только по Wi-Fi",
                checked = settings.autoDownloadMediaOnWifiOnly,
                onCheckedChange = viewModel::setWifiOnlyMedia,
            )
            SyncStatusRow(state.sync)
        }
    }
}

@Composable
private fun ProfileRow(user: User?) {
    ListItem(
        leadingContent = {
            MangoAvatar(
                initials = user?.initials ?: "?",
                imageUrl = user?.avatarUrl,
                seed = user?.id?.value ?: "anonymous",
                size = 56.dp,
            )
        },
        headlineContent = {
            Text(user?.displayName ?: "Вход не выполнен")
        },
        supportingContent = {
            Text(
                text = user?.username?.let { "@$it" }
                    ?: "Регистрация и вход подключаются на Этапе 4",
                style = MaterialTheme.typography.bodySmall,
            )
        },
    )
}

@Composable
private fun ThemeModeSelector(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    val options = ThemeMode.entries
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        options.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = {
                    Text(
                        when (mode) {
                            ThemeMode.SYSTEM -> "Системная"
                            ThemeMode.LIGHT -> "Светлая"
                            ThemeMode.DARK -> "Тёмная"
                        },
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    ListItem(
        modifier = Modifier.semantics {
            contentDescription = buildString {
                append(title)
                if (subtitle != null) append(". $subtitle")
                append(if (checked) ". Включено" else ". Выключено")
            }
        },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let {
            { Text(it, style = MaterialTheme.typography.bodySmall) }
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
    )
}

@Composable
private fun SyncStatusRow(sync: SyncSnapshot) {
    ListItem(
        headlineContent = { Text("Состояние синхронизации") },
        supportingContent = {
            Text(
                text = when {
                    sync.hasProblems ->
                        "${sync.failedOperations} операций не удалось отправить"
                    sync.pendingOperations > 0 ->
                        "${sync.pendingOperations} операций в очереди отправки"
                    else -> "Все локальные изменения записаны"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        },
    )
}
