package rs.pametnakupovina.backend.product;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductSearchService {

    private final ProductRepository productRepository;

    public ProductSearchService(
            ProductRepository productRepository
    ) {
        this.productRepository = productRepository;
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

        return productRepository.search(
                query.trim(),
                limit
        );
    }
}