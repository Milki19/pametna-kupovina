package rs.pametnakupovina.backend.receipt;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import rs.pametnakupovina.backend.market.Market;
import rs.pametnakupovina.backend.market.TestMarkets;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReceiptReadersTest {

    @Test
    void readsEachMarketsReceiptsWithThatMarketsReader() {
        ReceiptReader serbia = mock(ReceiptReader.class);
        when(serbia.marketCode()).thenReturn("RS");

        assertThat(new ReceiptReaders(List.of(serbia)).forMarket(TestMarkets.serbia()))
                .isSameAs(serbia);
    }

    @Test
    void refusesReceiptsFromAMarketWithoutAReader() {
        Market serbia = TestMarkets.serbia();
        Market croatia = new Market(2, "HR", "Hrvatska", "EUR", 2, "hr-HR", "hr",
                java.time.ZoneId.of("Europe/Zagreb"), serbia.travelCostPerKm(),
                serbia.valuePerHour(), serbia.costPerStop());

        assertThatThrownBy(() -> new ReceiptReaders(List.of()).forMarket(croatia))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("ove zemlje");
    }
}
