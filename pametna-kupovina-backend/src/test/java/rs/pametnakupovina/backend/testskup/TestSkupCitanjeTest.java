package rs.pametnakupovina.backend.testskup;

import org.junit.jupiter.api.Test;
import rs.pametnakupovina.backend.testskup.TestSkup.Ocena;
import rs.pametnakupovina.backend.testskup.TestSkup.Stavka;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The part of the measurement CI can run without a database: how the parser
 * reads the amount of every line in the set that says what it asks for.
 * The share may not fall below {@code test-skup/pragovi.properties}; when a
 * change reads more lines right, the threshold goes up with it.
 */
class TestSkupCitanjeTest {

    @Test
    void theParserReadsAtLeastAsManyAmountsAsBefore() throws IOException {
        List<Stavka> stavke = new java.util.ArrayList<>(TestSkup.load("RS", "sr").stavke());
        stavke.addAll(TestSkup.load("RS", "sr", "test-skup/primer-spiskovi.tsv").stavke());
        List<Stavka> sKolicinom = stavke.stream().filter(s -> s.kolicina() != null).toList();
        List<String> pogresno = sKolicinom.stream()
                .filter(s -> TestSkupMerac.Citanje.of(s).ocena() != Ocena.ISPRAVNO)
                .map(s -> s.id() + " „" + s.tekst() + "“: " + TestSkupMerac.Citanje.of(s).procitano() + ", treba " + s.kolicina())
                .toList();
        double procenat = 100.0 * (sKolicinom.size() - pogresno.size()) / sKolicinom.size();
        System.out.printf("Čitanje količine: %d/%d (%.0f%%)%n%s%n", sKolicinom.size() - pogresno.size(), sKolicinom.size(),
                procenat, pogresno.stream().map(p -> "  ✗ " + p).collect(Collectors.joining("\n")));

        assertThat(procenat).as("Čitanje količine palo; pogrešno: " + pogresno)
                .isGreaterThanOrEqualTo(Double.parseDouble(prag("citanje.min")));
    }

    private static String prag(String key) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = TestSkupCitanjeTest.class.getClassLoader().getResourceAsStream("test-skup/pragovi.properties")) {
            properties.load(in);
        }
        return properties.getProperty(key);
    }
}
