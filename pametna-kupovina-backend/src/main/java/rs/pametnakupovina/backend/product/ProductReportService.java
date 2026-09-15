package rs.pametnakupovina.backend.product;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListClientTokenPolicy;

@Service
public class ProductReportService {

    private static final int MAX_NOTE_LENGTH = 500;

    private final ProductReportRepository repository;
    private final ShoppingListClientTokenPolicy clientTokenPolicy;

    public ProductReportService(
            ProductReportRepository repository,
            ShoppingListClientTokenPolicy clientTokenPolicy
    ) {
        this.repository = repository;
        this.clientTokenPolicy = clientTokenPolicy;
    }

    @Transactional
    public ProductReportResponse report(
            Long canonicalProductId,
            String clientToken,
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

        String clientTokenHash = clientToken == null || clientToken.isBlank()
                ? null
                : clientTokenPolicy.validateAndHash(clientToken);

        long id = repository.insert(
                canonicalProductId,
                request.retailerProductId(),
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
