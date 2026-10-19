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

    // Isto kao ShoppingListTextParser na serveru: „Kupi:" je naslov poruke,
    // „(3)" i „oko 1kg" su količina, a „za malog" je beleška uz stavku.
    private val heading = Regex("^[^0-9:]{1,40}:$")
    private val headingBeforeItems = Regex(
        "^(?:kupi(?:ti)?|spisak|lista|treba|potrebno|nabavka|market|prodavnica" +
            "|купи(?:ти)?|списак|листа|треба|потребно|набавка)\\s*:\\s*(?=\\S)",
        RegexOption.IGNORE_CASE
    )
    private val approximately = Regex(
        "(?<![\\p{L}0-9])(?:oko|otprilike|cca\\.?|около|отприлике|~)\\s*(?=[0-9])",
        RegexOption.IGNORE_CASE
    )
    private val countInBrackets = Regex(
        "\\s*\\(\\s*($DECIMAL_NUMBER)\\s*(?:kom(?:ad(?:a|i)?)?\\.?|ком(?:ад(?:а|и)?)?\\.?|pcs)?\\s*\\)",
        RegexOption.IGNORE_CASE
    )
    private const val NOTE_WORDS =
        "(?:malog|malu|male|mamu|tatu|baku|deku|dedu|decu|dete|sina|[cć]erku|[žz]enu|mu[žz]a" +
            "|goste|gosta|kom[šs]ij[ue]|prijatelje|kuma|kumu|brata|sestru|nju|njega|sebe" +
            "|utakmicu|ro[đd]j?endan|slavu|[žz]urku|proslavu|vikend|put|posao|[šs]kolu" +
            "|vrti[ćc]|doru[čc]ak|ru[čc]ak|ve[čc]eru|u[žz]inu|sutra|danas|praznik" +
            "|novu godinu|bo[žz]i[ćc]|uskrs)"
    private val note = Regex(
        "\\s+(za\\s+$NOTE_WORDS(?![\\p{L}])[^0-9()]*?)(?=\\s+[0-9]|\\s*$)",
        RegexOption.IGNORE_CASE
    )
    private val noteInBrackets = Regex("\\s*\\((\\s*za\\s+[^()]+?)\\s*\\)", RegexOption.IGNORE_CASE)

    private fun isHeading(line: String): Boolean {
        val clean = withoutEmoji(line).replace(bullet, "").trim()
        return heading.matches(clean) && word.containsMatchIn(clean)
    }

    private fun withoutHeading(line: String): String =
        headingBeforeItems.find(line)?.let { line.substring(it.range.last + 1) } ?: line

    /** Red bez beleške, i beleška („za malog") ako je ima. */
    private fun withNote(line: String): Pair<String, String?> {
        var rest = line
        var found: String? = null
        noteInBrackets.find(rest)?.let {
            found = it.groupValues[1].trim()
            rest = rest.removeRange(it.range).trim()
        }
        note.find(rest)?.takeIf { word.containsMatchIn(rest.substring(0, it.range.first)) }?.let {
            found = found ?: it.groupValues[1].trim()
            rest = rest.removeRange(it.range).trim()
        }
        return rest to found
    }

    /** Beleška iz reda kako je napisan: „čokolada za malog" → „za malog". */
    fun noteOf(rawInput: String?): String? =
        rawInput?.takeIf(String::isNotBlank)
            ?.let { withNote(withoutEmoji(it).replace(bullet, "").trim()).second }

    /** „jogurte (3)": broj u zagradi ide na kraj, kao „jogurte x3". */
    private fun countFromBrackets(line: String): String =
        countInBrackets.find(line)?.let { "${line.removeRange(it.range).trim()} x${it.groupValues[1]}" } ?: line

    // Isto pravilo kao na serveru: „Mleko, hleb, jaja" su tri stavke, a
    // „Mleko 2,8%" i „mleko, 2l" ostaju jedna jer svaki deo nema svoju reč.
    private val itemSeparator = Regex("\\s*[,;]\\s+|\\s*;\\s*")
    private val word = Regex("\\p{L}{2,}")

    private fun itemsOnLine(line: String): List<String> {
        val parts = line.split(itemSeparator)
        if (parts.size < 2) return listOf(line)
        if (parts.any { it.isNotBlank() && !word.containsMatchIn(it) }) return listOf(line)
        return parts.map(String::trim).filter(String::isNotBlank)
    }

    fun parse(text: String): List<ParsedDraftLine> = text
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .filterNot(::isHeading)
        .flatMap { itemsOnLine(withoutHeading(it)) }
        .map { rawLine ->
            val written = withoutEmoji(rawLine).replace(bullet, "").trim()
            val clean = countFromBrackets(withNote(approximately.replace(written, "")).first)
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
        .filter { it.name.isNotBlank() }
        .toList()

    // "0x hleb" stays 0, so the server refuses that row with its reason and
    // the row is marked, instead of quietly becoming one loaf.
    private fun String.toQuantity(): Double =
        parseSerbianDecimal() ?: 1.0
}
