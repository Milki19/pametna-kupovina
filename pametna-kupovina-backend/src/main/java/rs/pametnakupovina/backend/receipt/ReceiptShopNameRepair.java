package rs.pametnakupovina.backend.receipt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import rs.pametnakupovina.backend.market.MarketRepository;

import java.util.List;

/**
 * Lidlovi računi su do 20.10.2026. zavođeni kao „Prodavnica 137", jer je to
 * sve što Lidl piše za objekat; firma, po kojoj se lanac prepoznaje, nije se
 * čuvala. Takvi računi se po pokretanju još jednom pitaju Poresku upravu za
 * ime, a novo ime više ne izgleda tako, pa se svaki popravlja samo jednom.
 */
@Component
public class ReceiptShopNameRepair {

    private static final Logger log =
            LoggerFactory.getLogger(ReceiptShopNameRepair.class);
    private static final int MOST_PER_START = 200;

    private final JdbcClient jdbcClient;
    private final MarketRepository marketRepository;
    private final ReceiptReaders receiptReaders;
    private final ReceiptRepository receiptRepository;

    public ReceiptShopNameRepair(
            JdbcClient jdbcClient,
            MarketRepository marketRepository,
            ReceiptReaders receiptReaders,
            ReceiptRepository receiptRepository
    ) {
        this.jdbcClient = jdbcClient;
        this.marketRepository = marketRepository;
        this.receiptReaders = receiptReaders;
        this.receiptRepository = receiptRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            repair();
        } catch (RuntimeException failed) {
            log.warn("Popravka imena prodavnica na računima nije uspela: {}",
                    failed.toString());
        }
    }

    /** @return koliko je računa dobilo novo ime */
    public int repair() {
        List<Unnamed> receipts = jdbcClient.sql("""
                        SELECT id, account_id, verification_url, shop_name
                        FROM app.receipt
                        WHERE verification_url IS NOT NULL
                          AND shop_name ~* '^\\s*(prodavnica|objekat|продавница|објекат)\\s*(br\\.?|бр\\.?)?\\s*[0-9]*\\s*$'
                        ORDER BY id
                        LIMIT :limit
                        """)
                .param("limit", MOST_PER_START)
                .query((resultSet, rowNumber) -> new Unnamed(
                        resultSet.getLong("id"),
                        resultSet.getLong("account_id"),
                        resultSet.getString("verification_url"),
                        resultSet.getString("shop_name")
                ))
                .list();

        int renamed = 0;

        for (Unnamed receipt : receipts) {
            try {
                if (rename(receipt)) {
                    renamed++;
                }
            } catch (RuntimeException failed) {
                // Račun iz zemlje bez čitača ili ćutljiva Poreska uprava ne
                // smeju da zaustave ostale.
                log.warn("Račun {} nije dobio novo ime: {}",
                        receipt.id(), failed.toString());
            }
        }

        if (!receipts.isEmpty()) {
            log.info("Imena prodavnica na računima: {} od {} popravljeno.",
                    renamed, receipts.size());
        }
        return renamed;
    }

    private boolean rename(Unnamed receipt) {
        ReceiptReader reader = receiptReaders.forMarket(
                marketRepository.forAccount(receipt.accountId())
        );
        var lines = reader.lines(receipt.verificationUrl());

        if (lines.isEmpty()
                || lines.get().shopName() == null
                || lines.get().shopName().isBlank()
                || lines.get().shopName().equals(receipt.shopName())) {
            return false;
        }

        receiptRepository.nameShop(
                receipt.id(),
                lines.get().shopName(),
                lines.get().taxIdentificationNumber()
        );
        return true;
    }

    private record Unnamed(
            long id,
            long accountId,
            String verificationUrl,
            String shopName
    ) {
    }
}
