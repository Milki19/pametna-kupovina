package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import rs.pametnakupovina.backend.account.AccountRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ShoppingListService {

    private static final int MAX_PASTED_TEXT_LENGTH = 50_000;
    private static final int MAX_PASTED_ITEM_COUNT = 200;
    private static final Set<String> SUPPORTED_BASE_UNITS =
            Set.of("g", "ml", "piece");

    private final ShoppingListRepository repository;
    private final ShoppingListClientTokenPolicy clientTokenPolicy;
    private final AccountRepository accountRepository;
    private final ShoppingListTextParser textParser;
    private final ProductNameNormalizer productNameNormalizer;
    private final ShoppingIntentResolver shoppingIntentResolver;
    private final ShoppingLineInterpreter lineInterpreter;

    public ShoppingListService(
            ShoppingListRepository repository,
            ShoppingListClientTokenPolicy clientTokenPolicy,
            AccountRepository accountRepository,
            ShoppingListTextParser textParser,
            ProductNameNormalizer productNameNormalizer,
            ShoppingIntentResolver shoppingIntentResolver,
            ShoppingLineInterpreter lineInterpreter
    ) {
        this.repository = repository;
        this.clientTokenPolicy = clientTokenPolicy;
        this.accountRepository = accountRepository;
        this.textParser = textParser;
        this.productNameNormalizer = productNameNormalizer;
        this.shoppingIntentResolver = shoppingIntentResolver;
        this.lineInterpreter = lineInterpreter;
    }

    @Transactional
    public ShoppingListSummary create(
            CreateShoppingListRequest request,
            String clientToken
    ) {
        String name = validListName(
                request == null ? null : request.name()
        );

        long accountId = accountFor(clientToken);

        return repository.create(name, accountId);
    }

    /**
     * The phone's own random number never leaves this line: it is hashed, and
     * from there on a list belongs to an account, not to a handset.
     */
    private long accountFor(String clientToken) {
        return accountRepository.forDevice(
                clientTokenPolicy.validateAndHash(clientToken)
        );
    }

    public List<ShoppingListSummary> findAll(String clientToken) {
        return repository.findAll(
                accountFor(clientToken)
        );
    }

    public ShoppingListResponse findById(
            Long listId,
            String clientToken
    ) {
        long accountId = accountFor(clientToken);

        return repository.findById(listId, accountId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Spisak nije pronađen: " + listId
                ));
    }

    public ShoppingListResponse requireOwnedList(
            Long listId,
            String clientToken
    ) {
        return findById(listId, clientToken);
    }

    @Transactional
    public ShoppingListResponse updateList(
            Long listId,
            String clientToken,
            UpdateShoppingListRequest request
    ) {
        long accountId = accountFor(clientToken);

        String name = validListName(
                request == null ? null : request.name()
        );

        if (!repository.updateName(
                listId,
                accountId,
                name
        )) {
            throw listNotFound(listId);
        }

        return repository.findById(listId, accountId)
                .orElseThrow(() -> listNotFound(listId));
    }

    @Transactional
    public ShoppingListItemResponse addItem(
            Long listId,
            String clientToken,
            AddShoppingListItemRequest request
    ) {
        requireList(listId, clientToken);

        String name = requiredText(
                request == null ? null : request.name(),
                "Naziv artikla"
        );

        if (name.length() > 500) {
            throw badRequest(
                    "Naziv artikla može imati najviše 500 karaktera"
            );
        }

        String rawInput = requiredRawInput(
                request == null ? null : request.rawInput(),
                request == null ? null : request.name()
        );

        if (rawInput.length() > 1000) {
            throw badRequest(
                    "Sirovi unos može imati najviše 1000 karaktera"
            );
        }

        String barcode = nullableText(
                request == null ? null : request.barcode()
        );

        Long canonicalProductId = request == null
                ? null
                : request.canonicalProductId();
        Long productFamilyId = request == null
                ? null
                : request.productFamilyId();

        if (barcode != null && barcode.length() > 32) {
            throw badRequest(
                    "Barkod može imati najviše 32 karaktera"
            );
        }

        BigDecimal quantity =
                request == null || request.quantity() == null
                        ? BigDecimal.ONE
                        : request.quantity();

        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw badRequest(
                    "Količina mora biti veća od nule"
            );
        }

        ShoppingItemRule matchingRule =
                request == null || request.matchingRule() == null
                        ? ShoppingItemRule.EXACT_PRODUCT
                        : request.matchingRule();

        validateProductSelection(
                canonicalProductId,
                productFamilyId,
                barcode,
                matchingRule
        );

        ValidatedFlexibleConstraints flexible =
                validateFlexibleConstraints(
                        name,
                        barcode,
                        matchingRule,
                        request == null
                                ? null
                                : request.flexibleConstraints()
                );

        ShoppingListItemResponse item = repository.addItem(
                listId,
                name,
                rawInput,
                barcode,
                canonicalProductId,
                productFamilyId,
                quantity,
                matchingRule,
                flexible.category(),
                flexible.normalizedCategory(),
                flexible.shoppingIntentId(),
                flexible.requiredBrand(),
                flexible.minPackageQuantity(),
                flexible.maxPackageQuantity(),
                flexible.requiredBaseUnit(),
                flexible.targetQuantity()
        );

        repository.touch(listId);

        return item;
    }

    @Transactional
    public PasteShoppingListItemsResponse addPastedItems(
            Long listId,
            String clientToken,
            PasteShoppingListItemsRequest request
    ) {
        requireList(listId, clientToken);

        String text = request == null ? null : request.text();

        if (text == null || text.isBlank()) {
            throw badRequest(
                    "Zalepljeni spisak mora imati bar jedan neprazan red"
            );
        }

        if (text.length() > MAX_PASTED_TEXT_LENGTH) {
            throw badRequest(
                    "Zalepljeni spisak može imati najviše "
                            + MAX_PASTED_TEXT_LENGTH
                            + " karaktera"
            );
        }

        ParsedShoppingListText parsedText = textParser.parse(text);

        if (parsedText.items().isEmpty()) {
            throw badRequest(
                    "Zalepljeni spisak mora imati bar jedan neprazan red"
            );
        }

        if (parsedText.items().size() > MAX_PASTED_ITEM_COUNT) {
            throw badRequest(
                    "Jednim zahtevom može se dodati najviše "
                            + MAX_PASTED_ITEM_COUNT
                            + " stavki"
            );
        }

        List<ValidatedShoppingListItem> validatedItems =
                parsedText.items().stream()
                        .map(this::validateParsedItem)
                        .toList();

        List<ShoppingListItemResponse> createdItems =
                validatedItems.stream()
                        .map(item -> repository.addItem(
                                listId,
                                item.name(),
                                item.rawInput(),
                                null,
                                null,
                                null,
                                item.quantity(),
                                item.matchingRule(),
                                item.flexibleCategory(),
                                item.flexibleCategoryNormalized(),
                                item.shoppingIntentId(),
                                item.requiredBrand(),
                                null,
                                null,
                                item.requiredBaseUnit(),
                                item.targetQuantity()
                        ))
                        .toList();

        repository.touch(listId);

        return new PasteShoppingListItemsResponse(
                createdItems.size(),
                parsedText.ignoredBlankLineCount(),
                createdItems
        );
    }

    @Transactional
    public void deleteItem(
            Long listId,
            Long itemId,
            String clientToken
    ) {
        requireList(listId, clientToken);

        if (!repository.deleteItem(listId, itemId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Stavka nije pronađena: " + itemId
            );
        }

        repository.touch(listId);
    }

    @Transactional
    public void deleteList(Long listId, String clientToken) {
        long accountId = accountFor(clientToken);

        if (!repository.deactivateList(listId, accountId)) {
            throw listNotFound(listId);
        }
    }

    @Transactional
    public ShoppingListItemResponse updateItem(
            Long listId,
            Long itemId,
            String clientToken,
            UpdateShoppingListItemRequest request
    ) {
        requireList(listId, clientToken);

        String name = requiredText(
                request == null ? null : request.name(),
                "Naziv artikla"
        );

        if (name.length() > 500) {
            throw badRequest(
                    "Naziv artikla može imati najviše 500 karaktera"
            );
        }

        String rawInput = requiredRawInput(
                request == null ? null : request.rawInput(),
                request == null ? null : request.name()
        );

        if (rawInput.length() > 1000) {
            throw badRequest(
                    "Sirovi unos može imati najviše 1000 karaktera"
            );
        }

        String barcode = nullableText(
                request == null ? null : request.barcode()
        );

        Long canonicalProductId = request == null
                ? null
                : request.canonicalProductId();
        Long productFamilyId = request == null
                ? null
                : request.productFamilyId();

        if (barcode != null && barcode.length() > 32) {
            throw badRequest(
                    "Barkod može imati najviše 32 karaktera"
            );
        }

        BigDecimal quantity =
                request == null || request.quantity() == null
                        ? BigDecimal.ONE
                        : request.quantity();

        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw badRequest(
                    "Količina mora biti veća od nule"
            );
        }

        ShoppingItemRule matchingRule =
                request == null || request.matchingRule() == null
                        ? ShoppingItemRule.EXACT_PRODUCT
                        : request.matchingRule();

        validateProductSelection(
                canonicalProductId,
                productFamilyId,
                barcode,
                matchingRule
        );

        ValidatedFlexibleConstraints flexible =
                validateFlexibleConstraints(
                        name,
                        barcode,
                        matchingRule,
                        request == null
                                ? null
                                : request.flexibleConstraints()
                );

        ShoppingListItemResponse updatedItem =
                repository.updateItem(
                        listId,
                        itemId,
                        name,
                        rawInput,
                        barcode,
                        canonicalProductId,
                        productFamilyId,
                        quantity,
                        matchingRule,
                        flexible.category(),
                        flexible.normalizedCategory(),
                        flexible.shoppingIntentId(),
                        flexible.requiredBrand(),
                        flexible.minPackageQuantity(),
                        flexible.maxPackageQuantity(),
                        flexible.requiredBaseUnit(),
                flexible.targetQuantity()
                ).orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Stavka nije pronađena: " + itemId
                        )
                );

        repository.touch(listId);

        return updatedItem;
    }

    private void requireList(Long listId, String clientToken) {
        long accountId = accountFor(clientToken);

        if (!repository.existsByIdAndAccount(
                listId,
                accountId
        )) {
            throw listNotFound(listId);
        }
    }

    private void validateProductSelection(
            Long canonicalProductId,
            Long productFamilyId,
            String barcode,
            ShoppingItemRule matchingRule
    ) {
        if (matchingRule == ShoppingItemRule.PRODUCT_FAMILY) {
            if (canonicalProductId != null || barcode != null) {
                throw badRequest(
                        "Porodica proizvoda ne može imati tačan barkod "
                                + "ili canonical proizvod"
                );
            }

            if (productFamilyId == null || productFamilyId <= 0) {
                throw badRequest(
                        "productFamilyId je obavezan i mora biti pozitivan"
                );
            }

            if (!repository.productFamilyExists(productFamilyId)) {
                throw badRequest(
                        "Porodica proizvoda nije pronađena: "
                                + productFamilyId
                );
            }

            return;
        }

        if (productFamilyId != null) {
            throw badRequest(
                    "productFamilyId važi samo za porodičnu stavku"
            );
        }

        if (canonicalProductId == null) {
            return;
        }

        if (canonicalProductId <= 0) {
            throw badRequest("canonicalProductId mora biti pozitivan");
        }

        if (matchingRule != ShoppingItemRule.EXACT_PRODUCT) {
            throw badRequest(
                    "Canonical proizvod važi samo za tačnu stavku"
            );
        }

        CanonicalProductReference product = repository
                .findCanonicalProductById(canonicalProductId)
                .orElseThrow(() -> badRequest(
                        "Canonical proizvod nije pronađen: "
                                + canonicalProductId
                ));

        if (barcode != null
                && !barcode.equals(product.barcode())) {
            throw badRequest(
                    "Barkod ne pripada izabranom canonical proizvodu"
            );
        }
    }

    private String validListName(String value) {
        String name = requiredText(value, "Naziv spiska");

        if (name.length() > 200) {
            throw badRequest(
                    "Naziv spiska može imati najviše 200 karaktera"
            );
        }

        return name;
    }

    private ValidatedFlexibleConstraints validateFlexibleConstraints(
            String itemName,
            String barcode,
            ShoppingItemRule matchingRule,
            FlexibleItemConstraints constraints
    ) {
        if (matchingRule == ShoppingItemRule.EXACT_PRODUCT
                || matchingRule == ShoppingItemRule.PRODUCT_FAMILY) {
            if (hasConstraintValue(constraints)) {
                throw badRequest(
                        "Ograničenja kategorije važe samo za fleksibilnu stavku"
                );
            }

            return ValidatedFlexibleConstraints.empty();
        }

        if (barcode != null) {
            throw badRequest(
                    "Fleksibilna stavka ne može imati tačan barkod"
            );
        }

        String category = nullableText(
                constraints == null
                        ? null
                        : constraints.category()
        );

        if (category == null) {
            category = itemName;
        }

        if (category.length() > 200) {
            throw badRequest(
                    "Fleksibilna kategorija može imati najviše 200 karaktera"
            );
        }

        String normalizedCategory =
                productNameNormalizer.normalize(category);

        if (normalizedCategory.isBlank()) {
            throw badRequest(
                    "Fleksibilna kategorija mora sadržati slovo ili broj"
            );
        }

        ShoppingIntentResolver.ResolvedShoppingIntent resolvedIntent =
                shoppingIntentResolver.resolve(category).orElse(null);

        BigDecimal inlineTarget = null;
        String inlineUnit = null;
        String inlineBrand = null;
        if (resolvedIntent != null && !resolvedIntent.exactAlias()) {
            String remainder = normalizedCategory.replaceFirst(
                    java.util.regex.Pattern.quote(resolvedIntent.normalizedAlias()), "").trim();
            if (remainder.matches("[0-9]+(?: [0-9]+)? (g|gr|kg|ml|l|kom|komada)")) {
                var inlineAmount = new rs.pametnakupovina.backend.matching.ProductQuantityParser()
                        .parse(category).orElse(null);
                if (inlineAmount != null) {
                    inlineTarget = inlineAmount.value();
                    inlineUnit = inlineAmount.unit().databaseValue();
                }
            }
            if (inlineTarget == null) {
                // "Pivo Zaječarsko 0.5" typed as any beer reads as a pasted
                // line would: beer of that brand, half a litre.
                ShoppingLineInterpreter.InterpretedLine line =
                        lineInterpreter.interpret(category);
                var reread = line.matchingRule() == ShoppingItemRule.FLEXIBLE_CATEGORY
                        ? shoppingIntentResolver.resolve(line.category())
                                .filter(ShoppingIntentResolver.ResolvedShoppingIntent::exactAlias)
                                .orElse(null)
                        : null;
                if (reread == null) {
                    throw badRequest("„" + category + "“ nije samo vrsta proizvoda. "
                            + "Izaberi proizvod iz pretrage, ili upiši vrstu (npr. „pivo“), "
                            + "a brend i količinu posebno.");
                }
                category = line.category();
                resolvedIntent = reread;
                inlineBrand = line.requiredBrand();
                inlineTarget = line.targetQuantity();
                inlineUnit = line.baseUnit();
            }
        }

        if (resolvedIntent != null) {
            normalizedCategory = resolvedIntent.normalizedAlias();
        }

        String requiredBrand = nullableText(
                constraints == null
                        ? null
                        : constraints.requiredBrand()
        );

        if (requiredBrand == null) {
            requiredBrand = inlineBrand;
        }

        if (requiredBrand != null && requiredBrand.length() > 200) {
            throw badRequest(
                    "Zahtevani brend može imati najviše 200 karaktera"
            );
        }

        BigDecimal minPackageQuantity = constraints == null
                ? null
                : constraints.minPackageQuantity();

        BigDecimal maxPackageQuantity = constraints == null
                ? null
                : constraints.maxPackageQuantity();

        validatePackageQuantity(
                minPackageQuantity,
                "Minimalna količina pakovanja"
        );

        validatePackageQuantity(
                maxPackageQuantity,
                "Maksimalna količina pakovanja"
        );

        if (minPackageQuantity != null
                && maxPackageQuantity != null
                && minPackageQuantity.compareTo(
                maxPackageQuantity
        ) > 0) {
            throw badRequest(
                    "Minimalna količina pakovanja ne može biti veća od maksimalne"
            );
        }

        String requiredBaseUnit = nullableText(
                constraints == null
                        ? null
                        : constraints.requiredBaseUnit()
        );
        if (requiredBaseUnit == null && inlineUnit != null) {
            requiredBaseUnit = inlineUnit;
        }

        if (requiredBaseUnit != null) {
            requiredBaseUnit = requiredBaseUnit.toLowerCase(
                    Locale.ROOT
            );

            if (!SUPPORTED_BASE_UNITS.contains(requiredBaseUnit)) {
                throw badRequest(
                        "Jedinica mora biti g, ml ili piece"
                );
            }
        }

        // "Pakovanje od 6" alone was read as 6 ml or 6 g of whatever the
        // product is measured in, never as six pieces.
        if ((minPackageQuantity != null || maxPackageQuantity != null)
                && requiredBaseUnit == null) {
            throw badRequest(
                    "Za veličinu pakovanja izaberi jedinicu: g, ml ili kom."
            );
        }

        BigDecimal targetQuantity = constraints == null ? null : constraints.targetQuantity();
        if (inlineTarget != null) {
            if ((targetQuantity != null && targetQuantity.compareTo(inlineTarget) != 0)
                    || !requiredBaseUnit.equals(inlineUnit)) {
                throw badRequest("Količina u nazivu i zadato ograničenje se razlikuju.");
            }
            targetQuantity = inlineTarget;
        }
        validatePackageQuantity(targetQuantity, "Tražena ukupna količina");
        if (targetQuantity != null && requiredBaseUnit == null) {
            throw badRequest("Ukupna količina zahteva jedinicu g, ml ili piece");
        }

        return new ValidatedFlexibleConstraints(
                category,
                normalizedCategory,
                resolvedIntent == null
                        ? null
                        : resolvedIntent.shoppingIntentId(),
                requiredBrand,
                minPackageQuantity,
                maxPackageQuantity,
                requiredBaseUnit,
                targetQuantity
        );
    }

    private boolean hasConstraintValue(
            FlexibleItemConstraints constraints
    ) {
        return constraints != null
                && (
                constraints.category() != null
                        || constraints.requiredBrand() != null
                        || constraints.minPackageQuantity() != null
                        || constraints.maxPackageQuantity() != null
                        || constraints.requiredBaseUnit() != null
                        || constraints.targetQuantity() != null
        );
    }

    private void validatePackageQuantity(
            BigDecimal value,
            String fieldName
    ) {
        if (value == null) {
            return;
        }

        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            throw badRequest(fieldName + " mora biti veća od nule");
        }

        BigDecimal normalized = value.stripTrailingZeros();
        int integerDigits = normalized.precision() - normalized.scale();

        if (normalized.scale() > 4 || integerDigits > 10) {
            throw badRequest(
                    fieldName
                            + " može imati najviše 10 celih i 4 decimalne cifre"
            );
        }
    }

    private ValidatedShoppingListItem validateParsedItem(
            ParsedShoppingListLine item
    ) {
        String name = requiredText(item.name(), "Naziv artikla");

        if (name.length() > 500) {
            throw badRequest(
                    "Naziv artikla može imati najviše 500 karaktera"
            );
        }

        String rawInput = requiredRawInput(
                item.rawInput(),
                item.name()
        );

        if (rawInput.length() > 1000) {
            throw badRequest(
                    "Sirovi unos može imati najviše 1000 karaktera"
            );
        }

        if (item.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw badRequest(
                    "Količina mora biti veća od nule"
            );
        }

        BigDecimal normalizedQuantity =
                item.quantity().stripTrailingZeros();
        int integerDigits = normalizedQuantity.precision()
                - normalizedQuantity.scale();

        if (normalizedQuantity.scale() > 3
                || integerDigits > 7) {
            throw badRequest(
                    "Količina može imati najviše 7 celih i 3 decimalne cifre"
            );
        }

        // A total amount only means something once the line resolved to a
        // kind of product. For one specific product the shop's pack decides,
        // and the size stays in the name for the search.
        ShoppingLineInterpreter.InterpretedLine line =
                lineInterpreter.interpret(item);

        if (line.name().length() > 500) {
            throw badRequest(
                    "Naziv artikla može imati najviše 500 karaktera"
            );
        }

        return new ValidatedShoppingListItem(
                line.name(),
                rawInput,
                normalizedQuantity,
                line.matchingRule(),
                line.category(),
                line.normalizedCategory(),
                line.shoppingIntentId(),
                line.targetQuantity(),
                line.baseUnit(),
                line.requiredBrand()
        );
    }

    private ResponseStatusException listNotFound(Long listId) {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Spisak nije pronađen: " + listId
        );
    }

    private String requiredText(
            String value,
            String fieldName
    ) {
        String normalized = nullableText(value);

        if (normalized == null) {
            throw badRequest(
                    fieldName + " ne sme biti prazan"
            );
        }

        return normalized;
    }

    private String nullableText(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();

        return normalized.isEmpty() ? null : normalized;
    }

    private String requiredRawInput(
            String rawInput,
            String fallbackName
    ) {
        String value = rawInput == null
                ? fallbackName
                : rawInput;

        if (value == null || value.isBlank()) {
            throw badRequest(
                    "Sirovi unos artikla ne sme biti prazan"
            );
        }

        return value;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                message
        );
    }

    private record ValidatedShoppingListItem(
            String name,
            String rawInput,
            BigDecimal quantity,
            ShoppingItemRule matchingRule,
            String flexibleCategory,
            String flexibleCategoryNormalized,
            Long shoppingIntentId,
            BigDecimal targetQuantity,
            String requiredBaseUnit,
            String requiredBrand
    ) {
    }

    private record ValidatedFlexibleConstraints(
            String category,
            String normalizedCategory,
            Long shoppingIntentId,
            String requiredBrand,
            BigDecimal minPackageQuantity,
            BigDecimal maxPackageQuantity,
            String requiredBaseUnit,
            BigDecimal targetQuantity
    ) {
        private static ValidatedFlexibleConstraints empty() {
            return new ValidatedFlexibleConstraints(
                    null, null, null, null, null, null, null, null
            );
        }
    }
}
