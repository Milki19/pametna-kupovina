package rs.pametnakupovina.backend.shoppinglist;

import org.junit.jupiter.api.Test;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The word the trigram index looks up for a line's expression (profil
 * 31.10.2026): every name the expression accepts says it.
 */
class WantedWordsHintTest {

    private final ProductNameNormalizer normalizer = new ProductNameNormalizer();

    @Test
    void aLineNoDictionaryKnowsIsLookedUpByItsLongestWord() {
        String pattern = WantedWords.pattern("pesak za macke", null, normalizer);

        assertThat(WantedWords.hint(pattern)).isEqualTo("pesak");
    }

    @Test
    void theOtherWordsCountTooWhenTheyAreLonger() {
        String pattern = WantedWords.pattern("vlazne maramice", null, normalizer);

        assertThat(WantedWords.hint(pattern)).isEqualTo("maramic");
    }

    @Test
    void wordsOfTheSameThingAreLookedUpTogether() {
        String pattern = WantedWords.pattern("sojino mleko", null, normalizer);

        assertThat(WantedWords.hint(pattern)).isIn("(soj|soy)", "(mlek|napit|drink|milk)");
    }

    @Test
    void aKindsOneWordIsItsOwnHint() {
        assertThat(WantedWords.hint(WantedWords.pattern("feta sir", "sir", normalizer)))
                .isEqualTo("fet");
    }

    @Test
    void aNumberAloneGivesNothingToLookUp() {
        assertThat(WantedWords.hint(WantedWords.pattern("mleko 3,2%", "mleko", normalizer)))
                .isNull();
        assertThat(WantedWords.hint(null)).isNull();
    }

    @Test
    void everyNameThePatternAcceptsSaysTheHint() {
        List<String> lines = List.of(
                "pesak za macke", "vlazne maramice", "sojino mleko", "tortilje",
                "losos", "folija za hranu", "sampon za decu", "kukuruzne pahuljice"
        );
        List<String> names = List.of(
                "pesak za macke cat 5 kg", "maramice vlazne baby 72", "soja drink alpro 1 l",
                "tortilje wraps 6 kom", "losos dimljeni 100 g", "folija alu 20 m",
                "sampon kids 250 ml", "corn flakes 500 g", "napitak od soje 1 l"
        );
        for (String line : lines) {
            String pattern = WantedWords.pattern(line, null, normalizer);
            String hint = WantedWords.hint(pattern);
            assertThat(hint).as(line).isNotNull();
            for (String name : names) {
                if (Pattern.compile(pattern.replace("\\m", "\\b").replace("\\M", "\\b"))
                        .matcher(name).find()) {
                    assertThat(Pattern.compile(hint).matcher(name).find())
                            .as(line + " / " + name)
                            .isTrue();
                }
            }
        }
    }
}
