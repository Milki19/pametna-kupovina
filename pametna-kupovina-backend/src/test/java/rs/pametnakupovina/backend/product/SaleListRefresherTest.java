package rs.pametnakupovina.backend.product;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** When the list of sales is read again without a price change. */
class SaleListRefresherTest {

    private static final ZoneId BELGRADE = ZoneId.of("Europe/Belgrade");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Test
    void aNewDayInAMarketMakesTheListDueAtOnce() {
        // 23:55 and 00:01 in Belgrade (summer time, UTC+2).
        Instant beforeMidnight = Instant.parse("2026-10-13T21:55:00Z");
        Instant afterMidnight = Instant.parse("2026-10-13T22:01:00Z");

        assertThat(SaleListRefresher.due(beforeMidnight, afterMidnight, List.of(BELGRADE))).isTrue();
        // Still the same day in New York.
        assertThat(SaleListRefresher.due(beforeMidnight, afterMidnight, List.of(NEW_YORK))).isFalse();
        assertThat(SaleListRefresher.due(beforeMidnight, afterMidnight, List.of(NEW_YORK, BELGRADE))).isTrue();
    }

    @Test
    void theSameDayWaitsForTheHour() {
        Instant read = Instant.parse("2026-10-14T08:00:00Z");

        assertThat(SaleListRefresher.due(read, read.plusSeconds(59 * 60), List.of(BELGRADE))).isFalse();
        assertThat(SaleListRefresher.due(read, read.plusSeconds(61 * 60), List.of(BELGRADE))).isTrue();
        assertThat(SaleListRefresher.due(read, read.plusSeconds(61 * 60), List.of())).isTrue();
    }
}
