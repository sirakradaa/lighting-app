package app.lights

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF24180F)
val Amber = Color(0xFFE8A15A)
val Paper = Color(0xFFF6F1E8)
val Muted = Color(0xFFB3A79A)
val Faint = Color(0xFF7D746A)
val Background = Color(0xFF12100E)
val Surface = Color(0xFF1C1916)
val SurfaceHigh = Color(0xFF26221D)

private val Colors = darkColorScheme(
    primary = Amber,
    onPrimary = Ink,
    background = Background,
    onBackground = Paper,
    surface = Surface,
    onSurface = Paper,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = Muted,
    error = Color(0xFFE07A5F),
)

@Composable
fun LightsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
