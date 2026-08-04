package app.kairo.anime.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF080A0F)
val InkRaised = Color(0xFF10141D)
val Surface = Color(0xFF171C26)
val SurfaceBright = Color(0xFF222A38)
val Violet = Color(0xFFA78BFA)
val Teal = Color(0xFF2DD4BF)
val Amber = Color(0xFFFFB86B)
val Cloud = Color(0xFFF4F1FF)
val Muted = Color(0xFF9AA5B7)
val Danger = Color(0xFFFF6B81)

private val KairoColors = darkColorScheme(
    primary = Violet, onPrimary = Ink, secondary = Teal, onSecondary = Ink,
    tertiary = Amber, background = Ink, onBackground = Cloud,
    surface = Surface, onSurface = Cloud, surfaceVariant = SurfaceBright,
    onSurfaceVariant = Muted, error = Danger
)

@Composable
fun KairoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KairoColors,
        typography = MaterialTheme.typography.copy(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 32.sp, letterSpacing = (-1).sp),
            headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, letterSpacing = (-0.5).sp),
            titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 20.sp),
            titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 16.sp),
            bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 23.sp),
            bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
            labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        ),
        content = content
    )
}
