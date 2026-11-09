package rs.pametnakupovina.backend.testskup;

import org.junit.jupiter.api.Test;
import rs.pametnakupovina.backend.testskup.TestSkup.Ocena;
import rs.pametnakupovina.backend.testskup.TestSkup.Stavka;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The visible part of the set built from the catalogue
 * ({@code test-skup/RS-sr-spiskovi.tsv}): 40 real-looking lists, 400 lines,
 * written in both scripts, with pieces instead of kilograms and the variant
 * words the sixth test tripped over. These tests keep the grading honest:
 * the product a person buys passes, the one the tester saw the plan pick
 * fails. The hidden 100 lines never enter the repository.
 */
class TestSkupKatalogTest {

    private final TestSkup skup = TestSkup.load("RS", "sr", "test-skup/RS-sr-spiskovi.tsv");

    private Stavka stavka(String id) {
        return skup.stavke().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void fortyListsOfTenLinesAcrossTheCountry() {
        assertThat(skup.stavke()).hasSize(400);
        Map<String, Long> perList = skup.stavke().stream()
                .collect(Collectors.groupingBy(Stavka::spisak, Collectors.counting()));
        assertThat(perList).hasSize(40);
        assertThat(perList.values()).allMatch(n -> n == 10);
        assertThat(skup.lokacije()).hasSizeGreaterThanOrEqualTo(10);
        assertThat(skup.stavke()).allSatisfy(s -> {
            assertThat(s.kolicina()).as(s.id()).isNotNull();
            assertThat(s.opis()).as(s.id()).isNotBlank();
        });
    }

    @Test
    void theLinesAreWrittenTheWayPeopleWriteThem() {
        List<String> texts = skup.stavke().stream().map(Stavka::tekst).toList();
        assertThat(texts.stream().filter(t -> t.matches(".*\\p{IsCyrillic}.*")).count()).isGreaterThanOrEqualTo(30);
        assertThat(texts.stream().filter(t -> t.matches(".*[čćšžđ].*")).count()).isGreaterThanOrEqualTo(40);
        assertThat(skup.stavke().stream().filter(s -> s.kolicina().jedinica().equals("kom")
                && s.kolicina().ukupno().compareTo(BigDecimal.ONE) > 0).count()).isGreaterThanOrEqualTo(60);
    }

    @Test
    void aPieceIsNotAKilogram() {
        // "limun 3" is three lemons: 3 kg (the plan's 389,97 RSD) is too much.
        assertThat(stavka("L003").kolicina()).isEqualTo(TestSkup.Kolicina.parse("3 kom"));
        assertThat(stavka("L003").oceni("Limun rinfuz kg", BigDecimal.valueOf(3))).isEqualTo(Ocena.POGRESNO);
        assertThat(stavka("L003").oceni("Limun rinfuz kg", new BigDecimal("0.3"))).isEqualTo(Ocena.ISPRAVNO);
        assertThat(stavka("L005").oceni("Banane kg", new BigDecimal("0.7"))).isEqualTo(Ocena.ISPRAVNO);
        assertThat(stavka("L005").oceni("Banane kg", BigDecimal.valueOf(4))).isEqualTo(Ocena.POGRESNO);
        assertThat(stavka("L004").oceni("Avokado komad", BigDecimal.valueOf(2))).isEqualTo(Ocena.ISPRAVNO);
        assertThat(stavka("L004").oceni("Guakamole 200 g", BigDecimal.ONE)).isEqualTo(Ocena.POGRESNO);
    }

    @Test
    void aKindIsAWholeWordNeverTheStartOfOne() {
        assertThat(stavka("L008").oceni("Kinoa bela 500 g", BigDecimal.ONE)).isEqualTo(Ocena.ISPRAVNO);
        assertThat(stavka("L008").oceni("Kinder Cards 128 g", BigDecimal.ONE)).isEqualTo(Ocena.POGRESNO);
        assertThat(stavka("L297").oceni("Quinoa 400g", BigDecimal.ONE)).isEqualTo(Ocena.ISPRAVNO);
        assertThat(stavka("L061").oceni("Tofu natur 200 g", BigDecimal.ONE)).isEqualTo(Ocena.ISPRAVNO);
        assertThat(stavka("L061").oceni("Toffifee 125 g", BigDecimal.ONE)).isEqualTo(Ocena.POGRESNO);
        assertThat(stavka("L062").oceni("Humus od leblebija 200 g", BigDecimal.ONE)).isEqualTo(Ocena.ISPRAVNO);
        assertThat(stavka("L062").oceni("Humus zemlja za cveće 20 l", BigDecimal.ONE)).isEqualTo(Ocena.POGRESNO);
    }

    @Test
    void theVariantInTheLineIsNotOptional() {
        assertThat(stavka("L041").oceni("Kapsule Nespresso Ristretto 10 kom", BigDecimal.ONE)).isEqualTo(Ocena.ISPRAVNO);
        assertThat(stavka("L041").oceni("Kafa mlevena Grand 200 g", BigDecimal.ONE)).isEqualTo(Ocena.POGRESNO);
        assertThat(stavka("L301").oceni("Kafa kapsule Nescafe Dolce Gusto Espresso 16 kom", BigDecimal.ONE)).isEqualTo(Ocena.ISPRAVNO);
        assertThat(stavka("L301").oceni("Kapsule kompatibilne sa Nespresso 10 kom", BigDecimal.ONE)).isEqualTo(Ocena.POGRESNO);
        assertThat(stavka("L042").oceni("Kafa u zrnu Lavazza Crema e Aroma 1 kg", BigDecimal.ONE)).isEqualTo(Ocena.ISPRAVNO);
        assertThat(stavka("L042").oceni("Kafa mlevena Bonito 1 kg", BigDecimal.ONE)).isEqualTo(Ocena.POGRESNO);
        assertThat(stavka("L011").oceni("Coca-Cola Zero 2 l", BigDecimal.ONE)).isEqualTo(Ocena.ISPRAVNO);
        assertThat(stavka("L011").oceni("Coca-Cola 2 l", BigDecimal.ONE)).isEqualTo(Ocena.POGRESNO);
        assertThat(stavka("L011").oceni("Sok Next Zero jabuka 1 l Coca-Cola", BigDecimal.ONE)).isEqualTo(Ocena.POGRESNO);
        assertThat(stavka("L126").oceni("Orasi jezgro 200 g", BigDecimal.valueOf(3))).isEqualTo(Ocena.ISPRAVNO);
        assertThat(stavka("L126").oceni("Mešavina orasi i suvo voće 200 g", BigDecimal.ONE)).isEqualTo(Ocena.POGRESNO);
        assertThat(stavka("L043").oceni("Mleko bez laktoze 2,8% 1 l", BigDecimal.ONE)).isEqualTo(Ocena.ISPRAVNO);
        assertThat(stavka("L043").oceni("Mleko 2,8% 1 l", BigDecimal.ONE)).isEqualTo(Ocena.POGRESNO);
        assertThat(stavka("L044").oceni("Oatly ovseni napitak 1 l", BigDecimal.ONE)).isEqualTo(Ocena.ISPRAVNO);
        assertThat(stavka("L044").oceni("Ovsene pahuljice 500 g", BigDecimal.ONE)).isEqualTo(Ocena.POGRESNO);
        assertThat(stavka("L111").oceni("Persil Discs Color 26 kom", BigDecimal.ONE)).isEqualTo(Ocena.ISPRAVNO);
        assertThat(stavka("L111").oceni("Persil prašak 4,5 kg", BigDecimal.ONE)).isEqualTo(Ocena.POGRESNO);
        assertThat(stavka("L384").oceni("Majonez Light Dijamant 295 g", BigDecimal.ONE)).isEqualTo(Ocena.ISPRAVNO);
        assertThat(stavka("L384").oceni("Majonez Dijamant 295 g", BigDecimal.ONE)).isEqualTo(Ocena.POGRESNO);
    }

    @Test
    void oneStoreDoesNotSellFourSeaBassForAFish() {
        assertThat(stavka("L221").oceni("Oslić file smrznuti 1 kg", BigDecimal.ONE)).isEqualTo(Ocena.ISPRAVNO);
        assertThat(stavka("L221").oceni("Brancin svež kg", BigDecimal.valueOf(4))).isEqualTo(Ocena.POGRESNO);
    }

    @Test
    void theBlindLinesTakeTheSameShape() {
        // The hidden part uses B001..B100 and the same columns; the visible part never uses B.
        assertThat(skup.stavke()).allMatch(s -> s.id().matches("L\\d{3}"));
    }
}
