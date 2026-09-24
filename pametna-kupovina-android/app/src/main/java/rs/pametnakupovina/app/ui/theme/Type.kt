package rs.pametnakupovina.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import rs.pametnakupovina.app.R

/**
 * Inter iz dizajna (24.09.2026), jedna datoteka za sve debljine. Veličina
 * slova i dalje prati podešavanje telefona.
 */
@OptIn(ExperimentalTextApi::class)
private val AppFont = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map {
        Font(R.font.inter, it, variationSettings = FontVariation.Settings(FontVariation.weight(it.weight)))
    }
)

/** Cifre iste širine, da se cene poravnaju jedna ispod druge. */
private const val Tabular = "tnum"

/**
 * Material's own sizes, deliberately. The app used to add about 15% on top of
 * them so a price would read in a badly lit aisle — but someone who needs
 * bigger text has already told the phone so, and the app's extra zoom landed
 * on top of theirs: on a phone set to large text and large display the list
 * showed six items and a single card filled the screen.
 *
 * How big the text is belongs to the reader's phone setting, not to us. What
 * belongs to us is that the app still reads well at any of those settings,
 * which is why nothing here is clipped to one line.
 */
val Typography = Typography(
    /** For the one number a screen exists to deliver, and nothing else. */
    displaySmall = TextStyle(
        fontFamily = AppFont,
        fontFeatureSettings = Tabular,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.02).em
    ),
    headlineSmall = TextStyle(
        fontFamily = AppFont,
        fontFeatureSettings = Tabular,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.015).em
    ),
    titleLarge = TextStyle(
        fontFamily = AppFont,
        fontFeatureSettings = Tabular,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.01).em
    ),
    titleMedium = TextStyle(
        fontFamily = AppFont,
        fontFeatureSettings = Tabular,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = AppFont,
        fontFeatureSettings = Tabular,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = AppFont,
        fontFeatureSettings = Tabular,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = AppFont,
        fontFeatureSettings = Tabular,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = AppFont,
        fontFeatureSettings = Tabular,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = AppFont,
        fontFeatureSettings = Tabular,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = AppFont,
        fontFeatureSettings = Tabular,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
)
