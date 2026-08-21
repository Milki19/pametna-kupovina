package rs.pametnakupovina.backend.product;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CanonicalProductDetailsResponse(
        Long canonicalProductId,
        String name,
        String brand,
        String barcode,
        BigDecimal quantityValue,
        String baseUnit,
        LocalDate requestedDate,
        LocalDate latestPriceDate,
        List<CanonicalProductOffer> offers,
        List<CanonicalProductPricePoint> priceHistory
) {
    public CanonicalProductDetailsResponse {
        offers = List.copyOf(offers);
        priceHistory = List.copyOf(priceHistory);
    }
}
