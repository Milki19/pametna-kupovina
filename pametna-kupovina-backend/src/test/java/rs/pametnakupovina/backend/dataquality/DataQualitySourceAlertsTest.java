package rs.pametnakupovina.backend.dataquality;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DataQualitySourceAlertsTest {

    private final Instant now = Instant.parse("2026-09-15T02:00:00Z");

    private List<String> neverRun(String sourceType, boolean retailerHasStores) {
        return DataQualityService.sourceAlerts(true, sourceType, retailerHasStores, "NEVER_RUN",
                null, null, 45, 192, null, new BigDecimal("0.6000"), null, now);
    }

    @Test
    void aStoreLocatorThatWasNeverNeededDoesNotRaiseTheAlarm() {
        // Lidl's 86 stores came in another way.
        assertThat(neverRun("STORE_LOCATIONS", true)).isEmpty();
        assertThat(neverRun("STORE_LOCATIONS", false)).contains("NEVER_SUCCEEDED");
        assertThat(neverRun("PRICE_CATALOG", true)).contains("NEVER_SUCCEEDED");
    }
}
