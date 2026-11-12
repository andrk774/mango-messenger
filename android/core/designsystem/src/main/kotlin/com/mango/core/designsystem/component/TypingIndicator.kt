package com.mango.core.designsystem.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

/**
 * Индикатор набора текста — три пульсирующие точки.
 *
 * Анимация реализована через [rememberInfiniteTransition] со смещением фазы,
 * а не тремя независимыми анимациями: это одна подписка на кадры вместо трёх.
 *
 * Для TalkBack анимация бессмысленна, поэтому узел подменяется текстовым
 * описанием через [clearAndSetSemantics].
 */
@Composable
fun TypingIndicator(
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val accessibilityLabel = label?.let { "$it печатает" } ?: "печатает"

    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = accessibilityLabel },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
        }
        TypingDots()
    }
}

@Composable
private fun TypingDots(dotCount: Int = 3) {
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = dotCount.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Restart,
        ),
        label = "typing-phase",
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(dotCount) { index ->
            // Точка «загорается», когда фаза проходит через её позицию.
            val distance = ((phase - index + dotCount) % dotCount)
            val alpha = (1f - (distance / dotCount)).coerceIn(0.3f, 1f)

            Spacer(
                Modifier
                    .size(6.dp)
                    .alpha(alpha)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
            if (index < dotCount - 1) Spacer(Modifier.width(3.dp))
        }
    }
}
