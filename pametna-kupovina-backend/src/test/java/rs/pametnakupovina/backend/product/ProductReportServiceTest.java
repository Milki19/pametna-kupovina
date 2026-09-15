package rs.pametnakupovina.backend.product;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListClientTokenPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductReportServiceTest {

    private final ProductReportRepository repository = mock(ProductReportRepository.class);
    private final ProductReportService service =
            new ProductReportService(repository, new ShoppingListClientTokenPolicy());

    @Test
    void aReportWithAReasonIsKeptForReview() {
        when(repository.productExists(42L)).thenReturn(true);
        when(repository.listingBelongsToProduct(42L, 7L)).thenReturn(true);
        when(repository.insert(eq(42L), eq(7L), eq(ProductReportReason.WRONG_PRICE), eq("Gajba, ne flaša"), any()))
                .thenReturn(3L);

        ProductReportResponse response = service.report(42L, "telefon",
                new ProductReportRequest(ProductReportReason.WRONG_PRICE, "  Gajba, ne flaša ", 7L));

        assertThat(response).isEqualTo(new ProductReportResponse(3L, "NEW"));
    }

    @Test
    void aReasonIsRequiredAndTheListingMustBeThisProduct() {
        when(repository.productExists(42L)).thenReturn(true);

        assertThatThrownBy(() -> service.report(42L, null, new ProductReportRequest(null, "x", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Izaberi");
        assertThatThrownBy(() -> service.report(42L, null,
                new ProductReportRequest(ProductReportReason.NOT_SAME_PRODUCT, null, 9L)))
                .hasMessageContaining("ne pripada");
        assertThatThrownBy(() -> service.report(5L, null,
                new ProductReportRequest(ProductReportReason.OTHER, null, null)))
                .hasMessageContaining("nije pronađen");
        verify(repository, never()).insert(anyLong(), any(), any(), any(), any());
    }

    @Test
    void aReportNeedsNoAccountButTheNoteHasALimit() {
        when(repository.productExists(42L)).thenReturn(true);
        when(repository.insert(eq(42L), isNull(), eq(ProductReportReason.OTHER), isNull(), isNull()))
                .thenReturn(4L);

        assertThat(service.report(42L, null, new ProductReportRequest(ProductReportReason.OTHER, " ", null)).id())
                .isEqualTo(4L);
        assertThatThrownBy(() -> service.report(42L, null,
                new ProductReportRequest(ProductReportReason.OTHER, "a".repeat(501), null)))
                .hasMessageContaining("500");
    }
}
