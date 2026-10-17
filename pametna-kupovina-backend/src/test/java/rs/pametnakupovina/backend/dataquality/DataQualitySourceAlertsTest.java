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

    private DataQualityReport.SourceQuality source(long id, String retailer, String health, String... alerts) {
        return new DataQualityReport.SourceQuality(id, retailer, "SOURCE_" + id, "PRICE_CATALOG", true,
                health, "SUCCEEDED", now, now, null, null, null, null, 48, null, null, 0, 0,
                null, null, null, List.of(alerts));
    }

    @Test
    void aChainsListThatAnotherOfItsSourcesCarriesOnlyWarns() {
        // Maxi's store files refused since 12.10., Delhaize's list in each day.
        var storeFiles = source(6, "MAXI", "CRITICAL", "SOURCE_DATA_STALE");
        var chainList = source(10, "MAXI", "HEALTHY");
        var otherChain = source(29, "EURO_SA_M", "CRITICAL", "SOURCE_DATA_STALE");

        assertThat(DataQualityService.coveredByOtherSources(List.of(storeFiles, chainList, otherChain)))
                .extracting(DataQualityReport.SourceQuality::health, DataQualityReport.SourceQuality::alerts)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("WARNING",
                                List.of("SOURCE_DATA_STALE", "COVERED_BY_OTHER_SOURCE")),
                        org.assertj.core.groups.Tuple.tuple("HEALTHY", List.of()),
                        org.assertj.core.groups.Tuple.tuple("CRITICAL", List.of("SOURCE_DATA_STALE"))
                );
    }

    @Test
    void aFailedRunOfAChainWithoutItsOwnLimitIsCriticalOnlyOnceItsListIsOld() {
        // Euro Ša M failed at 07:00 on 17.10.; its list of 16.10. still held.
        List<String> failedToday = DataQualityService.sourceAlerts(true, "PRICE_CATALOG", false, "FAILED",
                now.minusSeconds(24 * 3600L), 9730, null, null, null, new BigDecimal("0.6000"), null, now);
        assertThat(failedToday).containsExactly("LAST_RUN_FAILED");
        assertThat(DataQualityService.health(true, failedToday, false)).isEqualTo("WARNING");
        // A core chain with its own limit is critical on the first failure.
        assertThat(DataQualityService.health(true, failedToday, true)).isEqualTo("CRITICAL");

        List<String> failedThreeDays = DataQualityService.sourceAlerts(true, "PRICE_CATALOG", false, "FAILED",
                now.minusSeconds(72 * 3600L), 9730, null, null, null, new BigDecimal("0.6000"), null, now);
        assertThat(failedThreeDays).containsExactly("LAST_RUN_FAILED", "SOURCE_DATA_STALE");
        assertThat(DataQualityService.health(true, failedThreeDays, false)).isEqualTo("CRITICAL");
    }
}
