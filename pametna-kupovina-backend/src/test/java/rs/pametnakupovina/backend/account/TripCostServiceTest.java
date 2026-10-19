package rs.pametnakupovina.backend.account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.server.ResponseStatusException;
import rs.pametnakupovina.backend.security.DeviceCaller;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

class TripCostServiceTest {

    private static final DeviceCaller CALLER = new DeviceCaller(7L, 1L, "hash");

    private final AccountRepository repository = mock(AccountRepository.class);
    private final TripCostService service = new TripCostService(repository);

    /** Isti broj znači isto u evrima: plafon je umnožak cene tržišta. */
    @Test
    void theCeilingFollowsTheMarketsOwnValueInItsCurrency() {
        when(repository.tripCosts(7L)).thenReturn(new TripCostService.Stored(
                null, null, new BigDecimal("8.00"), new BigDecimal("1.00"), "EUR", 2));

        var costs = service.get(CALLER);

        assertThat(costs.maxValuePerHour()).isEqualByComparingTo("200");
        assertThat(costs.maxCostPerStop()).isEqualByComparingTo("25");
        assertThat(costs.currency()).isEqualTo("EUR");
        assertThatThrownBy(() -> service.save(CALLER,
                new TripCostService.TripCostRequest(new BigDecimal("200.01"), null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("od 0 do 200 EUR");
        verify(repository, never()).saveTripCosts(anyLong(), any(), any());
    }

    /** Kratak broj sa ogromnim eksponentom se odbija odmah, a ne zaokružuje sekundama. */
    @Test
    @Timeout(2)
    void aHugeOrTinyExponentIsRefusedBeforeAnyRounding() {
        when(repository.tripCosts(7L)).thenReturn(new TripCostService.Stored(
                null, null, new BigDecimal("400.00"), new BigDecimal("80.00"), "RSD", 2));

        for (String amount : new String[]{"1e10000000", "1e-10000000", "1e999999999"}) {
            assertThatThrownBy(() -> service.save(CALLER,
                    new TripCostService.TripCostRequest(new BigDecimal(amount), null)))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("od 0 do 10000 RSD");
        }
        verify(repository, never()).saveTripCosts(anyLong(), any(), any());
    }

    @Test
    void anAmountIsRoundedToTheCurrencyAndTheMarketsOwnIsNotStored() {
        when(repository.tripCosts(7L)).thenReturn(new TripCostService.Stored(
                null, null, new BigDecimal("400.00"), new BigDecimal("80.00"), "RSD", 2));

        service.save(CALLER, new TripCostService.TripCostRequest(
                new BigDecimal("650.555"), new BigDecimal("80")));

        verify(repository).saveTripCosts(eq(7L), eq(new BigDecimal("650.56")), isNull());
    }
}
