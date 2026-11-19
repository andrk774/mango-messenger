package com.mango.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mango.core.designsystem.theme.MangoTheme
import com.mango.core.model.MessageDeliveryStatus
import com.mango.core.model.PresenceStatus
import com.mango.core.model.SyncStatus
import com.mango.core.model.TaskPriority
import com.mango.core.model.TaskStatus

/**
 * Элемент списка чатов.
 *
 * Строка объявляется единым узлом доступности с полным описанием, включая
 * число непрочитанных: иначе TalkBack читает «Иван», «привет», «12:30», «3»
 * без связи между ними.
 */
@Composable
fun ConversationListItem(
    title: String,
    initials: String,
    lastMessageText: String,
    timeLabel: String,
    modifier: Modifier = Modifier,
    avatarUrl: String? = null,
    avatarSeed: String = title,
    presence: PresenceStatus? = null,
    unreadCount: Int = 0,
    isMuted: Boolean = false,
    lastMessageSenderPrefix: String? = null,
    deliveryStatus: MessageDeliveryStatus? = null,
    isTyping: Boolean = false,
    onClick: () -> Unit = {},
) {
    val accessibilityLabel = buildString {
        append(title)
        append(". ")
        if (isTyping) {
            append("печатает")
        } else {
            if (lastMessageSenderPrefix != null) append("$lastMessageSenderPrefix: ")
            append(lastMessageText)
        }
        append(". ")
        append(timeLabel)
        if (unreadCount > 0) append(", $unreadCount непрочитанных")
        if (isMuted) append(", уведомления отключены")
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 88.dp)
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) { contentDescription = accessibilityLabel },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangoAvatar(
            initials = initials,
            imageUrl = avatarUrl,
            seed = avatarSeed,
            presence = presence,
            size = 58.dp,
        )
        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isMuted) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.NotificationsOff,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))

            if (isTyping) {
                TypingIndicator()
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (deliveryStatus != null) {
                        SyncStatusIndicator(
                            status = when (deliveryStatus) {
                                MessageDeliveryStatus.PENDING -> SyncStatus.PENDING
                                MessageDeliveryStatus.SENDING -> SyncStatus.IN_FLIGHT
                                MessageDeliveryStatus.FAILED -> SyncStatus.FAILED
                                else -> SyncStatus.SYNCED
                            },
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = buildString {
                            if (lastMessageSenderPrefix != null) append("$lastMessageSenderPrefix: ")
                            append(lastMessageText)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (unreadCount > 0) {
                Spacer(Modifier.height(4.dp))
                UnreadBadge(count = unreadCount, muted = isMuted)
            }
        }
    }
}

/**
 * Карточка задачи для списков и Kanban-доски.
 *
 * Показывает связь с чатом и прогресс подзадач — то, что отличает задачу
 * в мессенджере от задачи в обычном трекере.
 */
@Composable
fun TaskCard(
    title: String,
    status: TaskStatus,
    priority: TaskPriority,
    modifier: Modifier = Modifier,
    dueLabel: String? = null,
    isOverdue: Boolean = false,
    assigneeInitials: List<String> = emptyList(),
    conversationTitle: String? = null,
    commentCount: Int = 0,
    attachmentCount: Int = 0,
    subtaskCompleted: Int = 0,
    subtaskTotal: Int = 0,
    syncStatus: SyncStatus = SyncStatus.SYNCED,
    onClick: () -> Unit = {},
) {
    val semantic = MangoTheme.semanticColors

    val accessibilityLabel = buildString {
        append(title)
        append(". Статус: ${status.label()}")
        append(". Приоритет: ${priority.label()}")
        if (dueLabel != null) {
            append(if (isOverdue) ". Просрочена: $dueLabel" else ". Срок: $dueLabel")
        }
        if (subtaskTotal > 0) append(". Подзадачи: $subtaskCompleted из $subtaskTotal")
        if (conversationTitle != null) append(". Из чата: $conversationTitle")
        if (syncStatus != SyncStatus.SYNCED) append(". Не синхронизирована")
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = accessibilityLabel },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (syncStatus != SyncStatus.SYNCED) {
                    Spacer(Modifier.width(8.dp))
                    SyncStatusIndicator(status = syncStatus)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TaskStatusChip(status)
                TaskPriorityChip(priority)
            }

            if (subtaskTotal > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { subtaskCompleted.toFloat() / subtaskTotal },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Подзадачи: $subtaskCompleted / $subtaskTotal",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dueLabel != null) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (isOverdue) semantic.overdue else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = dueLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOverdue) semantic.overdue else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isOverdue) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Spacer(Modifier.width(12.dp))
                }

                if (commentCount > 0) {
                    MetaCount(Icons.Default.ChatBubbleOutline, commentCount)
                    Spacer(Modifier.width(10.dp))
                }
                if (attachmentCount > 0) {
                    MetaCount(Icons.Default.AttachFile, attachmentCount)
                    Spacer(Modifier.width(10.dp))
                }
                if (conversationTitle != null) {
                    Icon(
                        Icons.Default.Checklist,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = conversationTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }

                Spacer(Modifier.weight(1f))

                assigneeInitials.take(3).forEach { initials ->
                    MangoAvatar(initials = initials, size = 24.dp, seed = initials)
                    Spacer(Modifier.width(2.dp))
                }
            }
        }
    }
}

@Composable
private fun MetaCount(icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Элемент списка участников чата. */
@Composable
fun MemberListItem(
    displayName: String,
    initials: String,
    roleLabel: String?,
    modifier: Modifier = Modifier,
    avatarUrl: String? = null,
    presence: PresenceStatus? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(displayName)
                    if (roleLabel != null) append(", $roleLabel")
                    if (presence != null) append(", ${presence.readableLabel()}")
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangoAvatar(
            initials = initials,
            imageUrl = avatarUrl,
            seed = displayName,
            presence = presence,
            size = 40.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            if (roleLabel != null) {
                Text(
                    text = roleLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
