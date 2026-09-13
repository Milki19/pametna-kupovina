package rs.pametnakupovina.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

private val DarkColorScheme = darkColorScheme(
    primary = MarketGreenDark,
    onPrimary = MarketGreenDarkContainer,
    primaryContainer = MarketGreenDarkContainer,
    onPrimaryContainer = MarketGreenLight,
    secondary = BasketAmberDark,
    onSecondary = Night,
    secondaryContainer = MarketGreenDarkContainer,
    onSecondaryContainer = MarketGreenLight,
    tertiary = BasketAmberDark,
    onTertiary = Night,
    tertiaryContainer = BasketAmberNightContainer,
    onTertiaryContainer = BasketAmberDark,
    error = TomatoDark,
    onError = Night,
    errorContainer = Color(0xFF4A1F1D),
    onErrorContainer = TomatoDark,
    background = Night,
    onBackground = Moonlight,
    surface = Night,
    onSurface = Moonlight,
    surfaceVariant = NightVariant,
    onSurfaceVariant = MoonlightMuted,
    surfaceContainerLowest = Night,
    surfaceContainerLow = NightLow,
    surfaceContainer = NightContainer,
    surfaceContainerHigh = NightContainerHigh,
    surfaceContainerHighest = NightContainerHighest,
    outline = NightOutline,
    outlineVariant = NightOutlineFaint,
    inverseSurface = Moonlight,
    inverseOnSurface = Night,
    inversePrimary = MarketGreen
)

private val LightColorScheme = lightColorScheme(
    primary = MarketGreen,
    onPrimary = Paper,
    primaryContainer = MarketGreenContainer,
    onPrimaryContainer = Ink,
    secondary = BasketAmber,
    secondaryContainer = MarketGreenSoft,
    tertiary = BasketAmber,
    tertiaryContainer = BasketAmberLight,
    onSecondary = Paper,
    onSecondaryContainer = Ink,
    onTertiary = Paper,
    onTertiaryContainer = Ink,
    error = Tomato,
    onError = Paper,
    errorContainer = TomatoContainer,
    onErrorContainer = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Mist,
    onSurfaceVariant = InkMuted,
    surfaceContainerLowest = Paper,
    surfaceContainerLow = PaperLow,
    surfaceContainer = PaperContainer,
    surfaceContainerHigh = PaperContainerHigh,
    surfaceContainerHighest = PaperContainerHighest,
    outline = InkOutline,
    outlineVariant = InkOutlineFaint,
    inverseSurface = Ink,
    inverseOnSurface = Paper,
    inversePrimary = MarketGreenDark
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp)
)

@Composable
fun PametnaKupovinaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // Raised once here rather than at fifty call sites: every Material control
    // gets a touch area a shaky hand can hit, without changing how it looks.
    CompositionLocalProvider(
        LocalMinimumInteractiveComponentSize provides 52.dp
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = AppShapes,
            content = content
        )
    }
}
