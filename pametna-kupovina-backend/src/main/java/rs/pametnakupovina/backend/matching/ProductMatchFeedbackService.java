package rs.pametnakupovina.backend.matching;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductMatchFeedbackService {

    private static final int MAX_NOTE_LENGTH = 500;

    private final ProductMatchFeedbackRepository feedbackRepository;
    private final ProductMatchClientTokenValidator clientTokenValidator;

    public ProductMatchFeedbackService(
            ProductMatchFeedbackRepository feedbackRepository,
            ProductMatchClientTokenValidator clientTokenValidator
    ) {
        this.feedbackRepository = feedbackRepository;
        this.clientTokenValidator = clientTokenValidator;
    }

    @Transactional
    public ProductMatchFeedback record(
            Long decisionId,
            ProductMatchFeedbackRequest request
    ) {
        if (decisionId == null || decisionId <= 0) {
            throw new IllegalArgumentException(
                    "decisionId mora biti pozitivan broj"
            );
        }

        if (request == null) {
            throw new IllegalArgumentException(
                    "Telo zahteva ne sme biti prazno"
            );
        }

        String clientToken = clientTokenValidator.validateRequired(
                request.clientToken()
        );

        if (request.action() == null) {
            throw new IllegalArgumentException(
                    "action je obavezan"
            );
        }

        validateSelectedProduct(
                request.action(),
                request.selectedCanonicalProductId()
        );

        String note = normalizeNote(request.note());

        if (!feedbackRepository.decisionExistsForClient(
                decisionId,
                clientToken
        )) {
            throw new IllegalArgumentException(
                    "Odluka o uparivanju ne postoji za dati clientToken"
            );
        }

        if (request.selectedCanonicalProductId() != null
                && !feedbackRepository.canonicalProductExists(
                request.selectedCanonicalProductId()
        )) {
            throw new IllegalArgumentException(
                    "Izabrani kanonski proizvod ne postoji"
            );
        }

        return feedbackRepository.save(
                decisionId,
                clientToken,
                request.action(),
                request.selectedCanonicalProductId(),
                note
        );
    }

    private void validateSelectedProduct(
            ProductMatchFeedbackAction action,
            Long selectedCanonicalProductId
    ) {
        if (action == ProductMatchFeedbackAction.CONFIRMED
                && selectedCanonicalProductId == null) {
            throw new IllegalArgumentException(
                    "selectedCanonicalProductId je obavezan za potvrdu"
            );
        }

        if (action == ProductMatchFeedbackAction.REJECTED
                && selectedCanonicalProductId != null) {
            throw new IllegalArgumentException(
                    "Odbijanje ne sme da izabere kanonski proizvod"
            );
        }
    }

    private String normalizeNote(String note) {
        if (note == null) {
            return null;
        }

        String normalizedNote = note.strip();

        if (normalizedNote.isEmpty()) {
            throw new IllegalArgumentException(
                    "note ne sme biti prazan kada je prosleđen"
            );
        }

        if (normalizedNote.length() > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException(
                    "note ne sme biti duži od 500 znakova"
            );
        }

        return normalizedNote;
    }
}
