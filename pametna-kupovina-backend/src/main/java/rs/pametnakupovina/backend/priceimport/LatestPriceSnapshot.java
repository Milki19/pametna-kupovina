package rs.pametnakupovina.backend.priceimport;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class LatestPriceSnapshot {

    private LocalDate snapshotDate;

    private final Map<PriceRowKey, PriceCsvRow> selectedRows =
            new LinkedHashMap<>();

    void accept(PriceCsvRow row) {
        Objects.requireNonNull(row, "row");

        LocalDate rowDate = Objects.requireNonNull(
                row.priceDate(),
                "row.priceDate"
        );

        if (snapshotDate == null || rowDate.isAfter(snapshotDate)) {
            snapshotDate = rowDate;
            selectedRows.clear();
        }

        if (!rowDate.equals(snapshotDate)) {
            return;
        }

        PriceRowKey key = new PriceRowKey(
                row.sourceProductKey(),
                normalizeFormatName(row.retailerFormatName())
        );

        selectedRows.put(key, row);
    }

    LocalDate snapshotDate() {
        return snapshotDate;
    }

    int rowsSelected() {
        return selectedRows.size();
    }

    List<PriceCsvRow> rows() {
        return List.copyOf(selectedRows.values());
    }

    private String normalizeFormatName(String value) {
        return value == null ? "" : value.trim();
    }

    private record PriceRowKey(
            String sourceProductKey,
            String retailerFormatName
    ) {
    }
}