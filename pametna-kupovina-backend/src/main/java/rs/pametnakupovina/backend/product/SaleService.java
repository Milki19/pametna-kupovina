package rs.pametnakupovina.backend.product;

import org.springframework.stereotype.Service;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;
import rs.pametnakupovina.backend.privacy.PreciseLocation;

import java.util.List;

/** The list of products on sale today, for the "Akcije" screen. */
@Service
public class SaleService {

    private static final int MAX_LIMIT = 100;
    private static final int MAX_QUERY_LENGTH = 200;

    private final SaleRepository saleRepository;
    private final ProductNameNormalizer productNameNormalizer;

    public SaleService(
            SaleRepository saleRepository,
            ProductNameNormalizer productNameNormalizer
    ) {
        this.saleRepository = saleRepository;
        this.productNameNormalizer = productNameNormalizer;
    }

    /**
     * @param near        only chains with a shop near here; null for every chain
     * @param minDiscount the smallest percentage off worth listing
     */
    public SalePage find(
            PreciseLocation near,
            String categoryCode,
            String retailerCode,
            String query,
            int minDiscount,
            SaleSort sort,
            int page,
            int limit
    ) {
        if (page < 0) {
            throw new IllegalArgumentException("Broj stranice ne sme biti negativan");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("Limit mora biti između 1 i 100");
        }
        if (minDiscount < 0 || minDiscount > 99) {
            throw new IllegalArgumentException("Najmanji popust mora biti između 0 i 99");
        }
        if (query != null && query.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("Parametar query ne sme biti duži od 200 znakova");
        }

        String normalizedQuery = query == null ? null : productNameNormalizer.normalize(query);
        var filter = new SaleRepository.SaleFilter(
                near == null ? null : near.latitude(),
                near == null ? null : near.longitude(),
                blankToNull(categoryCode),
                blankToNull(retailerCode),
                blankToNull(normalizedQuery),
                Math.max(minDiscount, 1)
        );

        List<SaleRepository.SaleRow> rows = saleRepository.findSales(
                filter,
                sort == null ? SaleSort.DISCOUNT : sort,
                limit,
                (long) page * limit
        );
        long total = rows.isEmpty() ? 0 : rows.getFirst().totalCount();
        int totalPages = (int) ((total + limit - 1) / limit);

        // Categories are counted without the category itself, so the chips
        // stay put while the shopper moves between them.
        List<SaleCategory> categories = page == 0
                ? saleRepository.findCategories(new SaleRepository.SaleFilter(
                        filter.latitude(),
                        filter.longitude(),
                        null,
                        filter.retailerCode(),
                        filter.normalizedQuery(),
                        filter.minDiscount()
                ))
                : List.of();

        return new SalePage(
                page,
                limit,
                total,
                totalPages,
                (long) page + 1 < totalPages,
                rows.stream().map(SaleRepository.SaleRow::item).toList(),
                categories,
                near != null
        );
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
