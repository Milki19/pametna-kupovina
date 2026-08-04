package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

@Service
public class ShoppingListService {

    private final ShoppingListRepository repository;

    public ShoppingListService(
            ShoppingListRepository repository
    ) {
        this.repository = repository;
    }

    @Transactional
    public ShoppingListSummary create(
            CreateShoppingListRequest request
    ) {
        String name = requiredText(
                request == null ? null : request.name(),
                "Naziv spiska"
        );

        if (name.length() > 200) {
            throw badRequest(
                    "Naziv spiska može imati najviše 200 karaktera"
            );
        }

        return repository.create(name);
    }

    public List<ShoppingListSummary> findAll() {
        return repository.findAll();
    }

    public ShoppingListResponse findById(Long listId) {
        return repository.findById(listId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Spisak nije pronađen: " + listId
                ));
    }

    @Transactional
    public ShoppingListItemResponse addItem(
            Long listId,
            AddShoppingListItemRequest request
    ) {
        requireList(listId);

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
    public void deleteItem(Long listId, Long itemId) {
        requireList(listId);

        if (!repository.deleteItem(listId, itemId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Stavka nije pronađena: " + itemId
            );
        }

        repository.touch(listId);
    }

    @Transactional
    public void deleteList(Long listId) {
        if (!repository.deleteList(listId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Spisak nije pronađen: " + listId
            );
        }
    }

    @Transactional
    public ShoppingListItemResponse updateItem(
            Long listId,
            Long itemId,
            UpdateShoppingListItemRequest request
    ) {
        requireList(listId);

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

    private void requireList(Long listId) {
        if (!repository.existsById(listId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Spisak nije pronađen: " + listId
            );
        }
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
}
