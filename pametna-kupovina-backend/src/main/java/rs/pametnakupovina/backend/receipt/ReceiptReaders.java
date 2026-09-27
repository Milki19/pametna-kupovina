package rs.pametnakupovina.backend.receipt;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import rs.pametnakupovina.backend.market.Market;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The receipt reader of each market that has one. */
@Component
public class ReceiptReaders {

    private final Map<String, ReceiptReader> byMarket;

    public ReceiptReaders(List<ReceiptReader> readers) {
        // Two readers for one market would make which one reads a receipt
        // depend on bean order, so that refuses to start.
        this.byMarket = readers.stream().collect(Collectors.toUnmodifiableMap(
                ReceiptReader::marketCode,
                Function.identity()
        ));
    }

    public ReceiptReader forMarket(Market market) {
        ReceiptReader reader = byMarket.get(market.code());

        if (reader == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Računi iz ove zemlje još ne mogu da se skeniraju."
            );
        }
        return reader;
    }
}
