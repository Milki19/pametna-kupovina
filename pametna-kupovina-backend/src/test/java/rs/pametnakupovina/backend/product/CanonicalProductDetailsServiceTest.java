package rs.pametnakupovina.backend.product;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CanonicalProductDetailsServiceTest {

    private final CanonicalProductDetailsRepository repository =
            mock(CanonicalProductDetailsRepository.class);
    private final CanonicalProductDetailsService service =
            new CanonicalProductDetailsService(repository);

    @Test
    void combinesProductLatestOffersAndHistory() {
        LocalDate date = LocalDate.of(2026, 3, 2);
        when(repository.findProduct(1L)).thenReturn(Optional.of(
                new CanonicalProductDetailsRepository.CanonicalProductSummary(
                        1L,
                        "VODA DONAT 1/1-PALANACKI-300",
                        "DONAT",
                        "3838600041300",
                        new BigDecimal("1000"),
                        "ml"
                )
        ));
        CanonicalProductOffer offer = new CanonicalProductOffer(
                10L,
                "EUROPROM",
                "Europrom",
                null,
                null,
                "EUROPROM",
                "Europrom",
                date,
                new BigDecimal("239.00"),
                null,
                new BigDecimal("239.00"),
                null,
                "STORE_FORMAT"
        );
        when(repository.findLatestOffers(1L, date))
                .thenReturn(List.of(offer));
        when(repository.findPriceHistory(1L, date, 30))
                .thenReturn(List.of());

        CanonicalProductDetailsResponse result = service.find(
                1L,
                date,
                30
        ).orElseThrow();

        assertThat(result.canonicalProductId()).isEqualTo(1L);
        assertThat(result.latestPriceDate()).isEqualTo(date);
        assertThat(result.offers()).containsExactly(offer);
        verify(repository).findPriceHistory(1L, date, 30);
    }

    @Test
    void validatesHistoryLimit() {
        assertThatThrownBy(() -> service.find(1L, null, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("historyLimit");
    }
}
