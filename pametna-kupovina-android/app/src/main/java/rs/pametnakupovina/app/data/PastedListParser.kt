package rs.pametnakupovina.app.data

data class ParsedDraftLine(
    val name: String,
    val rawInput: String,
    val quantity: Double
)

/**
 * Jedan red iz polja „Nalepi spisak" kako će biti pročitan, za pregled pre
 * dodavanja: stavka sa količinom i beleškom, ili red koji se ne dodaje.
 */
sealed interface PastePreviewLine {
    /** Red (ili deo reda) kako je napisan. */
    val text: String

    data class Item(val line: ParsedDraftLine, val note: String?) : PastePreviewLine {
        override val text: String get() = line.rawInput
    }

    data class Skipped(override val text: String, val reason: SkipReason) : PastePreviewLine
}

enum class SkipReason {
    /** „Kupi:", „Spisak za vikend:" — naslov poruke, ne stavka. */
    HEADING,

    /** „%%%###" — server ne prima red bez ijednog slova. */
    NO_LETTERS
}

object PastedListParser {
    private val leadingQuantity = Regex(
        "^($DECIMAL_NUMBER)\\s*[x×]\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val trailingQuantity = Regex(
        "^(.+?)\\s+[x×]\\s*($DECIMAL_NUMBER)$",
        RegexOption.IGNORE_CASE
    )

    // "grcki jogurt 3x", "jogurt 3 puta": broj pakovanja posle naziva, kao
    // rečnik jedinica na serveru (V157).
    private val trailingTimes = Regex(
        "^(.+?)\\s+($DECIMAL_NUMBER)\\s*(?:[x×]|puta)$",
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

    private val letter = Regex("\\p{L}")

    fun parse(text: String): List<ParsedDraftLine> =
        preview(text).filterIsInstance<PastePreviewLine.Item>().map { it.line }

    /**
     * Svaki red onako kako će biti dodat, istim pravilima kao [parse]: naslov
     * („Kupi:") i red bez slova su preskočeni, a ostalo je stavka.
     */
    fun preview(text: String): List<PastePreviewLine> = text
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .flatMap { line ->
            if (isHeading(line)) {
                return@flatMap sequenceOf(PastePreviewLine.Skipped(line, SkipReason.HEADING))
            }
            val rest = withoutHeading(line)
            val heading = line.substring(0, line.length - rest.length).trim()
                .takeIf(String::isNotEmpty)
                ?.let { PastePreviewLine.Skipped(it, SkipReason.HEADING) }
            listOfNotNull(heading).asSequence() + itemsOnLine(rest).asSequence().mapNotNull(::readLine)
        }
        .toList()

    private fun readLine(rawLine: String): PastePreviewLine? {
        val written = withoutEmoji(rawLine).replace(bullet, "").trim()
        val (withoutNote, note) = withNote(approximately.replace(written, ""))
        val clean = countFromBrackets(withoutNote)
        val leading = leadingQuantity.matchEntire(clean)
        val trailing = trailingQuantity.matchEntire(clean) ?: trailingTimes.matchEntire(clean)

        val line = when {
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
        return when {
            line.name.isBlank() -> null
            !letter.containsMatchIn(line.name) -> PastePreviewLine.Skipped(rawLine, SkipReason.NO_LETTERS)
            else -> PastePreviewLine.Item(line, note)
        }
    }

    // "0x hleb" stays 0, so the server refuses that row with its reason and
    // the row is marked, instead of quietly becoming one loaf.
    private fun String.toQuantity(): Double =
        parseSerbianDecimal() ?: 1.0
}
