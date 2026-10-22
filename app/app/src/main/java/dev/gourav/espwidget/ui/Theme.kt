package dev.gourav.espwidget.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Paper = Color(0xFFFDF8F3)
val CardWhite = Color(0xFFFFFDFB)
val Ink = Color(0xFF2E2A27)
val Muted = Color(0xFF8C857D)
val Line = Color(0xFFEDE6DC)
val Peach = Color(0xFFF4B8A4)
val Sage = Color(0xFFA8C6AE)
val Sky = Color(0xFFA9CBE8)
val Lavender = Color(0xFFC6B8E6)
val Butter = Color(0xFFF2D69B)
val Rose = Color(0xFFF0B7C4)
val Good = Color(0xFF5F8A6A)
val Bad = Color(0xFFB96A6A)

private val scheme = lightColorScheme(
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
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
}
