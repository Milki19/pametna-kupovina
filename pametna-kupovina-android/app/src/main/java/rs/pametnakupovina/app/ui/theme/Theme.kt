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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

private val DarkColorScheme = darkColorScheme(
    primary = TealLight,
    onPrimary = TealInk,
    primaryContainer = TealNightContainer,
    onPrimaryContainer = TealNightOn,
    secondary = MintNight,
    onSecondary = TealInk,
    secondaryContainer = MintNightContainer,
    onSecondaryContainer = Mint,
    tertiary = AmberNight,
    onTertiary = Night,
    tertiaryContainer = AmberNightContainer,
    onTertiaryContainer = AmberNight,
    error = CrimsonNight,
    onError = Night,
    errorContainer = CrimsonNightContainer,
    onErrorContainer = CrimsonNight,
    background = Night,
    onBackground = Moonlight,
    surface = Night,
    onSurface = Moonlight,
    surfaceVariant = NightHighest,
    onSurfaceVariant = MoonlightMuted,
    surfaceContainerLowest = NightLowest,
    surfaceContainerLow = NightLow,
    surfaceContainer = NightCard,
    surfaceContainerHigh = NightHigh,
    surfaceContainerHighest = NightHighest,
    outline = NightOutline,
    outlineVariant = NightHairline,
    inverseSurface = Moonlight,
    inverseOnSurface = Night,
    inversePrimary = Teal
)

private val LightColorScheme = lightColorScheme(
    primary = Teal,
    onPrimary = CardWhite,
    primaryContainer = TealMist,
    onPrimaryContainer = TealInk,
    secondary = MintInk,
    onSecondary = CardWhite,
    secondaryContainer = Mint,
    onSecondaryContainer = MintInk,
    tertiary = Amber,
    onTertiary = CardWhite,
    tertiaryContainer = AmberLight,
    onTertiaryContainer = AmberInk,
    error = Crimson,
    onError = CardWhite,
    errorContainer = CrimsonContainer,
    onErrorContainer = CrimsonInk,
    background = Canvas,
    onBackground = Slate,
    surface = Canvas,
    onSurface = Slate,
    surfaceVariant = CanvasHighest,
    onSurfaceVariant = SlateMuted,
    surfaceContainerLowest = CardWhite,
    surfaceContainerLow = CanvasLow,
    surfaceContainer = CardWhite,
    // Prozori su beli kao na nacrtu; svetla lavanda je surfaceContainerHighest.
    surfaceContainerHigh = CardWhite,
    surfaceContainerHighest = CanvasHighest,
    outline = SlateOutline,
    outlineVariant = Hairline,
    inverseSurface = InverseSlate,
    inverseOnSurface = InverseCanvas,
    inversePrimary = TealLight
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(12.dp),
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
