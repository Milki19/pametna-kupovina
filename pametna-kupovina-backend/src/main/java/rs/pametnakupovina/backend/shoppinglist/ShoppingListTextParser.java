package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import rs.pametnakupovina.backend.market.Market;
import rs.pametnakupovina.backend.market.MarketRepository;
import rs.pametnakupovina.backend.matching.BuiltInUnitWords;
import rs.pametnakupovina.backend.matching.SerbianTextTransliterator;
import rs.pametnakupovina.backend.matching.UnitVocabulary;
import rs.pametnakupovina.backend.matching.UnitWord;
import rs.pametnakupovina.backend.product.CatalogMemo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads a pasted list. The unit words ("kg", "kom", "kesica", "rolni",
 * "3x") come from the market's dictionary, app.unit_word (V157), not from
 * expressions written here, so another market or language only adds rows.
 */
@Component
public class ShoppingListTextParser {

    private static final int FLAGS =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final String QUANTITY =
            "(?<quantity>[0-9]+(?:[.,][0-9]+)?)";

    /** "• mleko", "- hleb", "1. jaja": list marks copied from notes and chats. */
    private static final Pattern LIST_MARK = Pattern.compile(
            "^(?:[*•·▪◦‣–—-]+\\s*|[0-9]{1,2}[.)]\\s+(?=\\p{L}))"
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

    /** "piletina oko 1kg", "cca 2 kom": an approximate amount is still the amount. */
    private static final Pattern APPROXIMATELY = Pattern.compile(
            "(?<![\\p{L}0-9])(?:oko|otprilike|cca\\.?|около|отприлике|~)\\s*(?=[0-9])", FLAGS
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

    private static final Pattern LETTERS = Pattern.compile("\\p{L}+");

    private final Supplier<List<UnitWord>> unitWords;
    private volatile Units units;

    /** The Serbian words of V157, for a parser made without the database. */
    public ShoppingListTextParser() {
        this(() -> BuiltInUnitWords.SERBIAN);
    }

    /** The default market's words, remembered until the catalogue changes. */
    @Autowired
    public ShoppingListTextParser(UnitVocabulary vocabulary, MarketRepository markets, CatalogMemo memo) {
        this(() -> {
            Market market = memo.remember(List.of("defaultMarket"), markets::defaultMarket);
            List<UnitWord> words = vocabulary.words(market);
            // A database the migration has not reached yet still reads "2 kg".
            return words.isEmpty() ? BuiltInUnitWords.SERBIAN : words;
        });
    }

    ShoppingListTextParser(Supplier<List<UnitWord>> unitWords) {
        this.unitWords = unitWords;
    }

    private Units units() {
        List<UnitWord> words = unitWords.get();
        Units current = units;
        if (current == null || current.words != words) {
            current = new Units(words);
            units = current;
        }
        return current;
    }

    /**
     * The expressions built from one dictionary. A unit next to a number is
     * first written as the dictionary has it ("Flaše" → "flase", "кг" →
     * "kg"), so the expressions only need the dictionary's own spelling.
     */
    private static final class Units {

        private final List<UnitWord> words;
        private final Map<String, UnitWord> byWord = new HashMap<>();

        /** How many packages: "2x mleko", "jaja 2 kom", "hleb x3", "grcki jogurt 3x". */
        private final List<Pattern> countPatterns;
        /** The same, said with a word for a pack: "5 kesica kvasca", "4 kutije jaja". */
        private final List<Pattern> packPatterns;
        /** "toalet papir 10 rolni": one pack, ten rolls in it. */
        private final List<Pattern> insidePatterns;
        /**
         * Weight and volume written next to the name state how much is needed
         * in total, not how many packages: "ćevapi 3kg" is three kilograms
         * however the shop packs them. Spelled-out forms appear in real lists
         * too ("sitan sir 400grama").
         */
        private final List<Pattern> amountPatterns;
        /** "voda 6x1,5l", "pivo 6x0,5": a pack of six bottles of a size. */
        private final Pattern multipackAfter;
        /** "6x1,5l vode": the same pack written first. */
        private final Pattern multipackBefore;
        /** "jogurte (3)", "sir (500g)", "kvasac (2 kesice)": the amount written in brackets. */
        private final Pattern amountInBrackets;

        Units(List<UnitWord> words) {
            this.words = words;
            for (UnitWord word : words) {
                byWord.putIfAbsent(word.word(), word);
            }
            String measure = alternation(word -> word.role() == UnitWord.Role.MEASURE);
            String pieces = alternation(word -> word.role() == UnitWord.Role.COUNT && !word.insidePack());
            String packs = alternation(word -> word.role() == UnitWord.Role.PACK);
            String inside = alternation(word -> word.role() == UnitWord.Role.COUNT && word.insidePack());
            String times = alternation(word -> word.role() == UnitWord.Role.MULTIPLIER);
            String counted = "(?:" + pieces + ")(?!\\p{L})\\.?";
            String packed = "(?:" + packs + ")(?!\\p{L})\\.?";
            String countedOrPacked = "(?:" + pieces + "|" + packs + ")(?!\\p{L})\\.?";
            String multiplied = "(?:" + times + ")(?!\\p{L})";
            String amount = "(?<unit>" + measure + ")(?!\\p{L})\\.?";
            String size = "(?:" + measure + ")";

            countPatterns = List.of(
                    Pattern.compile("^" + QUANTITY + "\\s*" + multiplied + "\\s*(?<name>.+)$", FLAGS),
                    Pattern.compile("^" + QUANTITY + "\\s*" + counted + "\\s+(?<name>.+)$", FLAGS),
                    Pattern.compile("^(?<name>.+?)\\s+" + multiplied + "\\s*" + QUANTITY + "$", FLAGS),
                    Pattern.compile("^(?<name>.+?)\\s+(?:[-–—]\\s*)?" + QUANTITY + "\\s*" + counted + "$", FLAGS),
                    Pattern.compile("^(?<name>.+?)\\s+(?:[-–—]\\s*)?" + QUANTITY + "\\s*" + multiplied + "$", FLAGS)
            );
            packPatterns = packs.isEmpty() ? List.of() : List.of(
                    Pattern.compile("^" + QUANTITY + "\\s*" + packed + "\\s+(?<name>.+)$", FLAGS),
                    Pattern.compile("^(?<name>.+?)\\s+(?:[-–—]\\s*)?" + QUANTITY + "\\s*" + packed + "$", FLAGS)
            );
            insidePatterns = inside.isEmpty() ? List.of() : List.of(
                    Pattern.compile("^(?<name>.+?)\\s+(?:[-–—]\\s*)?" + QUANTITY
                            + "\\s*(?:" + inside + ")(?!\\p{L})$", FLAGS),
                    Pattern.compile("^" + QUANTITY + "\\s*(?:" + inside + ")(?!\\p{L})\\s+(?<name>.+)$", FLAGS)
            );
            amountPatterns = List.of(
                    Pattern.compile("^(?<name>.+?)\\s*(?:[-–—]\\s*)?" + QUANTITY + "\\s*" + amount + "$", FLAGS),
                    Pattern.compile("^" + QUANTITY + "\\s*" + amount + "\\s+(?<name>.+)$", FLAGS)
            );
            multipackAfter = Pattern.compile(
                    "^(?<name>.*\\p{L}.*?)\\s+(?<count>[0-9]{1,2})\\s*x\\s*(?<size>[0-9]+(?:[.,][0-9]+)?\\s*"
                            + size + "?)$", FLAGS);
            multipackBefore = Pattern.compile(
                    "^(?<count>[0-9]{1,2})\\s*x\\s*(?<size>[0-9]+(?:[.,][0-9]+)?\\s*"
                            + size + ")\\s+(?<name>\\p{L}.*)$", FLAGS);
            amountInBrackets = Pattern.compile(
                    "\\s*\\(\\s*(?<number>[0-9]+(?:[.,][0-9]+)?)\\s*(?:(?<piece>" + countedOrPacked + ")|(?<measure>"
                            + size + "(?!\\p{L})\\.?))?\\s*\\)", FLAGS);
        }

        /** The words of a role, longest first, so "kilograma" is tried before "kg" and "g". */
        private String alternation(Predicate<UnitWord> role) {
            return words.stream()
                    .filter(role)
                    .map(UnitWord::word)
                    .distinct()
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .map(Pattern::quote)
                    .collect(Collectors.joining("|"));
        }

        boolean isUnit(String word) {
            return byWord.containsKey(SerbianTextTransliterator.toLatinAsciiLowercase(word));
        }

        UnitWord unit(String written) {
            String word = SerbianTextTransliterator.toLatinAsciiLowercase(written).replace(".", "");
            UnitWord unit = byWord.get(word);
            if (unit == null) {
                throw new IllegalStateException("Jedinica nije u rečniku: " + written);
            }
            return unit;
        }

        /**
         * "2 Flaše", "1,5 кг", "3X": a unit right after a number, or a
         * multiplier right before one ("x3"), written as the dictionary has
         * it. Other words stay as written: "6 jaja L" keeps its size letter.
         */
        String spelled(String line) {
            String text = line.replace('×', 'x');
            Matcher letters = LETTERS.matcher(text);
            StringBuilder out = new StringBuilder(text.length());
            int last = 0;
            while (letters.find()) {
                String word = letters.group();
                UnitWord unit = byWord.get(SerbianTextTransliterator.toLatinAsciiLowercase(word));
                boolean afterNumber = digitBefore(text, letters.start());
                boolean beforeNumber = unit != null && unit.role() == UnitWord.Role.MULTIPLIER
                        && digitAfter(text, letters.end());
                out.append(text, last, letters.start());
                out.append(unit != null && (afterNumber || beforeNumber) ? unit.word() : word);
                last = letters.end();
            }
            return out.append(text.substring(last)).toString();
        }

        private static boolean digitBefore(String text, int index) {
            int at = index - 1;
            while (at >= 0 && text.charAt(at) == ' ') {
                at--;
            }
            return at >= 0 && Character.isDigit(text.charAt(at));
        }

        private static boolean digitAfter(String text, int index) {
            int at = index;
            while (at < text.length() && text.charAt(at) == ' ') {
                at++;
            }
            return at < text.length() && Character.isDigit(text.charAt(at));
        }
    }

    public ParsedShoppingListText parse(String text) {
        if (text == null) {
            return new ParsedShoppingListText(List.of(), 0);
        }

        Units units = units();
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
                for (String item : bothOf(part, units::isUnit)) {
                    items.add(parseLine(item, units));
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
        return bothOf(part, word -> BuiltInUnitWords.SERBIAN.stream()
                .anyMatch(unit -> unit.word().equals(SerbianTextTransliterator.toLatinAsciiLowercase(word))));
    }

    private static List<String> bothOf(String part, Predicate<String> isUnit) {
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
        String[] leftWords = words(left, isUnit);
        String[] rightWords = words(right, isUnit);
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
    private static String[] words(String part, Predicate<String> isUnit) {
        return WORD.matcher(part).results()
                .map(MatchResult::group)
                .filter(word -> word.length() >= 2
                        && !isUnit.test(word)
                        && !word.matches("(?iu)pola|пола"))
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
    private static String amountFromBrackets(String line, Units units) {
        Matcher bracket = units.amountInBrackets.matcher(line);
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
        return parseLine(rawLine, units());
    }

    private ParsedShoppingListLine parseLine(String rawLine, Units units) {
        String line = withoutMarks(rawLine);
        if (line.isEmpty()) {
            line = rawLine.trim();
        }

        line = APPROXIMATELY.matcher(line).replaceAll("");
        line = withNote(line).line();
        line = units.spelled(line);
        line = amountFromBrackets(line, units);
        line = units.spelled(amountInWords(line));
        line = multipackFirst(line, units);

        BigDecimal quantity = BigDecimal.ONE;
        String rest = line;
        boolean packsNamed = false;

        counting:
        for (List<Pattern> patterns : List.of(units.countPatterns, units.packPatterns)) {
            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(line);
                if (matcher.matches() && !matcher.group("name").isBlank()) {
                    quantity = number(matcher.group("quantity"));
                    rest = matcher.group("name").trim();
                    packsNamed = patterns == units.packPatterns;
                    break counting;
                }
            }
        }

        // "toalet papir 10 rolni" is one pack of ten rolls: the words stay in
        // the name, which looks for a pack of ten ("10/1"), and the count
        // stays the packs written before them ("2x toalet papir 10 rolni").
        for (Pattern pattern : units.insidePatterns) {
            Matcher matcher = pattern.matcher(rest);
            if (matcher.matches() && hasLetter(matcher.group("name"))) {
                return new ParsedShoppingListLine(
                        rest, rawLine, quantity, null, null, rest, null,
                        number(matcher.group("quantity")), packsNamed);
            }
        }

        for (Pattern pattern : units.amountPatterns) {
            Matcher matcher = pattern.matcher(rest);
            if (matcher.matches() && hasLetter(matcher.group("name"))) {
                return amountLine(matcher, rawLine, quantity, rest, units, packsNamed);
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
                    number(bare.group("number")),
                    null,
                    packsNamed
            );
        }

        return new ParsedShoppingListLine(
                rest, rawLine, quantity, null, null, rest, null, null, packsNamed
        );
    }

    /** "voda 6x1,5l" reads as "6x voda 1,5l": six packages of 1,5 l. */
    private static String multipackFirst(String line, Units units) {
        for (Pattern pattern : List.of(units.multipackAfter, units.multipackBefore)) {
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
            String nameWithAmount,
            Units units,
            boolean packsNamed
    ) {
        BigDecimal amount = number(matcher.group("quantity"));
        UnitWord unit = units.unit(matcher.group("unit"));

        return new ParsedShoppingListLine(
                matcher.group("name").trim(),
                rawLine,
                quantity,
                unit.inBaseUnit(amount).stripTrailingZeros(),
                unit.baseUnit().databaseValue(),
                nameWithAmount,
                null,
                null,
                packsNamed
        );
    }

    private static boolean hasLetter(String value) {
        return value.codePoints().anyMatch(Character::isLetter);
    }

    private static BigDecimal number(String value) {
        return new BigDecimal(value.replace(',', '.')).stripTrailingZeros();
    }
}
