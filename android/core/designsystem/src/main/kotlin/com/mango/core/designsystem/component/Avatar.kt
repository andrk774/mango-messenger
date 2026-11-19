package com.mango.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import com.mango.core.designsystem.theme.MangoTheme
import com.mango.core.model.PresenceStatus

/**
 * Аватар пользователя или чата.
 *
 * Если изображения нет или оно не загрузилось, показываются инициалы на фоне,
 * цвет которого детерминированно выводится из [seed] (обычно это id).
 * Так список чатов выглядит осмысленно даже при полностью пустом кэше.
 *
 * Доступность: описание задаётся через [contentDescription]; при наличии
 * [presence] статус включается в описание, чтобы TalkBack сообщал его вслух.
 */
@Composable
fun MangoAvatar(
    initials: String,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    size: Dp = 44.dp,
    seed: String = initials,
    presence: PresenceStatus? = null,
    contentDescription: String? = null,
) {
    val semantic = MangoTheme.semanticColors
    val background = avatarColorFor(seed)

    val accessibilityLabel = buildString {
        append(contentDescription ?: initials)
        if (presence != null) {
            append(", ")
            append(presence.readableLabel())
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clearAndSetSemantics { this.contentDescription = accessibilityLabel },
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(background),
            contentAlignment = Alignment.Center,
        ) {
            if (imageUrl.isNullOrBlank()) {
                AvatarInitials(initials, size)
            } else {
                SubcomposeAsyncImage(
                    model = androidx.compose.runtime.remember(imageUrl) {
                        if(imageUrl.startsWith("data:image/jpeg;base64,"))
                            runCatching { android.util.Base64.decode(imageUrl.substringAfter(','),android.util.Base64.DEFAULT) }.getOrNull()
                        else imageUrl
                    },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size).clip(CircleShape),
                    loading = { AvatarInitials(initials, size) },
                    error = { AvatarInitials(initials, size) },
                )
            }
        }

        if (presence != null) {
            val dotSize = (size.value * 0.28f).coerceIn(8f, 18f).dp
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(
                        when (presence) {
                            PresenceStatus.ONLINE -> semantic.online
                            PresenceStatus.AWAY -> semantic.away
                            PresenceStatus.DO_NOT_DISTURB -> semantic.doNotDisturb
                            PresenceStatus.OFFLINE -> semantic.offline
                        },
                    )
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}

@Composable
private fun AvatarInitials(initials: String, size: Dp) {
    // Box, а не BoxWithConstraints: входящие constraints здесь не нужны,
    // а BoxWithConstraints платит за subcomposition на каждый аватар
    // в списке чатов (на это указал lint).
    Box(contentAlignment = Alignment.Center) {
        Text(
            text = initials.take(2),
            // Размер кегля пропорционален аватару, но в sp — масштабируется
            // системной настройкой размера шрифта.
            fontSize = initialsFontSize(size),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

private fun initialsFontSize(size: Dp): TextUnit = (size.value * 0.38f).sp

/**
 * Детерминированный выбор цвета фона по строке.
 *
 * Важно: это не хеш для безопасности, а стабильное отображение id → цвет,
 * чтобы один и тот же чат всегда выглядел одинаково на всех экранах.
 */
private fun avatarColorFor(seed: String): Color {
    val palette = AvatarPalette
    if (seed.isEmpty()) return palette[0]
    var hash = 0
    for (char in seed) {
        hash = hash * 31 + char.code
    }
    val index = ((hash % palette.size) + palette.size) % palette.size
    return palette[index]
}

private val AvatarPalette = listOf(
    Color(0xFFB36500), // манго
    Color(0xFF006A60), // морская волна
    Color(0xFF6D5679), // слива
    Color(0xFF0B6BCB), // синий
    Color(0xFF1B873B), // зелёный
    Color(0xFFA23E48), // терракота
    Color(0xFF4F5B93), // индиго
    Color(0xFF8A6D1F), // охра
)

internal fun PresenceStatus.readableLabel(): String = when (this) {
    PresenceStatus.ONLINE -> "в сети"
    PresenceStatus.AWAY -> "отошёл"
    PresenceStatus.DO_NOT_DISTURB -> "не беспокоить"
    PresenceStatus.OFFLINE -> "не в сети"
}
