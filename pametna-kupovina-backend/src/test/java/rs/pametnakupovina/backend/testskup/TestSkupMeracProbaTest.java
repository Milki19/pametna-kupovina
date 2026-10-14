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
 * and both plans without an error, the report lists all of them as not
 * found, and nothing of the run is left behind.
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
        TestSkup skup = TestSkup.load("RS", "sr");

        TestSkupMerac.Ishod ishod = merac.meri(skup, dir);

        String report = Files.readString(ishod.izvestaj());
        assertThat(report).contains("| Beograd | 0/" + skup.stavke().size())
                .contains("| Novi Sad | 0/" + skup.stavke().size());
        skup.stavke().forEach(stavka -> assertThat(report).contains("| " + stavka.id() + " |"));
        assertThat(ishod.prepoznato()).containsEntry("Beograd", 0.0).containsEntry("Novi Sad", 0.0);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM app.shopping_list WHERE active").query(Long.class).single())
                .isZero();
    }
}
