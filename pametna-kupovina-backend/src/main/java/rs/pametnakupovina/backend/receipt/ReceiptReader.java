package rs.pametnakupovina.backend.receipt;

import java.util.List;
import java.util.Optional;

/**
 * Reads the receipts of one market. Every country prints its own code on a
 * receipt and keeps the lines somewhere of its own (Serbia: the tax office's
 * SUF), so a market the app launches in brings one of these as a Spring
 * bean, and nothing else about receipts changes.
 */
public interface ReceiptReader {

    /** ISO 3166 code of the market, as in {@code app.market.code}. */
    String marketCode();

    /**
     * What the scanned code says on its own: enough to file the receipt
     * before anyone else is asked anything.
     *
     * @throws org.springframework.web.server.ResponseStatusException 400 when
     *         the code is not a purchase receipt this reader trusts
     */
    ScannedReceipt read(String scannedCode);

    /**
     * The lines and the shop, from wherever the market keeps them.
     *
     * @return empty when that does not answer; the receipt stays filed
     *         without lines, and they can come later
     */
    Optional<ReceiptLines> lines(String address);

    /**
     * @param address where the receipt can be read again, as it is stored
     */
    record ScannedReceipt(String address, FiscalReceiptStamp stamp) {
    }

    /**
     * @param shopName null when the source does not name the shop
     */
    record ReceiptLines(
            String shopName,
            String taxIdentificationNumber,
            List<Receipt.ReceiptItem> items
    ) {
    }
}
