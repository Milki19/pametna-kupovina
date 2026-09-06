package rs.pametnakupovina.backend.retailerlocation.univerexport;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationImportResult;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationImportService;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationSource;
import rs.pametnakupovina.backend.retailerlocation.VerifiedRetailerLocation;

import java.util.List;
import java.util.Locale;

@Service
public class UniverexportLocationImportService {

    private final UniverexportLocationClient client;
    private final RetailerLocationImportService locationImportService;
    private final String sourceUrl;

    public UniverexportLocationImportService(
            UniverexportLocationClient client,
            RetailerLocationImportService locationImportService,
            @Value("${univerexport.location-import.url}")
            String sourceUrl
    ) {
        this.client = client;
        this.locationImportService = locationImportService;
        this.sourceUrl = sourceUrl;
    }

    public RetailerLocationImportResult importLatest() {
        return locationImportService.importVerifiedLocations(
                "UNIVEREXPORT",
                mapLocations(client.fetchLocations()),
                new RetailerLocationSource(
                        "OFFICIAL_UNIVEREXPORT_LOCATION_API",
                        "UNIVEREXPORT_LOCATION_JSON",
                        sourceUrl,
                        "OFFICIAL_RETAILER_API",
                        false,
                        "PRICE_FORMAT_NOT_VERIFIED"
                )
        );
    }

    List<VerifiedRetailerLocation> mapLocations(
            List<UniverexportApiLocation> locations
    ) {
        return locations.stream().map(location -> {
            String normalizedFormat = location.format()
                    .strip()
                    .toUpperCase(Locale.ROOT);
            String formatCode = switch (normalizedFormat) {
                case "MINI" -> "UNIVEREXPORT_MINI";
                case "SUPER" -> "UNIVEREXPORT_SUPER";
                case "VELIKI" -> "UNIVEREXPORT_VELIKI";
                default -> throw new IllegalArgumentException(
                        "Nepoznat Univerexport format: "
                                + location.format()
                );
            };

            return new VerifiedRetailerLocation(
                    location.code(),
                    location.name().strip(),
                    location.address().strip(),
                    location.city().strip(),
                    formatCode,
                    "Univerexport " + location.format().strip(),
                    location.latitude(),
                    location.longitude(),
                    location.active()
            );
        }).toList();
    }
}
