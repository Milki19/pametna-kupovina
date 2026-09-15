package rs.pametnakupovina.backend.priceimport;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class DailyPriceRefreshServiceTest {
    private final LocalDate today = LocalDate.of(2026,9,11);
    @Test void onlyCleanFreshResultsCount() {
        assertThat(DailyPriceRefreshService.classify("SUCCEEDED",today,today)).isEqualTo("SUCCEEDED");
        assertThat(DailyPriceRefreshService.classify("SUCCEEDED",today.minusDays(1),today)).isEqualTo("SUCCEEDED");
        assertThat(DailyPriceRefreshService.classify("SUCCEEDED",today.minusDays(2),today)).isEqualTo("WARNING");
        assertThat(DailyPriceRefreshService.classify("SUCCEEDED",today.plusDays(1),today)).isEqualTo("WARNING");
        assertThat(DailyPriceRefreshService.classify("SUCCEEDED_WITH_ERRORS",today,today)).isEqualTo("WARNING");
        assertThat(DailyPriceRefreshService.classify("FAILED",today,today)).isEqualTo("FAILED");
    }
    @Test void chainWideListsWarnButDoNotFailTheDay() {
        assertThat(DailyPriceRefreshService.classifyExtra("SUCCEEDED")).isEqualTo("SUCCEEDED");
        assertThat(DailyPriceRefreshService.classifyExtra("SUCCEEDED_WITH_ERRORS")).isEqualTo("WARNING");
        assertThat(DailyPriceRefreshService.classifyExtra("FAILED")).isEqualTo("FAILED");
        assertThat(DailyPriceRefreshService.failsTheDay("METRO","FAILED")).isFalse();
        assertThat(DailyPriceRefreshService.warnsTheDay("METRO","FAILED")).isTrue();
        assertThat(DailyPriceRefreshService.failsTheDay("LIDL","FAILED")).isTrue();
        assertThat(DailyPriceRefreshService.warnsTheDay("VEROPOULOS","SUCCEEDED")).isFalse();
        assertThat(DailyPriceRefreshService.EXTRA).contains("MAXI_CATALOG", "METRO", "VEROPOULOS");
    }
    @Test void countsDistinctAdjacentDaysAndAllowsTodaysRunToBePending() {
        assertThat(DailyPriceRefreshService.consecutiveDays(Set.of(today,today.minusDays(1),today.minusDays(3)),today)).isEqualTo(2);
        assertThat(DailyPriceRefreshService.consecutiveDays(Set.of(today.minusDays(1),today.minusDays(2)),today)).isEqualTo(2);
        assertThat(DailyPriceRefreshService.consecutiveDays(Set.of(today.minusDays(2)),today)).isZero();
        assertThat(DailyPriceRefreshService.consecutiveDays(Set.of(today),today)).isEqualTo(1);
    }
}
