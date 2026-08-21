package rs.pametnakupovina.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

private val DarkColorScheme = darkColorScheme(
    primary = MarketGreenDark,
    onPrimary = MarketGreenDarkContainer,
    primaryContainer = MarketGreenDarkContainer,
    onPrimaryContainer = MarketGreenLight,
    secondary = BasketAmberDark,
    tertiary = BasketAmberDark,
    error = TomatoDark,
    background = Night,
    surface = NightSurface,
    surfaceVariant = NightSurface
)

private val LightColorScheme = lightColorScheme(
    primary = MarketGreen,
    onPrimary = Paper,
    primaryContainer = MarketGreenContainer,
    onPrimaryContainer = Ink,
    secondary = BasketAmber,
    secondaryContainer = BasketAmberLight,
    tertiary = BasketAmber,
    tertiaryContainer = BasketAmberLight,
    error = Tomato,
    errorContainer = TomatoContainer,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Mist
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
