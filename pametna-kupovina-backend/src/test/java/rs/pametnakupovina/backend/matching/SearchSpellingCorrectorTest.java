package rs.pametnakupovina.backend.matching;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rs.pametnakupovina.backend.matching.SearchWordVocabularyRepository.VocabularyWord;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The four misses measured on the real catalog — "mlkeo", "helb", "jogrut",
 * "jabke" — are each one slip from the word that was meant.
 */
class SearchSpellingCorrectorTest {

    private final SearchWordVocabularyRepository vocabularyRepository =
            mock(SearchWordVocabularyRepository.class);
    private final SearchSpellingCorrector corrector =
            new SearchSpellingCorrector(vocabularyRepository);

    @BeforeEach
    void aShopSellsTheseThings() {
        when(vocabularyRepository.findWordsOfLength(anyInt(), anyInt()))
                .thenReturn(List.of(
                        new VocabularyWord("mleko", 1),
                        new VocabularyWord("hleb", 1),
                        new VocabularyWord("jogurt", 1),
                        new VocabularyWord("jabuke", 1),
                        new VocabularyWord("kiselo", 1),
                        new VocabularyWord("bela", 2),
                        new VocabularyWord("meso", 1),
                        new VocabularyWord("cokolada", 1),
                        new VocabularyWord("peso", 2),
                        new VocabularyWord("imlek", 2)
                ));
    }

    @Test
    void oneSlipIsPutRight() {
        assertThat(corrector.corrections("mlkeo")).containsExactly("mleko");
        assertThat(corrector.corrections("helb")).containsExactly("hleb");
        assertThat(corrector.corrections("jogrut")).containsExactly("jogurt");
        assertThat(corrector.corrections("jabke")).containsExactly("jabuke");
    }

    @Test
    void onlyTheMistypedWordMoves() {
        assertThat(corrector.corrections("kiselo mlkeo"))
                .containsExactly("kiselo mleko");
    }

    @Test
    void aWordSpelledTheWayAShopSpellsItIsLeftAlone() {
        assertThat(corrector.corrections("mleko")).isEmpty();
        assertThat(corrector.corrections("kiselo mleko")).isEmpty();
    }

    @Test
    void sizesAndUnitsAreLeftAlone() {
        assertThat(corrector.corrections("mlkeo 2 l")).containsExactly("mleko 2 l");
    }

    /**
     * "sol" is three letters; every other three-letter word is a slip away,
     * so guessing would be guessing at the shopper.
     */
    @Test
    void aWordTooShortToBeSureAboutIsNotGuessedAt() {
        assertThat(corrector.corrections("sol")).isEmpty();
    }

    /**
     * "mkelo" is two apart from "mleko" — the swapped letters are not
     * neighbours — and a five-letter word that far off is left as a miss
     * rather than guessed at.
     */
    @Test
    void aShortWordIsGivenOneSlipAndALongWordTwo() {
        assertThat(corrector.corrections("haab")).isEmpty();
        assertThat(corrector.corrections("mkelo")).isEmpty();
        assertThat(corrector.corrections("cokolladaa")).containsExactly("cokolada");
    }

    /**
     * Two words the same slip away: the thing a shop sells beats a brand
     * that happens to sound like it.
     */
    @Test
    void theWordForTheThingItselfWinsATie() {
        assertThat(corrector.corrections("beso")).containsExactly("meso");
    }

    /**
     * "beli" is spelled perfectly well, but nothing here knows that, so both
     * readings are offered and the search settles it by trying them.
     */
    @Test
    void everyWordThatCouldBeTheMistypedOneIsOfferedBestFirst() {
        assertThat(corrector.corrections("beli helb")).containsExactly(
                "beli hleb",
                "bela helb",
                "bela hleb"
        );
    }

    @Test
    void aWordNothingIsCloseToStaysAsItWas() {
        assertThat(corrector.corrections("xyzwq")).isEmpty();
    }
}
