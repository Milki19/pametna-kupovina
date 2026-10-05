package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.stereotype.Component;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Reads one written line the way a shopper means it. A kind of product, alone
 * or with a brand and an amount ("Pivo Zaječarsko 0.5"), is a choice among
 * offers. Anything else names one product and keeps its size for the search
 * ("Plantaže smederevka belo vino 1l"). The phone sends pasted lines here too,
 * so a line reads the same wherever it was typed.
 */
@Component
public class ShoppingLineInterpreter {

    private static final BigDecimal ONE_THOUSAND = BigDecimal.valueOf(1000);

    /** A bare whole number up to this counts packages; a larger one is a size. */
    private static final BigDecimal MAX_BARE_COUNT = BigDecimal.valueOf(30);

    /** "jaja 10" means ten eggs, not ten cartons; "2x jaja" stays two cartons. */
    private static final BigDecimal MIN_COUNT_OF_PIECES = BigDecimal.valueOf(4);

    private final ShoppingListTextParser parser;
    private final ShoppingIntentResolver intentResolver;
    private final ProductNameNormalizer normalizer;

    public ShoppingLineInterpreter(
            ShoppingListTextParser parser,
            ShoppingIntentResolver intentResolver,
            ProductNameNormalizer normalizer
    ) {
        this.parser = parser;
        this.intentResolver = intentResolver;
        this.normalizer = normalizer;
    }

    public InterpretedLine interpret(String rawLine) {
        return interpret(parser.parseLine(rawLine));
    }

    public InterpretedLine interpret(ParsedShoppingListLine line) {
        // A number can be part of the kind itself: "3 u 1" is instant coffee.
        if (line.bareNumber() != null) {
            var whole = intentResolver.resolve(line.nameWithAmount())
                    .filter(ShoppingIntentResolver.ResolvedShoppingIntent::exactAlias);
            if (whole.isPresent()) {
                return choice(line.nameWithAmount(), line.nameWithAmount(), whole.get(),
                        null, line.quantity(), null, null);
            }
        }

        String name = line.name();
        ShoppingIntentResolver.ResolvedShoppingIntent intent =
                intentResolver.resolve(name).orElse(null);

        if (intent == null) {
            Optional<Spelled> dictionaryForm = dictionaryForm(name);
            if (dictionaryForm.isPresent()) {
                name = dictionaryForm.get().name();
                intent = dictionaryForm.get().intent();
            }
        }

        if (intent == null) {
            return product(line);
        }

        BigDecimal packages = line.quantity();
        BigDecimal target = line.targetQuantity();
        String unit = line.baseUnit();

        // "mleko 2" and "10 jaja" count packages. "Kisela voda 1.75" can only
        // be litres for something sold by volume, while "Vrat 1,5" could be
        // kilograms or pieces, so it stays unread.
        if (line.bareNumber() != null) {
            if (isCount(line.bareNumber())) {
                packages = packages.multiply(line.bareNumber());
            } else if ("ml".equals(intent.defaultBaseUnit())) {
                target = line.bareNumber().multiply(ONE_THOUSAND).stripTrailingZeros();
                unit = "ml";
            } else {
                return product(line);
            }
        }

        // Something counted by the piece and sold in cartons: "jaja 10 kom"
        // asks for ten eggs, however many fit in one shop's carton.
        if ("piece".equals(intent.defaultBaseUnit())
                && target == null
                && packages.compareTo(MIN_COUNT_OF_PIECES) >= 0
                && packages.stripTrailingZeros().scale() <= 0) {
            target = packages;
            unit = "piece";
            packages = BigDecimal.ONE;
        }

        if (intent.exactAlias()) {
            return choice(name, name, intent, null, packages, target, unit);
        }

        Optional<CategoryAndBrand> split =
                brandBesideCategory(name, intent.normalizedAlias());

        if (split.isEmpty()) {
            return product(line);
        }

        return choice(
                name,
                split.get().category(),
                intent,
                split.get().brand(),
                packages,
                target,
                unit
        );
    }

    private static boolean isCount(BigDecimal number) {
        return number.stripTrailingZeros().scale() <= 0
                && number.signum() > 0
                && number.compareTo(MAX_BARE_COUNT) <= 0;
    }

