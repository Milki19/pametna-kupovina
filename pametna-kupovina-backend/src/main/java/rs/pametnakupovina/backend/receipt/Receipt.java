package rs.pametnakupovina.backend.receipt;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * @param itemsRead false dok stavke nisu pročitane sa stranice Poreske
 *                  uprave; račun je i tada pun račun — zna se gde, kada i
 *                  koliko
 */
public record Receipt(
        long id,
        String invoiceNumber,
        String shopName,
        Instant issuedAt,
        BigDecimal totalAmount,
        boolean itemsRead,
        List<ReceiptItem> items
) {

    public record ReceiptItem(
            int lineNumber,
            String name,
            BigDecimal quantity,
            String unitOfMeasure,
            BigDecimal unitPrice,
            BigDecimal totalPrice
    ) {
    }
}
