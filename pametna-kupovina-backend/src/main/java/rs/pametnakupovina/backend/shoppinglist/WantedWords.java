package rs.pametnakupovina.backend.shoppinglist;

import rs.pametnakupovina.backend.matching.ProductNameNormalizer;
import rs.pametnakupovina.backend.matching.SerbianWordForms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a line asks for beyond the kind of product: "feta" in "feta sir",
 * 3,2 % in "mleko 3,2%", "za decu" in "šampon za decu". The plan prefers the
 * products whose names say all of it, and a product that does not is a
 * substitute it names as such (izveštaj 20.10.2026: feta became a young
 * cheese and 3,2 % milk 1,5 % without a word).
 *
 * <p>For a line no dictionary knows ("tortilje", "vlažne maramice") the same
 * words are the whole kind: the products whose names say them.
 *
 * <p>The result is a PostgreSQL regular expression over a product's
 * normalized name: every word as the start of a word, in any order.
 */
public final class WantedWords {

    private static final int MAX_LENGTH = 600;

    /** Words that only join the others. */
    private static final Set<String> JOINING_WORDS = Set.of(
            "za", "sa", "od", "i", "u", "na", "po", "s", "the", "and", "with", "of"
    );

    /** Units and packages: how much, not what. */
    private static final Set<String> AMOUNT_WORDS = Set.of(
            "g", "gr", "grama", "gram", "kg", "kila", "kilo", "kile", "kilogram", "kilograma",
            "l", "lit", "litar", "litra", "litre", "ml", "kom", "komad", "komada", "komadi",
            "pak", "pakovanje", "pakovanja", "x", "cca", "oko"
    );

    /**
     * Who or what a product is for, as product names write it: "šampon za
     * decu" is a children's shampoo, named "dečiji", "kids" or "baby" too.
     */
    private static final Map<String, String> PURPOSE = Map.ofEntries(
            Map.entry("dec", "(dec|djec|kid|baby|beb|junior|child)"),
            Map.entry("djec", "(dec|djec|kid|baby|beb|junior|child)"),
            Map.entry("det", "(dec|djec|kid|baby|beb|junior|child)"),
            Map.entry("beb", "(beb|baby)"),
            Map.entry("pas", "(pas|psa|pse|psi|dog|psec)"),
            Map.entry("psa", "(pas|psa|pse|psi|dog|psec)"),
            Map.entry("pse", "(pas|psa|pse|psi|dog|psec)"),
            Map.entry("psi", "(pas|psa|pse|psi|dog|psec)"),
            Map.entry("mack", "(mac|cat)"),
            Map.entry("mac", "(mac|cat)"),
            Map.entry("sudov", "(sud|posud)"),
            Map.entry("sudj", "(sud|posud)"),
            Map.entry("posudj", "(sud|posud)"),
            Map.entry("ves", "(ves|rublj)"),
            Map.entry("kos", "(kos|hair)"),
            Map.entry("ruk", "(ruk|hand)")
    );

    private WantedWords() {
    }

    /**
     * @param category       the words of the line, as kept on the item
     * @param kindAlias      the dictionary words of its kind, or null when
     *                       no dictionary knows it
     * @return the expression, or null when nothing is asked beyond the kind
     */
    public static String pattern(
            String category,
            String kindAlias,
            ProductNameNormalizer normalizer
    ) {
        List<String> terms = terms(category, kindAlias, normalizer);
        if (terms.isEmpty()) {
            return null;
        }
        String pattern;
        if (terms.size() == 1) {
            pattern = terms.getFirst();
        } else {
            StringBuilder all = new StringBuilder("^");
            for (String term : terms) {
                all.append("(?=.*").append(term).append(')');
            }
            pattern = all.toString();
        }
        return pattern.length() > MAX_LENGTH ? null : pattern;
    }

    /** The words of the line a product's name has to say, as expressions. */
    static List<String> terms(
            String category,
            String kindAlias,
            ProductNameNormalizer normalizer
    ) {
        if (category == null || category.isBlank()) {
            return List.of();
        }
        List<String> words = new ArrayList<>(List.of(normalizer.normalize(category).split(" ")));
        words.removeIf(String::isBlank);

        if (kindAlias != null && !kindAlias.isBlank()) {
            List<String> alias = List.of(kindAlias.trim().split(" "));
            int start = Collections.indexOfSubList(words, alias);
            if (start >= 0) {
                words.subList(start, start + alias.size()).clear();
            } else {
                // "mlevenog mesa" for "meso": the same word in another form.
                for (String aliasWord : alias) {
                    String stem = SerbianWordForms.stem(aliasWord);
                    words.removeIf(word -> SerbianWordForms.stem(word).equals(stem));
                }
            }
        }

        List<String> terms = new ArrayList<>();
        for (int index = 0; index < words.size(); index++) {
            String word = words.get(index);
            if (JOINING_WORDS.contains(word)) {
                continue;
            }
            // A number with its unit is how much: "2 l", "500 g".
            if (isNumber(word)) {
                int end = index;
                while (end + 1 < words.size() && isNumber(words.get(end + 1))) {
                    end++;
                }
                boolean amount = end + 1 < words.size() && AMOUNT_WORDS.contains(words.get(end + 1));
                if (amount) {
                    index = end + 1;
                    continue;
                }
                // "3 2" from 3,2 %, "t 500" from T-500: one term, as written.
                String number = String.join(" ", words.subList(index, end + 1));
                if (!terms.isEmpty() && terms.getLast().matches("\\\\m[a-z]\\\\M")) {
                    String letter = terms.removeLast().substring(2, 3);
                    number = letter + " " + number;
                }
                terms.add("\\m" + number + "\\M");
                index = end;
                continue;
            }
            if (AMOUNT_WORDS.contains(word)) {
                continue;
            }
            // A letter before a number: "t" of T-500.
            if (word.length() == 1) {
                if (index + 1 < words.size() && isNumber(words.get(index + 1))) {
                    terms.add("\\m" + word + "\\M");
                }
                continue;
            }
            String stem = SerbianWordForms.stem(word);
            String purpose = purpose(word, stem);
            terms.add(purpose != null ? "\\m" + purpose : "\\m" + stem);
        }
        return terms.stream().distinct().toList();
    }

    private static String purpose(String word, String stem) {
        String withoutVowel = word.length() > 2 && "aeiou".indexOf(word.charAt(word.length() - 1)) >= 0
                ? word.substring(0, word.length() - 1)
                : word;
        for (String form : List.of(word, withoutVowel, stem)) {
            String purpose = PURPOSE.get(form);
            if (purpose != null) {
                return purpose;
            }
        }
        // "deciji", "decji", "decu", "deca": all one purpose.
        if (stem.startsWith("dec") || stem.startsWith("djec")) {
            return PURPOSE.get("dec");
        }
        if (stem.startsWith("beb")) {
            return PURPOSE.get("beb");
        }
        return null;
    }

    private static boolean isNumber(String word) {
        return !word.isEmpty() && word.chars().allMatch(Character::isDigit);
    }
}
