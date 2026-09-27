package rs.pametnakupovina.app.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import rs.pametnakupovina.app.R
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/*
 * Every number and date on screen goes through here, written the way a Serbian
 * shelf label writes it: 1.086,92 RSD and 13.09.2026. The symbols are fixed
 * rather than taken from the device, so a phone set to English still shows
 * the format printed next to the product. Words (month names, "u", counted
 * nouns) come from string resources; counts use `<plurals>`.
 */

private fun serbianFormat(pattern: String): DecimalFormat {
    val symbols = DecimalFormatSymbols().apply {
        decimalSeparator = ','
        groupingSeparator = '.'
    }
    return DecimalFormat(pattern, symbols).apply {
        roundingMode = RoundingMode.HALF_UP
    }
}

/** A price to the para: 1.086,92 RSD. */
fun money(value: Double): String =
    serbianFormat("#,##0.00").format(BigDecimal.valueOf(value)) + " RSD"

/** Whole dinars without the currency, for comparing totals side by side. */
fun wholeDinars(value: Double): String =
    serbianFormat("#,##0").format(BigDecimal.valueOf(value))

/** A plain amount with at most [maxDecimals] decimals: 1,5 or 1.000. */
fun decimal(value: Double, maxDecimals: Int = 2): String =
    serbianFormat("#,##0." + "#".repeat(maxDecimals))
        .format(BigDecimal.valueOf(value))
        .removeSuffix(",")

/** Metres below a kilometre, because "0,55 km" makes the reader do sums. */
fun distance(kilometres: Double): String =
    if (kilometres < 1.0) {
        "${BigDecimal.valueOf(kilometres * 1000).setScale(-1, RoundingMode.HALF_UP).toInt()} m"
    } else {
        "${decimal(kilometres, maxDecimals = 1)} km"
    }

fun duration(seconds: Long): String {
    val totalMinutes = (seconds + 30) / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "$hours h $minutes min" else "$minutes min"
}

private val DateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy.")
private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm")

/** An ISO date from the server, 2026-09-13, as 13.09.2026. */
fun date(iso: String): String = try {
    LocalDate.parse(iso).format(DateFormat)
} catch (_: DateTimeParseException) {
    iso
}

/** Any date in September 2026 becomes "septembar 2026." (or "September 2026"). */
fun monthName(month: LocalDate, resources: Resources): String =
    resources.getString(R.string.format_month_year, monthWord(month, resources), month.year)

@Composable
fun monthName(month: LocalDate): String = monthName(month, LocalResources.current)

/** The month alone, lower case in Serbian: "septembar". */
fun monthWord(month: LocalDate, resources: Resources): String =
    resources.getStringArray(R.array.month_names)[month.monthValue - 1]

@Composable
fun monthWord(month: LocalDate): String = monthWord(month, LocalResources.current)

private val ShortDateFormat = DateTimeFormatter.ofPattern("dd.MM.")

/** Day and month only, for a date that sits next to other information. */
fun shortDate(iso: String): String = try {
    LocalDate.parse(iso).format(ShortDateFormat)
} catch (_: DateTimeParseException) {
    iso
}

fun dateTime(
    epochMillis: Long,
    resources: Resources,
    zone: ZoneId = ZoneId.systemDefault()
): String {
    val moment = Instant.ofEpochMilli(epochMillis).atZone(zone)
    return resources.getString(
        R.string.format_date_at_time,
        moment.format(DateFormat),
        moment.format(TimeFormat)
    )
}

@Composable
fun dateTime(epochMillis: Long): String = dateTime(epochMillis, LocalResources.current)
