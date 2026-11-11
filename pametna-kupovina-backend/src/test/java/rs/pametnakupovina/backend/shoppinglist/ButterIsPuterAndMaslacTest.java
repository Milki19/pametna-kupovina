package rs.pametnakupovina.backend.shoppinglist;

import org.junit.jupiter.api.Test;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;
import rs.pametnakupovina.backend.matching.WholeWords;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "puter" found only the butter named "puter", never "maslac" (sitnica F3
 * posle šestog testa, 11.11.2026). The words a shop writes for a list's
 * word come from app.same_word (V168), here as SameWords gives them.
 */
class ButterIsPuterAndMaslacTest {

    private final ProductNameNormalizer normalizer = new ProductNameNormalizer();

    private static final Map<String, String> SAME_WORDS = Map.of(
            "puter", WholeWords.anyFormOf(List.of("puter", "maslac")),
            "puterom", WholeWords.anyFormOf(List.of("puter", "maslac")),
            "maslac", WholeWords.anyFormOf(List.of("maslac", "puter"))
    );

    private boolean accepts(String line, String name) {
        String pattern = WantedWords.pattern(line, null, normalizer, SAME_WORDS);
        assertThat(pattern).as(line).isNotNull();
        return Pattern.compile(pattern.replace("\\m", "\\b").replace("\\M", "\\b"))
                .matcher(normalizer.normalize(name)).find();
    }

    @Test
    void puterIsMaslacTooAndMaslacPuter() {
        assertThat(accepts("puter", "Maslac Moja kravica 250 g")).isTrue();
        assertThat(accepts("puter", "Puter Dukat 200 g")).isTrue();
        assertThat(accepts("maslac", "Puter Dukat 200 g")).isTrue();
        assertThat(accepts("kikiriki puter", "Kikiriki maslac 340 g")).isTrue();
    }

    @Test
    void theWordsKeepTheirFormsButStayWhole() {
        assertThat(accepts("puter", "Keks sa puterom 200 g")).isFalse();
        assertThat(accepts("puter", "Puterica salata 1 kom")).isFalse();
        assertThat(accepts("maslac", "Maslinovo ulje 1 l")).isFalse();
    }

    @Test
    void bothWordsAreLookedUpTogether() {
        String pattern = WantedWords.pattern("puter", null, normalizer, SAME_WORDS);

        assertThat(WantedWords.hint(pattern)).isEqualTo("(puter|maslac)");
    }

    @Test
    void withoutTheDictionaryTheWordIsItsOwn() {
        String pattern = WantedWords.pattern("puter", null, normalizer);

        assertThat(WantedWords.hint(pattern)).isEqualTo("puter");
    }

    @Test
    void aWordBesideTheKindIsReadTheSameWay() {
        String pattern = WantedWords.pattern("sir sa puterom", "sir", normalizer, SAME_WORDS);

        assertThat(pattern).contains("(puter|maslac)");
    }
}
