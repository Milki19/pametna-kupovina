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
    private final ShoppingLineInterpreter lineInterpreter;

    public ShoppingListMatchingService(
            ShoppingListService shoppingListService,
            ShoppingListRepository shoppingListRepository,
            ProductMatchDecisionService decisionService,
            ProductMatchFeedbackService feedbackService,
            ProductNameNormalizer productNameNormalizer,
            ShoppingLineInterpreter lineInterpreter
    ) {
        this.shoppingListService = shoppingListService;
        this.shoppingListRepository = shoppingListRepository;
        this.decisionService = decisionService;
        this.feedbackService = feedbackService;
        this.productNameNormalizer = productNameNormalizer;
        this.lineInterpreter = lineInterpreter;
    }

    @Transactional
    public ShoppingListMatchingResponse match(
            Long listId,
            String clientToken
    ) {
        return match(listId, clientToken, false);
    }

    // Only a client that can confirm a product sold without a barcode asks
    // for such candidates; older apps expect every candidate to carry one.
    @Transactional
    public ShoppingListMatchingResponse match(
            Long listId,
            String clientToken,
            boolean includeProductsWithoutBarcode
    ) {
        ShoppingListResponse shoppingList =
                shoppingListService.requireOwnedList(
                        listId,
                        clientToken
                );

        List<ShoppingItemMatchResult> results = new ArrayList<>();

        for (ShoppingListItemResponse stored : shoppingList.items()) {
            ShoppingListItemResponse item = readAgain(listId, stored);
            results.add(matchItem(
                    listId,
                    item,
                    clientToken,
                    includeProductsWithoutBarcode
            ));
        }

        List<Long> blockingItemIds = results.stream()
                .filter(ShoppingItemMatchResult::blocksOptimization)
                .map(ShoppingItemMatchResult::itemId)
                .toList();

        int automaticallyMatched = countItemsWithStatus(
                results,
                ShoppingItemMatchingStatus.AUTO_MATCHED
        );

        int confirmed = countItemsWithStatus(
                results,
                ShoppingItemMatchingStatus.CONFIRMED
        );

        int needsConfirmation = countItemsWithStatus(
                results,
                ShoppingItemMatchingStatus.NEEDS_CONFIRMATION
        );

        int unmatched = countItemsWithStatus(
                results,
                ShoppingItemMatchingStatus.UNMATCHED
        );

        int flexible = (int) results.stream()
                .filter(result -> result.matchingRule()
                        == ShoppingItemRule.FLEXIBLE_CATEGORY)
                .count();

        shoppingListRepository.touch(listId);

        return new ShoppingListMatchingResponse(
                listId,
                results.size(),
                automaticallyMatched,
                confirmed,
                needsConfirmation,
                unmatched,
                flexible,
                blockingItemIds.isEmpty(),
                blockingItemIds,
                results
        );
    }

    static int countItemsWithStatus(
            List<ShoppingItemMatchResult> results,
            ShoppingItemMatchingStatus status
    ) {
        return (int) results.stream()
                .filter(result -> result.matchingStatus() == status)
                .count();
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
            if (request.canonicalProductId() == null
                    && request.productFamilyId() == null) {
                throw badRequest(
                        "canonicalProductId je obavezan za potvrdu"
                );
            }

            if (request.canonicalProductId() != null
                    && request.productFamilyId() != null) {
                throw badRequest(
                        "Potvrda bira kanonski proizvod ili porodicu, ne oba"
                );
            }

            if (request.productFamilyId() != null
                    && item.matchingRule() != ShoppingItemRule.EXACT_PRODUCT) {
                throw badRequest(
                        "Porodica proizvoda se potvrđuje samo za tačan proizvod"
                );
            }

            feedbackService.record(
                    item.matchingDecisionId(),
                    new ProductMatchFeedbackRequest(
                            clientToken,
                            ProductMatchFeedbackAction.CONFIRMED,
                            request.canonicalProductId(),
                            request.note(),
                            request.productFamilyId()
                    )
            );

            if (request.productFamilyId() != null) {
                item = shoppingListRepository.confirmProductFamilyMatch(
                        listId,
                        itemId,
                        request.productFamilyId(),
                        item.matchingDecisionId()
                ).orElseThrow(() -> itemNotFound(itemId));
            } else {
                item = updateResult(
                        listId,
                        itemId,
                        ShoppingItemMatchingStatus.CONFIRMED,
                        request.canonicalProductId(),
                        item.matchingDecisionId(),
                        BigDecimal.ONE.setScale(4),
                        "user-confirmation-v1"
                );
            }
        } else {
            if (request.canonicalProductId() != null
                    || request.productFamilyId() != null) {
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

    /**
     * A line keeps the reading it got when it was pasted, so the slava list of
     * 13.09. still had "Belo meso 3kg" as an unknown product after the words
     * were learned. What nobody has decided on is read again from what was
     * written. A product someone picked or turned down is left alone, and a
     * kind of product never falls back to a single product.
     */
    private ShoppingListItemResponse readAgain(
            Long listId,
            ShoppingListItemResponse item
    ) {
        if (item.rawInput() == null || item.rawInput().isBlank()) {
            return item;
        }

        boolean undecidedProduct =
                item.matchingRule() == ShoppingItemRule.EXACT_PRODUCT
                        && item.barcode() == null
                        && item.matchedCanonicalProductId() == null
                        && item.matchingStatus()
                        != ShoppingItemMatchingStatus.CONFIRMED
                        && (item.matchingAlgorithmVersion() == null
                        || !item.matchingAlgorithmVersion().startsWith("user-"));

        FlexibleItemConstraints constraints = item.flexibleConstraints();

        // A kind of product the words name is stored as confirmed.
        boolean unknownKind =
                item.matchingRule() == ShoppingItemRule.FLEXIBLE_CATEGORY
                        && item.matchingStatus()
                        != ShoppingItemMatchingStatus.CONFIRMED
                        && constraints != null
                        && constraints.requiredBrand() == null
                        && constraints.minPackageQuantity() == null
                        && constraints.maxPackageQuantity() == null;

        if (!undecidedProduct && !unknownKind) {
            return item;
        }

        ShoppingLineInterpreter.InterpretedLine line =
                lineInterpreter.interpret(item.rawInput());

        if (line.matchingRule() == ShoppingItemRule.FLEXIBLE_CATEGORY) {
            boolean keepsAmount = line.targetQuantity() == null
                    && constraints != null
                    && constraints.targetQuantity() != null;

            return shoppingListRepository.updateItem(
                    listId,
                    item.id(),
                    line.name(),
                    item.rawInput(),
                    null,
                    null,
                    null,
                    item.quantity(),
                    ShoppingItemRule.FLEXIBLE_CATEGORY,
                    line.category(),
                    line.normalizedCategory(),
                    line.shoppingIntentId(),
                    line.requiredBrand(),
                    null,
                    null,
                    keepsAmount ? constraints.requiredBaseUnit() : line.baseUnit(),
                    keepsAmount ? constraints.targetQuantity() : line.targetQuantity()
            ).orElse(item);
        }

        if (undecidedProduct && !line.name().equals(item.name())) {
            return shoppingListRepository.updateItem(
                    listId,
                    item.id(),
                    line.name(),
                    item.rawInput(),
                    null,
                    null,
                    null,
                    item.quantity(),
                    ShoppingItemRule.EXACT_PRODUCT,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            ).orElse(item);
        }

        return item;
    }

    private ShoppingItemMatchResult matchItem(
            Long listId,
            ShoppingListItemResponse item,
            String clientToken,
            boolean includeProductsWithoutBarcode
    ) {
        if (item.matchingStatus()
                == ShoppingItemMatchingStatus.CONFIRMED) {
            return resultForStoredItem(
                    item,
                    false,
                    item.matchingRule() == ShoppingItemRule.FLEXIBLE_CATEGORY
                            ? "Biramo najpovoljniju ponudu u prodavnicama u blizini."
                            : "Proizvod je potvrđen."
            );
        }

        ProductMatchDecision decision;

        if (item.matchingRule()
                == ShoppingItemRule.FLEXIBLE_CATEGORY) {
            FlexibleItemConstraints constraints =
                    item.flexibleConstraints();

            String query = flexibleQuery(item, constraints);

            // A flexible item is never pinned to one product, so it gets
            // no candidate it could only confirm as a family.
            decision = decisionService.decideFromProductSearch(
                    query,
                    CANDIDATE_LIMIT,
                    clientToken,
                    candidate -> satisfiesConstraints(
                            candidate,
                            constraints
                    ),
                    false,
                    false
            );
        } else {
            decision = decisionService.decideFromProductSearch(
                    item.name(),
                    CANDIDATE_LIMIT,
                    clientToken,
                    candidate -> true,
                    true,
                    includeProductsWithoutBarcode
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
                explanation(
                        updated,
                        decision.candidates().isEmpty()
                                ? null
                                : decision.candidates().getFirst().name()
                ),
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

    private String explanation(
            ShoppingListItemResponse item,
            String topCandidateName
    ) {
        if (item.matchingRule()
                == ShoppingItemRule.FLEXIBLE_CATEGORY) {
            String category = item.flexibleConstraints() == null
                    ? item.name()
                    : item.flexibleConstraints().category();

            return item.matchingStatus() == ShoppingItemMatchingStatus.CONFIRMED
                    ? "Biramo najpovoljniju ponudu u prodavnicama u blizini."
                    : "„" + category + "“ ne prepoznajemo kao vrstu proizvoda. "
                    + "Tražimo proizvode čiji naziv tako počinje, a sigurnije "
                    + "je da izabereš proizvod iz pretrage.";
        }

        return switch (item.matchingStatus()) {
            case AUTO_MATCHED -> topCandidateName == null
                    ? "Proizvod je prepoznat."
                    : "Prepoznato kao: " + topCandidateName;
            case NEEDS_CONFIRMATION ->
                    "Ima više sličnih proizvoda. Izaberi pravi pre računanja.";
            case UNMATCHED ->
                    "Ne nalazimo ovaj proizvod u cenovnicima, pa neće ući u račun.";
            case CONFIRMED -> "Proizvod je potvrđen.";
            case PENDING -> "Provera još nije pokrenuta.";
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
