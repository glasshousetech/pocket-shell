package network.ght.pocketshell.ui

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import network.ght.pocketshell.R
import network.ght.pocketshell.TermTheme
import network.ght.pocketshell.TermThemes

val RailMono = FontFamily(
    Font(R.font.jbm_regular, FontWeight.Normal),
    Font(R.font.jbm_medium, FontWeight.Medium),
    Font(R.font.jbm_bold, FontWeight.Bold),
)

fun themeColor(rgb: Long) = Color(0xFF000000L or rgb)
val LocalPocketTheme = compositionLocalOf { TermThemes.DEFAULT }

// Recompose chrome and dialogs without replacing the Activity or its PTYs.
val RailAccent: Color @Composable get() = themeColor(LocalPocketTheme.current.ui.accent)
val RailAccentDim: Color @Composable get() = themeColor(LocalPocketTheme.current.ui.muted)
val RailBg: Color @Composable get() = themeColor(LocalPocketTheme.current.background)
val RailSurface: Color @Composable get() = themeColor(LocalPocketTheme.current.ui.surface)
val RailSurfaceAlt: Color @Composable get() = themeColor(LocalPocketTheme.current.ui.surfaceAlt)
val RailKeyChip: Color @Composable get() = themeColor(LocalPocketTheme.current.ui.control)
val RailPromptText: Color @Composable get() = themeColor(LocalPocketTheme.current.ui.text)
val RailDimText: Color @Composable get() = themeColor(LocalPocketTheme.current.ui.muted)
val RailOutText: Color @Composable get() = themeColor(LocalPocketTheme.current.ui.text)
val RailError: Color @Composable get() = themeColor(LocalPocketTheme.current.ui.error)
val RailBorder: Color @Composable get() = themeColor(LocalPocketTheme.current.ui.border)
val RailOnAccent: Color @Composable get() = themeColor(LocalPocketTheme.current.ui.onAccent)

@Composable
fun PocketShellTheme(theme: TermTheme = TermThemes.DEFAULT, content: @Composable () -> Unit) {
    val scheme = remember(theme) {
        val base = if (theme.dark) darkColorScheme() else lightColorScheme()
        base.copy(
            primary = themeColor(theme.ui.accent), onPrimary = themeColor(theme.ui.onAccent),
            primaryContainer = themeColor(theme.ui.control), onPrimaryContainer = themeColor(theme.ui.text),
            secondary = themeColor(theme.ui.accent), onSecondary = themeColor(theme.ui.onAccent),
            secondaryContainer = themeColor(theme.ui.control), onSecondaryContainer = themeColor(theme.ui.text),
            tertiary = themeColor(theme.ui.accent), onTertiary = themeColor(theme.ui.onAccent),
            tertiaryContainer = themeColor(theme.ui.control), onTertiaryContainer = themeColor(theme.ui.text),
            background = themeColor(theme.background), onBackground = themeColor(theme.ui.text),
            surface = themeColor(theme.ui.surface), onSurface = themeColor(theme.ui.text),
            surfaceVariant = themeColor(theme.ui.control), onSurfaceVariant = themeColor(theme.ui.muted),
            outline = themeColor(theme.ui.muted), outlineVariant = themeColor(theme.ui.border),
            surfaceTint = themeColor(theme.ui.accent),
            error = themeColor(theme.ui.error), onError = themeColor(theme.ui.onAccent),
            errorContainer = themeColor(theme.ui.control), onErrorContainer = themeColor(theme.ui.error),
        )
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                window.statusBarColor = themeColor(theme.ui.surfaceAlt).toArgb()
                window.navigationBarColor = themeColor(theme.ui.surfaceAlt).toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !theme.dark
                    isAppearanceLightNavigationBars = !theme.dark
                }
            }
        }
    }
    CompositionLocalProvider(LocalPocketTheme provides theme) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
