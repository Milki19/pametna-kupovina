package rs.pametnakupovina.backend.priceimport.probe;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate every new chain passes before its prices reach a plan. The samples
 * follow the two header families published on data.gov.rs.
 */
class PriceListProbeServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 18);

    private final PriceListProbeService service = new PriceListProbeService();

    @Test
    void pravilnikPriceListIsReady() throws IOException {
        PriceListProbeReport report = probe(pravilnik(200, "2026-09-18"));

        assertThat(report.verdict()).isEqualTo(PriceListProbeVerdict.READY);
        assertThat(report.rowsRead()).isEqualTo(200);
        assertThat(report.usableShare()).isEqualTo(100);
        assertThat(report.barcodeShare()).isEqualTo(100);
        assertThat(report.newestPriceDate()).isEqualTo(LocalDate.of(2026, 9, 18));
        assertThat(report.priceListNames()).containsExactly("Gomex maloprodaja");
        assertThat(report.findings()).isEmpty();
    }

    @Test
    void aDashOrABracketInTheHeaderIsStillTheSameColumn() throws IOException {
        String header = "Kategorija;Naziv kategorije;Naziv proizvoda;Robna marka;"
                + "Barkod proizvoda (EAN kod);Jedinica mere;Naziv trgovca – formata;"
                + "Datum cenovnika;Redovna cena;Cena po jedinici mere;Snižena cena\n";
        String rows = IntStream.range(0, 150)
                .mapToObj(index -> "1;Mleko;Mleko " + index + ";Imlek;860000000000" + (index % 10)
                        + ";KOM;Gomex maloprodaja;2026-09-18;99,99;99,99;\n")
                .collect(Collectors.joining());

        PriceListProbeReport report = probe(header + rows);

        assertThat(report.verdict()).isEqualTo(PriceListProbeVerdict.READY);
        assertThat(report.missingColumns()).isEmpty();
    }

    @Test
    void theShorterFamilyNeedsAPersonBecauseItHidesTheDayAndTheShop() throws IOException {
        String header = "sifra_proizvoda;barkod;naziv_proizvoda;jedinica_mere;"
                + "prodajna_cena_rsd;jedinicna_cena_rsd;prethodna_cena_rsd\n";
        String rows = IntStream.range(0, 150)
                .mapToObj(index -> index + ";860000000000" + (index % 10) + ";Hleb " + index
                        + ";KOM;89,90;89,90;\n")
                .collect(Collectors.joining());

        PriceListProbeReport report = probe(header + rows);

        assertThat(report.verdict()).isEqualTo(PriceListProbeVerdict.NEEDS_REVIEW);
        assertThat(report.rowsUsable()).isEqualTo(150);
        assertThat(report.findings())
                .anyMatch(finding -> finding.contains("datumom cenovnika"))
                .anyMatch(finding -> finding.contains("naziv formata"));
    }

    @Test
    void aFileWithoutPricesIsRejected() throws IOException {
        String csv = "Naziv proizvoda;Barkod proizvoda;Jedinica mere;Datum cenovnika\n"
                + "Mleko;8600000000001;KOM;2026-09-18\n";

        PriceListProbeReport report = probe(csv);

        assertThat(report.verdict()).isEqualTo(PriceListProbeVerdict.REJECTED);
        assertThat(report.findings().getFirst()).contains("redovna cena");
    }

    @Test
    void lastMonthsPricesDoNotGoLiveOnTheirOwn() throws IOException {
        PriceListProbeReport report = probe(pravilnik(200, "2026-08-20"));

        assertThat(report.verdict()).isEqualTo(PriceListProbeVerdict.NEEDS_REVIEW);
        assertThat(report.findings()).anyMatch(finding -> finding.contains("star 29 dana"));
    }

    @Test
    void unreadablePricesAndMissingBarcodesAreCounted() throws IOException {
        String header = "Naziv proizvoda;Barkod proizvoda;Jedinica mere;"
                + "Naziv trgovca - formata;Datum cenovnika;Redovna cena\n";
        String rows = IntStream.range(0, 100)
                .mapToObj(index -> "Proizvod " + index + ";"
                        + (index % 2 == 0 ? "8600000000001" : "")
                        + ";KOM;Aman;2026-09-18;"
                        + (index % 4 == 0 ? "cena na upit" : "129,90")
                        + "\n")
                .collect(Collectors.joining());

        PriceListProbeReport report = probe(header + rows);

        assertThat(report.priceShare()).isEqualTo(75);
        assertThat(report.barcodeShare()).isEqualTo(50);
        assertThat(report.verdict()).isEqualTo(PriceListProbeVerdict.NEEDS_REVIEW);
        assertThat(report.findings())
                .anyMatch(finding -> finding.contains("Cena je čitljiva u 75%"))
                .anyMatch(finding -> finding.contains("Barkod ima 50%"));
    }

    @Test
    void severalPriceListsInOneFileAreReported() throws IOException {
        String header = "Naziv proizvoda;Barkod proizvoda;Jedinica mere;"
                + "Naziv trgovca - formata;Datum cenovnika;Redovna cena\n";
        String rows = IntStream.range(0, 150)
                .mapToObj(index -> "Proizvod " + index + ";8600000000001;KOM;Univerexport "
                        + (index % 3) + ";2026-09-18;129,90\n")
                .collect(Collectors.joining());

        PriceListProbeReport report = probe(header + rows);

        assertThat(report.priceListNames()).hasSize(3);
        assertThat(report.findings()).anyMatch(finding -> finding.contains("3 cenovnika"));
        assertThat(report.verdict()).isEqualTo(PriceListProbeVerdict.NEEDS_REVIEW);
    }

    @Test
    void aMonthlyListIsCurrentWhileThePortalKeepsReplacingTheFile() throws IOException {
        PriceListProbeReport report = service.probe(
                "Aman d.o.o.",
                new java.io.ByteArrayInputStream(
                        pravilnik(200, "01-09-2026").getBytes(StandardCharsets.UTF_8)
                ),
                TODAY,
                TODAY
        );

        assertThat(report.newestPriceDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(report.verdict()).isEqualTo(PriceListProbeVerdict.READY);
        assertThat(report.findings())
                .anyMatch(finding -> finding.contains("mesečni cenovnik"));
    }

    @Test
    void aFileNobodyHasReplacedForWeeksNeedsAPerson() throws IOException {
        PriceListProbeReport report = service.probe(
                "Gomex DOO",
                new java.io.ByteArrayInputStream(
                        pravilnik(200, "01-09-2026").getBytes(StandardCharsets.UTF_8)
                ),
                TODAY,
                LocalDate.of(2026, 9, 2)
        );

        assertThat(report.verdict()).isEqualTo(PriceListProbeVerdict.NEEDS_REVIEW);
        assertThat(report.findings())
                .anyMatch(finding -> finding.contains("nije osvežen od 2026-09-02"));
    }

    private PriceListProbeReport probe(String csv) throws IOException {
        return service.probe(
                "proba",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                TODAY
        );
    }

    private String pravilnik(int rows, String date) {
        String header = "Kategorija;Naziv kategorije;Naziv proizvoda;Robna marka;"
                + "Barkod proizvoda;Jedinica mere;Naziv trgovca - formata;Datum cenovnika;"
                + "Redovna cena;Cena po jedinici mere;Snižena cena;Stopa PDV\n";

        return header + IntStream.range(0, rows)
                .mapToObj(index -> "1;Mleko, mlečni, mešoviti jaja;Mleko " + index
                        + ";Imlek;860000000000" + (index % 10) + ";KOM;Gomex maloprodaja;"
                        + date + ";99,99;99,99;;20\n")
                .collect(Collectors.joining());
    }
}
