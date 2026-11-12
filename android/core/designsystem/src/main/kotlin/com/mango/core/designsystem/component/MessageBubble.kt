package com.mango.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.mango.core.designsystem.theme.BubbleShapes
import com.mango.core.designsystem.theme.MangoTheme
import com.mango.core.model.MessageDeliveryStatus
import com.mango.core.model.MessageReference

/**
 * Пузырь сообщения.
 *
 * Компонент принимает уже подготовленные строки (время, текст), а не domain-модель
 * целиком: форматирование времени зависит от локали и настроек, а это
 * ответственность presentation-слоя, не design system.
 *
 * Доступность: вся карточка объявляется одним узлом с осмысленным описанием,
 * иначе TalkBack зачитывает автора, текст, время и галочки доставки как
 * четыре несвязанных элемента.
 */
@Composable
fun MessageBubble(
    text: String?,
    isOutgoing: Boolean,
    timeLabel: String,
    deliveryStatus: MessageDeliveryStatus,
    modifier: Modifier = Modifier,
    senderName: String? = null,
    replyTo: MessageReference? = null,
    reactions: List<ReactionChipData> = emptyList(),
    isEdited: Boolean = false,
    isDeleted: Boolean = false,
    isPinned: Boolean = false,
    isGroupedWithPrevious: Boolean = false,
    onReactionClick: ((String) -> Unit)? = null,
) {
    val semantic = MangoTheme.semanticColors
    val bubbleColor = if (isOutgoing) semantic.outgoingBubble else semantic.incomingBubble
    val contentColor = if (isOutgoing) semantic.onOutgoingBubble else semantic.onIncomingBubble
    val shape = when {
        isGroupedWithPrevious -> BubbleShapes.grouped
        isOutgoing -> BubbleShapes.outgoing
        else -> BubbleShapes.incoming
    }

    val accessibilityLabel = buildString {
        append(if (isOutgoing) "Исходящее сообщение" else "Входящее сообщение")
        if (senderName != null && !isOutgoing) append(" от $senderName")
        append(". ")
        if (isDeleted) {
            append("Сообщение удалено. ")
        } else {
            append(text ?: "Без текста")
            append(". ")
        }
        if (isEdited) append("Изменено. ")
        append(timeLabel)
        if (isOutgoing) {
            append(", ")
            append(deliveryStatus.readableLabel())
        }
        if (reactions.isNotEmpty()) {
            append(", реакции: ")
            append(reactions.joinToString(", ") { "${it.emoji} ${it.count}" })
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(shape)
                .background(bubbleColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics { contentDescription = accessibilityLabel },
        ) {
            if (senderName != null && !isOutgoing && !isGroupedWithPrevious) {
                Text(
                    text = senderName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(2.dp))
            }

            if (replyTo != null) {
                ReplyPreview(replyTo, contentColor)
                Spacer(Modifier.height(6.dp))
            }

            if (isDeleted) {
                Text(
                    text = "Сообщение удалено",
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = contentColor.copy(alpha = 0.6f),
                )
            } else if (!text.isNullOrEmpty()) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor,
                )
            }

            if (reactions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                ReactionsRow(reactions, onReactionClick)
            }

            Spacer(Modifier.height(4.dp))
            MessageMetaRow(
                timeLabel = timeLabel,
                isEdited = isEdited,
                isPinned = isPinned,
                isOutgoing = isOutgoing,
                deliveryStatus = deliveryStatus,
                contentColor = contentColor,
            )
        }
    }
}

@Composable
private fun ReplyPreview(reference: MessageReference, contentColor: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier
            .background(
                contentColor.copy(alpha = 0.08f),
                RoundedCornerShape(8.dp),
            )
            .padding(8.dp),
    ) {
        Spacer(
            Modifier
                .width(3.dp)
                .height(32.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = reference.senderName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            Text(
                text = when {
                    reference.isUnavailable -> "Сообщение недоступно"
                    reference.textSnapshot.isNullOrBlank() -> "Вложение"
                    else -> reference.textSnapshot.orEmpty()
                },
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.75f),
                fontStyle = if (reference.isUnavailable) FontStyle.Italic else FontStyle.Normal,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun MessageMetaRow(
    timeLabel: String,
    isEdited: Boolean,
    isPinned: Boolean,
    isOutgoing: Boolean,
    deliveryStatus: MessageDeliveryStatus,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isPinned) {
            Icon(
                Icons.Default.PushPin,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = contentColor.copy(alpha = 0.6f),
            )
            Spacer(Modifier.width(4.dp))
        }
        if (isEdited) {
            Text(
                text = "изменено",
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.6f),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = timeLabel,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.7f),
        )
        if (isOutgoing) {
            Spacer(Modifier.width(4.dp))
            DeliveryStatusIcon(deliveryStatus, contentColor)
        }
    }
}

@Composable
private fun DeliveryStatusIcon(
    status: MessageDeliveryStatus,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    val semantic = MangoTheme.semanticColors
    when (status) {
        MessageDeliveryStatus.PENDING, MessageDeliveryStatus.SENDING ->
            Icon(
                Icons.Default.Schedule,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = contentColor.copy(alpha = 0.6f),
            )
        MessageDeliveryStatus.SENT ->
            Icon(
                Icons.Default.Done,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = contentColor.copy(alpha = 0.7f),
            )
        MessageDeliveryStatus.DELIVERED ->
            Icon(
                Icons.Default.DoneAll,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = contentColor.copy(alpha = 0.7f),
            )
        MessageDeliveryStatus.READ ->
            Icon(
                Icons.Default.DoneAll,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        MessageDeliveryStatus.FAILED ->
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = semantic.syncFailed,
            )
    }
}

data class ReactionChipData(
    val emoji: String,
    val count: Int,
    val reactedByMe: Boolean,
)

@Composable
private fun ReactionsRow(
    reactions: List<ReactionChipData>,
    onReactionClick: ((String) -> Unit)?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        reactions.forEach { reaction ->
            val background = if (reaction.reactedByMe) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(background)
                    .then(
                        if (onReactionClick != null) {
                            Modifier.clickable(
                                onClickLabel = "Переключить реакцию ${reaction.emoji}",
                            ) { onReactionClick(reaction.emoji) }
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(reaction.emoji, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(3.dp))
                Text(
                    text = reaction.count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun MessageDeliveryStatus.readableLabel(): String = when (this) {
    MessageDeliveryStatus.PENDING -> "ожидает отправки"
    MessageDeliveryStatus.SENDING -> "отправляется"
    MessageDeliveryStatus.SENT -> "отправлено"
    MessageDeliveryStatus.DELIVERED -> "доставлено"
    MessageDeliveryStatus.READ -> "прочитано"
    MessageDeliveryStatus.FAILED -> "не отправлено"
}
