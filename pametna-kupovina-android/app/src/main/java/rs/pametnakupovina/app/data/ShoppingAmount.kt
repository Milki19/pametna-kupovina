package rs.pametnakupovina.app.data

import java.text.Normalizer
import java.math.BigDecimal

data class ShoppingAmount(val value: Double, val unit: String)
data class RequestedShoppingAmount(val name: String, val amount: ShoppingAmount)

internal const val DECIMAL_NUMBER = "\\d+(?:[.,]\\d+)?"

/** "2,5" or "2.5" as a Double — the Serbian decimal comma either way. */
internal fun String.parseSerbianDecimal(): Double? = replace(',', '.').toDoubleOrNull()

/** Suggestions apply only to new generic entries, never to existing drafts. */
fun suggestedAmount(name: String): ShoppingAmount? {
    val normalized = Normalizer.normalize(name.trim().lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
    return when (normalized) {
        "mleko", "obicno mleko" -> ShoppingAmount(1000.0, "ml")
        "jogurt", "obicni jogurt", "obican jogurt" -> ShoppingAmount(1000.0, "g")
        "jaja" -> ShoppingAmount(10.0, "piece")
        else -> null
    }
}

fun parseShoppingAmount(input: String): RequestedShoppingAmount? {
    val suffix = Regex("^(.+?)\\s+($DECIMAL_NUMBER)\\s*(kg|g|l|ml|kom|komada)\\s*$", RegexOption.IGNORE_CASE)
    val prefix = Regex("^($DECIMAL_NUMBER)\\s*(kg|g|l|ml|kom|komada)\\s+(.+)$", RegexOption.IGNORE_CASE)
    val end = suffix.matchEntire(input.trim())
    val start = prefix.matchEntire(input.trim())
    val name = end?.groupValues?.get(1) ?: start?.groupValues?.get(3) ?: return null
    val number = end?.groupValues?.get(2) ?: start!!.groupValues[1]
    val unit = (end?.groupValues?.get(3) ?: start!!.groupValues[2]).lowercase()
    val value = number.parseSerbianDecimal() ?: return null
    val baseValue = value * if (unit in setOf("kg", "l")) 1000 else 1
    if (!baseValue.isFinite() || baseValue <= 0) return null
    return RequestedShoppingAmount(name.trim(), ShoppingAmount(baseValue, when (unit) {
        "kg", "g" -> "g"
        "l", "ml" -> "ml"
        else -> "piece"
    }))
}

/** A package of several units reads "2 × 1,5 l"; its value is the whole package. */
fun amountLabel(value: Double, unit: String?, packageCount: Int = 1): String {
    if (packageCount > 1) return "$packageCount × ${amountLabel(value / packageCount, unit)}"
    val large = unit in setOf("g", "ml") && value >= 1000
    val number = BigDecimal.valueOf(if (large) value / 1000 else value).stripTrailingZeros().toPlainString().replace('.', ',')
    val label = when {
        unit == "g" && large -> "kg"
        unit == "ml" && large -> "l"
        unit == "piece" -> "kom"
        else -> unit ?: "?"
    }
    return "$number $label"
}
