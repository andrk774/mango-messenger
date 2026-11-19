package com.mango.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Палитра Mango Messenger — оригинальная, построена вокруг тёплого
 * манго-оранжевого как основного и глубокого морского как вторичного.
 *
 * Контраст основных пар проверен на соответствие WCAG AA (4.5:1 для текста):
 * onPrimary на primary, onSurface на surface, onSurfaceVariant на surfaceVariant.
 */

// --- Primary: манго ---
internal val Mango10 = Color(0xFF2E1500)
internal val Mango20 = Color(0xFF4C2700)
internal val Mango30 = Color(0xFF6D3A00)
internal val Mango40 = Color(0xFF8F4F00)
internal val Mango50 = Color(0xFFB36500)
internal val Mango60 = Color(0xFFD97E07)
internal val Mango70 = Color(0xFFF59A2E)
internal val Mango80 = Color(0xFFFFB871)
internal val Mango90 = Color(0xFFFFDCBE)
internal val Mango95 = Color(0xFFFFEEE0)

// --- Secondary: морская волна ---
internal val Teal10 = Color(0xFF00201C)
internal val Teal20 = Color(0xFF003731)
internal val Teal30 = Color(0xFF005048)
internal val Teal40 = Color(0xFF006A60)
internal val Teal80 = Color(0xFF59DBCB)
internal val Teal90 = Color(0xFF78F8E6)

// --- Tertiary: сливовый, для акцентов на задачах ---
internal val Plum10 = Color(0xFF261431)
internal val Plum20 = Color(0xFF3C2948)
internal val Plum30 = Color(0xFF543F60)
internal val Plum40 = Color(0xFF6D5679)
internal val Plum80 = Color(0xFFD9BDE6)
internal val Plum90 = Color(0xFFF2DAFF)

// --- Error ---
internal val Red10 = Color(0xFF410002)
internal val Red20 = Color(0xFF690005)
internal val Red30 = Color(0xFF93000A)
internal val Red40 = Color(0xFFBA1A1A)
internal val Red80 = Color(0xFFFFB4AB)
internal val Red90 = Color(0xFFFFDAD6)

// --- Нейтральные ---
internal val Neutral6 = Color(0xFF131110)
internal val Neutral10 = Color(0xFF1F1B16)
internal val Neutral12 = Color(0xFF231F1A)
internal val Neutral17 = Color(0xFF2E2A25)
internal val Neutral20 = Color(0xFF362F2A)
internal val Neutral22 = Color(0xFF3A342E)
internal val Neutral24 = Color(0xFF3F3832)
internal val Neutral30 = Color(0xFF4F4539)
internal val Neutral50 = Color(0xFF807567)
internal val Neutral60 = Color(0xFF9A8F80)
internal val Neutral80 = Color(0xFFD5C3B5)
internal val Neutral87 = Color(0xFFEBE0D6)
internal val Neutral90 = Color(0xFFEFE0D5)
internal val Neutral92 = Color(0xFFF5EAE1)
internal val Neutral94 = Color(0xFFF9F0E8)
internal val Neutral96 = Color(0xFFFCF5EF)
internal val Neutral98 = Color(0xFFFFFBF7)
internal val NeutralVariant30 = Color(0xFF50453B)
internal val NeutralVariant50 = Color(0xFF837468)
internal val NeutralVariant60 = Color(0xFF9D8E81)
internal val NeutralVariant80 = Color(0xFFD6C3B4)
internal val NeutralVariant90 = Color(0xFFF2E0D0)

val MangoLightColorScheme = lightColorScheme(
    primary = Color(0xFF1766D4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4EFFF),
    onPrimaryContainer = Color(0xFF163A68),
    inversePrimary = Mango80,

    secondary = Teal40,
    onSecondary = Color.White,
    secondaryContainer = Teal90,
    onSecondaryContainer = Teal10,

    tertiary = Plum40,
    onTertiary = Color.White,
    tertiaryContainer = Plum90,
    onTertiaryContainer = Plum10,

    error = Red40,
    onError = Color.White,
    errorContainer = Red90,
    onErrorContainer = Red10,

    background = Color.White,
    onBackground = Neutral10,
    surface = Color.White,
    onSurface = Color(0xFF192535),
    surfaceVariant = Color(0xFFEEF2F7),
    onSurfaceVariant = Color(0xFF596678),
    surfaceTint = Color(0xFF1766D4),

    surfaceDim = Neutral87,
    surfaceBright = Neutral98,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF1F4F9),
    surfaceContainerHigh = Color(0xFFE9EEF6),
    surfaceContainerHighest = Color(0xFFE2E9F2),

    outline = NeutralVariant50,
    outlineVariant = Color(0xFFDCE3ED),
    scrim = Color.Black,

    inverseSurface = Neutral20,
    inverseOnSurface = Neutral96,
)

