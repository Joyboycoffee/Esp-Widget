package dev.gourav.espwidget.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gourav.espwidget.AppGraph
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Resolved theme. Reading it inside a composable subscribes that composable to theme changes. */
object ThemeState {
    var dark by mutableStateOf(false)
}

val Paper: Color get() = if (ThemeState.dark) Color(0xFF16141A) else Color(0xFFFDF8F3)
val CardWhite: Color get() = if (ThemeState.dark) Color(0xFF211F27) else Color(0xFFFFFDFB)
val Ink: Color get() = if (ThemeState.dark) Color(0xFFF1ECE6) else Color(0xFF2E2A27)
val Muted: Color get() = if (ThemeState.dark) Color(0xFF9A93A3) else Color(0xFF8C857D)
val Line: Color get() = if (ThemeState.dark) Color(0xFF38343F) else Color(0xFFEDE6DC)
val Peach = Color(0xFFF4B8A4)
val Sage = Color(0xFFA8C6AE)
val Sky = Color(0xFFA9CBE8)
val Lavender = Color(0xFFC6B8E6)
val Butter = Color(0xFFF2D69B)
val Rose = Color(0xFFF0B7C4)
val Good: Color get() = if (ThemeState.dark) Color(0xFF7FB28C) else Color(0xFF5F8A6A)
val Bad: Color get() = if (ThemeState.dark) Color(0xFFD98A8A) else Color(0xFFB96A6A)

private fun lightScheme() = lightColorScheme(
    primary = Peach,
    onPrimary = Ink,
    primaryContainer = Color(0xFFFBE4DC),
    onPrimaryContainer = Ink,
    secondary = Sage,
    onSecondary = Ink,
    secondaryContainer = Color(0xFFE4EFE6),
    onSecondaryContainer = Ink,
    tertiary = Lavender,
    background = Paper,
    onBackground = Ink,
    surface = CardWhite,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF6EFE7),
    onSurfaceVariant = Muted,
    outline = Line,
    error = Bad,
)

private fun darkScheme() = darkColorScheme(
    primary = Peach,
    onPrimary = Color(0xFF2E2A27),
    primaryContainer = Color(0xFF4A3530),
    onPrimaryContainer = Ink,
    secondary = Sage,
    onSecondary = Color(0xFF2E2A27),
    secondaryContainer = Color(0xFF2F3D33),
    onSecondaryContainer = Ink,
    tertiary = Lavender,
    background = Paper,
    onBackground = Ink,
    surface = CardWhite,
    onSurface = Ink,
    surfaceVariant = Color(0xFF2B2832),
    onSurfaceVariant = Muted,
    outline = Line,
    error = Bad,
)

private val typography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
    ),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium),
)

private val shapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
)

@Composable
fun EspWidgetTheme(content: @Composable () -> Unit) {
    val state by AppGraph.repository.state.collectAsStateWithLifecycle()
    val system = isSystemInDarkTheme()
    val dark = when (state.persisted.settings.themeMode) {
        1 -> false
        2 -> true
        else -> system
    }
    // Set before any child reads the palette so everything draws with the right colours.
    ThemeState.dark = dark
    MaterialTheme(
        colorScheme = if (dark) darkScheme() else lightScheme(),
        typography = typography,
        shapes = shapes,
        content = content,
    )
}
