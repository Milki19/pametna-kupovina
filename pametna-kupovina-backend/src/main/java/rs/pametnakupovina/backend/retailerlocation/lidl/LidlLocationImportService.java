package rs.pametnakupovina.backend.retailerlocation.lidl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationImportResult;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationImportService;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationSource;
import rs.pametnakupovina.backend.retailerlocation.VerifiedRetailerLocation;

import java.util.List;

@Service
public class LidlLocationImportService {

    private static final String FORMAT_CODE = "LIDL_KD";
    private static final String FORMAT_NAME = "Lidl Srbija KD";

    private final LidlLocationClient client;
    private final RetailerLocationImportService locationImportService;
    private final String sourceUrl;

    public LidlLocationImportService(
            LidlLocationClient client,
            RetailerLocationImportService locationImportService,
            @Value("${lidl.location-import.url}")
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
                "LIDL",
                locations,
                new RetailerLocationSource(
                        "OFFICIAL_LIDL_LOCATION_API",
                        "LIDL_LOCATION_JSON",
                        sourceUrl,
                        "OFFICIAL_RETAILER_API",
                        true,
                        null
                )
        );
    }

    List<VerifiedRetailerLocation> mapLocations(
            List<LidlApiLocation> apiLocations
    ) {
        return apiLocations.stream()
                .map(this::mapLocation)
                .toList();
    }

    private VerifiedRetailerLocation mapLocation(
            LidlApiLocation location
    ) {
        LidlApiLocation.Address address = required(
                location.address(),
                "address"
        );
        LidlApiLocation.Status status = required(
                location.status(),
                "status"
        );
        String storeName = required(location.storeName(), "storeName");

        return new VerifiedRetailerLocation(
                required(location.objectNumber(), "objectNumber"),
                storeName.regionMatches(true, 0, "Lidl", 0, 4)
                        ? storeName
                        : "Lidl " + storeName,
                joinAddress(
                        required(address.streetName(), "streetName"),
                        address.streetNumber()
                ),
                required(address.city(), "city"),
                FORMAT_CODE,
                FORMAT_NAME,
                coordinate(address.latitude(), "latitude"),
                coordinate(address.longitude(), "longitude"),
                "open".equalsIgnoreCase(
                        required(status.name(), "status.name")
                )
        );
    }

    private String joinAddress(String streetName, String streetNumber) {
        if (streetNumber == null || streetNumber.isBlank()) {
            return streetName;
        }

        return streetName + " " + streetNumber.strip();
    }

    private String required(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Lidl lokacija nema polje: " + fieldName
            );
        }

        return value.strip();
    }

    private <T> T required(T value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "Lidl lokacija nema polje: " + fieldName
            );
        }

        return value;
    }

    private double coordinate(Double value, String fieldName) {
        if (value == null || !Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    "Lidl lokacija ima neispravnu koordinatu: "
                            + fieldName
            );
        }

        return value;
    }
}
