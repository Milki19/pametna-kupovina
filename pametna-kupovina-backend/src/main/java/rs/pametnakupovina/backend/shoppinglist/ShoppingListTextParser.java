package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ShoppingListTextParser {

    private static final int FLAGS =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final String QUANTITY =
            "(?<quantity>[0-9]+(?:[.,][0-9]+)?)";

    private static final String PIECE_UNIT =
            "(?:kom(?:ad(?:a|i)?)?\\.?|ком(?:ад(?:а|и)?)?\\.?|pcs)";

    /** "• mleko", "- hleb", "1. jaja": list marks copied from notes and chats. */
    private static final Pattern LIST_MARK = Pattern.compile(
            "^(?:[*•·▪◦‣–—-]+\\s*|[0-9]{1,2}[.)]\\s+(?=\\p{L}))"
    );

    /** How many packages: "2x mleko", "jaja 2 kom", "hleb x3". */
    private static final List<Pattern> COUNT_PATTERNS = List.of(
            Pattern.compile(
                    "^" + QUANTITY + "\\s*[x×]\\s*(?<name>.+)$", FLAGS),
            Pattern.compile(
                    "^" + QUANTITY + "\\s*" + PIECE_UNIT
                            + "\\s+(?<name>.+)$", FLAGS),
            Pattern.compile(
                    "^(?<name>.+?)\\s+[x×]\\s*" + QUANTITY + "$", FLAGS),
            Pattern.compile(
                    "^(?<name>.+?)\\s+(?:[-–—]\\s*)?" + QUANTITY
                            + "\\s+" + PIECE_UNIT + "$", FLAGS)
    );

    /**
     * Weight and volume written next to the name state how much is needed in
     * total, not how many packages: "ćevapi 3kg" is three kilograms however
     * the shop packs them. Spelled-out forms appear in real lists too
     * ("sitan sir 400grama").
     */
    private static final String AMOUNT_UNIT =
            "(?<unit>kg|kilogram(?:a)?|kil[aeou]|g|gr|grama|gram"
                    + "|l|lit(?:ar|ra|re|ara)?|ml|mililitar(?:a)?"
                    + "|кг|килограм(?:а)?|кил[аеоу]|г|гр|грама|грам"
                    + "|л|лит(?:ар|ра|ре|ара)?|мл|милилитар(?:а)?)";

    private static final List<Pattern> AMOUNT_PATTERNS = List.of(
            Pattern.compile(
                    "^(?<name>.+?)\\s*(?:[-–—]\\s*)?" + QUANTITY
                            + "\\s*" + AMOUNT_UNIT + "$", FLAGS),
            Pattern.compile(
                    "^" + QUANTITY + "\\s*" + AMOUNT_UNIT
                            + "\\s+(?<name>.+)$", FLAGS)
    );

    /** "pola kile sira": half of a kilogram or a litre, said in words. */
    private static final Pattern HALF = Pattern.compile(
            "(?<![\\p{L}0-9])(?:pola|пола)\\s+(?=(?:kil|kg|lit|кил|кг|лит))", FLAGS
    );

    /** "kilo i po", "litar i po": one and a half. */
    private static final Pattern AND_A_HALF = Pattern.compile(
            "(?<![\\p{L}0-9])(?<unit>kil[aeou]|litar|litr[ae]|кил[аеоу]|литар|литр[ае])\\s+(?:i|и)\\s+(?:po|по)(?![\\p{L}0-9])",
            FLAGS
    );

    /** "kila sira", "sir kilo", "litar mleka": one, when no number is written. */
    private static final Pattern UNIT_WITHOUT_NUMBER = Pattern.compile(
            "(?<![\\p{L}0-9]|[0-9]\\s)(?<unit>kil[aeou]|litar|litr[ae]|кил[аеоу]|литар|литр[ае])(?![\\p{L}0-9])",
            FLAGS
    );

    /**
     * "Pivo Zaječarsko 0.5", "mleko 2": a number whose meaning the kind of
     * product has to supply, a size for a decimal and a count for a whole one.
     */
    private static final Pattern BARE_NUMBER = Pattern.compile(
            "^(?<name>.*\\p{L}.*?)\\s+(?<number>[0-9]+(?:[.,][0-9]+)?)$", FLAGS
    );

    /** "10 jaja", "2 mleka": a count written first, without "x" or "kom". */
    private static final Pattern LEADING_NUMBER = Pattern.compile(
            "^(?<number>[0-9]{1,2})\\s+(?<name>\\p{L}{2,}.*)$", FLAGS
    );

    /** "Mleko, hleb, jaja": several items written on one line of a message. */
    private static final Pattern ITEM_SEPARATOR = Pattern.compile("\\s*[,;]\\s+|\\s*;\\s*");

    private static final Pattern WORD = Pattern.compile("\\p{L}{2,}");

    /** "Kupi:", "Spisak za subotu:": a heading over the list, nothing to buy. */
    private static final Pattern HEADING = Pattern.compile(
            "^[^0-9:]{1,40}:$", FLAGS
    );

    /** "Kupi: mleko, hleb": the heading written on the same line as the items. */
    private static final Pattern HEADING_BEFORE_ITEMS = Pattern.compile(
            "^(?:kupi(?:ti)?|spisak|lista|treba|potrebno|nabavka|market|prodavnica"
                    + "|купи(?:ти)?|списак|листа|треба|потребно|набавка)\\s*:\\s*(?=\\S)",
            FLAGS
    );

    /** "uzmi 2 hleba i pola kile sira": what to do with the items, not an item. */
    private static final Pattern VERB_BEFORE_ITEMS = Pattern.compile(
            "^(?:uzmi|kupi|donesi|pokupi|treba(?:ju)?\\s+(?:nam|mi)"
                    + "|узми|купи|донеси|покупи|треба(?:ју)?\\s+(?:нам|ми))\\s+(?=\\S)",
            FLAGS
    );

    /** "voda 6x1,5l", "pivo 6x0,5": a pack of six bottles of a size. */
    private static final Pattern MULTIPACK_AFTER = Pattern.compile(
            "^(?<name>.*\\p{L}.*?)\\s+(?<count>[0-9]{1,2})\\s*[x×]\\s*(?<size>[0-9]+(?:[.,][0-9]+)?\\s*"
                    + AMOUNT_UNIT.replace("(?<unit>", "(?:") + "?)$", FLAGS
    );

    /** "6x1,5l vode": the same pack written first. */
    private static final Pattern MULTIPACK_BEFORE = Pattern.compile(
            "^(?<count>[0-9]{1,2})\\s*[x×]\\s*(?<size>[0-9]+(?:[.,][0-9]+)?\\s*"
                    + AMOUNT_UNIT.replace("(?<unit>", "(?:") + ")\\s+(?<name>\\p{L}.*)$", FLAGS
    );

    /** "piletina oko 1kg", "cca 2 kom": an approximate amount is still the amount. */
    private static final Pattern APPROXIMATELY = Pattern.compile(
            "(?<![\\p{L}0-9])(?:oko|otprilike|cca\\.?|около|отприлике|~)\\s*(?=[0-9])", FLAGS
    );

    /** "jogurte (3)", "sir (500g)": the amount written in brackets. */
    private static final Pattern AMOUNT_IN_BRACKETS = Pattern.compile(
            "\\s*\\(\\s*(?<number>[0-9]+(?:[.,][0-9]+)?)\\s*(?:(?<piece>" + PIECE_UNIT + ")|(?<measure>"
                    + AMOUNT_UNIT.replace("(?<unit>", "(?:") + "))?\\s*\\)", FLAGS
    );

    /**
     * "čokolada za malog", "pivo za utakmicu 6": who or what the item is for.
     * Only these words make a note, because "prašak za veš", "hrana za pse"
     * and "kore za pitu" name what is bought. "Šampon za decu" is a kind of
     * shampoo, so "za decu" stays in the name (izveštaj 20.10.2026).
     */
    private static final String NOTE_WORDS =
            "(?:malog|malu|male|mamu|tatu|baku|deku|dedu|sina|[cć]erku|[žz]enu|mu[žz]a"
                    + "|goste|gosta|kom[šs]ij[ue]|prijatelje|kuma|kumu|brata|sestru|nju|njega|sebe"
                    + "|utakmicu|ro[đd]j?endan|slavu|[žz]urku|proslavu|vikend|put|posao|[šs]kolu"
                    + "|vrti[ćc]|doru[čc]ak|ru[čc]ak|ve[čc]eru|u[žz]inu|sutra|danas|praznik"
                    + "|novu godinu|bo[žz]i[ćc]|uskrs)";

    private static final Pattern NOTE = Pattern.compile(
            "\\s+(?<note>za\\s+" + NOTE_WORDS + "(?![\\p{L}])[^0-9()]*?)(?=\\s+[0-9]|\\s*$)", FLAGS
    );

    /** "(za malog)": anything written for whom in brackets is a note. */
    private static final Pattern NOTE_IN_BRACKETS = Pattern.compile(
            "\\s*\\((?<note>\\s*za\\s+[^()]+?)\\s*\\)", FLAGS
    );

    /**
     * "za decu", "za sudove", "za pse": what a product is made for, part of
     * its name in brackets too ("šampon (za decu)").
     */
    private static final Pattern PURPOSE = Pattern.compile(
            "^za\\s+(?:decu|dete|decicu|bebe|bebu|bebi|sudove|sud[jđ]e|posu[dđ]e|ma[sš]inu|ve[sš]"
                    + "|pse|psa|pas|ma[cč]k[eu]|ma[cč]ke|ma[cč]ku|ljubimce|kosu|lice|ruke|telo|zube"
                    + "|pranje\\b.*|[cč]i[sš][cć]enje\\b.*|kupatilo|kuhinju|pod|podove|wc|toalet)$", FLAGS
    );

    private static final Pattern TRAILING_NUMBER = Pattern.compile("\\s+[0-9]+(?:[.,][0-9]+)?$");

    private static final Pattern PURPOSE_IN_NAME = Pattern.compile(
            "\\s+za\\s+(?:decu|dete|decicu|bebe|bebu|bebi)(?![\\p{L}])", FLAGS
    );

    /** "pelene za bebu" without "za bebu": a purpose every such product has. */
    public static String withoutPurpose(String name) {
        return PURPOSE_IN_NAME.matcher(name).replaceAll("").trim();
    }

    /** "paradajz i krastavac": two things to buy on one line. */
    private static final Pattern AND = Pattern.compile("\\s+(?:i|и)\\s+", FLAGS);

    public ParsedShoppingListText parse(String text) {
        if (text == null) {
            return new ParsedShoppingListText(List.of(), 0);
        }

        List<ParsedShoppingListLine> items = new ArrayList<>();
        List<Integer> lineNumbers = new ArrayList<>();
        int blankLineCount = 0;
        int lineNumber = 0;

        for (String rawLine : text.split("\\R", -1)) {
            lineNumber++;
            if (rawLine.isBlank()) {
                blankLineCount++;
                continue;
            }
            if (isHeading(rawLine)) {
                continue;
            }

            for (String part : itemsOnLine(withoutHeading(rawLine))) {
                for (String item : bothOf(part)) {
                    items.add(parseLine(item));
                    lineNumbers.add(lineNumber);
                }
            }
        }

        return new ParsedShoppingListText(
                List.copyOf(items),
                blankLineCount,
                List.copyOf(lineNumbers)
        );
    }

    /**
     * A comma or semicolon followed by a space separates items only when every
     * part has a word of its own, so "Mleko 2,8%" and "mleko, 2l" stay whole.
     */
    private static List<String> itemsOnLine(String rawLine) {
        String[] parts = ITEM_SEPARATOR.split(rawLine.trim());
        if (parts.length < 2) {
            return List.of(rawLine);
        }
        List<String> items = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank() && !WORD.matcher(part).find()) {
                return List.of(rawLine);
            }
            if (!part.isBlank()) {
                items.add(part.trim());
            }
        }
        return items;
    }

    /**
     * "paradajz i krastavac" is two items, and "crni i beli luk" two kinds
     * of onion. Only short parts are split: "tečni sapun med i mleko" and
     * "kilo i po" are one thing.
     */
    static List<String> bothOf(String part) {
        String line = withoutMarks(part);
        Matcher and = AND.matcher(line);
        if (!and.find()) {
            return List.of(part);
        }
        String left = line.substring(0, and.start()).trim();
        String right = line.substring(and.end()).trim();
        if (and.find() || AND_A_HALF.matcher(line).find()
                || right.matches("(?iu)^(?:po|pol|по)\\b.*")
                || left.contains("(") || right.contains(")")) {
            return List.of(part);
        }
        String[] leftWords = words(left);
        String[] rightWords = words(right);
        if (leftWords.length == 0 || rightWords.length == 0
                || leftWords.length > 2 || rightWords.length > 2) {
            return List.of(part);
        }
        // "crni i beli luk": two adjectives share the noun after them.
        if (leftWords.length == 1 && rightWords.length == 2
                && left.equals(leftWords[0]) && right.startsWith(rightWords[0])
                && sameEnding(leftWords[0], rightWords[0])) {
            left = left + " " + rightWords[1];
        }
        return List.of(left, right);
    }

    /** The words of a part, numbers and units left out. */
    private static String[] words(String part) {
        return WORD.matcher(part).results()
                .map(MatchResult::group)
                .filter(word -> word.length() >= 2
                        && !word.matches("(?iu)kg|gr?|l|ml|kom|komad[a]?|x|pola|kil[aeou]|kilograma?"
                                + "|grama?|lit(?:ar|ra|re)|пола|кил[аеоу]|литар|литр[ае]"))
                .toArray(String[]::new);
    }

    private static boolean sameEnding(String first, String second) {
        String a = first.toLowerCase(Locale.ROOT);
        String b = second.toLowerCase(Locale.ROOT);
        return a.length() > 2 && b.length() > 2
                && a.charAt(a.length() - 1) == b.charAt(b.length() - 1)
                && "aeiou".indexOf(a.charAt(a.length() - 1)) >= 0;
    }

    /** "Kupi:" over a message, with an emoji or a list mark around it. */
    static boolean isHeading(String rawLine) {
        String line = withoutMarks(rawLine);
        return HEADING.matcher(line).matches() && WORD.matcher(line).find();
    }

    private static String withoutHeading(String rawLine) {
        String line = rawLine.trim();
        Matcher heading = HEADING_BEFORE_ITEMS.matcher(line);
        String items = heading.find() ? line.substring(heading.end()) : rawLine;
        String item = withoutMarks(items);
        Matcher verb = VERB_BEFORE_ITEMS.matcher(item);
        return verb.find() ? item.substring(verb.end()) : items;
    }

    private static String withoutMarks(String rawLine) {
        return LIST_MARK.matcher(ItemNames.withoutEmoji(rawLine).trim()).replaceFirst("").trim();
    }

    /**
     * The note a written line carries ("za malog"), or null. The list shows
     * it beside the item, read from the line as it was written, so nothing
     * but the line itself has to be kept.
     */
    public static String noteOf(String rawLine) {
        if (rawLine == null || rawLine.isBlank()) {
            return null;
        }
        return withNote(withoutMarks(rawLine)).note();
    }

    /** What a line asks for, and the note it carries, apart. */
    private static Noted withNote(String line) {
        String note = null;
        Matcher bracketed = NOTE_IN_BRACKETS.matcher(line);
        if (bracketed.find()) {
            String inBrackets = bracketed.group("note").trim();
            boolean purpose = PURPOSE.matcher(inBrackets).matches();
            note = purpose ? null : inBrackets;
            String before = line.substring(0, bracketed.start()).trim();
            if (purpose) {
                // "pelene 4 (za bebu)": the purpose joins the name before
                // the number, which stays the line's count or size.
                Matcher number = TRAILING_NUMBER.matcher(before);
                before = number.find()
                        ? before.substring(0, number.start()) + " " + inBrackets + number.group()
                        : before + " " + inBrackets;
            }
            line = (before + " " + line.substring(bracketed.end())).trim().replaceAll("\\s{2,}", " ");
        }
        Matcher written = NOTE.matcher(line);
        if (written.find() && hasLetter(line.substring(0, written.start()))) {
            note = note == null ? written.group("note").trim() : note;
            line = (line.substring(0, written.start()) + line.substring(written.end())).trim();
        }
        return new Noted(line, note);
    }

    private record Noted(String line, String note) {
    }

    /**
     * "jogurte (3)" is three, "sir (500g)" half a kilo: the brackets move to
     * the end of the line, where the counting and the amount patterns look.
     */
    private static String amountFromBrackets(String line) {
        Matcher bracket = AMOUNT_IN_BRACKETS.matcher(line);
        if (!bracket.find()) {
            return line;
        }
        String rest = (line.substring(0, bracket.start()) + " " + line.substring(bracket.end())).trim();
        String number = bracket.group("number");
        return bracket.group("measure") == null
                ? rest + " x" + number
                : rest + " " + number + bracket.group("measure");
    }

    /** A count and an amount can share a line: "2x ćevapi 3kg" is 2 × 3 kg. */
    public ParsedShoppingListLine parseLine(String rawLine) {
        String line = withoutMarks(rawLine);
        if (line.isEmpty()) {
            line = rawLine.trim();
        }

        line = APPROXIMATELY.matcher(line).replaceAll("");
        line = withNote(line).line();
        line = amountFromBrackets(line);
        line = amountInWords(line);
        line = multipackFirst(line);

        BigDecimal quantity = BigDecimal.ONE;
        String rest = line;

        for (Pattern pattern : COUNT_PATTERNS) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.matches() && !matcher.group("name").isBlank()) {
                quantity = number(matcher.group("quantity"));
                rest = matcher.group("name").trim();
                break;
            }
        }

        for (Pattern pattern : AMOUNT_PATTERNS) {
            Matcher matcher = pattern.matcher(rest);
            if (matcher.matches() && hasLetter(matcher.group("name"))) {
                return amountLine(matcher, rawLine, quantity, rest);
            }
        }

        Matcher bare = BARE_NUMBER.matcher(rest);
        if (!bare.matches()) {
            bare = LEADING_NUMBER.matcher(rest);
        }
        if (bare.matches()) {
            return new ParsedShoppingListLine(
                    bare.group("name").trim(),
                    rawLine,
                    quantity,
                    null,
                    null,
                    rest,
                    number(bare.group("number"))
            );
        }

        return new ParsedShoppingListLine(
                rest, rawLine, quantity, null, null, rest, null
        );
    }

    /** "voda 6x1,5l" reads as "6x voda 1,5l": six packages of 1,5 l. */
    private static String multipackFirst(String line) {
        for (Pattern pattern : List.of(MULTIPACK_AFTER, MULTIPACK_BEFORE)) {
            Matcher pack = pattern.matcher(line);
            if (pack.matches()) {
                return pack.group("count") + "x " + pack.group("name").trim() + " "
                        + pack.group("size").replaceAll("\\s+", "");
            }
        }
        return line;
    }

    /**
     * Amounts said the way people say them: "pola kile" is 0,5 kg, "kilo i
     * po" 1,5 kg and "kila sira" one kilogram. Only next to a word for a
     * kilogram or a litre, so "pola" elsewhere stays part of the name.
     */
    private static String amountInWords(String line) {
        String half = HALF.matcher(line).replaceAll("0,5 ");
        String andAHalf = AND_A_HALF.matcher(half).replaceAll("1,5 ${unit}");
        return UNIT_WITHOUT_NUMBER.matcher(andAHalf).replaceAll("1 ${unit}");
    }

    private ParsedShoppingListLine amountLine(
            Matcher matcher,
            String rawLine,
            BigDecimal quantity,
            String nameWithAmount
    ) {
        BigDecimal amount = number(matcher.group("quantity"));
        String unit = latinUnit(matcher.group("unit").toLowerCase(Locale.ROOT));

        BigDecimal inBaseUnit = unit.startsWith("k") || unit.startsWith("l")
                ? amount.multiply(BigDecimal.valueOf(1000))
                : amount;
        String baseUnit = unit.startsWith("l") || unit.startsWith("m")
                ? "ml"
                : "g";

        return new ParsedShoppingListLine(
                matcher.group("name").trim(),
                rawLine,
                quantity,
                inBaseUnit.stripTrailingZeros(),
                baseUnit,
                nameWithAmount,
                null
        );
    }

    /** "кг" reads as "kg": only the letters the units are written with. */
    private static String latinUnit(String unit) {
        StringBuilder latin = new StringBuilder(unit.length());
        for (char letter : unit.toCharArray()) {
            latin.append(switch (letter) {
                case 'к' -> 'k';
                case 'г' -> 'g';
                case 'р' -> 'r';
                case 'а' -> 'a';
                case 'м' -> 'm';
                case 'л' -> 'l';
                case 'и' -> 'i';
                case 'т' -> 't';
                case 'о' -> 'o';
                default -> letter;
            });
        }
        return latin.toString();
    }

    private static boolean hasLetter(String value) {
        return value.codePoints().anyMatch(Character::isLetter);
    }

    private static BigDecimal number(String value) {
        return new BigDecimal(value.replace(',', '.')).stripTrailingZeros();
    }
}
