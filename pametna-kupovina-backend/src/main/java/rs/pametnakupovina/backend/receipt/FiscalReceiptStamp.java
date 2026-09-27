package rs.pametnakupovina.backend.receipt;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What the QR code on a fiscal receipt says on its own, before anyone asks
 * the tax office anything. The shop's name is not in it — only the tax
 * office's page gives it.
 */
public record FiscalReceiptStamp(
        String invoiceNumber,
        long totalCounter,
        BigDecimal totalAmount,
        Instant issuedAt
) {
}
