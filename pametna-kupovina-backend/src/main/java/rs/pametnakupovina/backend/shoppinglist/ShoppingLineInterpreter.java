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
        ShoppingIntentResolver.ResolvedShoppingIntent intent =
                intentResolver.resolve(line.name()).orElse(null);

        if (intent == null) {
            return product(line);
        }

        BigDecimal target = line.targetQuantity();
        String unit = line.baseUnit();

        // "Kisela voda 1.75" can only be litres for something sold by volume.
        // "Vrat 1,5" could be kilograms or pieces, so it stays unread.
        if (line.bareNumber() != null) {
            if (!"ml".equals(intent.defaultBaseUnit())) {
                return product(line);
            }
            target = line.bareNumber().multiply(ONE_THOUSAND).stripTrailingZeros();
            unit = "ml";
        }

        if (intent.exactAlias()) {
            return choice(line, line.name(), intent, null, target, unit);
        }

        Optional<CategoryAndBrand> split =
                brandBesideCategory(line.name(), intent.normalizedAlias());

        if (split.isEmpty()) {
            return product(line);
        }

        return choice(
                line,
                split.get().category(),
                intent,
                split.get().brand(),
                target,
                unit
        );
    }

    private InterpretedLine product(ParsedShoppingListLine line) {
        return new InterpretedLine(
                line.nameWithAmount(),
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
            ParsedShoppingListLine line,
            String category,
            ShoppingIntentResolver.ResolvedShoppingIntent intent,
            String brand,
            BigDecimal target,
            String unit
    ) {
        boolean carriesAmount = target != null
                && target.compareTo(BigDecimal.ZERO) > 0;

        return new InterpretedLine(
                line.name(),
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
