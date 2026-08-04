package rs.pametnakupovina.backend.geocoding;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class StoreGeocodingService {

    private static final BigDecimal AUTO_VERIFY_CONFIDENCE =
            new BigDecimal("0.8500");

    private static final Pattern HOUSE_NUMBER_PATTERN =
            Pattern.compile("\\b\\d+[a-z]?\\b");

    private static final Pattern SOURCE_PATTERN =
            Pattern.compile("[A-Z0-9][A-Z0-9_-]{0,99}");

    private final StoreGeocodingRepository repository;
    private final ProductNameNormalizer textNormalizer;

    public StoreGeocodingService(
            StoreGeocodingRepository repository,
            ProductNameNormalizer textNormalizer
    ) {
        this.repository = repository;
        this.textNormalizer = textNormalizer;
    }

    @Transactional
    public StoreGeocodingResult recordCandidate(
            Long storeId,
            StoreGeocodingCandidateRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Geocoding rezultat je obavezan"
            );
        }

        StoreGeocodingState current = requiredState(storeId);

        double latitude = coordinate(
                request.latitude(),
                "latitude",
                -90,
                90
        );

        double longitude = coordinate(
                request.longitude(),
                "longitude",
                -180,
                180
        );

        BigDecimal confidence = confidence(request.confidence());
        String source = source(request.source());
        String sourceReference = optionalText(
                request.sourceReference(),
                "sourceReference",
                1000
        );
        String matchedAddress = requiredText(
                request.matchedAddress(),
                "matchedAddress",
                500
        );
        String address = requiredText(
                current.address(),
                "store.address",
                300
        );
        String city = requiredText(
                current.city(),
                "store.city",
                100
        );
        String query = normalize(
                address + ", " + city
        );

        if (isCached(
                current,
                query,
                source,
                sourceReference,
                matchedAddress,
                latitude,
                longitude,
                confidence
        )) {
            return toResult(current, true);
        }

        String suspiciousReason = suspiciousReason(
                current,
                matchedAddress,
                confidence
        );

        StoreGeocodingStatus status =
                suspiciousReason == null
                        ? StoreGeocodingStatus.AUTO_VERIFIED
                        : StoreGeocodingStatus.NEEDS_REVIEW;

        StoreGeocodingState saved = repository.saveCandidate(
                storeId,
                latitude,
                longitude,
                confidence,
                query,
                source,
                sourceReference,
                matchedAddress,
                status,
                suspiciousReason
        );

        return toResult(saved, false);
    }

    @Transactional(readOnly = true)
    public List<StoreGeocodingResult> findReviewQueue(String city) {
        String requiredCity = requiredText(city, "city", 200);

        return repository.findReviewQueue(requiredCity)
                .stream()
                .map(state -> toResult(state, true))
                .toList();
    }

    @Transactional
    public StoreGeocodingResult review(
            Long storeId,
            StoreGeocodingReviewRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Geocoding review je obavezan"
            );
        }

        StoreGeocodingState current = requiredState(storeId);

        if (current.status()
                != StoreGeocodingStatus.NEEDS_REVIEW) {
            throw new IllegalArgumentException(
                    "Samo geocoding rezultat sa statusom "
                            + "NEEDS_REVIEW može biti ručno proveren"
            );
        }

        String note = requiredText(
                request.note(),
                "note",
                500
        );

        Coordinates reviewedCoordinates = reviewedCoordinates(
                current,
                request
        );

        StoreGeocodingStatus status = request.accepted()
                ? StoreGeocodingStatus.MANUALLY_VERIFIED
                : StoreGeocodingStatus.REJECTED;

        StoreGeocodingState reviewed = repository.review(
                storeId,
                status,
                reviewedCoordinates.latitude(),
                reviewedCoordinates.longitude(),
                note
        );

        return toResult(reviewed, false);
    }

    private StoreGeocodingState requiredState(Long storeId) {
        if (storeId == null || storeId < 1) {
            throw new IllegalArgumentException(
                    "storeId mora biti pozitivan broj"
            );
        }

        return repository.findById(storeId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Objekat nije pronađen: " + storeId
                ));
    }

    private String suspiciousReason(
            StoreGeocodingState state,
            String matchedAddress,
            BigDecimal confidence
    ) {
        List<String> reasons = new ArrayList<>();

        if (confidence.compareTo(AUTO_VERIFY_CONFIDENCE) < 0) {
            reasons.add("LOW_CONFIDENCE");
        }

        String normalizedMatch = normalize(matchedAddress);
        String normalizedCity = normalize(state.city());

        if (!normalizedMatch.contains(normalizedCity)) {
            reasons.add("CITY_MISMATCH");
        }

        Matcher houseNumberMatcher = HOUSE_NUMBER_PATTERN.matcher(
                normalize(state.address())
        );

        if (houseNumberMatcher.find()
                && !normalizedMatch.contains(
                houseNumberMatcher.group()
        )) {
            reasons.add("HOUSE_NUMBER_MISMATCH");
        }

        return reasons.isEmpty()
                ? null
                : String.join(",", reasons);
    }

    private Coordinates reviewedCoordinates(
            StoreGeocodingState current,
            StoreGeocodingReviewRequest request
    ) {
        boolean hasLatitude = request.correctedLatitude() != null;
        boolean hasLongitude = request.correctedLongitude() != null;

        if (hasLatitude != hasLongitude) {
            throw new IllegalArgumentException(
                    "correctedLatitude i correctedLongitude "
                            + "moraju biti uneti zajedno"
            );
        }

        if (!request.accepted() && (hasLatitude || hasLongitude)) {
            throw new IllegalArgumentException(
                    "Odbijeni rezultat ne prihvata ispravljene koordinate"
            );
        }

        if (!hasLatitude) {
            return new Coordinates(
                    current.candidateLatitude(),
                    current.candidateLongitude()
            );
        }

        return new Coordinates(
                coordinate(
                        request.correctedLatitude(),
                        "correctedLatitude",
                        -90,
                        90
                ),
                coordinate(
                        request.correctedLongitude(),
                        "correctedLongitude",
                        -180,
                        180
                )
        );
    }

    private boolean isCached(
            StoreGeocodingState current,
            String query,
            String source,
            String sourceReference,
            String matchedAddress,
            double latitude,
            double longitude,
            BigDecimal confidence
    ) {
        return current.geocodedAt() != null
                && Objects.equals(current.query(), query)
                && Objects.equals(current.source(), source)
                && Objects.equals(
                current.sourceReference(),
                sourceReference
        )
                && Objects.equals(
                current.matchedAddress(),
                matchedAddress
        )
                && Objects.equals(current.confidence(), confidence)
                && sameCoordinate(
                current.candidateLatitude(),
                latitude
        )
                && sameCoordinate(
                current.candidateLongitude(),
                longitude
        );
    }

    private boolean sameCoordinate(
            Double cachedValue,
            double submittedValue
    ) {
        return cachedValue != null
                && Double.compare(
                cachedValue,
                submittedValue
        ) == 0;
    }

    private StoreGeocodingResult toResult(
            StoreGeocodingState state,
            boolean cached
    ) {
        return new StoreGeocodingResult(
                state.storeId(),
                state.retailerCode(),
                state.externalCode(),
                state.address(),
                state.city(),
                state.query(),
                state.source(),
                state.sourceReference(),
                state.matchedAddress(),
                state.candidateLatitude(),
                state.candidateLongitude(),
                state.appliedLatitude(),
                state.appliedLongitude(),
                state.confidence(),
                state.status(),
                state.suspiciousReason(),
                state.reviewNote(),
                state.geocodedAt(),
                state.reviewedAt(),
                cached,
                state.appliedLatitude() != null
                        && state.appliedLongitude() != null
        );
    }

    private BigDecimal confidence(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "confidence je obavezan"
            );
        }

        BigDecimal normalized = value.setScale(
                4,
                RoundingMode.HALF_UP
        );

        if (normalized.compareTo(BigDecimal.ZERO) < 0
                || normalized.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(
                    "confidence mora biti između 0 i 1"
            );
        }

        return normalized;
    }

    private String source(String value) {
        String normalized = requiredText(
                value,
                "source",
                100
        )
                .toUpperCase(Locale.ROOT)
                .replace(' ', '_');

        if (!SOURCE_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Neispravan geocoding source: " + value
            );
        }

        return normalized;
    }

    private String requiredText(
            String value,
            String field,
            int maximumLength
    ) {
        String text = optionalText(value, field, maximumLength);

        if (text == null) {
            throw new IllegalArgumentException(
                    field + " je obavezan"
            );
        }

        return text;
    }

    private String optionalText(
            String value,
            String field,
            int maximumLength
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String text = value.trim();

        if (text.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field
                            + " ne sme imati više od "
                            + maximumLength
                            + " znakova"
            );
        }

        return text;
    }

    private double coordinate(
            double value,
            String field,
            double minimum,
            double maximum
    ) {
        if (!Double.isFinite(value)
                || value < minimum
                || value > maximum) {
            throw new IllegalArgumentException(
                    field
                            + " mora biti između "
                            + minimum
                            + " i "
                            + maximum
            );
        }

        return value;
    }

    private String normalize(String value) {
        return textNormalizer.normalize(value);
    }

    private record Coordinates(
            double latitude,
            double longitude
    ) {
    }
}
