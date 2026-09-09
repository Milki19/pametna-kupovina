package rs.pametnakupovina.backend.matching;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ProductQuantityParser {

    private static final String NUMBER_PATTERN =
            "(?:[1-9][0-9]{0,2}(?:\\.[0-9]{3})+"
                    + "|[0-9]+(?:[.,][0-9]+)?)";

    private static final String UNIT_PATTERN =
            "(?:kilograma?|kg|grama?|gr|g|"
                    + "mililit(?:ar|ra|ara)?|ml|"
                    + "litar(?:a)?|litr(?:a|e)?|l|"
                    + "kom(?:ad(?:a|i)?)?|pcs)";

    private static final Pattern MULTIPACK_PATTERN = Pattern.compile(
            "(?<![-a-z0-9])"
                    + "(?<count>[0-9]+)\\s*x\\s*"
                    + "(?<amount>" + NUMBER_PATTERN + ")\\s*"
                    + "(?<unit>" + UNIT_PATTERN + ")"
                    + "(?![a-z])"
    );

    private static final Pattern SINGLE_QUANTITY_PATTERN = Pattern.compile(
            "(?<![-a-z0-9])"
                    + "(?<amount>" + NUMBER_PATTERN + ")\\s*"
                    + "(?<unit>" + UNIT_PATTERN + ")"
                    + "(?![a-z])"
    );

    private static final Pattern BONUS_PATTERN = Pattern.compile(
            "(?<![-a-z0-9])(?<first>" + NUMBER_PATTERN + ")\\s*(?<unit1>" + UNIT_PATTERN
            + ")?\\s*\\+\\s*(?<second>" + NUMBER_PATTERN + ")\\s*(?<unit2>" + UNIT_PATTERN + ")(?![a-z])");
    private static final Pattern EGG_COUNT = Pattern.compile("\\b(?:jaja|jaje)\\b.*?\\b([1-9][0-9]*)\\s*/\\s*1\\b");

    private static final BigDecimal ONE_THOUSAND =
            BigDecimal.valueOf(1000);

    public Optional<ParsedQuantity> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        String normalizedValue =
                SerbianTextTransliterator.toLatinAsciiLowercase(value)
                        .replace('×', 'x')
                        .replace('\u00A0', ' ')
                        .replaceAll("\\s+", " ");

        if (normalizedValue.chars().filter(c -> c == '+').count() > 1
                || (normalizedValue.contains("+") && MULTIPACK_PATTERN.matcher(normalizedValue).find())) {
            return Optional.empty();
        }
        Matcher bonus = BONUS_PATTERN.matcher(normalizedValue);
        if (bonus.find()) {
            // Mixed dimensions and an ambiguous multipack+bonus are deliberately unknown.
            if (MULTIPACK_PATTERN.matcher(normalizedValue).find()) return Optional.empty();
            String firstUnit = bonus.group("unit1") == null ? bonus.group("unit2") : bonus.group("unit1");
            var first = createQuantity(bonus.group("first"), firstUnit, BigDecimal.ONE);
            var second = createQuantity(bonus.group("second"), bonus.group("unit2"), BigDecimal.ONE);
            if (first.isEmpty() || second.isEmpty() || first.get().unit() != second.get().unit()) {
                return Optional.empty();
            }
            return Optional.of(new ParsedQuantity(first.get().value().add(second.get().value()),
                    first.get().unit()));
        }

        Matcher eggCount = EGG_COUNT.matcher(normalizedValue);
        if (eggCount.find()) return createQuantity(eggCount.group(1), "kom", BigDecimal.ONE);

        Matcher multipackMatcher =
                MULTIPACK_PATTERN.matcher(normalizedValue);

        if (multipackMatcher.find()) {
            BigDecimal packageCount = new BigDecimal(
                    multipackMatcher.group("count")
            );

            return createQuantity(
                    multipackMatcher.group("amount"),
                    multipackMatcher.group("unit"),
                    packageCount
            );
        }

        Matcher singleQuantityMatcher =
                SINGLE_QUANTITY_PATTERN.matcher(normalizedValue);

        if (!singleQuantityMatcher.find()) {
            return Optional.empty();
        }

        return createQuantity(
                singleQuantityMatcher.group("amount"),
                singleQuantityMatcher.group("unit"),
                BigDecimal.ONE
        );
    }

    private Optional<ParsedQuantity> createQuantity(
            String rawAmount,
            String rawUnit,
            BigDecimal packageCount
    ) {
        UnitConversion unitConversion = resolveUnit(rawUnit);
        BigDecimal amount = parseAmount(
                rawAmount,
                unitConversion.smallBaseUnit()
        );

        BigDecimal normalizedAmount = amount
                .multiply(unitConversion.factor())
                .multiply(packageCount);

        if (normalizedAmount.signum() <= 0) {
            return Optional.empty();
        }

        return Optional.of(
                new ParsedQuantity(
                        normalizedAmount.stripTrailingZeros(),
                        unitConversion.baseUnit()
                )
        );
    }

    private BigDecimal parseAmount(
            String rawAmount,
            boolean smallBaseUnit
    ) {
        String normalizedAmount;

        boolean dotIsThousandsSeparator =
                smallBaseUnit
                        && rawAmount.matches(
                        "[1-9][0-9]{0,2}(?:\\.[0-9]{3})+"
                );

        if (dotIsThousandsSeparator) {
            normalizedAmount = rawAmount.replace(".", "");
        } else {
            normalizedAmount = rawAmount.replace(',', '.');
        }

        return new BigDecimal(normalizedAmount);
    }

    private UnitConversion resolveUnit(String rawUnit) {
        if (rawUnit.equals("kg")
                || rawUnit.startsWith("kilogram")) {
            return new UnitConversion(
                    BaseUnit.GRAM,
                    ONE_THOUSAND,
                    false
            );
        }

        if (rawUnit.equals("g")
                || rawUnit.equals("gr")
                || rawUnit.startsWith("gram")) {
            return new UnitConversion(
                    BaseUnit.GRAM,
                    BigDecimal.ONE,
                    true
            );
        }

        if (rawUnit.equals("ml")
                || rawUnit.startsWith("mililit")) {
            return new UnitConversion(
                    BaseUnit.MILLILITER,
                    BigDecimal.ONE,
                    true
            );
        }

        if (rawUnit.equals("l")
                || rawUnit.startsWith("litar")
                || rawUnit.startsWith("litr")) {
            return new UnitConversion(
                    BaseUnit.MILLILITER,
                    ONE_THOUSAND,
                    false
            );
        }

        return new UnitConversion(
                BaseUnit.PIECE,
                BigDecimal.ONE,
                true
        );
    }

    private record UnitConversion(
            BaseUnit baseUnit,
            BigDecimal factor,
            boolean smallBaseUnit
    ) {
    }
}
