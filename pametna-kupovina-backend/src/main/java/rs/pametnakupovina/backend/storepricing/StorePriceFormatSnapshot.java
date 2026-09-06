package rs.pametnakupovina.backend.storepricing;

import java.time.Instant;
import java.util.List;

public record StorePriceFormatSnapshot(
        String sourceUrl,
        Instant sourceLastModified,
        List<OfficialStorePriceFormat> assignments
) {
}
