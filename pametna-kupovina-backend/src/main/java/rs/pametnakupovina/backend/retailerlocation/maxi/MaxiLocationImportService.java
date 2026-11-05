package rs.pametnakupovina.backend.retailerlocation.maxi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log =
            LoggerFactory.getLogger(MaxiLocationImportService.class);

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
        RetailerLocationSource source = new RetailerLocationSource(
                "OFFICIAL_MAXI_STORE_LOCATOR",
                "MAXI_STORE_LOCATOR_GRAPHQL",
                sourceUrl,
                "OFFICIAL_RETAILER_API",
                false,
                "NO_STORE_PRICE_FEED"
        );
        List<VerifiedRetailerLocation> locations;
        try {
            locations = mapLocations(client.fetchLocations());
        } catch (RuntimeException exception) {
            locationImportService.recordVerifiedSourceFailure(
                    "MAXI",
                    source,
                    "Čitanje Maxi lokatora nije uspelo: "
                            + exception.getMessage()
            );
            throw exception;
        }
        return locationImportService.importVerifiedLocations(
                "MAXI",
                locations,
                source
        );
    }

    /**
     * A store the locator describes in a way we do not know yet keeps its
     * place in the list with the unknown part left empty. The import then
     * skips only that store, saves the rest, and leaves every store active,
     * because a skipped one must not look closed.
     */
    List<VerifiedRetailerLocation> mapLocations(
            List<MaxiApiLocation> locations
    ) {
        return locations.stream().map(location -> {
            String formatCode = formatCode(location.storeType());
            if (formatCode == null) {
                log.warn(
                        "Maxi lokacija {} ima nepoznat format: {}",
                        location.code(),
                        location.storeType()
                );
            }
            String formatName = formatCode == null
                    ? null
                    : "SHOPNGO".equals(formatCode) ? "Shop & Go" : "Maxi";

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

    private String formatCode(String rawType) {
        String type = rawType == null
                ? ""
                : rawType.strip().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "MAXI" -> "MAXI";
            case "SHOPNGO" -> "SHOPNGO";
            default -> null;
        };
    }

    private String normalizeCode(String rawCode) {
        String code = rawCode == null ? "" : rawCode.strip();
        if (code.matches("[sS]\\d+")) {
            return code.toUpperCase(Locale.ROOT);
        }
        log.warn("Maxi lokacija ima nepoznatu šifru: {}", rawCode);
        return null;
    }
}
