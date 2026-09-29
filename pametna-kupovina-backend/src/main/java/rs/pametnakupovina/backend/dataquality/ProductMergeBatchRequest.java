package rs.pametnakupovina.backend.dataquality;

import java.util.List;

/** One answer for several look-alike pairs: {@code same} true when each pair is one product. */
public record ProductMergeBatchRequest(List<Long> suggestionIds, Boolean same) {
}
