package rs.pametnakupovina.backend.product;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProductReportService {

    private static final int MAX_NOTE_LENGTH = 500;

    private final ProductReportRepository repository;

    public ProductReportService(
            ProductReportRepository repository
    ) {
        this.repository = repository;
    }

    /** @param clientTokenHash the reporting phone, or null when it is not known */
    @Transactional
    public ProductReportResponse report(
            Long canonicalProductId,
            String clientTokenHash,
            ProductReportRequest request
    ) {
        if (request == null || request.reason() == null) {
            throw badRequest("Izaberi šta nije u redu.");
        }

        String note = request.note() == null || request.note().isBlank()
                ? null
                : request.note().strip();

        if (note != null && note.length() > MAX_NOTE_LENGTH) {
            throw badRequest("Napomena može imati najviše " + MAX_NOTE_LENGTH + " karaktera.");
        }

        if (canonicalProductId == null || !repository.productExists(canonicalProductId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Proizvod nije pronađen.");
        }

        if (request.retailerProductId() != null
                && !repository.listingBelongsToProduct(canonicalProductId, request.retailerProductId())) {
            throw badRequest("Ta ponuda ne pripada ovom proizvodu.");
        }

        Long storeId = null;
        if (request.reason() == ProductReportReason.NOT_IN_STORE) {
            if (request.retailerProductId() == null || request.storeId() == null) {
                throw badRequest("Javi koja ponuda i koja prodavnica.");
            }
            if (!repository.storeSellsListing(request.storeId(), request.retailerProductId())) {
                throw badRequest("Ta ponuda nije iz te prodavnice.");
            }
            storeId = request.storeId();
        }

        long id = repository.insert(
                canonicalProductId,
                request.retailerProductId(),
                storeId,
                request.reason(),
                note,
                clientTokenHash
        );

        return new ProductReportResponse(id, "NEW");
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