    /**
     * "2 mleka", "kisele vode": a list says how many of something, so its
     * last word often comes in the genitive. Only a form that is exactly a
     * kind of product counts, so a product's own name never turns into one.
     */
    private Optional<Spelled> dictionaryForm(String name) {
        String trimmed = name.trim();
        int lastSpace = trimmed.lastIndexOf(' ');
        String head = lastSpace < 0 ? "" : trimmed.substring(0, lastSpace + 1);
        String word = trimmed.substring(lastSpace + 1);
        if (word.length() < 3) {
            return Optional.empty();
        }
        String stem = word.substring(0, word.length() - 1);
        List<String> forms = switch (Character.toLowerCase(word.charAt(word.length() - 1))) {
            case 'a' -> List.of(stem, stem + "o", stem + "e");
            case 'e' -> List.of(stem + "a");
            default -> List.of();
        };
        for (String form : forms) {
            String candidate = head + form;
            Optional<ShoppingIntentResolver.ResolvedShoppingIntent> intent =
                    intentResolver.resolve(candidate)
                            .filter(ShoppingIntentResolver.ResolvedShoppingIntent::exactAlias);
            if (intent.isPresent()) {
                return Optional.of(new Spelled(candidate, intent.get()));
            }
        }
        return Optional.empty();
    }

    private record Spelled(
            String name,
            ShoppingIntentResolver.ResolvedShoppingIntent intent
    ) {
    }

    private InterpretedLine product(ParsedShoppingListLine line) {
        return new InterpretedLine(
                line.nameWithAmount(),
                line.quantity(),
                ShoppingItemRule.EXACT_PRODUCT,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private InterpretedLine choice(
            String name,
            String category,
            ShoppingIntentResolver.ResolvedShoppingIntent intent,
            String brand,
            BigDecimal packages,
            BigDecimal target,
            String unit
    ) {
        boolean carriesAmount = target != null
                && target.compareTo(BigDecimal.ZERO) > 0;

        return new InterpretedLine(
                name,
                packages,
                ShoppingItemRule.FLEXIBLE_CATEGORY,
                category,
                intent.normalizedAlias(),
                intent.shoppingIntentId(),
                brand,
                carriesAmount ? target : null,
                carriesAmount ? unit : null
        );
    }

    /**
     * The words left beside the kind of product, when they are exactly a
     * brand: "Pivo Zaječarsko" is beer of that brand, while "Plantaže
     * smederevka belo vino" also names a grape and stays one product.
     */
    private Optional<CategoryAndBrand> brandBesideCategory(
            String name,
            String normalizedAlias
    ) {
        String[] words = name.trim().split("\\s+");
        List<String> tokens = new ArrayList<>();
        List<Integer> wordOfToken = new ArrayList<>();

        for (int index = 0; index < words.length; index++) {
            for (String token : normalizer.normalize(words[index]).split(" ")) {
                if (!token.isBlank()) {
                    tokens.add(token);
                    wordOfToken.add(index);
                }
            }
        }

        List<String> aliasTokens = List.of(normalizedAlias.split(" "));
        int start = Collections.indexOfSubList(tokens, aliasTokens);

        if (start < 0) {
            return Optional.empty();
        }

        int end = start + aliasTokens.size();
        int firstWord = wordOfToken.get(start);
        int lastWord = wordOfToken.get(end - 1);

        // The kind of product has to be whole words, not half of one.
        if ((start > 0 && wordOfToken.get(start - 1) == firstWord)
                || (end < tokens.size() && wordOfToken.get(end) == lastWord)) {
            return Optional.empty();
        }

        List<String> brandWords = new ArrayList<>();
        List<String> categoryWords = new ArrayList<>();

        for (int index = 0; index < words.length; index++) {
            if (index >= firstWord && index <= lastWord) {
                categoryWords.add(words[index]);
            } else {
                brandWords.add(words[index]);
            }
        }

        String brand = String.join(" ", brandWords);

        if (brandWords.isEmpty() || !intentResolver.isKnownBrand(brand)) {
            return Optional.empty();
        }

        return Optional.of(new CategoryAndBrand(
                String.join(" ", categoryWords),
                brand
        ));
    }

    private record CategoryAndBrand(String category, String brand) {
    }

    public record InterpretedLine(
            String name,
            BigDecimal quantity,
            ShoppingItemRule matchingRule,
            String category,
            String normalizedCategory,
            Long shoppingIntentId,
            String requiredBrand,
            BigDecimal targetQuantity,
            String baseUnit
    ) {
    }
}
