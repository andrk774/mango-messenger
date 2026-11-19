package com.mango.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

private val LocalMangoSemanticColors = staticCompositionLocalOf<MangoSemanticColors> {
    error("MangoSemanticColors не предоставлены. Оберните UI в MangoTheme.")
}

private val LocalMangoSpacing = staticCompositionLocalOf { MangoSpacing() }

/**
 * Тема Mango Messenger.
 *
 * @param dynamicColor использовать Dynamic Color (Material You). Доступно с Android 12.
 *   Семантические цвета (пузыри сообщений, статусы задач) при этом НЕ подменяются
 *   обоями — иначе «выполнено» могло бы стать красным, а «просрочено» зелёным.
 */
@Composable
fun MangoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> MangoDarkColorScheme
        else -> MangoLightColorScheme
    }

    val semanticColors = if (darkTheme) DarkSemanticColors else LightSemanticColors

    CompositionLocalProvider(
        LocalMangoSemanticColors provides semanticColors,
        LocalMangoSpacing provides MangoSpacing(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MangoTypography,
            shapes = MangoShapes,
            content = content,
        )
    }
}

/** Доступ к теме Mango в дополнение к [MaterialTheme]. */
object MangoTheme {
    val semanticColors: MangoSemanticColors
        @Composable @ReadOnlyComposable
        get() = LocalMangoSemanticColors.current

    val spacing: MangoSpacing
        @Composable @ReadOnlyComposable
        get() = LocalMangoSpacing.current
}
