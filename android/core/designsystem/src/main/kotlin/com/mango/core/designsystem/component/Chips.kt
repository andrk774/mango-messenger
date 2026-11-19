package com.mango.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mango.core.designsystem.theme.MangoTheme
import com.mango.core.model.SyncStatus
import com.mango.core.model.TaskPriority
import com.mango.core.model.TaskStatus

/** Базовый чип с цветом-акцентом. Основа для статусов и приоритетов. */
@Composable
private fun ColoredChip(
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .background(accent.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .then(
                if (contentDescription != null) {
                    Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = accent,
        )
    }
}

@Composable
fun TaskStatusChip(status: TaskStatus, modifier: Modifier = Modifier) {
    val semantic = MangoTheme.semanticColors
    val accent = when (status) {
        TaskStatus.TODO -> semantic.statusTodo
        TaskStatus.IN_PROGRESS -> semantic.statusInProgress
        TaskStatus.REVIEW -> semantic.statusReview
        TaskStatus.DONE -> semantic.statusDone
        TaskStatus.CANCELLED -> semantic.statusCancelled
    }
    ColoredChip(
        label = status.label(),
        accent = accent,
        modifier = modifier,
        contentDescription = "Статус: ${status.label()}",
    )
}

@Composable
fun TaskPriorityChip(priority: TaskPriority, modifier: Modifier = Modifier) {
    val semantic = MangoTheme.semanticColors
    val accent = when (priority) {
        TaskPriority.URGENT -> semantic.priorityUrgent
        TaskPriority.HIGH -> semantic.priorityHigh
        TaskPriority.MEDIUM -> semantic.priorityMedium
        TaskPriority.LOW -> semantic.priorityLow
    }
    ColoredChip(
        label = priority.label(),
        accent = accent,
        modifier = modifier,
        contentDescription = "Приоритет: ${priority.label()}",
    )
}

/**
 * Счётчик непрочитанных.
 *
 * Значения больше 99 показываются как «99+», иначе бейдж распирает строку
 * и ломает вёрстку списка чатов.
 */
@Composable
fun UnreadBadge(
    count: Int,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
) {
    if (count <= 0) return

    val background = if (muted) {
        MaterialTheme.colorScheme.outline
    } else {
        MaterialTheme.colorScheme.primary
    }
    val label = if (count > 99) "99+" else count.toString()

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .background(background, CircleShape)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clearAndSetSemantics {
                contentDescription = "$count непрочитанных"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (muted) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.onPrimary
            },
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Индикатор состояния синхронизации записи.
 *
 * Пользователь должен видеть, что его сообщение или задача ещё не доехали
 * до сервера — это прямое требование offline-first: без индикатора
 * «отправлено» и «лежит в очереди» выглядят одинаково.
 */
@Composable
fun SyncStatusIndicator(
    status: SyncStatus,
    modifier: Modifier = Modifier,
    showLabel: Boolean = false,
) {
    val semantic = MangoTheme.semanticColors

    val (icon, tint, label) = when (status) {
        SyncStatus.SYNCED -> Triple(Icons.Default.CheckCircle, semantic.online, "Синхронизировано")
        SyncStatus.PENDING -> Triple(Icons.Default.Schedule, semantic.syncPending, "Ожидает отправки")
        SyncStatus.IN_FLIGHT -> Triple(null, semantic.syncPending, "Отправляется")
        SyncStatus.FAILED -> Triple(Icons.Default.ErrorOutline, semantic.syncFailed, "Не отправлено")
    }

    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon == null) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = tint,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp),
            )
        }
        if (showLabel) {
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
        }
    }
}

/** Баннер «нет сети» — показывается поверх контента, не блокируя его. */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Нет подключения. Изменения сохранятся и отправятся позже.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun TaskStatus.label(): String = when (this) {
    TaskStatus.TODO -> "Новая"
    TaskStatus.IN_PROGRESS -> "В работе"
    TaskStatus.REVIEW -> "На проверке"
    TaskStatus.DONE -> "Готово"
    TaskStatus.CANCELLED -> "Отменена"
}

fun TaskPriority.label(): String = when (this) {
    TaskPriority.LOW -> "Низкий"
    TaskPriority.MEDIUM -> "Средний"
    TaskPriority.HIGH -> "Высокий"
    TaskPriority.URGENT -> "Срочно"
}
