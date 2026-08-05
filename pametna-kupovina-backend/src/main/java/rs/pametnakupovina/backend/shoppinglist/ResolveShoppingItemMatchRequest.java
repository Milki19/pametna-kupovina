package rs.pametnakupovina.backend.shoppinglist;

public record ResolveShoppingItemMatchRequest(
        ShoppingItemMatchAction action,
        Long canonicalProductId,
        String note
) {
}
