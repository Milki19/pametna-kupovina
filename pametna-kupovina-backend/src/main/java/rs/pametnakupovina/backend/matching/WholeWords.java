package rs.pametnakupovina.backend.matching;

import java.util.Arrays;
import java.util.List;

/**
 * The one way a list word meets a product name (V155): whole word against
 * whole word, never the start of a word. "kinoa" is not in "kinder cards",
 * "orasi" is in "orasi jezgro" but "kafa" is not in "kafamat". Both sides
 * are normalized first (ProductNameNormalizer: latin, lower case, no
 * diacritics, single spaces).
 */
public final class WholeWords {

    private WholeWords() {
    }

    /** The words of a normalized text, split on anything that is not a letter or a digit. */
    public static List<String> of(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return List.of();
        }
        return Arrays.stream(normalized.split("[^\\p{L}\\p{N}]+"))
                .filter(word -> !word.isEmpty())
                .toList();
    }

    /** The phrase's words appear in the text one after another, each whole. */
    public static boolean contains(String text, String phrase) {
        List<String> words = of(text);
        List<String> wanted = of(phrase);
        if (wanted.isEmpty() || wanted.size() > words.size()) {
            return false;
        }
        for (int start = 0; start + wanted.size() <= words.size(); start++) {
            if (words.subList(start, start + wanted.size()).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    /** The text starts with the phrase's words, each whole. */
    public static boolean startsWith(String text, String phrase) {
        List<String> words = of(text);
        List<String> wanted = of(phrase);
        return !wanted.isEmpty() && wanted.size() <= words.size()
                && words.subList(0, wanted.size()).equals(wanted);
    }

    /**
     * The same rule as a PostgreSQL regular expression, for SQL that must
     * agree with {@link #contains}: {@code name ~ WholeWords.sqlPattern(word)}.
     * The phrase is normalized already; anything but letters, digits and
     * spaces is escaped.
     */
    public static String sqlPattern(String phrase) {
        String words = String.join("\\s+", of(phrase).stream()
                .map(word -> word.replaceAll("([^\\p{L}\\p{N}])", "\\\\$1"))
                .toList());
        return "\\m" + words + "\\M";
    }
}
