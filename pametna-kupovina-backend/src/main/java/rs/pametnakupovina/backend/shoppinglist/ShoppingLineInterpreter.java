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
        // The words as a dictionary has them, where the kind sits among them:
        // "mlevenog mesa" holds "meso" only once read as "mleveno meso".
        String kindPhrase = name;

        if (intent == null || !intent.exactAlias()) {
            Optional<ShoppingIntentResolver.FormMatch> form =
                    intentResolver.resolveForms(name);
            if (form.isPresent() && form.get().intent().exactAlias()) {
                name = respelled(name, form.get().phrase());
                kindPhrase = form.get().phrase();
                intent = form.get().intent();
            } else if (intent == null && form.isPresent()) {
                intent = form.get().intent();
                kindPhrase = form.get().phrase();
            }
        }

        if (intent == null) {
            return kindByName(line);
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
                brandBesideCategory(kindPhrase, intent.normalizedAlias());

        if (split.isEmpty()) {
            // "feta sir", "mleko 3,2%", "šampon za decu": the kind, and what
            // the plan looks for among its products. Only where some product
            // of the kind says it: "toaletna voda" is no water.
            String wanted = WantedWords.pattern(kindPhrase, intent.normalizedAlias(), normalizer);
            if (wanted != null
                    && intentResolver.kindHasProductsMatching(intent.shoppingIntentId(), wanted)) {
                return choice(name, kindPhrase, intent, null, packages, target, unit);
            }
            return kindByName(line);
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
     * What no dictionary knows ("tortilje", "vlažne maramice", "hrana za
     * pse") is any product whose name says its words, as "Neka aplikacija
     * izabere" does, without a click (izveštaj 20.10.2026). Only where such
     * products have a price; anything else is looked up as one product.
     */
    private InterpretedLine kindByName(ParsedShoppingListLine line) {
        String written = line.name() == null ? "" : line.name().trim();
        // "pelene za bebu": diapers are for babies whether or not their
        // names say it, so the purpose is dropped where nothing says it.
        for (String name : List.of(written, ShoppingListTextParser.withoutPurpose(written))) {
            if (name.isBlank()) {
                continue;
            }
            BigDecimal packages = line.quantity();
            String words = name;
            if (line.bareNumber() != null) {
                // "pelene 4" is size four where diapers say it; "tortilje 2"
                // two packs.
                String withNumber = name + " " + line.bareNumber().toPlainString();
                String numbered = WantedWords.pattern(withNumber, null, normalizer);
                if (numbered != null && intentResolver.productsMatch(numbered)) {
                    words = withNumber;
                } else if (isCount(line.bareNumber())) {
                    packages = packages.multiply(line.bareNumber());
                } else {
                    continue;
                }
            }
            String wanted = WantedWords.pattern(words, null, normalizer);
            if (wanted != null && intentResolver.productsMatch(wanted)) {
                return byName(line, written.equals(name) ? words : written, words, packages);
            }
            if (written.equals(ShoppingListTextParser.withoutPurpose(written))) {
                break;
            }
        }
        return product(line);
    }

    private InterpretedLine byName(
            ParsedShoppingListLine line,
            String name,
            String words,
            BigDecimal packages
    ) {
        BigDecimal target = line.targetQuantity();
        return new InterpretedLine(
                name,
                packages,
                ShoppingItemRule.FLEXIBLE_CATEGORY,
                words,
                normalizer.normalize(words),
                null,
                null,
                target != null && target.signum() > 0 ? target : null,
                target != null && target.signum() > 0 ? line.baseUnit() : null
        );
    }

    /**
     * The dictionary's form in the shopper's own letters: "brašna T-500"
     * read as "brasno t 500" is shown as "brašno T-500", "kisele vode" as
     * "kisela voda". Only the endings change.
     */
    static String respelled(String written, String phrase) {
        java.util.regex.Matcher words = java.util.regex.Pattern
                .compile("\\p{L}+").matcher(written);
        List<String> forms = new ArrayList<>(List.of(phrase.split(" ")));
        StringBuilder result = new StringBuilder();
        int last = 0;
        while (words.find()) {
            String word = words.group();
            String folded = rs.pametnakupovina.backend.matching.SerbianTextTransliterator
                    .toLatinAsciiLowercase(word);
            String form = forms.stream()
                    .filter(candidate -> sharesStem(folded, candidate))
                    .findFirst()
                    .orElse(null);
            result.append(written, last, words.start());
            if (form == null || form.equals(folded) || folded.length() != word.length()) {
                result.append(form == null || folded.length() == word.length() ? word : form);
            } else {
                int same = 0;
                while (same < folded.length() && same < form.length()
                        && folded.charAt(same) == form.charAt(same)) {
                    same++;
                }
                result.append(word, 0, same).append(form.substring(same));
            }
            if (form != null) {
                forms.remove(form);
            }
            last = words.end();
        }
        result.append(written.substring(last));
        return result.toString().trim();
    }

    private static boolean sharesStem(String word, String form) {
        int same = 0;
        while (same < word.length() && same < form.length()
                && word.charAt(same) == form.charAt(same)) {
            same++;
        }
        return same == word.length() && same == form.length()
                || same >= Math.max(1, Math.min(word.length(), form.length()) - 2);
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
