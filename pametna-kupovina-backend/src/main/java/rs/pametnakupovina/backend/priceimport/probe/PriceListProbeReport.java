package rs.pametnakupovina.backend.priceimport.probe;

import java.time.LocalDate;
import java.util.List;

/**
 * What one price list looks like before a single price is saved: how much of
 * it we can read, which day it is for, and whether it may go live.
 */
public record PriceListProbeReport(
        String label,
        long rowsRead,
        long rowsUsable,
        long rowsWithBarcode,
        long rowsWithPrice,
        List<String> priceListNames,
        LocalDate oldestPriceDate,
        LocalDate newestPriceDate,
        List<String> missingColumns,
        List<String> findings,
        PriceListProbeVerdict verdict
) {

    public int usableShare() {
        return share(rowsUsable);
    }

    public int barcodeShare() {
        return share(rowsWithBarcode);
    }

    public int priceShare() {
        return share(rowsWithPrice);
    }

    private int share(long counted) {
        return rowsRead == 0 ? 0 : (int) Math.round(100.0 * counted / rowsRead);
    }
}
