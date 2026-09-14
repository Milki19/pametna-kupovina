package rs.pametnakupovina.backend.product;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class CanonicalProductDetailsService {

    private final CanonicalProductDetailsRepository repository;

    public CanonicalProductDetailsService(
            CanonicalProductDetailsRepository repository
    ) {
        this.repository = repository;
    }

    public Optional<CanonicalProductDetailsResponse> find(
            Long productId,
            LocalDate requestedDate,
            int historyLimit
    ) {
        if (productId == null || productId <= 0) {
            throw new IllegalArgumentException(
                    "canonicalProductId mora biti pozitivan"
            );
        }
        if (historyLimit < 0 || historyLimit > 100) {
            throw new IllegalArgumentException(
                    "historyLimit mora biti između 0 i 100"
            );
        }

        LocalDate date = requestedDate == null
                ? LocalDate.now()
                : requestedDate;

        return repository.findProduct(productId).map(product -> {
            List<CanonicalProductOffer> offers =
                    repository.findLatestOffers(productId, date);
            List<CanonicalProductPricePoint> history = historyLimit == 0
                    ? List.of()
                    : repository.findPriceHistory(
                            productId,
                            date,
                            historyLimit
                    );
            LocalDate latestPriceDate = offers.stream()
                    .map(CanonicalProductOffer::priceDate)
                    .max(LocalDate::compareTo)
                    .orElse(null);

            return new CanonicalProductDetailsResponse(
                    product.id(),
                    product.name(),
                    product.brand(),
                    product.barcode(),
                    product.quantityValue(),
                    product.baseUnit(),
                    date,
                    latestPriceDate,
                    offers,
                    history,
                    product.packageCount()
            );
        });
    }
}
