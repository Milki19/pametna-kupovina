package rs.pametnakupovina.backend.retailerlocation.lidl;

import java.util.List;

record LidlLocationResponse(
        Meta meta,
        List<LidlApiLocation> items
) {

    record Meta(
            Integer limit,
            Integer offset,
            Integer total,
            String created
    ) {
    }
}
