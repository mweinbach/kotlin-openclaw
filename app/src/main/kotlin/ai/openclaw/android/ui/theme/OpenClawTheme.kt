package ai.openclaw.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Custom status colors used across the app. */
data class StatusColors(
    val connected: Color = Color(0xFF4CAF50),
    val warning: Color = Color(0xFFFFC107),
    val error: Color = Color(0xFFF44336),
    val offline: Color = Color(0xFF9E9E9E),
    val info: Color = Color(0xFF2196F3),
)

val LocalStatusColors = staticCompositionLocalOf { StatusColors() }

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFA5D6A7),
    onSecondary = Color(0xFF0D3819),
    secondaryContainer = Color(0xFF1B5E20),
    onSecondaryContainer = Color(0xFFC8E6C9),
    tertiary = Color(0xFFCE93D8),
    onTertiary = Color(0xFF4A0072),
    tertiaryContainer = Color(0xFF6A1B9A),
    onTertiaryContainer = Color(0xFFF3E5F5),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    surfaceVariant = Color(0xFF2C2C2C),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF2E7D32),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC8E6C9),
    onSecondaryContainer = Color(0xFF002106),
    tertiary = Color(0xFF7B1FA2),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF3E5F5),
    onTertiaryContainer = Color(0xFF2E004E),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OpenClawTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val statusColors = if (darkTheme) StatusColors() else StatusColors(
        connected = Color(0xFF388E3C),
        warning = Color(0xFFF9A825),
        error = Color(0xFFD32F2F),
        offline = Color(0xFF757575),
        info = Color(0xFF1976D2),
    )

    CompositionLocalProvider(LocalStatusColors provides statusColors) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            typography = Typography(),
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}
