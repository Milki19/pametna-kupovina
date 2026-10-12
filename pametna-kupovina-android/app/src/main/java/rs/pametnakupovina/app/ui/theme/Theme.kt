package rs.pametnakupovina.app.ui.theme

import android.app.Activity
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/** Izbor iz Menija. SYSTEM u tamnom režimu telefona znači sivu tamnu. */
enum class ThemeChoice { SYSTEM, LIGHT, DARK, BLACK }

/**
 * Material popunjava svaku ulogu koja nije navedena iz svoje ljubičaste
 * osnove, pa je ovde svaki korak površine naveden. surfaceContainer je
 * staklo, jer sve kartice u aplikaciji crtaju baš tu ulogu; prozori i meniji
 * (surfaceContainerHigh, surfaceContainerLowest) ostaju neprovidni.
 */
private val LightColorScheme = lightColorScheme(
    primary = Blue,
    onPrimary = White,
    primaryContainer = BlueSoft,
    onPrimaryContainer = BlueInk,
    secondary = Blue,
    onSecondary = White,
    secondaryContainer = BlueSoft,
    onSecondaryContainer = BlueInk,
    tertiary = Mustard,
    onTertiary = White,
    tertiaryContainer = MustardSoft,
    onTertiaryContainer = Mustard,
    error = Red,
    onError = White,
    errorContainer = RedSoft,
    onErrorContainer = RedInk,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    // Različito od surfaceContainerLow: Material po boji podloge bira boju teksta.
    surfaceVariant = Line,
    onSurfaceVariant = InkMuted,
    surfaceContainerLowest = White,
    surfaceContainerLow = PaperDim,
    surfaceContainer = White.copy(alpha = 0.72f),
    surfaceContainerHigh = White,
    surfaceContainerHighest = PaperDim,
    outline = InkMuted,
    outlineVariant = Line,
    inverseSurface = Ink,
    inverseOnSurface = Paper,
    inversePrimary = SnackBlue
)

/** Akcenat samo na sitnim delovima; površine su neutralno sive. */
private val DarkColorScheme = darkColorScheme(
    primary = BlueNight,
    onPrimary = BlueNightInk,
    primaryContainer = White.copy(alpha = 0.12f),
    onPrimaryContainer = White,
    secondary = BlueNight,
    onSecondary = BlueNightInk,
    secondaryContainer = White.copy(alpha = 0.12f),
    onSecondaryContainer = White,
    tertiary = MustardNight,
    onTertiary = Coal,
    tertiaryContainer = MustardNightSoft,
    onTertiaryContainer = MustardNight,
    error = RedNight,
    onError = RedNightInk,
    errorContainer = RedNightSoft,
    onErrorContainer = RedNight,
    background = Graphite,
    onBackground = Snow,
    surface = Graphite,
    onSurface = Snow,
    surfaceVariant = White.copy(alpha = 0.11f),
    onSurfaceVariant = SnowMuted,
    surfaceContainerLowest = GraphiteSheet,
    surfaceContainerLow = White.copy(alpha = 0.1f),
    surfaceContainer = Color(0x9934363C),
    surfaceContainerHigh = GraphiteDialog,
    surfaceContainerHighest = White.copy(alpha = 0.1f),
    outline = SnowMuted,
    outlineVariant = White.copy(alpha = 0.12f),
    inverseSurface = Snow,
    inverseOnSurface = Graphite,
    inversePrimary = Blue
)

private val BlackColorScheme = DarkColorScheme.copy(
    background = Amoled,
    surface = Amoled,
    surfaceVariant = White.copy(alpha = 0.1f),
    surfaceContainerLowest = AmoledSheet,
    surfaceContainerLow = White.copy(alpha = 0.09f),
    surfaceContainer = Color(0x8C1C1C20),
    surfaceContainerHigh = AmoledDialog,
    surfaceContainerHighest = White.copy(alpha = 0.09f),
    outlineVariant = White.copy(alpha = 0.1f),
    inverseOnSurface = Amoled
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * Tačno kad je u telefonu uključeno uklanjanje animacija. Compose tada sam
 * završava animacije u trenutku; ovo služi za ono što on ne vidi (kašnjenja
 * između kartica, plovljenje pozadine).
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun PametnaKupovinaTheme(
    choice: ThemeChoice = ThemeChoice.SYSTEM,
    content: @Composable () -> Unit
) {
    val resolved = when (choice) {
        ThemeChoice.SYSTEM -> if (isSystemInDarkTheme()) ThemeChoice.DARK else ThemeChoice.LIGHT
        else -> choice
    }
    val (colorScheme, appColors) = when (resolved) {
        ThemeChoice.BLACK -> BlackColorScheme to BlackAppColors
        ThemeChoice.DARK -> DarkColorScheme to DarkAppColors
        else -> LightColorScheme to LightAppColors
    }

    // Sat i baterija prate temu aplikacije, ne temu telefona.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !appColors.dark
                    isAppearanceLightNavigationBars = !appColors.dark
                }
            }
        }
    }

    val context = LocalContext.current
    val reduceMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    // Raised once here rather than at fifty call sites: every Material control
    // gets a touch area a shaky hand can hit, without changing how it looks.
    CompositionLocalProvider(
        LocalMinimumInteractiveComponentSize provides 52.dp,
        LocalAppColors provides appColors,
        LocalReduceMotion provides reduceMotion
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = AppShapes
        ) {
            // Ekrani imaju providnu podlogu, pa boju teksta ne dobijaju od nje.
            CompositionLocalProvider(LocalContentColor provides colorScheme.onBackground, content = content)
        }
    }
}
