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

    /** The list as every app before 2.0.8 asks for it: shops within 10 km. */
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
        return find(near, null, categoryCode, retailerCode, query, minDiscount, sort, page, limit);
    }

    /**
     * @param near         only chains with a shop near here; null for every chain
     * @param radiusMeters how far that shop may be; null for 10 km
     * @param minDiscount  the smallest percentage off worth listing
     */
    public SalePage find(
            PreciseLocation near,
            Integer radiusMeters,
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
        if (radiusMeters != null
                && (radiusMeters < SaleRepository.MIN_RADIUS_METERS
                    || radiusMeters > SaleRepository.MAX_RADIUS_METERS)) {
            throw new IllegalArgumentException("Krug mora biti između 300 i 15000 metara");
        }
        int radius = radiusMeters == null ? SaleRepository.NEARBY_METERS : radiusMeters;

        String normalizedQuery = query == null ? null : productNameNormalizer.normalize(query);
        var filter = new SaleRepository.SaleFilter(
                near == null ? null : near.latitude(),
                near == null ? null : near.longitude(),
                blankToNull(categoryCode),
                blankToNull(retailerCode),
                blankToNull(normalizedQuery),
                Math.max(minDiscount, 1),
                radius
        );

        List<SaleRepository.SaleRow> rows = saleRepository.findSales(
                filter,
                sort == null ? SaleSort.DISCOUNT : sort,
                limit,
                (long) page * limit
        );
        long total = rows.isEmpty() ? 0 : rows.getFirst().totalCount();
        int totalPages = (int) ((total + limit - 1) / limit);

        // Categories are counted without the category itself, and chains
        // without the chain, so the chips stay put while the shopper moves
        // between them.
        List<SaleCategory> categories = page == 0
                ? saleRepository.findCategories(filter.withoutCategory())
                : List.of();
        List<SaleRetailer> retailers = page == 0
                ? saleRepository.findRetailers(filter.withoutRetailer())
                : List.of();

        return new SalePage(
                page,
                limit,
                total,
                totalPages,
                (long) page + 1 < totalPages,
                rows.stream().map(SaleRepository.SaleRow::item).toList(),
                categories,
                near != null,
                retailers,
                near == null ? null : radius
        );
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
