package com.mango.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Токены отступов.
 *
 * Шкала кратна 4 dp. Отдельная структура вместо «магических» значений по месту:
 * иначе выравнивание разъезжается между экранами, и это невозможно
 * исправить централизованно.
 */
data class MangoSpacing(
    val none: Dp = 0.dp,
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val xxxl: Dp = 48.dp,

    /** Горизонтальный отступ контента экрана. */
    val screenHorizontal: Dp = 16.dp,

    /** Минимальный размер зоны нажатия — требование доступности. */
    val minTouchTarget: Dp = 48.dp,

    /** Размеры аватаров. */
    val avatarSmall: Dp = 32.dp,
    val avatarMedium: Dp = 44.dp,
    val avatarLarge: Dp = 56.dp,
    val avatarHuge: Dp = 96.dp,
)

val MangoShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Формы пузырей сообщений: «хвостик» со стороны автора. */
object BubbleShapes {
    val outgoing = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
    val incoming = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
    val grouped = RoundedCornerShape(18.dp)
}

/**
 * Типографика.
 *
 * Используется системный шрифт: он уже установлен, не увеличивает APK
 * и корректно поддерживает кириллицу и настройку размера шрифта в системе.
 * Размеры заданы в sp, поэтому масштабируются при увеличенном шрифте.
 */
private val lineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun mangoTextStyle(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: Double = 0.0,
) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
    lineHeightStyle = lineHeightStyle,
)

val MangoTypography = Typography(
    displayLarge = mangoTextStyle(57, 64, FontWeight.Normal, -0.25),
    displayMedium = mangoTextStyle(45, 52),
    displaySmall = mangoTextStyle(36, 44),

    headlineLarge = mangoTextStyle(32, 40, FontWeight.SemiBold),
    headlineMedium = mangoTextStyle(28, 36, FontWeight.SemiBold),
    headlineSmall = mangoTextStyle(24, 32, FontWeight.SemiBold),

    titleLarge = mangoTextStyle(22, 28, FontWeight.SemiBold),
    titleMedium = mangoTextStyle(16, 24, FontWeight.SemiBold, 0.15),
    titleSmall = mangoTextStyle(14, 20, FontWeight.Medium, 0.1),

    bodyLarge = mangoTextStyle(16, 24, letterSpacing = 0.5),
    bodyMedium = mangoTextStyle(14, 20, letterSpacing = 0.25),
    bodySmall = mangoTextStyle(12, 16, letterSpacing = 0.4),

    labelLarge = mangoTextStyle(14, 20, FontWeight.Medium, 0.1),
    labelMedium = mangoTextStyle(12, 16, FontWeight.Medium, 0.5),
    labelSmall = mangoTextStyle(11, 16, FontWeight.Medium, 0.5),
)
