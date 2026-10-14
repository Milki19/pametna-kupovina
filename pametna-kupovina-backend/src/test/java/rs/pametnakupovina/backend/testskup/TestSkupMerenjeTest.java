package rs.pametnakupovina.backend.testskup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import rs.pametnakupovina.backend.product.SaleListRefresher;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the test set against a copy of the real database and writes a report:
 * how many lines got a product in the plan, how many of those are what a
 * person would buy, and how long "Izračunaj" takes for 30 lines. It never
 * runs in CI; {@code infra/test-skup.sh} makes the copy and runs it.
 *
 * <p>Environment: {@code TEST_SKUP_DB_URL}, {@code TEST_SKUP_DB_USERNAME},
 * {@code TEST_SKUP_DB_PASSWORD}; optionally {@code TEST_SKUP_TRZISTE} (RS),
 * {@code TEST_SKUP_JEZIK} (sr), {@code TEST_SKUP_IZLAZ} (target/test-skup)
 * and {@code TEST_SKUP_MIN_PREPOZNATO} / {@code TEST_SKUP_MIN_ISPRAVNO}
 * (percent; the test fails below them).
 */
@SpringBootTest(properties = {
        "price-refresh.daily.enabled=false",
        "price-import.schedule.enabled=false",
        "maxi.price-import.schedule.enabled=false",
        "verified-location-import.schedule.enabled=false",
        "data-quality.monitor.enabled=false",
        "routing.osrm.enabled=false",
        "logging.file.name="
})
@EnabledIfEnvironmentVariable(named = "TEST_SKUP_DB_URL", matches = ".+")
class TestSkupMerenjeTest {

    @DynamicPropertySource
    static void kopijaBaze(DynamicPropertyRegistry registry) {
        String url = System.getenv("TEST_SKUP_DB_URL");
        // Flyway migrates whatever this points at, the way the server does
        // on deploy: that is only ever allowed on a copy.
        String database = url.replaceAll("[?;].*$", "").replaceAll("^.*/", "");
        if (!database.matches(".*(copy|kopija|test).*")) {
            throw new IllegalStateException("Test skup radi samo nad kopijom baze (ime sa copy, kopija ili test), ne nad " + database);
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("TEST_SKUP_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("TEST_SKUP_DB_PASSWORD"));
    }

    /** Its first tick would rebuild the sale list in the middle of a timing. */
    @MockitoBean
    private SaleListRefresher saleListRefresher;

    @Autowired
    private TestSkupMerac merac;

    @Test
    void merenje() throws IOException {
        TestSkup skup = TestSkup.load(env("TEST_SKUP_TRZISTE", "RS"), env("TEST_SKUP_JEZIK", "sr"));
        TestSkupMerac.Ishod ishod = merac.meri(skup, Path.of(env("TEST_SKUP_IZLAZ", "target/test-skup")));
        System.out.println("Izveštaj test skupa: " + ishod.izvestaj().toAbsolutePath());

        minimum("TEST_SKUP_MIN_PREPOZNATO", ishod.prepoznato());
        minimum("TEST_SKUP_MIN_ISPRAVNO", ishod.ispravno());
    }

    private static void minimum(String variable, Map<String, Double> perCity) {
        String limit = System.getenv(variable);
        if (limit != null && !limit.isBlank()) {
            perCity.forEach((grad, actual) -> assertThat(actual).as(variable + " za " + grad)
                    .isGreaterThanOrEqualTo(Double.parseDouble(limit)));
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
