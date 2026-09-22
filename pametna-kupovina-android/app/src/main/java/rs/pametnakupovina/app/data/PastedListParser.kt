package rs.pametnakupovina.app.data

data class ParsedDraftLine(
    val name: String,
    val rawInput: String,
    val quantity: Double
)

object PastedListParser {
    private val leadingQuantity = Regex(
        "^($DECIMAL_NUMBER)\\s*[x×]\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val trailingQuantity = Regex(
        "^(.+?)\\s+[x×]\\s*($DECIMAL_NUMBER)$",
        RegexOption.IGNORE_CASE
    )
    private val bullet = Regex("^[\\s*•·▪◦-]+")

    fun parse(text: String): List<ParsedDraftLine> = text
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { rawLine ->
            val clean = rawLine.replace(bullet, "").trim()
            val leading = leadingQuantity.matchEntire(clean)
            val trailing = trailingQuantity.matchEntire(clean)

            when {
                leading != null -> ParsedDraftLine(
                    name = leading.groupValues[2].trim(),
                    rawInput = rawLine,
                    quantity = leading.groupValues[1].toQuantity()
                )

                trailing != null -> ParsedDraftLine(
                    name = trailing.groupValues[1].trim(),
                    rawInput = rawLine,
                    quantity = trailing.groupValues[2].toQuantity()
                )

                else -> ParsedDraftLine(
                    name = clean,
                    rawInput = rawLine,
                    quantity = 1.0
                )
            }
        }
        .filter { it.name.isNotBlank() && it.quantity > 0 }
        .toList()

    private fun String.toQuantity(): Double =
        parseSerbianDecimal()?.takeIf { it > 0 } ?: 1.0
}
