package rs.pametnakupovina.backend.retailerlocation.idearoda;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationImportResult;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationImportService;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationSource;
import rs.pametnakupovina.backend.retailerlocation.VerifiedRetailerLocation;
import rs.pametnakupovina.backend.storepricing.IdeaRodaPriceFormatMappingService;

import java.util.List;
import java.util.Locale;

@Service
public class IdeaRodaLocationImportService {

    private final IdeaRodaLocationClient client;
    private final RetailerLocationImportService locationImportService;
    private final IdeaRodaPriceFormatMappingService priceFormatMappingService;
    private final String sourceDescription;

    public IdeaRodaLocationImportService(
            IdeaRodaLocationClient client,
            RetailerLocationImportService locationImportService,
            IdeaRodaPriceFormatMappingService priceFormatMappingService,
            @Value("${idea.location-import.url}") String ideaUrl,
            @Value("${roda.location-import.url}") String rodaUrl
    ) {
        this.client = client;
        this.locationImportService = locationImportService;
        this.priceFormatMappingService = priceFormatMappingService;
        this.sourceDescription = ideaUrl + " | " + rodaUrl;
    }

    public RetailerLocationImportResult importLatest() {
        RetailerLocationImportResult result =
                locationImportService.importVerifiedLocations(
                "IDEA_RODA",
                mapLocations(client.fetchLocations()),
                new RetailerLocationSource(
                        "OFFICIAL_IDEA_RODA_STORE_LOCATORS",
                        "IDEA_RODA_LOCATION_JSON",
                        sourceDescription,
                        "OFFICIAL_RETAILER_API",
                        false,
                        "PRICE_FORMAT_NOT_VERIFIED"
                )
        );
        priceFormatMappingService.importLatest();
        return result;
    }

    List<VerifiedRetailerLocation> mapLocations(
            List<IdeaRodaApiLocation> locations
    ) {
        return locations.stream().map(location -> {
            String type = location.format()
                    .strip()
                    .toUpperCase(Locale.ROOT);
            String formatCode;
            String formatName;
            if ("IDEA".equals(location.brand())) {
                formatCode = switch (type) {
                    case "IDEA" -> "IDEA_LOCATION";
                    case "IDEA SUPER" -> "IDEA_SUPER_LOCATION";
                    default -> throw new IllegalArgumentException(
                            "Nepoznat IDEA format: " + location.format()
                    );
                };
                formatName = "IDEA SUPER".equals(type)
                        ? "IDEA Super (lokacija)"
                        : "IDEA (lokacija)";
            } else if ("RODA".equals(location.brand())) {
                formatCode = switch (type) {
                    case "RODA" -> "RODA_LOCATION";
                    case "RODA MEGA" -> "RODA_MEGA_LOCATION";
                    default -> throw new IllegalArgumentException(
                            "Nepoznat Roda format: " + location.format()
                    );
                };
                formatName = "RODA MEGA".equals(type)
                        ? "Roda Mega (lokacija)"
                        : "Roda (lokacija)";
            } else {
                throw new IllegalArgumentException(
                        "Nepoznat IDEA/Roda brend: " + location.brand()
                );
            }

            return new VerifiedRetailerLocation(
                    location.code(),
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
}
