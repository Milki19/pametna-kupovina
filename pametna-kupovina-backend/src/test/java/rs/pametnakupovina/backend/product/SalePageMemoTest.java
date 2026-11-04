package rs.pametnakupovina.backend.product;

import org.junit.jupiter.api.Test;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;
import rs.pametnakupovina.backend.privacy.PreciseLocation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A sales page is read once for a place the app rounded; an exact place is never kept. */
class SalePageMemoTest {

    private final SaleRepository repository = mock(SaleRepository.class);
    private final CatalogMemo memo = new CatalogMemo(true, java.time.Clock.systemUTC());
    private final SaleService service = new SaleService(repository, new ProductNameNormalizer(), memo);

    SalePageMemoTest() {
        when(repository.findSales(any(), any(), anyInt(), anyLong())).thenReturn(List.of());
        when(repository.findCategories(any())).thenReturn(List.of());
        when(repository.findRetailers(any())).thenReturn(List.of());
    }

    private SalePage page(double latitude, double longitude) {
        return service.find(new PreciseLocation(latitude, longitude), 3000, null, null, null,
                1, SaleSort.DISCOUNT, 0, 20);
    }

    @Test
    void theSamePageForTheSameRoughPlaceIsReadOnce() {
        page(44.81, 20.46);
        page(44.81, 20.46);
        page(44.82, 20.46);

        verify(repository, times(2)).findSales(any(), any(), anyInt(), anyLong());
    }

    @Test
    void anExactPlaceIsReadEveryTime() {
        page(44.8125, 20.4612);
        page(44.8125, 20.4612);

        verify(repository, times(2)).findSales(any(), any(), anyInt(), anyLong());
        assertThat(memo.size()).isZero();
    }

    @Test
    void aNewReadOfTheSalesListStartsOverButKeepsThePlans() {
        page(44.81, 20.46);
        memo.rememberOffers(List.of("planOffers", 1L), () -> "ponude");
        memo.salesChanged();
        page(44.81, 20.46);

        verify(repository, times(2)).findSales(any(), any(), anyInt(), anyLong());
        assertThat(memo.<String>rememberOffers(List.of("planOffers", 1L), () -> "novo")).isEqualTo("ponude");
    }

    @Test
    void twoDecimalsAreRough() {
        assertThat(SaleService.roughly(44.81)).isTrue();
        assertThat(SaleService.roughly(-0.07)).isTrue();
        assertThat(SaleService.roughly(44.8125)).isFalse();
    }
}
