package com.mango.messenger.ui.stub

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mango.core.designsystem.component.EmptyState
import com.mango.core.designsystem.component.MangoTopAppBar

/**
 * Экран-заглушка для функциональности, запланированной на следующие этапы.
 *
 * Сознательное решение: вместо имитации работы с выдуманными данными экран
 * прямо сообщает, что именно ещё не реализовано и на каком этапе появится.
 * Поддельный интерфейс создаёт ложное впечатление готовности и мешает
 * оценивать реальный прогресс.
 */
@Composable
fun NotImplementedScreen(
    title: String,
    stage: String,
    details: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            MangoTopAppBar(
                title = title,
                subtitle = stage,
                onNavigateBack = onNavigateBack,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            EmptyState(
                icon = Icons.Outlined.Construction,
                title = "Ещё не реализовано",
                description = details,
                actionText = "Назад",
                onAction = onNavigateBack,
            )
        }
    }
}
