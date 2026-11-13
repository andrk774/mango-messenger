package com.mango.messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mango.core.datastore.SettingsStore
import com.mango.core.datastore.ThemeMode
import com.mango.core.datastore.UserSettings
import com.mango.core.designsystem.component.FullScreenLoading
import com.mango.core.designsystem.theme.MangoTheme
import com.mango.messenger.navigation.Routes
import com.mango.messenger.navigation.topLevelDestinations
import com.mango.messenger.ui.auth.AuthScreen
import com.mango.messenger.ui.chats.ChatsScreen
import com.mango.messenger.ui.conversation.ConversationScreen
import com.mango.messenger.ui.misc.ContactsScreen
import com.mango.messenger.ui.misc.NotificationsScreen
import com.mango.messenger.ui.settings.SettingsScreen
import com.mango.messenger.ui.stub.NotImplementedScreen
import com.mango.messenger.ui.tasks.TasksScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsStore: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            // Настройки темы читаются из DataStore, поэтому выбор пользователя
            // применяется сразу и переживает перезапуск. Подписка привязана
            // к жизненному циклу: в фоне чтение DataStore останавливается.
            val settings by settingsStore.settings
                .collectAsStateWithLifecycle(initialValue = UserSettings())

            val mainViewModel: MainViewModel = hiltViewModel()
            val appState by mainViewModel.appState.collectAsStateWithLifecycle()

            MangoTheme(
                darkTheme = when (settings.themeMode) {
                    ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                },
                dynamicColor = settings.dynamicColorEnabled,
            ) {
                Surface(Modifier.fillMaxSize()) {
                    when (appState) {
                        // Пока сессия восстанавливается, экран входа
                        // показывать нельзя: он мелькал бы при каждом
                        // запуске у вошедшего пользователя.
                        AppState.Loading -> FullScreenLoading()

                        AppState.SignedOut -> AuthScreen(
                            onAuthenticated = mainViewModel::onSignedIn,
                        )

                        AppState.SignedIn -> MangoApp(onSignOut = mainViewModel::signOut)
                    }
                }
            }
        }
    }
}

@Composable
private fun MangoApp(onSignOut: () -> Unit) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            // Нижняя навигация скрывается на вложенных экранах:
            // в переписке и карточке задачи нужен весь экран.
            val isTopLevel = topLevelDestinations.any { destination ->
                currentDestination?.hierarchy?.any { it.route == destination.route } == true
            }
            if (isTopLevel) {
                NavigationBar {
                    topLevelDestinations.forEach { destination ->
                        val selected = currentDestination?.hierarchy
                            ?.any { it.route == destination.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTopLevel(destination.route) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) {
                                        destination.selectedIcon
                                    } else {
                                        destination.unselectedIcon
                                    },
                                    contentDescription = destination.label,
                                )
                            },
                            label = { Text(destination.label, maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.CHATS,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Routes.CHATS) {
                ChatsScreen(
                    onConversationClick = { conversation ->
                        navController.navigate(Routes.conversation(conversation.id.value))
                    },
                    onOpenConversationById = { id ->
                        navController.navigate(Routes.conversation(id))
                    },
                    modifier = Modifier.padding(padding),
                )
            }
            composable(Routes.TASKS) {
                TasksScreen(
                    onTaskClick = { taskId ->
                        navController.navigate(Routes.taskDetails(taskId.value))
                    },
                    modifier = Modifier.padding(padding),
                )
            }
            composable(Routes.CONTACTS) {
                ContactsScreen(modifier = Modifier.padding(padding))
            }
            composable(Routes.NOTIFICATIONS) {
                NotificationsScreen(modifier = Modifier.padding(padding))
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(modifier = Modifier.padding(padding))
            }

            composable(Routes.CONVERSATION) {
                ConversationScreen(
                    onNavigateBack = navController::popBackStack,
                    onOpenInfo = { id -> navController.navigate(Routes.groupInfo(id)) },
                )
            }
            composable(Routes.GROUP_INFO) {
                com.mango.messenger.ui.group.GroupInfoScreen(
                    onNavigateBack = navController::popBackStack,
                    // После выхода из группы её переписки больше нет —
                    // возврат идёт сразу к списку чатов, минуя её экран.
                    onLeft = { navController.popBackStack(Routes.CHATS, inclusive = false) },
                )
            }
            composable(Routes.TASK_DETAILS) {
                NotImplementedScreen(
                    title = "Задача",
                    stage = "Этап 5 — Tasks MVP",
                    details = "Карточка задачи: описание, исполнители, подзадачи, " +
                        "комментарии, история изменений и связь с исходным сообщением.",
                    onNavigateBack = navController::popBackStack,
                )
            }
        }
    }
}

/**
 * Переход между корневыми разделами.
 *
 * `launchSingleTop` и возврат к start-destination не дают стеку расти при
 * многократном переключении табов, а `saveState`/`restoreState` сохраняют
 * позицию скролла каждого раздела.
 */
private fun NavHostController.navigateToTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
