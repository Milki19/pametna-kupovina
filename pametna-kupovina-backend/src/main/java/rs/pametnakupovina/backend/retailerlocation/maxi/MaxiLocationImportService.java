package rs.pametnakupovina.backend.retailerlocation.maxi;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationImportResult;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationImportService;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationSource;
import rs.pametnakupovina.backend.retailerlocation.VerifiedRetailerLocation;

import java.util.List;
import java.util.Locale;

@Service
public class MaxiLocationImportService {

    private final MaxiLocationClient client;
    private final RetailerLocationImportService locationImportService;
    private final String sourceUrl;

    public MaxiLocationImportService(
            MaxiLocationClient client,
            RetailerLocationImportService locationImportService,
            @Value("${maxi.location-import.graphql-url}")
            String sourceUrl
    ) {
        this.client = client;
        this.locationImportService = locationImportService;
        this.sourceUrl = sourceUrl;
    }

    public RetailerLocationImportResult importLatest() {
        return locationImportService.importVerifiedLocations(
                "MAXI",
                mapLocations(client.fetchLocations()),
                new RetailerLocationSource(
                        "OFFICIAL_MAXI_STORE_LOCATOR",
                        "MAXI_STORE_LOCATOR_GRAPHQL",
                        sourceUrl,
                        "OFFICIAL_RETAILER_API",
                        false,
                        "NO_STORE_PRICE_FEED"
                )
        );
    }

    List<VerifiedRetailerLocation> mapLocations(
            List<MaxiApiLocation> locations
    ) {
        return locations.stream().map(location -> {
            String type = location.storeType()
                    .strip()
                    .toUpperCase(Locale.ROOT);
            String formatCode = switch (type) {
                case "MAXI" -> "MAXI";
                case "SHOPNGO" -> "SHOPNGO";
                default -> throw new IllegalArgumentException(
                        "Nepoznat Maxi format: " + location.storeType()
                );
            };
            String formatName = "SHOPNGO".equals(formatCode)
                    ? "Shop & Go"
                    : "Maxi";

            return new VerifiedRetailerLocation(
                    normalizeCode(location.code()),
                    location.name(),
                    location.address(),
                    location.city(),
                    formatCode,
                    formatName,
                    location.latitude(),
                    location.longitude(),
                    true
            );
        }).toList();
    }

    private String normalizeCode(String rawCode) {
        String code = rawCode == null ? "" : rawCode.strip();
        if (code.matches("[sS]\\d+")) {
            return code.toUpperCase(Locale.ROOT);
        }
        throw new IllegalArgumentException(
                "Maxi lokacija ima nepoznatu šifru: " + rawCode
        );
    }
}
