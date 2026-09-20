package rs.pametnakupovina.backend.matching;

import org.springframework.stereotype.Component;
import rs.pametnakupovina.backend.matching.SearchWordVocabularyRepository.VocabularyWord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * "mlkeo" and "helb" used to find nothing at all. Most mistyped words are one
 * slip away from the word that was meant — two letters swapped, one letter
 * missing, one letter too many — so a query that found nothing is tried once
 * more with every such word put right.
 *
 * <p>A short word is given one slip only. "helb" is already a quarter wrong,
 * and allowing two would let it become almost any other four-letter word.
 *
 * <p>Which word was the mistyped one is not known here, so the mistyped
 * words are offered one at a time, best guess first, and the search decides
 * by trying them: in "beli helb" the shopper spelled "beli" perfectly well,
 * and only putting "helb" right finds the bread.
 */
@Component
public class SearchSpellingCorrector {

    private static final int SHORTEST_CORRECTABLE_WORD = 4;
    private static final int LONGEST_ONE_SLIP_WORD = 6;
    private static final Pattern LETTERS_ONLY = Pattern.compile("[a-z]+");

    private final SearchWordVocabularyRepository vocabularyRepository;

    public SearchSpellingCorrector(
            SearchWordVocabularyRepository vocabularyRepository
    ) {
        this.vocabularyRepository = vocabularyRepository;
    }

    private static final int MOST_TRIES = 4;

    /**
     * @param normalizedQuery a query as {@link ProductNameNormalizer} leaves
     *                        it: lowercase Latin words separated by spaces
     * @return queries worth trying instead, best guess first; empty when no
     *         word in the query is one slip from a word a shop uses
     */
    public List<String> corrections(String normalizedQuery) {
        String[] words = normalizedQuery.split(" ");

        int shortestWanted = Integer.MAX_VALUE;
        int longestWanted = 0;

        for (String word : words) {
            if (!correctable(word)) {
                continue;
            }

            int slips = allowedSlips(word.length());
            shortestWanted = Math.min(shortestWanted, word.length() - slips);
            longestWanted = Math.max(longestWanted, word.length() + slips);
        }

        if (longestWanted == 0) {
            return List.of();
        }

        List<VocabularyWord> vocabulary =
                vocabularyRepository.findWordsOfLength(
                        Math.max(1, shortestWanted),
                        longestWanted
                );

        if (vocabulary.isEmpty()) {
            return List.of();
        }

        List<Replacement> replacements = new ArrayList<>();

        for (int position = 0; position < words.length; position++) {
            if (!correctable(words[position])) {
                continue;
            }

            closestWord(words[position], vocabulary, position)
                    .ifPresent(replacements::add);
        }

        if (replacements.isEmpty()) {
            return List.of();
        }

        replacements.sort(
                Comparator.comparingInt(Replacement::distance)
                        .thenComparingInt(Replacement::sourceRank)
                        .thenComparingInt(Replacement::position)
        );

        List<String> tries = new ArrayList<>();

        for (Replacement replacement : replacements) {
            if (tries.size() == MOST_TRIES) {
                break;
            }

            tries.add(queryWith(words, List.of(replacement)));
        }

        // Two words mistyped at once is rare, so putting every one of them
        // right is the last thing tried, not the first.
        if (replacements.size() > 1 && tries.size() < MOST_TRIES) {
            tries.add(queryWith(words, replacements));
        }

        return List.copyOf(tries);
    }

    private static String queryWith(
            String[] words,
            List<Replacement> replacements
    ) {
        String[] corrected = words.clone();

        for (Replacement replacement : replacements) {
            corrected[replacement.position()] = replacement.word();
        }

        return String.join(" ", corrected);
    }

    private static boolean correctable(String word) {
        return word.length() >= SHORTEST_CORRECTABLE_WORD
                && LETTERS_ONLY.matcher(word).matches();
    }

    private static int allowedSlips(int wordLength) {
        return wordLength <= LONGEST_ONE_SLIP_WORD ? 1 : 2;
    }

    private static Optional<Replacement> closestWord(
            String word,
            List<VocabularyWord> vocabulary,
            int position
    ) {
        int allowedSlips = allowedSlips(word.length());
        VocabularyWord closest = null;
        int closestDistance = Integer.MAX_VALUE;

        for (VocabularyWord candidate : vocabulary) {
            if (candidate.word().equals(word)) {
                // Spelled the way a shop spells it; nothing to put right.
                return Optional.empty();
            }

            int distance = distance(
                    word,
                    candidate.word(),
                    allowedSlips
            );

            if (distance > allowedSlips) {
                continue;
            }

            if (closest == null
                    || distance < closestDistance
                    || (distance == closestDistance
                            && closerInSpirit(candidate, closest, word))) {
                closest = candidate;
                closestDistance = distance;
            }
        }

        if (closest == null) {
            return Optional.empty();
        }

        return Optional.of(new Replacement(
                position,
                closest.word(),
                closestDistance,
                closest.sourceRank()
        ));
    }

    private record Replacement(
            int position,
            String word,
            int distance,
            int sourceRank
    ) {
    }

    /**
     * Two words the same number of slips away: the one that starts with the
     * same letter wins, because a first letter is rarely the one mistyped,
     * then the word for the thing itself over a brand that sounds like it.
     */
    private static boolean closerInSpirit(
            VocabularyWord candidate,
            VocabularyWord closest,
            String word
    ) {
        boolean candidateKeepsFirstLetter =
                candidate.word().charAt(0) == word.charAt(0);
        boolean closestKeepsFirstLetter =
                closest.word().charAt(0) == word.charAt(0);

        if (candidateKeepsFirstLetter != closestKeepsFirstLetter) {
            return candidateKeepsFirstLetter;
        }

        if (candidate.sourceRank() != closest.sourceRank()) {
            return candidate.sourceRank() < closest.sourceRank();
        }

        return candidate.word().compareTo(closest.word()) < 0;
    }

    /**
     * Slips between two words, counting two swapped letters as one slip
     * (optimal string alignment). Gives up as soon as the answer is above
     * {@code most}, which is what makes running this over the whole word list
     * cheap.
     */
    static int distance(String left, String right, int most) {
        if (Math.abs(left.length() - right.length()) > most) {
            return most + 1;
        }

        int[] twoRowsBack = new int[right.length() + 1];
        int[] previousRow = new int[right.length() + 1];
        int[] currentRow = new int[right.length() + 1];

        for (int column = 0; column <= right.length(); column++) {
            previousRow[column] = column;
        }

        for (int row = 1; row <= left.length(); row++) {
            currentRow[0] = row;
            int bestInRow = row;

            for (int column = 1; column <= right.length(); column++) {
                int best = Math.min(
                        previousRow[column - 1]
                                + (left.charAt(row - 1)
                                        == right.charAt(column - 1) ? 0 : 1),
                        Math.min(
                                previousRow[column] + 1,
                                currentRow[column - 1] + 1
                        )
                );

                if (row > 1
                        && column > 1
                        && left.charAt(row - 1) == right.charAt(column - 2)
                        && left.charAt(row - 2) == right.charAt(column - 1)) {
                    best = Math.min(best, twoRowsBack[column - 2] + 1);
                }

                currentRow[column] = best;
                bestInRow = Math.min(bestInRow, best);
            }

            if (bestInRow > most) {
                return most + 1;
            }

            int[] spare = twoRowsBack;
            twoRowsBack = previousRow;
            previousRow = currentRow;
            currentRow = spare;
        }

        return previousRow[right.length()];
    }
}
