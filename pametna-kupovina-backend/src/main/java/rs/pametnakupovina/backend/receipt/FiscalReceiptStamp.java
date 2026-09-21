package rs.pametnakupovina.backend.receipt;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What the QR code on a fiscal receipt says on its own, before anyone asks
 * the tax office anything.
 *
 * @param shopName the seller's own name for the till's location; best effort,
 *                 because it sits after the fields the format pins down and
 *                 the tax office's page gives it too
 */
public record FiscalReceiptStamp(
        String invoiceNumber,
        long totalCounter,
        BigDecimal totalAmount,
        Instant issuedAt,
        String shopName
) {
}
