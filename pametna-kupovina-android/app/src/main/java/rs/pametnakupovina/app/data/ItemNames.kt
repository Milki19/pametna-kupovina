package rs.pametnakupovina.app.data

// Isti opsezi kao na serveru (ItemNames.java): emodži i znaci prepisani iz
// beleški („🍅 paradajz", „✔ hleb") nisu deo naziva.
private val emoji = Regex(
    "[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{2300}-\\x{23FF}" +
        "\\x{FE0E}\\x{FE0F}\\x{200D}\\x{20E3}\\x{E0020}-\\x{E007F}]+"
)
private val spaces = Regex("\\s{2,}")

fun withoutEmoji(text: String): String {
    val stripped = text.replace(emoji, " ")
    return if (stripped == text) text else stripped.replace(spaces, " ").trim()
}
