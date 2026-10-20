package rs.pametnakupovina.backend.matching;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The forms a word on a shopping list takes, read back to the form a
 * dictionary has. A list says how much of something, so words come in the
 * genitive ("2 kg brašna", "pola kg mlevenog mesa", "1,5l kisele vode"), and
 * a message asks in the accusative ("uzmi piletinu", "jogurte (3)").
 *
 * <p>Works on normalized text (lower case, ASCII letters: "č" is "c"). Every
 * candidate is only a guess: a form counts where the dictionary or the
 * products' own names have it, never on its own.
 */
public final class SerbianWordForms {

    /** Phrases of more words than this are read as written. */
    private static final int MAX_WORDS = 5;

    /** Enough for every reading of a phrase of three or four words. */
    private static final int MAX_PHRASES = 256;

    private SerbianWordForms() {
    }

    /**
     * The word itself first, then the forms it may stand for:
     * "brasna" → brasno, brasn, brasne; "mlevenog" → mleveno, mleveni.
     */
    public static List<String> wordCandidates(String word) {
        Set<String> forms = new LinkedHashSet<>();
        forms.add(word);
        if (word.length() < 4 || !word.chars().allMatch(Character::isLetter)) {
            return List.copyOf(forms);
        }

        // Adjectives: "mlevenog mesa", "kiseloj vodi", "svezih jaja".
        if (word.endsWith("og") || word.endsWith("eg")) {
            String stem = cut(word, 2);
            forms.add(stem + "o");
            forms.add(stem + "i");
            forms.add(stem + "e");
        }
        if (word.endsWith("om") || word.endsWith("em")) {
            String stem = cut(word, 2);
            forms.add(stem + "o");
            forms.add(stem + "i");
            forms.add(stem);
        }
        if (word.endsWith("oj")) {
            forms.add(cut(word, 2) + "a");
        }
        if (word.endsWith("ih") || word.endsWith("im")) {
            String stem = cut(word, 2);
            forms.add(stem + "i");
            forms.add(stem + "e");
            forms.add(stem + "a");
        }
        if (word.endsWith("ama")) {
            String stem = cut(word, 3);
            forms.add(stem + "a");
            forms.add(stem + "e");
        }

        String stem = cut(word, 1);
        switch (word.charAt(word.length() - 1)) {
            // "brasna" → brasno, "sira" → sir, "vode" in "kisele vode" → voda.
            case 'a' -> {
                forms.add(stem);
                forms.add(stem + "o");
                forms.add(stem + "e");
                // "krastavca" → krastavac, "paradajza" → paradajz.
                if (stem.length() > 3 && isConsonant(stem.charAt(stem.length() - 1))
                        && isConsonant(stem.charAt(stem.length() - 2))) {
                    forms.add(stem.substring(0, stem.length() - 1) + "a"
                            + stem.charAt(stem.length() - 1));
                }
            }
            // "kisele" → kisela, "jogurte" → jogurt, "pelene" → pelena.
            case 'e' -> {
                forms.add(stem + "a");
                forms.add(stem);
                forms.add(stem + "i");
                forms.add(stem + "o");
            }
            // "piletinu" → piletina, "siru" → sir.
            case 'u' -> {
                forms.add(stem + "a");
                forms.add(stem);
                forms.add(stem + "o");
            }
            // "krastavci" → krastavac, "jogurti" → jogurt.
            case 'i' -> {
                forms.add(stem);
                forms.add(stem + "a");
                if (stem.endsWith("c") || stem.endsWith("k")) {
                    forms.add(stem.substring(0, stem.length() - 1) + "ac");
                    forms.add(stem.substring(0, stem.length() - 1) + "ak");
                }
            }
            // "mleko" stays; "brasno" may be written for "brasna".
            case 'o' -> {
                forms.add(stem + "a");
                forms.add(stem + "i");
            }
            default -> {
            }
        }
        return List.copyOf(forms);
    }

    /**
     * Every reading of a phrase, the phrase as written first: "kisele vode"
     * → kisele vode, kisela voda, … Words that are numbers stay as they are.
     */
    public static List<String> phraseCandidates(String normalizedPhrase) {
        String trimmed = normalizedPhrase == null ? "" : normalizedPhrase.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        String[] words = trimmed.split(" ");
        if (words.length > MAX_WORDS) {
            return List.of(trimmed);
        }

        List<String> phrases = new ArrayList<>();
        phrases.add("");
        for (String word : words) {
            List<String> next = new ArrayList<>();
            for (String phrase : phrases) {
                for (String form : wordCandidates(word)) {
                    next.add(phrase.isEmpty() ? form : phrase + " " + form);
                    if (next.size() >= MAX_PHRASES) {
                        break;
                    }
                }
                if (next.size() >= MAX_PHRASES) {
                    break;
                }
            }
            phrases = next;
        }

        Set<String> ordered = new LinkedHashSet<>();
        ordered.add(trimmed);
        ordered.addAll(phrases);
        return List.copyOf(ordered);
    }

    /**
     * The part of a word every form of it shares, for finding it in product
     * names: "tortilje" → tortilj, "maramice" → maramic, "feta" → fet.
     * Short words are kept whole.
     */
    public static String stem(String word) {
        if (word.length() < 4 || !word.chars().allMatch(Character::isLetter)) {
            return word;
        }
        String stem = word;
        // Adjective endings first: "mlevenog" and "mleveno" share "mleven".
        for (String ending : List.of("ama", "og", "eg", "om", "em", "oj", "ih", "im")) {
            if (stem.endsWith(ending) && stem.length() - ending.length() >= 4) {
                return stem.substring(0, stem.length() - ending.length());
            }
        }
        // "mesa" and "meso" share "mes", "feta" and "fete" "fet".
        while (stem.length() > 3 && isVowel(stem.charAt(stem.length() - 1))) {
            stem = stem.substring(0, stem.length() - 1);
        }
        // "krastavac" and "krastavci": the "a" before the last letter goes.
        if (stem.length() > 5 && stem.charAt(stem.length() - 2) == 'a'
                && (stem.endsWith("ac") || stem.endsWith("ak"))) {
            stem = stem.substring(0, stem.length() - 2);
        } else if (stem.length() > 5 && (stem.endsWith("c") || stem.endsWith("k"))
                && isConsonant(stem.charAt(stem.length() - 2))) {
            stem = stem.substring(0, stem.length() - 1);
        }
        return stem;
    }

    private static String cut(String word, int letters) {
        return word.substring(0, word.length() - letters);
    }

    private static boolean isVowel(char letter) {
        return "aeiou".indexOf(letter) >= 0;
    }

    private static boolean isConsonant(char letter) {
        return Character.isLetter(letter) && !isVowel(letter);
    }
}
