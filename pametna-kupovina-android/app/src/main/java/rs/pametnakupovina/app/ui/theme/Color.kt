package rs.pametnakupovina.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * Boje iz odobrenog nacrta (12.10.2026): plava za radnje, narandžasta za
 * uštedu, žuta za upozorenja; zelene nema. Kartice su poluprovidno „staklo"
 * preko pozadine sa mekim mrljama boje.
 */
val Blue = Color(0xFF2F4BD8)
val BlueSoft = Color(0xFFE0E6FF)
val BlueInk = Color(0xFF1A2A80)
val BlueNight = Color(0xFF8EA2FF)
val BlueNightInk = Color(0xFF0B1446)
val SnackBlue = Color(0xFFB9C6FF)

val Orange = Color(0xFFC2410C)
val OrangeSoft = Color(0xFFFFE6D6)
val OrangeNight = Color(0xFFFF9E73)
val OrangeNightSoft = Color(0xFF4A2414)

val Mustard = Color(0xFF6B5300)
val MustardSoft = Color(0xFFFFF1B8)
val MustardNight = Color(0xFFFFD966)
val MustardNightSoft = Color(0xFF3A3010)

val Red = Color(0xFFB3261E)
val RedSoft = Color(0xFFFFDAD6)
val RedInk = Color(0xFF93000A)
val RedNight = Color(0xFFFFB4AB)
val RedNightSoft = Color(0xFF4A1F1D)
val RedNightInk = Color(0xFF690005)

val Paper = Color(0xFFF5F6FA)
val PaperDim = Color(0xFFECEEF5)
val Ink = Color(0xFF12162A)
val InkMuted = Color(0xFF545C70)
val Line = Color(0xFFDFE3EC)
val White = Color(0xFFFFFFFF)

val Graphite = Color(0xFF1C1D21)
val GraphiteDialog = Color(0xFF2B2D32)
val GraphiteSheet = Color(0xFF26272C)
val Amoled = Color(0xFF050506)
val AmoledDialog = Color(0xFF1A1A1D)
val AmoledSheet = Color(0xFF121214)
val Snow = Color(0xFFF4F4F5)
val SnowMuted = Color(0xFFA1A1AA)
val Coal = Color(0xFF0A0A0B)

/** Ono što Material šema nema: staklo, kartica potrošnje, glavno dugme, ušteda. */
data class AppColors(
    val dark: Boolean,
    /** Ivica staklene kartice i njen svetli gornji rub. */
    val glassEdge: Color,
    val glassHigh: Color,
    /** Lebdeća donja traka: skoro neprovidna, jer iza nje nema zamućenja. */
    val navBar: Color,
    /** Kartica potrošnje: plava u svetloj temi, neutralno staklo u tamnim. */
    val hero: Color,
    val onHero: Color,
    val heroPill: Color,
    val heroDivider: Color,
    /** Glavno dugme: u tamnim temama belo sa crnim tekstom. */
    val button: Color,
    val onButton: Color,
    val save: Color,
    val saveContainer: Color,
    /** Stubić koji nije izabran. */
    val bar: Color,
    /** Mrlje iza stakla. */
    val orbs: List<Color>
)

val LightAppColors = AppColors(
    dark = false,
    glassEdge = White.copy(alpha = 0.75f),
    glassHigh = White.copy(alpha = 0.95f),
    navBar = White.copy(alpha = 0.96f),
    hero = Blue,
    onHero = White,
    heroPill = White.copy(alpha = 0.18f),
    heroDivider = White.copy(alpha = 0.24f),
    button = Blue,
    onButton = White,
    save = Orange,
    saveContainer = OrangeSoft,
    bar = BlueSoft,
    orbs = listOf(BlueSoft, OrangeSoft, White.copy(alpha = 0.8f))
)

val DarkAppColors = AppColors(
    dark = true,
    glassEdge = White.copy(alpha = 0.14f),
    glassHigh = White.copy(alpha = 0.2f),
    navBar = Color(0xF534363C),
    hero = White.copy(alpha = 0.09f),
    onHero = Snow,
    heroPill = White.copy(alpha = 0.1f),
    heroDivider = White.copy(alpha = 0.1f),
    button = Snow,
    onButton = Coal,
    save = OrangeNight,
    saveContainer = OrangeNightSoft,
    bar = White.copy(alpha = 0.14f),
    orbs = listOf(Color(0x2E969BAF), Color(0x24787D91), White.copy(alpha = 0.05f))
)

val BlackAppColors = DarkAppColors.copy(
    glassEdge = White.copy(alpha = 0.12f),
    glassHigh = White.copy(alpha = 0.16f),
    navBar = Color(0xF51C1C20),
    hero = White.copy(alpha = 0.07f),
    orbs = listOf(Color(0x2978788C), Color(0x1F5A5A6E), White.copy(alpha = 0.05f))
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }
