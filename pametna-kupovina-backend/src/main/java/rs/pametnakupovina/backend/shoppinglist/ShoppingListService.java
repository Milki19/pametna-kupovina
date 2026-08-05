package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

@Service
public class ShoppingListService {

    private static final int MAX_PASTED_TEXT_LENGTH = 50_000;
    private static final int MAX_PASTED_ITEM_COUNT = 200;

    private final ShoppingListRepository repository;
    private final ShoppingListClientTokenPolicy clientTokenPolicy;
    private final ShoppingListTextParser textParser;

    public ShoppingListService(
            ShoppingListRepository repository,
            ShoppingListClientTokenPolicy clientTokenPolicy,
            ShoppingListTextParser textParser
    ) {
        this.repository = repository;
        this.clientTokenPolicy = clientTokenPolicy;
        this.textParser = textParser;
    }

    @Transactional
    public ShoppingListSummary create(
            CreateShoppingListRequest request,
            String clientToken
    ) {
        String name = validListName(
                request == null ? null : request.name()
        );

        String clientTokenHash =
                clientTokenPolicy.validateAndHash(clientToken);

        return repository.create(name, clientTokenHash);
    }

    public List<ShoppingListSummary> findAll(String clientToken) {
        return repository.findAll(
                clientTokenPolicy.validateAndHash(clientToken)
        );
    }

    public ShoppingListResponse findById(
            Long listId,
            String clientToken
    ) {
        String clientTokenHash =
                clientTokenPolicy.validateAndHash(clientToken);

        return repository.findById(listId, clientTokenHash)
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
        String clientTokenHash =
                clientTokenPolicy.validateAndHash(clientToken);

        String name = validListName(
                request == null ? null : request.name()
        );

        if (!repository.updateName(
                listId,
                clientTokenHash,
                name
        )) {
            throw listNotFound(listId);
        }

        return repository.findById(listId, clientTokenHash)
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

        ShoppingListItemResponse item = repository.addItem(
                listId,
                name,
                rawInput,
                barcode,
                quantity,
                matchingRule
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

        ShoppingItemRule matchingRule =
                ShoppingItemRule.EXACT_PRODUCT;

        List<ValidatedShoppingListItem> validatedItems =
                parsedText.items().stream()
                        .map(item -> validateParsedItem(
                                item,
                                matchingRule
                        ))
                        .toList();

        List<ShoppingListItemResponse> createdItems =
                validatedItems.stream()
                        .map(item -> repository.addItem(
                                listId,
                                item.name(),
                                item.rawInput(),
                                null,
                                item.quantity(),
                                item.matchingRule()
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
        String clientTokenHash =
                clientTokenPolicy.validateAndHash(clientToken);

        if (!repository.deactivateList(listId, clientTokenHash)) {
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

        ShoppingListItemResponse updatedItem =
                repository.updateItem(
                        listId,
                        itemId,
                        name,
                        rawInput,
                        barcode,
                        quantity,
                        matchingRule
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
        String clientTokenHash =
                clientTokenPolicy.validateAndHash(clientToken);

        if (!repository.existsByIdAndClientTokenHash(
                listId,
                clientTokenHash
        )) {
            throw listNotFound(listId);
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

    private ValidatedShoppingListItem validateParsedItem(
            ParsedShoppingListLine item,
            ShoppingItemRule matchingRule
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

        return new ValidatedShoppingListItem(
                name,
                rawInput,
                normalizedQuantity,
                matchingRule
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

        if (value == null || value.trim().isEmpty()) {
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
            ShoppingItemRule matchingRule
    ) {
    }
}
