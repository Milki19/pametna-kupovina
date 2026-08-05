package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import rs.pametnakupovina.backend.matching.FuzzyProductCandidate;
import rs.pametnakupovina.backend.matching.ProductMatchDecision;
import rs.pametnakupovina.backend.matching.ProductMatchDecisionService;
import rs.pametnakupovina.backend.matching.ProductMatchFeedbackAction;
import rs.pametnakupovina.backend.matching.ProductMatchFeedbackRequest;
import rs.pametnakupovina.backend.matching.ProductMatchFeedbackService;
import rs.pametnakupovina.backend.matching.ProductMatchStatus;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class ShoppingListMatchingService {

    private static final int CANDIDATE_LIMIT = 5;

    private final ShoppingListService shoppingListService;
    private final ShoppingListRepository shoppingListRepository;
    private final ProductMatchDecisionService decisionService;
    private final ProductMatchFeedbackService feedbackService;
    private final ProductNameNormalizer productNameNormalizer;

    public ShoppingListMatchingService(
            ShoppingListService shoppingListService,
            ShoppingListRepository shoppingListRepository,
            ProductMatchDecisionService decisionService,
            ProductMatchFeedbackService feedbackService,
            ProductNameNormalizer productNameNormalizer
    ) {
        this.shoppingListService = shoppingListService;
        this.shoppingListRepository = shoppingListRepository;
        this.decisionService = decisionService;
        this.feedbackService = feedbackService;
        this.productNameNormalizer = productNameNormalizer;
    }

    @Transactional
    public ShoppingListMatchingResponse match(
            Long listId,
            String clientToken
    ) {
        ShoppingListResponse shoppingList =
                shoppingListService.requireOwnedList(
                        listId,
                        clientToken
                );

        List<ShoppingItemMatchResult> results = new ArrayList<>();

        for (ShoppingListItemResponse item : shoppingList.items()) {
            results.add(matchItem(listId, item, clientToken));
        }

        List<Long> blockingItemIds = results.stream()
                .filter(ShoppingItemMatchResult::blocksOptimization)
                .map(ShoppingItemMatchResult::itemId)
                .toList();

        int automaticallyMatched = (int) results.stream()
                .filter(result -> result.matchingStatus()
                        == ShoppingItemMatchingStatus.AUTO_MATCHED)
                .count();

        int needsConfirmation = (int) results.stream()
                .filter(result -> result.matchingStatus()
                        == ShoppingItemMatchingStatus.NEEDS_CONFIRMATION)
                .count();

        int unmatched = (int) results.stream()
                .filter(result -> result.matchingStatus()
                        == ShoppingItemMatchingStatus.UNMATCHED)
                .count();

        int flexible = (int) results.stream()
                .filter(result -> result.matchingRule()
                        == ShoppingItemRule.FLEXIBLE_CATEGORY)
                .count();

        shoppingListRepository.touch(listId);

        return new ShoppingListMatchingResponse(
                listId,
                results.size(),
                automaticallyMatched,
                needsConfirmation,
                unmatched,
                flexible,
                blockingItemIds.isEmpty(),
                blockingItemIds,
                results
        );
    }

    @Transactional
    public ShoppingListItemResponse resolve(
            Long listId,
            Long itemId,
            String clientToken,
            ResolveShoppingItemMatchRequest request
    ) {
        shoppingListService.requireOwnedList(listId, clientToken);

        if (request == null || request.action() == null) {
            throw badRequest("Match action je obavezan");
        }

        ShoppingListItemResponse item = shoppingListRepository
                .findItemById(listId, itemId)
                .orElseThrow(() -> itemNotFound(itemId));

        if (item.matchingDecisionId() == null) {
            throw badRequest(
                    "Stavka nema odluku o uparivanju koju je moguće potvrditi"
            );
        }

        if (request.action() == ShoppingItemMatchAction.CONFIRM) {
            if (request.canonicalProductId() == null) {
                throw badRequest(
                        "canonicalProductId je obavezan za potvrdu"
                );
            }

            feedbackService.record(
                    item.matchingDecisionId(),
                    new ProductMatchFeedbackRequest(
                            clientToken,
                            ProductMatchFeedbackAction.CONFIRMED,
                            request.canonicalProductId(),
                            request.note()
                    )
            );

            item = updateResult(
                    listId,
                    itemId,
                    ShoppingItemMatchingStatus.CONFIRMED,
                    request.canonicalProductId(),
                    item.matchingDecisionId(),
                    BigDecimal.ONE.setScale(4),
                    "user-confirmation-v1"
            );
        } else {
            if (request.canonicalProductId() != null) {
                throw badRequest(
                        "Odbijanje ne sme da izabere kanonski proizvod"
                );
            }

            feedbackService.record(
                    item.matchingDecisionId(),
                    new ProductMatchFeedbackRequest(
                            clientToken,
                            ProductMatchFeedbackAction.REJECTED,
                            null,
                            request.note()
                    )
            );

            item = updateResult(
                    listId,
                    itemId,
                    ShoppingItemMatchingStatus.UNMATCHED,
                    null,
                    item.matchingDecisionId(),
                    item.matchingScore(),
                    "user-rejection-v1"
            );
        }

        shoppingListRepository.touch(listId);
        return item;
    }

    private ShoppingItemMatchResult matchItem(
            Long listId,
            ShoppingListItemResponse item,
            String clientToken
    ) {
        if (item.matchingStatus()
                == ShoppingItemMatchingStatus.CONFIRMED) {
            return resultForStoredItem(
                    item,
                    false,
                    "Proizvod je već potvrđen."
            );
        }

        ProductMatchDecision decision;

        if (item.matchingRule()
                == ShoppingItemRule.FLEXIBLE_CATEGORY) {
            FlexibleItemConstraints constraints =
                    item.flexibleConstraints();

            String query = flexibleQuery(item, constraints);

            decision = decisionService.decideWithCandidateFilter(
                    query,
                    CANDIDATE_LIMIT,
                    clientToken,
                    candidate -> satisfiesConstraints(
                            candidate,
                            constraints
                    )
            );
        } else {
            decision = decisionService.decide(
                    item.name(),
                    CANDIDATE_LIMIT,
                    clientToken
            );
        }

        ShoppingItemMatchingStatus status = switch (
                decision.status()
        ) {
            case AUTO_ACCEPTED ->
                    ShoppingItemMatchingStatus.AUTO_MATCHED;
            case NEEDS_CONFIRMATION ->
                    ShoppingItemMatchingStatus.NEEDS_CONFIRMATION;
            case UNMATCHED -> ShoppingItemMatchingStatus.UNMATCHED;
        };

        ShoppingListItemResponse updated = updateResult(
                listId,
                item.id(),
                status,
                decision.matchedCanonicalProductId(),
                decision.decisionId(),
                decision.score(),
                decision.algorithmVersion()
        );

        boolean blocksOptimization =
                updated.matchingRule() == ShoppingItemRule.EXACT_PRODUCT
                        && updated.matchingStatus()
                        == ShoppingItemMatchingStatus.NEEDS_CONFIRMATION;

        return new ShoppingItemMatchResult(
                updated.id(),
                updated.name(),
                updated.matchingRule(),
                updated.matchingStatus(),
                updated.matchedCanonicalProductId(),
                updated.matchingDecisionId(),
                updated.matchingScore(),
                blocksOptimization,
                explanation(updated),
                decision.candidates()
        );
    }

    private ShoppingItemMatchResult resultForStoredItem(
            ShoppingListItemResponse item,
            boolean blocksOptimization,
            String explanation
    ) {
        return new ShoppingItemMatchResult(
                item.id(),
                item.name(),
                item.matchingRule(),
                item.matchingStatus(),
                item.matchedCanonicalProductId(),
                item.matchingDecisionId(),
                item.matchingScore(),
                blocksOptimization,
                explanation,
                List.of()
        );
    }

    private ShoppingListItemResponse updateResult(
            Long listId,
            Long itemId,
            ShoppingItemMatchingStatus status,
            Long matchedCanonicalProductId,
            Long decisionId,
            BigDecimal score,
            String algorithmVersion
    ) {
        return shoppingListRepository.updateMatchingResult(
                listId,
                itemId,
                status,
                matchedCanonicalProductId,
                decisionId,
                score,
                algorithmVersion
        ).orElseThrow(() -> itemNotFound(itemId));
    }

    private String flexibleQuery(
            ShoppingListItemResponse item,
            FlexibleItemConstraints constraints
    ) {
        if (constraints == null) {
            return item.name();
        }

        StringBuilder query = new StringBuilder(
                constraints.category()
        );

        if (constraints.requiredBrand() != null) {
            query.append(' ').append(constraints.requiredBrand());
        }

        if (constraints.minPackageQuantity() != null
                && constraints.maxPackageQuantity() != null
                && constraints.minPackageQuantity().compareTo(
                constraints.maxPackageQuantity()
        ) == 0
                && constraints.requiredBaseUnit() != null) {
            query.append(' ')
                    .append(
                            constraints.minPackageQuantity()
                                    .stripTrailingZeros()
                                    .toPlainString()
                    )
                    .append(' ')
                    .append(constraints.requiredBaseUnit());
        }

        return query.toString();
    }

    private boolean satisfiesConstraints(
            FuzzyProductCandidate candidate,
            FlexibleItemConstraints constraints
    ) {
        if (constraints == null) {
            return true;
        }

        if (constraints.requiredBrand() != null) {
            String requestedBrand = productNameNormalizer.normalize(
                    constraints.requiredBrand()
            );

            String candidateBrand = productNameNormalizer.normalize(
                    candidate.brand()
            );

            if (!requestedBrand.equals(candidateBrand)) {
                return false;
            }
        }

        if (constraints.requiredBaseUnit() != null
                && !constraints.requiredBaseUnit().equals(
                candidate.baseUnit()
        )) {
            return false;
        }

        if (constraints.minPackageQuantity() != null
                && (
                candidate.quantityValue() == null
                        || candidate.quantityValue().compareTo(
                        constraints.minPackageQuantity()
                ) < 0
        )) {
            return false;
        }

        return constraints.maxPackageQuantity() == null
                || (
                candidate.quantityValue() != null
                        && candidate.quantityValue().compareTo(
                        constraints.maxPackageQuantity()
                ) <= 0
        );
    }

    private String explanation(ShoppingListItemResponse item) {
        if (item.matchingRule()
                == ShoppingItemRule.FLEXIBLE_CATEGORY) {
            return switch (item.matchingStatus()) {
                case AUTO_MATCHED ->
                        "Fleksibilna kategorija ima automatski predlog; optimizator i dalje sme da izabere jeftiniji proizvod koji ispunjava ograničenja.";
                case NEEDS_CONFIRMATION ->
                        "Kandidati su prikazani, ali fleksibilna stavka može da se optimizuje po zadatim ograničenjima.";
                case UNMATCHED ->
                        "Nema kanonskog kandidata; fleksibilna kategorija ostaje vidljiva i proverava se među ponudama trgovaca.";
                default -> "Fleksibilna stavka je spremna.";
            };
        }

        return switch (item.matchingStatus()) {
            case AUTO_MATCHED -> "Proizvod je automatski povezan.";
            case NEEDS_CONFIRMATION ->
                    "Potrebna je potvrda kandidata pre optimizacije.";
            case UNMATCHED ->
                    "Proizvod nije uparen i neće biti sakriven iz rezultata.";
            case CONFIRMED -> "Proizvod je potvrđen.";
            case PENDING -> "Uparivanje još nije pokrenuto.";
        };
    }

    private ResponseStatusException itemNotFound(Long itemId) {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Stavka nije pronađena: " + itemId
        );
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                message
        );
    }
}
