package rs.pametnakupovina.backend.retailerlocation;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class RetailerLocationService {

    private final RetailerLocationRepository repository;

    public RetailerLocationService(
            RetailerLocationRepository repository
    ) {
        this.repository = repository;
    }

    public List<RetailerLocationResponse> findNearest(
            double latitude,
            double longitude,
            int limit
    ) {
        if (latitude < -90 || latitude > 90) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Latitude mora biti između -90 i 90"
            );
        }

        if (longitude < -180 || longitude > 180) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Longitude mora biti između -180 i 180"
            );
        }

        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Limit mora biti između 1 i 100"
            );
        }

        return repository.findNearest(
                latitude,
                longitude,
                limit
        );
    }
}