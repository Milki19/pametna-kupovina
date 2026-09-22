package rs.pametnakupovina.app.ui

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
 * the format printed next to the product.
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

private val MonthNames = listOf(
    "januar", "februar", "mart", "april", "maj", "jun",
    "jul", "avgust", "septembar", "oktobar", "novembar", "decembar"
)

/** "2026-09-01" or any date in September 2026 becomes "septembar 2026.". */
fun monthName(month: LocalDate): String =
    "${MonthNames[month.monthValue - 1]} ${month.year}."

private val ShortDateFormat = DateTimeFormatter.ofPattern("dd.MM.")

/** Day and month only, for a date that sits next to other information. */
fun shortDate(iso: String): String = try {
    LocalDate.parse(iso).format(ShortDateFormat)
} catch (_: DateTimeParseException) {
    iso
}

fun dateTime(
    epochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault()
): String {
    val moment = Instant.ofEpochMilli(epochMillis).atZone(zone)
    return "${moment.format(DateFormat)} u ${moment.format(TimeFormat)}"
}

/**
 * Serbian nouns take one of three forms after a number: 1 stavka, 2 stavke,
 * 5 stavki. The teens always take the last form.
 */
fun plural(count: Int, one: String, few: String, many: String): String {
    val lastTwo = count % 100
    return when {
        count % 10 == 1 && lastTwo != 11 -> one
        count % 10 in 2..4 && lastTwo !in 12..14 -> few
        else -> many
    }
}

fun counted(count: Int, one: String, few: String, many: String): String =
    "$count ${plural(count, one, few, many)}"

fun items(count: Int): String = counted(count, "stavka", "stavke", "stavki")
