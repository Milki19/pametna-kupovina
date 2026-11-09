package rs.pametnakupovina.backend.shoppinglist;

import org.junit.jupiter.api.Test;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;
import rs.pametnakupovina.backend.matching.WholeWords;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A line's words meet a product's name whole, in their forms, never as the
 * start of another word (šesti test, 6.11.2026: "kinoa" became Kinder Cards).
 */
class WholeWordKindTest {

    private final ProductNameNormalizer normalizer = new ProductNameNormalizer();

    private boolean accepts(String line, String alias, String name) {
        String pattern = WantedWords.pattern(line, alias, normalizer);
        assertThat(pattern).as(line).isNotNull();
        return Pattern.compile(pattern.replace("\\m", "\\b").replace("\\M", "\\b"))
                .matcher(normalizer.normalize(name)).find();
    }

    @Test
    void kinoaIsNoKinder() {
        assertThat(accepts("kinoa", null, "Kinder Cards 128g")).isFalse();
        assertThat(accepts("kinoa", null, "Kinder Bueno 43g")).isFalse();
        assertThat(accepts("kinoa", null, "Kinoa bela 500 g")).isTrue();
        assertThat(accepts("kinoe", null, "Kinoa bela 500 g")).isTrue();
    }

    @Test
    void aWordStillMeetsItsOtherForms() {
        assertThat(accepts("tortilje", null, "Tortilja pšenična 6 kom")).isTrue();
        assertThat(accepts("vlazne maramice", null, "Maramice vlažne baby 72")).isTrue();
        assertThat(accepts("vlazne maramice", null, "Vlažna maramica 1 kom")).isTrue();
        assertThat(accepts("krastavci", null, "Krastavac kornišon 680 g")).isTrue();
        assertThat(accepts("semenke", null, "Semenke suncokreta 200 g")).isTrue();
        assertThat(accepts("losos", null, "Losos dimljeni 100 g")).isTrue();
        assertThat(accepts("feta sir", "sir", "Sir feta 200 g")).isTrue();
        assertThat(accepts("kakao", null, "Kakao prah 100 g")).isTrue();
    }

    @Test
    void aWordIsNoStartOfAnotherWord() {
        assertThat(accepts("feta sir", "sir", "Fetaki 200 g")).isFalse();
        assertThat(accepts("semenke", null, "Semenkasti hleb 400 g")).isFalse();
        assertThat(accepts("losos", null, "Lososovina pašteta")).isFalse();
    }

    @Test
    void theHintIsStillTheStem() {
        assertThat(WantedWords.hint(WantedWords.pattern("kinoa", null, normalizer))).isEqualTo("kin");
        assertThat(WantedWords.hint(WantedWords.pattern("feta sir", "sir", normalizer))).isEqualTo("fet");
    }

    @Test
    void shortAndOddWordsStayWhole() {
        assertThat(WholeWords.formsPattern("sos")).isEqualTo("\\msos\\M");
        assertThat(WholeWords.formsPattern("t500")).isEqualTo("\\mt500\\M");
        assertThat(WholeWords.formsPattern("kinoa")).startsWith("\\mkin(?:|");
    }

    @Test
    void aLongLineStillFitsItsColumn() {
        String pattern = WantedWords.pattern(
                "organske integralne pirincane palacinke bez glutena kokosove",
                null, normalizer);
        assertThat(pattern).as(String.valueOf(pattern == null ? null : pattern.length())).isNotNull();
    }
}
