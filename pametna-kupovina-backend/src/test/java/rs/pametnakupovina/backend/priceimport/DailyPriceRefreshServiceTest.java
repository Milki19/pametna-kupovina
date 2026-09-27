package rs.pametnakupovina.backend.priceimport;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class DailyPriceRefreshServiceTest {
    private final LocalDate today = LocalDate.of(2026,9,11);
    @Test void onlyCleanFreshResultsCount() {
        assertThat(ChainRefreshOutcome.classify("SUCCEEDED",today,today)).isEqualTo("SUCCEEDED");
        assertThat(ChainRefreshOutcome.classify("SUCCEEDED",today.minusDays(1),today)).isEqualTo("SUCCEEDED");
        assertThat(ChainRefreshOutcome.classify("SUCCEEDED",today.minusDays(2),today)).isEqualTo("WARNING");
        assertThat(ChainRefreshOutcome.classify("SUCCEEDED",today.plusDays(1),today)).isEqualTo("WARNING");
        assertThat(ChainRefreshOutcome.classify("SUCCEEDED_WITH_ERRORS",today,today)).isEqualTo("WARNING");
        assertThat(ChainRefreshOutcome.classify("FAILED",today,today)).isEqualTo("FAILED");
    }
    @Test void chainWideListsWarnButDoNotFailTheDay() {
        assertThat(ChainRefreshOutcome.classifyChainWide("SUCCEEDED")).isEqualTo("SUCCEEDED");
        assertThat(ChainRefreshOutcome.classifyChainWide("SUCCEEDED_WITH_ERRORS")).isEqualTo("WARNING");
        assertThat(ChainRefreshOutcome.classifyChainWide("FAILED")).isEqualTo("FAILED");
        assertThat(ChainRefreshOutcome.failsTheDay(false,"FAILED")).isFalse();
        assertThat(ChainRefreshOutcome.warnsTheDay(false,"FAILED")).isTrue();
        assertThat(ChainRefreshOutcome.failsTheDay(true,"FAILED")).isTrue();
        assertThat(ChainRefreshOutcome.warnsTheDay(false,"SUCCEEDED")).isFalse();
    }
    @Test void countsDistinctAdjacentDaysAndAllowsTodaysRunToBePending() {
        assertThat(DailyPriceRefreshService.consecutiveDays(Set.of(today,today.minusDays(1),today.minusDays(3)),today)).isEqualTo(2);
        assertThat(DailyPriceRefreshService.consecutiveDays(Set.of(today.minusDays(1),today.minusDays(2)),today)).isEqualTo(2);
        assertThat(DailyPriceRefreshService.consecutiveDays(Set.of(today.minusDays(2)),today)).isZero();
        assertThat(DailyPriceRefreshService.consecutiveDays(Set.of(today),today)).isEqualTo(1);
    }
}
