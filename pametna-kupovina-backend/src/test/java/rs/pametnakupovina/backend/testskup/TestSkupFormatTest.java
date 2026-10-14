package rs.pametnakupovina.backend.testskup;

import org.junit.jupiter.api.Test;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListTextParser;
import rs.pametnakupovina.backend.testskup.TestSkup.Ocena;
import rs.pametnakupovina.backend.testskup.TestSkup.Stavka;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The test set runs only against a copy of the database, so CI at least keeps
 * the file readable: every line is one shopping-list item and the grading
 * rules say what the tester's report called right and wrong.
 */
class TestSkupFormatTest {

    private final TestSkup skup = TestSkup.load("RS", "sr");

    private Stavka stavka(String id) {
        return skup.stavke().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void theSetHasAboutSixtyUniqueLinesAndTwoCities() {
        assertThat(skup.stavke()).hasSizeGreaterThanOrEqualTo(60);
        assertThat(new HashSet<>(skup.stavke().stream().map(Stavka::id).toList()))
                .hasSameSizeAs(skup.stavke());
        assertThat(skup.lokacije()).extracting(TestSkup.Lokacija::grad)
                .containsExactly("Beograd", "Novi Sad");
        assertThat(skup.stavke()).allSatisfy(s -> assertThat(s.opis()).isNotBlank());
    }

    @Test
    void everyLineIsOneShoppingListItem() {
        ShoppingListTextParser parser = new ShoppingListTextParser();
        assertThat(skup.stavke()).allSatisfy(s ->
                assertThat(parser.parse(s.tekst()).items()).as(s.id() + " " + s.tekst()).hasSize(1));
    }

    @Test
    void theSetCoversEveryKindOfLine() {
        Map<String, Long> perSource = skup.stavke().stream()
                .collect(Collectors.groupingBy(Stavka::izvor, Collectors.counting()));
        assertThat(perSource).containsKeys("IZVESTAJ", "CESTO", "KVACICE", "SLENG", "KOLICINA");
        assertThat(perSource.get("IZVESTAJ")).isGreaterThanOrEqualTo(20);
    }

    @Test
    void theTestersWrongPicksAreGradedWrong() {
        assertThat(stavka("S01").oceni("Ice Coffee Freeze Grand 16g", BigDecimal.ONE)).isEqualTo(Ocena.POGRESNO);
        assertThat(stavka("S01").oceni("Grand kafa 200g", BigDecimal.valueOf(13))).isEqualTo(Ocena.POGRESNO);
        assertThat(stavka("S01").oceni("PROIZVOD NA BAZI KAFE 200G", BigDecimal.ONE)).isEqualTo(Ocena.POGRESNO);
        assertThat(stavka("S02").oceni("500G CRVENA BANANA", BigDecimal.valueOf(3))).isEqualTo(Ocena.POGRESNO);
        assertThat(stavka("S03").oceni("Secer u prahu 500g", BigDecimal.ONE)).isEqualTo(Ocena.POGRESNO);
        assertThat(stavka("S04").oceni("Prašak za pecivo Dr. Oetker 12g", BigDecimal.ONE)).isEqualTo(Ocena.POGRESNO);
        assertThat(stavka("S06").oceni(null, null)).isEqualTo(Ocena.NEMA);
    }

    @Test
    void whatAPersonWouldBuyIsGradedRight() {
        assertThat(stavka("S01").oceni("Grand kafa 200g", BigDecimal.ONE)).isEqualTo(Ocena.ISPRAVNO);
        assertThat(stavka("S02").oceni("Banane", new BigDecimal("1.5"))).isEqualTo(Ocena.ISPRAVNO);
        assertThat(stavka("S03").oceni("Šećer kristal 1kg", BigDecimal.ONE)).isEqualTo(Ocena.ISPRAVNO);
        assertThat(stavka("S04").oceni("Merix prašak za veš 3kg", BigDecimal.ONE)).isEqualTo(Ocena.ISPRAVNO);
        assertThat(stavka("S06").oceni("Toalet papir Maxi 1/1      ", BigDecimal.ONE)).isEqualTo(Ocena.ISPRAVNO);
        assertThat(stavka("S09").oceni("BRAŠNO T-400 1KG", BigDecimal.ONE)).isEqualTo(Ocena.ISPRAVNO);
        assertThat(stavka("S11").oceni("Juneci cevap 500g", BigDecimal.valueOf(2))).isEqualTo(Ocena.ISPRAVNO);
        assertThat(stavka("S14").oceni("Ulje suncokretovo Maxi 1l", BigDecimal.ONE)).isEqualTo(Ocena.ISPRAVNO);
        assertThat(stavka("S14").oceni("Ulje maslinovo extra vergine WM 1l", BigDecimal.ONE)).isEqualTo(Ocena.POGRESNO);
    }
}
