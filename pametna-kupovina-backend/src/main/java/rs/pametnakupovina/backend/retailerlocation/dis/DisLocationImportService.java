package rs.pametnakupovina.backend.retailerlocation.dis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationImportResult;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationImportService;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationSource;
import rs.pametnakupovina.backend.retailerlocation.VerifiedRetailerLocation;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class DisLocationImportService {

    private static final Set<String> STANDARD_PLUS_CODES = Set.of(
            "28000",
            "38000",
            "66000"
    );

    private final DisLocationClient client;
    private final RetailerLocationImportService locationImportService;
    private final String sourceUrl;

    public DisLocationImportService(
            DisLocationClient client,
            RetailerLocationImportService locationImportService,
            @Value("${dis.location-import.url}")
            String sourceUrl
    ) {
        this.client = client;
        this.locationImportService = locationImportService;
        this.sourceUrl = sourceUrl;
    }

    public RetailerLocationImportResult importLatest() {
        List<VerifiedRetailerLocation> locations = mapLocations(
                client.fetchLocations()
        );

        return locationImportService.importVerifiedLocations(
                "DIS",
                locations,
                new RetailerLocationSource(
                        "OFFICIAL_DIS_LOCATION_API",
                        "DIS_LOCATION_JSON",
                        sourceUrl,
                        "OFFICIAL_RETAILER_API"
                )
        );
    }

    List<VerifiedRetailerLocation> mapLocations(
            List<DisApiLocation> apiLocations
    ) {
        return apiLocations.stream()
                .map(this::mapLocation)
                .toList();
    }

    private VerifiedRetailerLocation mapLocation(
            DisApiLocation location
    ) {
        String code = required(location.code(), "code");
        String type = required(location.type(), "type")
                .toLowerCase(Locale.ROOT);
        StoreFormat format = switch (type) {
            case "superdis" -> new StoreFormat(
                    "DIS_SUPER",
                    "Dis Super",
                    "Super DIS "
            );
            case "dis" -> STANDARD_PLUS_CODES.contains(code)
                    ? new StoreFormat(
                            "DIS_STANDARD_PLUS",
                            "Dis Standard +",
                            "DIS "
                    )
                    : new StoreFormat(
                            "DIS_STANDARD",
                            "Dis Standard",
                            "DIS "
                    );
            default -> throw new IllegalArgumentException(
                    "Nepoznat DIS tip objekta: " + type
            );
        };

        return new VerifiedRetailerLocation(
                code,
                format.namePrefix() + required(location.name(), "name"),
                required(location.address(), "address"),
                required(location.place(), "place"),
                format.code(),
                format.name(),
                coordinate(location.latitude(), "latitude"),
                coordinate(location.longitude(), "longitude"),
                true
        );
    }

    private String required(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "DIS lokacija nema polje: " + fieldName
            );
        }

        return value.strip();
    }

    private double coordinate(String value, String fieldName) {
        try {
            return Double.parseDouble(
                    required(value, fieldName).replace(',', '.')
            );
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "DIS lokacija ima neispravnu koordinatu: "
                            + fieldName,
                    exception
            );
        }
    }

    private record StoreFormat(
            String code,
            String name,
            String namePrefix
    ) {
    }
}
