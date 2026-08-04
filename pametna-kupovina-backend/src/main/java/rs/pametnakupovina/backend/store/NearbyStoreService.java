package rs.pametnakupovina.backend.store;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class NearbyStoreService {

    private static final int MAX_RADIUS_METERS = 100_000;
    private static final int MAX_RESULTS = 100;

    private final NearbyStoreRepository repository;

    public NearbyStoreService(NearbyStoreRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<NearbyStore> findNearby(
            double latitude,
            double longitude,
            int radiusMeters,
            int limit
    ) {
        validateCoordinate(latitude, "latitude", -90, 90);
        validateCoordinate(longitude, "longitude", -180, 180);

        if (radiusMeters < 1 || radiusMeters > MAX_RADIUS_METERS) {
            throw badRequest(
                    "radiusMeters mora biti između 1 i "
                            + MAX_RADIUS_METERS
            );
        }

        if (limit < 1 || limit > MAX_RESULTS) {
            throw badRequest(
                    "limit mora biti između 1 i " + MAX_RESULTS
            );
        }

        return repository.findNearby(
                latitude,
                longitude,
                radiusMeters,
                limit
        );
    }

    private void validateCoordinate(
            double value,
            String field,
            double minimum,
            double maximum
    ) {
        if (!Double.isFinite(value)
                || value < minimum
                || value > maximum) {
            throw badRequest(
                    field + " mora biti između "
                            + (int) minimum
                            + " i "
                            + (int) maximum
            );
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                message
        );
    }
}
