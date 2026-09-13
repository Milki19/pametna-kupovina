package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ShoppingListTextParser {

    private static final String QUANTITY =
            "(?<quantity>[0-9]+(?:[.,][0-9]+)?)";

    private static final String PIECE_UNIT =
            "(?:kom(?:ad(?:a|i)?)?\\.?|pcs)";

    private static final Pattern PREFIX_X_PATTERN = Pattern.compile(
            "^" + QUANTITY + "\\s*[x×]\\s*(?<name>.+)$",
            Pattern.CASE_INSENSITIVE
                    | Pattern.UNICODE_CASE
    );

    private static final Pattern PREFIX_PIECE_PATTERN =
            Pattern.compile(
                    "^" + QUANTITY + "\\s*" + PIECE_UNIT
                            + "\\s+(?<name>.+)$",
                    Pattern.CASE_INSENSITIVE
                            | Pattern.UNICODE_CASE
            );

    private static final Pattern SUFFIX_X_PATTERN = Pattern.compile(
            "^(?<name>.+?)\\s+[x×]\\s*" + QUANTITY + "$",
            Pattern.CASE_INSENSITIVE
                    | Pattern.UNICODE_CASE
    );

    private static final Pattern SUFFIX_PIECE_PATTERN =
            Pattern.compile(
                    "^(?<name>.+?)\\s+(?:[-–—]\\s*)?"
                            + QUANTITY + "\\s+" + PIECE_UNIT + "$",
                    Pattern.CASE_INSENSITIVE
                            | Pattern.UNICODE_CASE
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

    private static final Pattern SUFFIX_AMOUNT_PATTERN = Pattern.compile(
            "^(?<name>.+?)\\s*(?:[-–—]\\s*)?" + QUANTITY
                    + "\\s*" + AMOUNT_UNIT + "$",
            Pattern.CASE_INSENSITIVE
                    | Pattern.UNICODE_CASE
    );

    private static final Pattern PREFIX_AMOUNT_PATTERN = Pattern.compile(
            "^" + QUANTITY + "\\s*" + AMOUNT_UNIT
                    + "\\s+(?<name>.+)$",
            Pattern.CASE_INSENSITIVE
                    | Pattern.UNICODE_CASE
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

    private ParsedShoppingListLine parseLine(String rawLine) {
        String trimmedLine = rawLine.trim();

        ParsedShoppingListLine parsed = match(
                PREFIX_X_PATTERN,
                trimmedLine,
                rawLine
        );

        if (parsed != null) {
            return parsed;
        }

        parsed = match(
                PREFIX_PIECE_PATTERN,
                trimmedLine,
                rawLine
        );

        if (parsed != null) {
            return parsed;
        }

        parsed = match(
                SUFFIX_X_PATTERN,
                trimmedLine,
                rawLine
        );

        if (parsed != null) {
            return parsed;
        }

        parsed = match(
                SUFFIX_PIECE_PATTERN,
                trimmedLine,
                rawLine
        );

        if (parsed != null) {
            return parsed;
        }

        parsed = matchAmount(
                SUFFIX_AMOUNT_PATTERN,
                trimmedLine,
                rawLine
        );

        if (parsed != null) {
            return parsed;
        }

        parsed = matchAmount(
                PREFIX_AMOUNT_PATTERN,
                trimmedLine,
                rawLine
        );

        if (parsed != null) {
            return parsed;
        }

        return new ParsedShoppingListLine(
                trimmedLine,
                rawLine,
                BigDecimal.ONE
        );
    }

    private ParsedShoppingListLine matchAmount(
            Pattern pattern,
            String trimmedLine,
            String rawLine
    ) {
        Matcher matcher = pattern.matcher(trimmedLine);

        if (!matcher.matches()) {
            return null;
        }

        String name = matcher.group("name").trim();

        if (name.isEmpty()) {
            return null;
        }

        BigDecimal amount = new BigDecimal(
                matcher.group("quantity").replace(',', '.')
        );
        String unit = matcher.group("unit")
                .toLowerCase(java.util.Locale.ROOT);

        BigDecimal inBaseUnit = switch (unit.charAt(0)) {
            case 'k' -> amount.multiply(BigDecimal.valueOf(1000));
            case 'l' -> amount.multiply(BigDecimal.valueOf(1000));
            case 'm' -> amount;
            default -> amount;
        };
        String baseUnit = unit.startsWith("l")
                || unit.startsWith("m")
                ? "ml"
                : "g";

        return new ParsedShoppingListLine(
                name,
                rawLine,
                BigDecimal.ONE,
                inBaseUnit.stripTrailingZeros(),
                baseUnit
        );
    }

    private ParsedShoppingListLine match(
            Pattern pattern,
            String trimmedLine,
            String rawLine
    ) {
        Matcher matcher = pattern.matcher(trimmedLine);

        if (!matcher.matches()) {
            return null;
        }

        BigDecimal quantity = new BigDecimal(
                matcher.group("quantity").replace(',', '.')
        ).stripTrailingZeros();

        return new ParsedShoppingListLine(
                matcher.group("name").trim(),
                rawLine,
                quantity
        );
    }
}
