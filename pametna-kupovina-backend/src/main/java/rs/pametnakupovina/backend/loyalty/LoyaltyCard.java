package rs.pametnakupovina.backend.loyalty;

public record LoyaltyCard(
        long id,
        String name,
        String cardNumber,
        String barcodeFormat
) {
}
