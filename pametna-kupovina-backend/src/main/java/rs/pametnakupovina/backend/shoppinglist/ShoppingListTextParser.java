package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
            "(?<unit>kg|kilogram(?:a)?|g|gr|grama|gram"
                    + "|l|lit(?:ar|ra|ara)?|ml|mililitar(?:a)?"
                    + "|кг|килограм(?:а)?|г|гр|грама|грам"
                    + "|л|лит(?:ар|ра|ара)?|мл|милилитар(?:а)?)";

    private static final List<Pattern> AMOUNT_PATTERNS = List.of(
            Pattern.compile(
                    "^(?<name>.+?)\\s*(?:[-–—]\\s*)?" + QUANTITY
                            + "\\s*" + AMOUNT_UNIT + "$", FLAGS),
            Pattern.compile(
                    "^" + QUANTITY + "\\s*" + AMOUNT_UNIT
                            + "\\s+(?<name>.+)$", FLAGS)
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

    public ParsedShoppingListText parse(String text) {
        if (text == null) {
            return new ParsedShoppingListText(List.of(), 0);
        }

        List<ParsedShoppingListLine> items = new ArrayList<>();
        int blankLineCount = 0;

        for (String rawLine : text.split("\\R", -1)) {
            if (rawLine.isBlank()) {
                blankLineCount++;
                continue;
            }

            for (String part : itemsOnLine(rawLine)) {
                items.add(parseLine(part));
            }
        }

        return new ParsedShoppingListText(
                List.copyOf(items),
                blankLineCount
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

    /** A count and an amount can share a line: "2x ćevapi 3kg" is 2 × 3 kg. */
    public ParsedShoppingListLine parseLine(String rawLine) {
        String line = LIST_MARK.matcher(rawLine.trim()).replaceFirst("").trim();
        if (line.isEmpty()) {
            line = rawLine.trim();
        }

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
