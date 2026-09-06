package rs.pametnakupovina.backend.retailerlocation.europrom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationImportResult;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationImportService;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationSource;
import rs.pametnakupovina.backend.retailerlocation.VerifiedRetailerLocation;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

@Service
public class EuropromLocationImportService {

    private final EuropromLocationClient client;
    private final RetailerLocationImportService locationImportService;
    private final String sourceUrl;

    public EuropromLocationImportService(
            EuropromLocationClient client,
            RetailerLocationImportService locationImportService,
            @Value("${europrom.location-import.url}")
            String sourceUrl
    ) {
        this.client = client;
        this.locationImportService = locationImportService;
        this.sourceUrl = sourceUrl;
    }

    public RetailerLocationImportResult importLatest() {
        return locationImportService.importVerifiedLocations(
                "EUROPROM",
                mapLocations(client.fetchLocations()),
                new RetailerLocationSource(
                        "OFFICIAL_EUROPROM_STORE_PAGE",
                        "EUROPROM_STORE_PAGE_HTML",
                        sourceUrl,
                        "OFFICIAL_RETAILER_PAGE",
                        true,
                        null
                )
        );
    }

    List<VerifiedRetailerLocation> mapLocations(
            List<EuropromPageLocation> locations
    ) {
        return locations.stream()
                .map(location -> new VerifiedRetailerLocation(
                        slug(location.name()),
                        "Europrom " + location.name(),
                        location.address(),
                        location.city(),
                        "EUROPROM",
                        "Europrom",
                        location.latitude(),
                        location.longitude(),
                        true
                ))
                .toList();
    }

    private String slug(String value) {
        StringBuilder latin = new StringBuilder();

        for (int index = 0; index < value.length(); index++) {
            latin.append(transliterate(value.charAt(index)));
        }

        return Normalizer.normalize(latin, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
    }

    private String transliterate(char value) {
        return switch (value) {
            case 'А', 'а' -> "a";
            case 'Б', 'б' -> "b";
            case 'В', 'в' -> "v";
            case 'Г', 'г' -> "g";
            case 'Д', 'д' -> "d";
            case 'Ђ', 'ђ' -> "dj";
            case 'Е', 'е' -> "e";
            case 'Ж', 'ж' -> "z";
            case 'З', 'з' -> "z";
            case 'И', 'и' -> "i";
            case 'Ј', 'ј' -> "j";
            case 'К', 'к' -> "k";
            case 'Л', 'л' -> "l";
            case 'Љ', 'љ' -> "lj";
            case 'М', 'м' -> "m";
            case 'Н', 'н' -> "n";
            case 'Њ', 'њ' -> "nj";
            case 'О', 'о' -> "o";
            case 'П', 'п' -> "p";
            case 'Р', 'р' -> "r";
            case 'С', 'с' -> "s";
            case 'Т', 'т' -> "t";
            case 'Ћ', 'ћ' -> "c";
            case 'У', 'у' -> "u";
            case 'Ф', 'ф' -> "f";
            case 'Х', 'х' -> "h";
            case 'Ц', 'ц' -> "c";
            case 'Ч', 'ч' -> "c";
            case 'Џ', 'џ' -> "dz";
            case 'Ш', 'ш' -> "s";
            default -> String.valueOf(value);
        };
    }
}
