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

/**
 * Predlog vrste za „Može i drugi brend": „PERUTNINA PTUJ pileća prsa 250 g"
 * → „pileća prsa". Katalog piše brend velikim slovima na početku naziva, pa
 * se skida i kad brend ne znamo; kratke oznake („pp", „tb") se preskaču, a
 * brojevi i procenti prekidaju naziv. Imena su neujednačena, pa kupac
 * predlog vidi i može da ga ispravi pre čuvanja.
 */
fun similarKind(name: String, brand: String?): String {
    val words = (parseShoppingAmount(name)?.name ?: name)
        .split(Regex("[\\s,;]+"))
        .filter(String::isNotBlank)
    val brandWords = brand.orEmpty().lowercase().split(Regex("[^\\p{L}]+")).filter { it.length > 1 }.toSet()
    fun isWord(word: String) = word.length > 2 && word.all(Char::isLetter)
    val kind = words
        .dropWhile { word ->
            word.lowercase() in brandWords || !isWord(word) ||
                word.none(Char::isLowerCase)
        }
        .takeWhile { word -> word.all(Char::isLetter) }
        .filter { word -> isWord(word) && word.lowercase() !in brandWords }
        .take(2)
        .ifEmpty { words.filter(::isWord).take(2) }
    return kind.joinToString(" ").lowercase().ifBlank { name.trim().lowercase() }
}

/** Stavka „bilo koji brend": data vrsta i količina iz naziva proizvoda. */
fun similarItem(
    name: String,
    packages: Double,
    rawInput: String? = null,
    kind: String = similarKind(name, null)
): DraftItemInput {
    val amount = parseShoppingAmount(name)?.amount
    val category = kind.trim().ifBlank { similarKind(name, null) }
    return DraftItemInput(
        name = category,
        rawInput = rawInput,
        quantity = packages,
        matchingRule = rs.pametnakupovina.app.data.network.ShoppingItemRuleDto.FLEXIBLE_CATEGORY,
        category = category,
        targetQuantity = amount?.value,
        requiredBaseUnit = amount?.unit
    )
}