val MangoDarkColorScheme = darkColorScheme(
    primary = Mango80,
    onPrimary = Mango20,
    primaryContainer = Mango30,
    onPrimaryContainer = Mango90,
    inversePrimary = Mango40,

    secondary = Teal80,
    onSecondary = Teal20,
    secondaryContainer = Teal30,
    onSecondaryContainer = Teal90,

    tertiary = Plum80,
    onTertiary = Plum20,
    tertiaryContainer = Plum30,
    onTertiaryContainer = Plum90,

    error = Red80,
    onError = Red20,
    errorContainer = Red30,
    onErrorContainer = Red90,

    background = Neutral6,
    onBackground = Neutral90,
    surface = Neutral6,
    onSurface = Neutral90,
    surfaceVariant = NeutralVariant30,
    onSurfaceVariant = NeutralVariant80,
    surfaceTint = Mango80,

    surfaceDim = Neutral6,
    surfaceBright = Neutral24,
    surfaceContainerLowest = Color(0xFF0E0C0B),
    surfaceContainerLow = Neutral10,
    surfaceContainer = Neutral12,
    surfaceContainerHigh = Neutral17,
    surfaceContainerHighest = Neutral22,

    outline = NeutralVariant60,
    outlineVariant = NeutralVariant30,
    scrim = Color.Black,

    inverseSurface = Neutral90,
    inverseOnSurface = Neutral20,
)

/**
 * Семантические цвета, которых нет в Material 3 ColorScheme,
 * но которые нужны мессенджеру и менеджеру задач.
 *
 * Держим их отдельной структурой, а не «магическими» Color(...) по месту,
 * чтобы тёмная тема и Dynamic Color не ломали смысл.
 */
data class MangoSemanticColors(
    val outgoingBubble: Color,
    val onOutgoingBubble: Color,
    val incomingBubble: Color,
    val onIncomingBubble: Color,
    val online: Color,
    val away: Color,
    val doNotDisturb: Color,
    val offline: Color,
    val syncPending: Color,
    val syncFailed: Color,
    val priorityUrgent: Color,
    val priorityHigh: Color,
    val priorityMedium: Color,
    val priorityLow: Color,
    val statusTodo: Color,
    val statusInProgress: Color,
    val statusReview: Color,
    val statusDone: Color,
    val statusCancelled: Color,
    val overdue: Color,
)

internal val LightSemanticColors = MangoSemanticColors(
    outgoingBubble = Color(0xFFE4EFFF),
    onOutgoingBubble = Color(0xFF163A68),
    incomingBubble = Color.White,
    onIncomingBubble = Neutral10,
    online = Color(0xFF1B873B),
    away = Color(0xFFB36500),
    doNotDisturb = Red40,
    offline = Neutral60,
    syncPending = Neutral50,
    syncFailed = Red40,
    priorityUrgent = Color(0xFFBA1A1A),
    priorityHigh = Color(0xFFC25E00),
    priorityMedium = Color(0xFF6D5679),
    priorityLow = Neutral50,
    statusTodo = Neutral50,
    statusInProgress = Color(0xFF0B6BCB),
    statusReview = Color(0xFF8F4F00),
    statusDone = Color(0xFF1B873B),
    statusCancelled = Neutral60,
    overdue = Red40,
)

internal val DarkSemanticColors = MangoSemanticColors(
    outgoingBubble = Mango30,
    onOutgoingBubble = Mango90,
    incomingBubble = Neutral22,
    onIncomingBubble = Neutral90,
    online = Color(0xFF6BDD8B),
    away = Color(0xFFFFB871),
    doNotDisturb = Red80,
    offline = Neutral60,
    syncPending = Neutral60,
    syncFailed = Red80,
    priorityUrgent = Red80,
    priorityHigh = Color(0xFFFFB871),
    priorityMedium = Plum80,
    priorityLow = Neutral60,
    statusTodo = Neutral60,
    statusInProgress = Color(0xFF9CCAFF),
    statusReview = Color(0xFFFFB871),
    statusDone = Color(0xFF6BDD8B),
    statusCancelled = Neutral50,
    overdue = Red80,
)
