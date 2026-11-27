package com.mango.messenger.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Маршруты навигации.
 *
 * Строковые константы собраны в одном месте, чтобы опечатка в route
 * не превращалась в молчаливо неработающий переход.
 */
object Routes {
    const val CHATS = "chats"
    const val TASKS = "tasks"
    const val CONTACTS = "contacts"
    const val NOTIFICATIONS = "notifications"
    const val SETTINGS = "settings"
    const val NEARBY = "nearby"

    /** Экран переписки. Реализуется на Этапе 4. */
    const val CONVERSATION = "conversation/{conversationId}"
    fun conversation(conversationId: String) = "conversation/$conversationId"

    /** Сведения о группе: участники, роли, выход. */
    const val GROUP_INFO = "conversation/{conversationId}/info"
    fun groupInfo(conversationId: String) = "conversation/$conversationId/info"

    /** Детали задачи. Реализуется на Этапе 5. */
    const val TASK_DETAILS = "task/{taskId}"
    fun taskDetails(taskId: String) = "task/$taskId"

    const val ARG_CONVERSATION_ID = "conversationId"
    const val ARG_TASK_ID = "taskId"
}

/** Пункт нижней навигации. */
data class TopLevelDestination(
    val route: String,
    @androidx.annotation.StringRes val label: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

val topLevelDestinations = listOf(
    TopLevelDestination(
        route = Routes.CHATS,
        label = com.mango.messenger.R.string.nav_chats,
        selectedIcon = Icons.AutoMirrored.Filled.Chat,
        unselectedIcon = Icons.AutoMirrored.Outlined.Chat,
    ),
    TopLevelDestination(
        route = Routes.TASKS,
        label = com.mango.messenger.R.string.nav_tasks,
        selectedIcon = Icons.Filled.Checklist,
        unselectedIcon = Icons.Outlined.Checklist,
    ),
    TopLevelDestination(
        route = Routes.SETTINGS,
        label = com.mango.messenger.R.string.nav_settings,
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
    ),
)
