package rs.pametnakupovina.backend.matching;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductNameNormalizerTest {

    private final ProductNameNormalizer normalizer =
            new ProductNameNormalizer();

    @Test
    void cyrillicAndLatinNamesProduceTheSameValue() {
        String latin = normalizer.normalize(
                "Čokoladno mleko 1 L"
        );

        String cyrillic = normalizer.normalize(
                "Чоколадно млеко 1 л"
        );

        assertThat(latin)
                .isEqualTo("cokoladno mleko 1 l")
                .isEqualTo(cyrillic);
    }

    @Test
    void removesPunctuationAndSeparatesNumbersFromUnits() {
        String result = normalizer.normalize(
                "  MLEKO---ČOKOLADNO!!! 2×500g  "
        );

        assertThat(result)
                .isEqualTo("mleko cokoladno 2 x 500 g");
    }

    @Test
    void normalizesSerbianSpecialLetters() {
        String latin = normalizer.normalize(
                "Đački džem, njoke i ljutenica"
        );

        String cyrillic = normalizer.normalize(
                "Ђачки џем, њоке и љутеница"
        );

        assertThat(latin)
                .isEqualTo("djacki dzem njoke i ljutenica")
                .isEqualTo(cyrillic);
    }

    @Test
    void nullAndBlankValuesBecomeEmpty() {
        assertThat(normalizer.normalize(null)).isEmpty();
        assertThat(normalizer.normalize("   ")).isEmpty();
        assertThat(normalizer.normalize("---")).isEmpty();
    }
}
