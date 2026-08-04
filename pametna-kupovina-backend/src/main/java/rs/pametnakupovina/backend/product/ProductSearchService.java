package rs.pametnakupovina.backend.product;

import org.springframework.stereotype.Service;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;

import java.util.List;

@Service
public class ProductSearchService {

    private final ProductRepository productRepository;
    private final ProductNameNormalizer productNameNormalizer;

    public ProductSearchService(
            ProductRepository productRepository,
            ProductNameNormalizer productNameNormalizer
    ) {
        this.productRepository = productRepository;
        this.productNameNormalizer = productNameNormalizer;
    }

    public List<ProductSearchResult> search(
            String query,
            int limit
    ) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException(
                    "Parametar query ne sme biti prazan"
            );
        }

        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException(
                    "Limit mora biti između 1 i 100"
            );
        }

        String normalizedQuery =
                productNameNormalizer.normalize(query);

        if (normalizedQuery.isBlank()) {
            throw new IllegalArgumentException(
                    "Parametar query mora sadržati slovo ili broj"
            );
        }

        return productRepository.search(
                query.strip(),
                normalizedQuery,
                limit
        );
    }
}
