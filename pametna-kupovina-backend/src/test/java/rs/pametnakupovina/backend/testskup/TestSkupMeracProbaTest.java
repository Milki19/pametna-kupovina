package rs.pametnakupovina.backend.testskup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The measurement itself only runs against a copy of the real database, so
 * CI walks it once over an empty catalogue: every line goes through step 1
 * and both plans without an error, the report lists none of them in the
 * plan, and nothing of the run is left behind.
 */
@SpringBootTest(properties = "price-import.minimum-snapshot-date=")
@Testcontainers
class TestSkupMeracProbaTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName
                            .parse("ghcr.io/baosystems/postgis:16-3.5")
                            .asCompatibleSubstituteFor("postgres")
            )
                    .withDatabaseName("pametna_kupovina_test")
                    .withUsername("test")
                    .withPassword("test");

    @Autowired
    private TestSkupMerac merac;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void anEmptyCatalogueGivesAFullReportOfNothingFound(@TempDir Path dir) throws IOException {
        // Only the loose lines: the 400 catalogue lines in 13 cities would slow CI down and prove nothing more here.
        TestSkup skup = TestSkup.load("RS", "sr", "test-skup/RS-sr.tsv");

        TestSkupMerac.Ishod ishod = merac.meri(skup, dir);

        String report = Files.readString(ishod.izvestaj());
        int beograd = skup.u("Beograd").size();
        assertThat(report).contains("| Beograd | " + beograd + " | ")
                .contains("| " + "0/" + beograd + " (0%) |")
                .contains("| Novi Sad | " + skup.u("Novi Sad").size() + " | ");
        skup.stavke().forEach(stavka -> assertThat(report).contains("| " + stavka.id() + " |"));
        assertThat(ishod.prepoznato()).containsEntry("Beograd", 0.0).containsEntry("Novi Sad", 0.0);
        // Kinds come with the migrations, so step 1 reads lines even without a single product.
        assertThat(ishod.korak1()).containsKeys("Beograd", "Novi Sad");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM app.shopping_list WHERE active").query(Long.class).single())
                .isZero();
    }

    /** The blind part's report is only totals: no line, id or product of it may be read there. */
    @Test
    void theBlindReportShowsNoLine(@TempDir Path dir) throws IOException {
        TestSkup skup = TestSkup.load("RS", "sr", "test-skup/primer-spiskovi.tsv");

        TestSkupMerac.Ishod ishod = merac.meri(skup, dir, true);

        String report = Files.readString(ishod.izvestaj());
        assertThat(report).contains("slepi deo").contains("| Niš | ");
        skup.stavke().forEach(stavka -> assertThat(report).doesNotContain(stavka.id()).doesNotContain(stavka.tekst()));
        try (var files = Files.list(dir)) {
            assertThat(files).containsExactly(ishod.izvestaj());
        }
        assertThat(ishod.citanje()).isNotNull();
    }
}
