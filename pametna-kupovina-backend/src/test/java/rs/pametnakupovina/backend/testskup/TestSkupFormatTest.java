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
        // Jedna stanica, 17.10.
        assertThat(stavka("C33").oceni("DIJAMANT ST.MARG.MASLAC 500G", BigDecimal.ONE)).isEqualTo(Ocena.POGRESNO);
        assertThat(stavka("C05").oceni("PIL. JETRA I SRCE 1KG", BigDecimal.ONE)).isEqualTo(Ocena.POGRESNO);
        assertThat(stavka("C05").oceni("PILECA LEDJA OKO 1KG", BigDecimal.ONE)).isEqualTo(Ocena.POGRESNO);
        assertThat(stavka("C19").oceni("SAMPON DECIJI 300ML", BigDecimal.ONE)).isEqualTo(Ocena.POGRESNO);
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
        assertThat(stavka("C05").oceni("PILECI BATAK 1KG", BigDecimal.ONE)).isEqualTo(Ocena.ISPRAVNO);
        assertThat(stavka("C33").oceni("MASLAC 82% 250G", BigDecimal.ONE)).isEqualTo(Ocena.ISPRAVNO);
        assertThat(stavka("S14").oceni("Ulje maslinovo extra vergine WM 1l", BigDecimal.ONE)).isEqualTo(Ocena.POGRESNO);
    }

    @Test
    void realListsKeepTheirListCityAndAmount() {
        TestSkup primer = TestSkup.load("RS", "sr", "test-skup/primer-spiskovi.tsv");

        assertThat(primer.lokacije()).extracting(TestSkup.Lokacija::grad).containsExactly("Beograd", "Niš");
        assertThat(primer.u("Beograd")).extracting(Stavka::id).containsExactly("P01", "P02", "P03", "P04", "P05");
        assertThat(primer.u("Niš")).allSatisfy(s -> assertThat(s.spisak()).isEqualTo("primer-2"));
        Stavka voda = primer.stavke().stream().filter(s -> s.id().equals("P08")).findFirst().orElseThrow();
        assertThat(voda.kolicina()).isEqualTo(new TestSkup.Kolicina(new BigDecimal("9000.0"), "ml"));
        assertThat(primer.stavke()).allSatisfy(s -> assertThat(s.kolicina()).as(s.id()).isNotNull());
    }

    @Test
    void anAmountIsWrittenTheWayAPersonSaysIt() {
        assertThat(TestSkup.Kolicina.parse("1,5 kg")).isEqualTo(new TestSkup.Kolicina(new BigDecimal("1500.0"), "g"));
        assertThat(TestSkup.Kolicina.parse("500g").isti(TestSkup.Kolicina.parse("0,5 kg"))).isTrue();
        assertThat(TestSkup.Kolicina.parse("2 l").isti(TestSkup.Kolicina.parse("2000 ml"))).isTrue();
        assertThat(TestSkup.Kolicina.parse("-")).isEqualTo(TestSkup.Kolicina.parse("1 kom"));
        assertThat(TestSkup.Kolicina.parse("3")).isEqualTo(TestSkup.Kolicina.parse("3 kom"));
        assertThat(TestSkup.Kolicina.parse("6 x 1,5 l").isti(TestSkup.Kolicina.parse("9 l"))).isTrue();
        assertThat(TestSkup.Kolicina.parse("2 l").isti(TestSkup.Kolicina.parse("2 kom"))).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TestSkup.Kolicina.parse("dve kile"))
                .hasMessageContaining("1,5 kg");
    }

    @Test
    void theBlindPartIsReadFromADiskOutsideTheRepository(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws java.io.IOException {
        java.nio.file.Path file = dir.resolve("slepi.tsv");
        java.nio.file.Files.writeString(file, String.join("\t", TestSkup.KOLONE_SPISKOVA) + "\n"
                + "B01\ts1\tKragujevac\tVIBER\tkafa 200g\t200 g\tkafa\tinstant\t2\tMlevena kafa.\n");

        TestSkup slepi = TestSkup.load("RS", "sr", file.toString());

        assertThat(slepi.stavke()).singleElement().satisfies(s -> {
            assertThat(s.grad()).isEqualTo("Kragujevac");
            assertThat(s.kolicina()).isEqualTo(TestSkup.Kolicina.parse("200 g"));
        });
        assertThat(slepi.lokacije()).extracting(TestSkup.Lokacija::grad).containsExactly("Kragujevac");
    }

    @Test
    void aMistakeInTheFileSaysWhereItIs(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws java.io.IOException {
        java.nio.file.Path city = dir.resolve("grad.tsv");
        java.nio.file.Files.writeString(city, String.join("\t", TestSkup.KOLONE_SPISKOVA) + "\n"
                + "B01\ts1\tAtlantida\tVIBER\tkafa\t-\tkafa\t\t\tKafa.\n");
        java.nio.file.Path header = dir.resolve("zaglavlje.tsv");
        java.nio.file.Files.writeString(header, "id\ttekst\n");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TestSkup.load("RS", "sr", city.toString()))
                .hasMessageContaining("B01").hasMessageContaining("Atlantida");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TestSkup.load("RS", "sr", header.toString()))
                .hasMessageContaining("zaglavlje");
    }
}
