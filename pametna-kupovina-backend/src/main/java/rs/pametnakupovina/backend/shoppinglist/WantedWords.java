package rs.pametnakupovina.backend.shoppinglist;

import rs.pametnakupovina.backend.matching.ProductNameNormalizer;
import rs.pametnakupovina.backend.matching.SerbianWordForms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    /** The codes a chain writes into its names: Lidl's "MK20", "SK6", "RFA". */
    private static final String NOT_A_CODE = "(?<!mk )(?<!sk )(?<!rfa )";

    /**
     * How a pattern without a kind opens. The offers read it: a product that
     * misses such a pattern is a lesser choice, not a substitute.
     */
    static final String HEAD = "^(?=([^ ]+ ){0,2}";

    /** "pašteta od lososa", "donut sa kakao glazurom": the word is an ingredient. */
    private static final String AFTER_NO_JOINING_WORD =
            "(?<!\\msa )(?<!\\mod )(?<!\\mza )(?<!\\ms )(?<!\\mu )(?<!\\mi )";

    /**
     * Names opening with what is made of the word: "pašteta Patelina losos"
     * is no salmon, "kroasan Monster kakao" no cocoa (provera uživo 23.10.).
     */
    private static final List<String> MADE_OF_IT = List.of(
            "pastet", "pate", "namaz", "sos", "supa", "corba", "puding",
            "sladoled", "kroasan", "bombon", "napolitank", "vafli", "vafel",
            "krofn", "donut", "biskvit"
    );

    /**
     * Words that make a name something else wherever they stand: "Kesica
     * losos Whiskas" is cat food, "Kakao krem tabla" chocolate, "Tortilja
     * chips" and "Tortilja morska so" crisps, "Kakao napitak" a drink
     * (provera uživo 23.10.).
     */
    private static final List<String> SOMETHING_ELSE = List.of(
            "chips", "cips", "nachos", "morska", "tabla", "kesic", "cokolad", "napit"
    );

    /** Pet food, unless the line asks for a pet's: "hrana za pse". */
    private static final List<String> PET_FOOD = List.of(
            "whiskas", "felix", "friskies", "pedigree", "macke", "pse", "cat", "dog"
    );

    private static final String NOT_A_WEIGHT = "(?! ?(g|gr|kg|ml|l|kom)\\M)";

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

    /**
     * Words product names write otherwise: "sojino mleko" is "napitak od
     * soje" or "soja drink", "kukuruzne pahuljice" "corn flakes" (proba V140).
     */
    private static final Map<String, String> SAME_THING = Map.of(
            "sojin", "(soj|soy)",
            "mlek", "(mlek|napit|drink|milk)",
            "kukuruzn", "(kukuruz|corn)",
            "pahuljic", "(pahulj|flake)"
    );

    private static final Set<String> FOR_FOOD = Set.of("hran", "namirnic");

    private static final String FOOD_WRAP =
            "(hran|namirn|alu|aluminij|stretch|prijanj|frigo|kuhinj|pvc)";

    private WantedWords() {
    }

    private static String notAsked(List<String> words, List<String> terms) {
        return words.stream()
                .filter(word -> terms.stream().noneMatch(term -> term.contains(word)))
                .map(word -> word.length() > 4 ? word + "[a-z]*" : word)
                .collect(Collectors.joining("|"));
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
        if (kindAlias == null || kindAlias.isBlank()) {
            // Without a kind the first word is the thing itself, so it opens
            // the name: "losos" is no "hrana za mačke losos", "kakao" no
            // "vafel proiz. kakao", "semenke" no "baget sa semenkama"
            // (proba V140, 20.10.2026). A brand may come first, but not a
            // word it is made with: "pašteta od lososa", "pašteta sa šunkom".
            StringBuilder all = new StringBuilder(HEAD)
                    .append(AFTER_NO_JOINING_WORD).append(terms.getFirst()).append(')');
            // Short words whole, longer ones in any form: "sos" is no "sosiske".
            all.append("(?!(").append(notAsked(MADE_OF_IT, terms)).append(")\\M)");
            boolean forAPet = terms.stream().anyMatch(term -> term.contains("dog") || term.contains("cat"));
            List<String> somethingElse = new ArrayList<>(SOMETHING_ELSE);
            if (!forAPet) {
                somethingElse.addAll(PET_FOOD);
            }
            all.append("(?!.*\\m(").append(notAsked(somethingElse, terms)).append(")\\M)");
            for (String term : terms.subList(1, terms.size())) {
                all.append("(?=.*").append(term).append(')');
            }
            pattern = all.toString();
        } else if (terms.size() == 1) {
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
                // Not a chain's code ("MK20" in "Kisela pavlaka 12% MK20" is
                // no 20 %) and not a weight ("20 g").
                terms.add(NOT_A_CODE + "\\m" + number + "\\M" + NOT_A_WEIGHT);
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
            // "folija za hranu" is a kitchen foil, named for its metal or
            // its cling as often as for the food (proba V140).
            if (index > 0 && "za".equals(words.get(index - 1)) && FOR_FOOD.contains(stem)) {
                terms.add("\\m" + FOOD_WRAP);
                continue;
            }
            String purpose = purpose(word, stem);
            String synonym = SAME_THING.get(stem);
            if (synonym != null) {
                terms.add("\\m" + synonym);
            } else if (purpose != null) {
                terms.add("\\m" + purpose);
            } else if (word.length() < 4) {
                // A short word is the whole word: "AA" batteries are no "AAA".
                terms.add("\\m" + word + "\\M");
            } else {
                terms.add("\\m" + stem);
            }
        }
        return terms.stream().distinct().toList();
    }

    /** Fewer letters in a row give no trigram for the index to look up. */
    private static final int MIN_HINT_LETTERS = 3;

    /**
     * One word every name the expression accepts says, as an expression the
     * trigram index of the names can look up: the expression itself opens
     * with look-arounds, which no index reads, so each match read every
     * product's name (0.6 s for "pesak za mačke", profil 31.10.2026). The
     * hint narrows the names to read; the expression still decides.
     *
     * @return null when no word is long enough to look up
     */
    public static String hint(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return null;
        }
        List<String> terms = new ArrayList<>();
        int index = pattern.startsWith("^") ? 1 : 0;
        while (index < pattern.length()) {
            int end = groupEnd(pattern, index);
            if (end < 0) {
                return null;
            }
            if (pattern.startsWith("(?=", index)) {
                String inner = pattern.substring(index + 3, end - 1);
                if (inner.startsWith(".*")) {
                    terms.add(inner.substring(2));
                } else if (inner.startsWith("([^ ]+ ){0,2}")) {
                    terms.add(inner.substring("([^ ]+ ){0,2}".length()));
                }
            }
            index = end;
        }
        if (terms.isEmpty()) {
            // A kind's one word, perhaps with the guards of a number.
            terms.add(pattern);
        }
        String best = null;
        int bestLetters = MIN_HINT_LETTERS - 1;
        for (String term : terms) {
            String plain = withoutGuards(term);
            int letters = fewestLetters(plain);
            if (letters > bestLetters) {
                best = plain;
                bestLetters = letters;
            }
        }
        return best;
    }

    /** Where the group or the single token starting at index ends. */
    private static int groupEnd(String pattern, int index) {
        char first = pattern.charAt(index);
        if (first == '\\') {
            return Math.min(index + 2, pattern.length());
        }
        if (first != '(') {
            return index + 1;
        }
        int depth = 0;
        for (int at = index; at < pattern.length(); at++) {
            char c = pattern.charAt(at);
            if (c == '\\') {
                at++;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return at + 1;
            }
        }
        return -1;
    }

    /** The term without look-arounds and word edges: what it must contain. */
    private static String withoutGuards(String term) {
        StringBuilder plain = new StringBuilder();
        int index = 0;
        while (index < term.length()) {
            if (term.startsWith("(?", index)) {
                int end = groupEnd(term, index);
                if (end < 0) {
                    return "";
                }
                index = end;
            } else if (term.startsWith("\\m", index) || term.startsWith("\\M", index)) {
                index += 2;
            } else {
                plain.append(term.charAt(index));
                index++;
            }
        }
        return plain.toString();
    }

    /**
     * Letters every match has in a row: "pesak" 5, "(mac|cat)" 3; anything
     * more involved counts none.
     */
    private static int fewestLetters(String plain) {
        if (plain.matches("[a-z0-9]+")) {
            return plain.length();
        }
        if (plain.matches("\\(([a-z0-9]+\\|)*[a-z0-9]+\\)")) {
            return java.util.Arrays.stream(plain.substring(1, plain.length() - 1).split("\\|"))
                    .mapToInt(String::length)
                    .min()
                    .orElse(0);
        }
        return 0;
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
