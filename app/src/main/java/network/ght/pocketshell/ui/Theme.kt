package network.ght.pocketshell.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import network.ght.pocketshell.R

// A real embedded family, not the platform default — three weights give the
// terminal chrome an actual type scale (output vs. prompt vs. active tab).
val RailMono = FontFamily(
    Font(R.font.jbm_regular, FontWeight.Normal),
    Font(R.font.jbm_medium, FontWeight.Medium),
    Font(R.font.jbm_bold, FontWeight.Bold),
)

// Same identity as the "Tab Rail" mockup concept: indigo accent on near-black.
// Panel colors carry real alpha (not opaque) so the home-screen wallpaper shows
// through — a Kali-style frosted terminal rather than a solid dark app.
val RailAccent = Color(0xFF5B7FFF)
val RailAccentDim = Color(0xFF7883B8)
val RailBg = Color(0xBF0C0E13)
val RailSurface = Color(0xD9161B2C)
val RailSurfaceAlt = Color(0xD9101422)
val RailKeyChip = Color(0xD91C2338)
val RailPromptText = Color(0xFFB9C6FF)
val RailDimText = Color(0xFF565F80)
val RailOutText = Color(0xFF9AA3C9)

private val RailDarkScheme = darkColorScheme(
    primary = RailAccent,
    background = RailBg,
    surface = RailSurface,
    onPrimary = Color.White,
    onBackground = RailOutText,
    onSurface = RailPromptText,
)

private val RailLightScheme = lightColorScheme(
    primary = RailAccent,
    background = Color(0xFFF3F1EC),
    surface = Color.White,
    onPrimary = Color.White,
    onBackground = Color(0xFF1B1B18),
    onSurface = Color(0xFF1B1B18),
)

@Composable
fun PocketShellTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) RailDarkScheme else RailLightScheme
    MaterialTheme(colorScheme = scheme, content = content)
}
