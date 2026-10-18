package io.github.abrahampaulj.pocketfuse.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Violet = Color(0xFFB388FF)
val VioletDeep = Color(0xFF7C4DFF)
val Ok = Color(0xFF4ADE80)
val Warn = Color(0xFFFBBF24)
val Bad = Color(0xFFF87171)

private val scheme = darkColorScheme(
    primary = Violet,
    onPrimary = Color(0xFF1A1033),
    primaryContainer = Color(0xFF2A1F47),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFF9CA3AF),
    background = Color(0xFF0E0F13),
    onBackground = Color(0xFFE6E7EB),
    surface = Color(0xFF16181D),
    onSurface = Color(0xFFE6E7EB),
    surfaceVariant = Color(0xFF1F2229),
    onSurfaceVariant = Color(0xFFA1A6B0),
    surfaceContainerLowest = Color(0xFF0E0F13),
    surfaceContainerLow = Color(0xFF13151A),
    surfaceContainer = Color(0xFF16181D),
    surfaceContainerHigh = Color(0xFF1C1F25),
    surfaceContainerHighest = Color(0xFF23262D),
    outline = Color(0xFF3A3E47),
    outlineVariant = Color(0xFF2A2D34),
    error = Bad,
)

private val type = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

@Composable
fun PocketfuseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
