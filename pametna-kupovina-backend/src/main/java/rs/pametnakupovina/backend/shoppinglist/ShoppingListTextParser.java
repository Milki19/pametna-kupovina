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
            "(?:kom(?:ad(?:a|i)?)?\\.?|pcs)";

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
                    + "|l|lit(?:ar|ra|ara)?|ml|mililitar(?:a)?)";

    private static final List<Pattern> AMOUNT_PATTERNS = List.of(
            Pattern.compile(
                    "^(?<name>.+?)\\s*(?:[-–—]\\s*)?" + QUANTITY
                            + "\\s*" + AMOUNT_UNIT + "$", FLAGS),
            Pattern.compile(
                    "^" + QUANTITY + "\\s*" + AMOUNT_UNIT
                            + "\\s+(?<name>.+)$", FLAGS)
    );

    /** "Pivo Zaječarsko 0.5": a size whose unit the reader has to supply. */
    private static final Pattern BARE_DECIMAL = Pattern.compile(
            "^(?<name>.*\\p{L}.*?)\\s+(?<number>[0-9]+[.,][0-9]+)$", FLAGS
    );

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

            items.add(parseLine(rawLine));
        }

        return new ParsedShoppingListText(
                List.copyOf(items),
                blankLineCount
        );
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

        Matcher bare = BARE_DECIMAL.matcher(rest);
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
        String unit = matcher.group("unit").toLowerCase(Locale.ROOT);

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

    private static boolean hasLetter(String value) {
        return value.codePoints().anyMatch(Character::isLetter);
    }

    private static BigDecimal number(String value) {
        return new BigDecimal(value.replace(',', '.')).stripTrailingZeros();
    }
}
